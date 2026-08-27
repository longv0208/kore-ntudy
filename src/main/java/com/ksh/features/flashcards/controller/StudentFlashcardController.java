package com.ksh.features.flashcards.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckEditorView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ksh.features.flashcards.dto.FlashcardDtos.StudentDeckList;
import com.ksh.features.flashcards.service.CardService;
import com.ksh.features.flashcards.service.DeckService;
import com.ksh.features.flashcards.service.DeckPublicLinkService;
import com.ksh.features.flashcards.service.FlashcardStudyService;
import com.ksh.features.flashcards.service.SmartReviewService;
import com.ksh.security.KshUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

import static com.ksh.common.IConstant.*;

/**
 * Student-facing controller for personal flashcard decks under
 * {@code /my/flashcards}. Any authenticated user may manage their own decks.
 *
 * <p>Authorization for individual decks is enforced at the service layer via
 * {@code DeckAccessResolver}: inaccessible decks raise 404, owner-only
 * mutations by a non-owner raise 403 (both mapped by {@code GlobalExceptionHandler}).
 */
@Controller
@RequestMapping(BASE_FLASHCARDS)
@PreAuthorize("isAuthenticated()")
public class StudentFlashcardController {

    private final DeckService deckService;
    private final DeckPublicLinkService publicLinkService;
    private final CardService cardService;
    private final SmartReviewService smartReviewService;
    private final FlashcardStudyService flashcardStudyService;
    private final ObjectMapper objectMapper;

    public StudentFlashcardController(DeckService deckService,
                                      DeckPublicLinkService publicLinkService,
                                      CardService cardService,
                                      SmartReviewService smartReviewService,
                                      FlashcardStudyService flashcardStudyService,
                                      ObjectMapper objectMapper) {
        this.deckService = deckService;
        this.publicLinkService = publicLinkService;
        this.cardService = cardService;
        this.smartReviewService = smartReviewService;
        this.flashcardStudyService = flashcardStudyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists the caller's own decks (SSR numbered pager) + decks shared to their
     * classes (unpaginated). The pager fragment computes its own button window
     * from {@code ownDecksPage} via {@link com.ksh.common.PageWindow}.
     */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "q", defaultValue = "") String query,
                       @AuthenticationPrincipal KshUserDetails user, Model model) {
        String normalizedQuery = query == null ? "" : query.trim();
        StudentDeckList decks = deckService.listForStudent(user.getId(), page, normalizedQuery);
        model.addAttribute(ATTR_DECKS_OWN_PAGE, decks.ownDecks());
        model.addAttribute(ATTR_DECKS_SHARED, decks.sharedDecks());
        model.addAttribute("q", normalizedQuery);
        model.addAttribute("searchParams", normalizedQuery.isEmpty()
                ? Map.of() : Map.of("q", normalizedQuery));
        return VIEW_FLASHCARD_LIST;
    }

    /** Renders the complete new-deck editor with two starter card rows. */
    @GetMapping("/new")
    public String newForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, DeckForm.empty());
        }
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_CARDS, starterCards());
        model.addAttribute("subjectOptions", deckService.activeSubjects());
        return VIEW_FLASHCARD_FORM;
    }

    /** Creates a PRIVATE deck and its initial cards in one editor submission. */
    @PostMapping
    public String create(@Valid @ModelAttribute("form") DeckForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails user,
                         @RequestParam(name = "cardsJson", defaultValue = "[]") String cardsJson,
                         Model model) {
        List<CardItem> cards = parseCreateCards(cardsJson, result);
        if (result.hasErrors()) {
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_CARDS, toCardViews(cards));
            model.addAttribute("subjectOptions", deckService.activeSubjects());
            return VIEW_FLASHCARD_FORM;
        }
        Long deckId = deckService.createDeckWithCards(user.getId(), form, cards).deckId();
        return "redirect:" + deckUrl(deckId);
    }

    /** Deck launcher page (owner or shared member): metadata + study launchers. */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model) {
        DeckDetailView deck = deckService.getDetail(id, user.getId());
        model.addAttribute(ATTR_DECK, deck);
        model.addAttribute(ATTR_SHARE_CLASSES, deck.shareClasses());
        model.addAttribute(ATTR_DUE_COUNT, smartReviewService.countDue(id, user.getId()));
        model.addAttribute(ATTR_CARDS, flashcardStudyService.getStudyCards(id, user.getId()));
        return VIEW_FLASHCARD_DETAIL;
    }

    /** Card editor page (owner-only): metadata + Quizlet-style card rows. */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal KshUserDetails user,
                           Model model) {
        DeckEditorView editor = cardService.getEditorView(id, user.getId());
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, new DeckForm(
                    editor.title(), editor.description(), editor.subjectId()));
        }
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_DECK, editor);
        model.addAttribute(ATTR_CARDS, editor.cards());
        model.addAttribute("subjectOptions", deckService.activeSubjects());
        return VIEW_FLASHCARD_FORM;
    }

    /** Saves deck metadata (cards are saved separately via AJAX before submit). */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") DeckForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            DeckEditorView editor = cardService.getEditorView(id, user.getId());
            model.addAttribute(ATTR_MODE, MODE_EDIT);
            model.addAttribute(ATTR_DECK, editor);
            model.addAttribute(ATTR_CARDS, editor.cards());
            model.addAttribute("subjectOptions", deckService.activeSubjects());
            return VIEW_FLASHCARD_FORM;
        }
        deckService.updateMetadata(id, user.getId(), form);
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_UPDATED);
        return "redirect:" + deckUrl(id);
    }

    /** Soft-deletes a deck; owner-only. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails user,
                         RedirectAttributes ra) {
        deckService.softDelete(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_DELETED);
        return "redirect:" + BASE_FLASHCARDS;
    }

    /** Shares a deck to one of the owner's classes; owner-only. */
    @PostMapping("/{id}/share")
    public String share(@PathVariable Long id,
                        @RequestParam(name = "classIds", required = false) List<Long> classIds,
                        @RequestParam(name = "classId", required = false) Long legacyClassId,
                        @AuthenticationPrincipal KshUserDetails user,
                        RedirectAttributes ra) {
        List<Long> targets = classIds == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(classIds);
        if (legacyClassId != null) targets.add(legacyClassId);
        deckService.syncShares(id, user.getId(), targets);
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_SHARED);
        return "redirect:" + deckUrl(id);
    }

    /** Reverts a deck to PRIVATE; owner-only. */
    @PostMapping("/{id}/unshare")
    public String unshare(@PathVariable Long id,
                          @RequestParam(name = "classId", required = false) Long classId,
                          @AuthenticationPrincipal KshUserDetails user,
                          RedirectAttributes ra) {
        if (classId == null) deckService.unshare(id, user.getId());
        else deckService.unshare(id, user.getId(), classId);
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_UNSHARED);
        return "redirect:" + deckUrl(id);
    }

    /** Enables a stable anonymous read-only link; owner-only. */
    @PostMapping("/{id}/public-link/enable")
    public String enablePublicLink(@PathVariable Long id,
                                   @AuthenticationPrincipal KshUserDetails user,
                                   RedirectAttributes ra) {
        publicLinkService.enable(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_PUBLIC_LINK_ENABLED);
        return "redirect:" + deckUrl(id) + "#share";
    }

    /** Disables anonymous access while preserving the current token. */
    @PostMapping("/{id}/public-link/disable")
    public String disablePublicLink(@PathVariable Long id,
                                    @AuthenticationPrincipal KshUserDetails user,
                                    RedirectAttributes ra) {
        publicLinkService.disable(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_PUBLIC_LINK_DISABLED);
        return "redirect:" + deckUrl(id) + "#share";
    }

    /** Replaces a leaked public token and immediately enables the new link. */
    @PostMapping("/{id}/public-link/regenerate")
    public String regeneratePublicLink(@PathVariable Long id,
                                       @AuthenticationPrincipal KshUserDetails user,
                                       RedirectAttributes ra) {
        publicLinkService.regenerate(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_PUBLIC_LINK_REGENERATED);
        return "redirect:" + deckUrl(id) + "#share";
    }

    /** Canonical URL for a single deck. Carries a path variable, so not a constant. */
    private static String deckUrl(Long id) {
        return BASE_FLASHCARDS + "/" + id;
    }

    private List<CardItem> parseCreateCards(String cardsJson, BindingResult result) {
        try {
            return objectMapper.readValue(cardsJson, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            result.reject("flashcards.cards.invalid", "Dữ liệu thẻ không hợp lệ, vui lòng thử lại");
            return List.of();
        }
    }

    private static List<CardView> starterCards() {
        return List.of(
                new CardView(null, "", "", null, null, null),
                new CardView(null, "", "", null, null, null)
        );
    }

    private static List<CardView> toCardViews(List<CardItem> cards) {
        if (cards == null || cards.isEmpty()) {
            return starterCards();
        }
        return cards.stream()
                .map(card -> new CardView(card.id(), card.front(), card.back(),
                        card.frontImage(), card.backImage(), card.alternativesJson()))
                .toList();
    }
}
