package com.ksh.features.flashcards.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ksh.features.flashcards.dto.FlashcardDtos.PublicDeckView;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import com.ksh.features.flashcards.service.DeckPublicLinkService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static com.ksh.common.IConstant.ATTR_CARDS;
import static com.ksh.common.IConstant.ATTR_DECK;
import static com.ksh.common.IConstant.PATH_PUBLIC_DECK;
import static com.ksh.common.IConstant.VIEW_PUBLIC_DECK;

/** Anonymous, read-only flashcard deck view protected by a high-entropy token. */
@Controller
public class PublicDeckController {

    private final DeckPublicLinkService publicLinkService;
    private final FlashcardRepository cardRepository;
    private final ObjectMapper objectMapper;

    public PublicDeckController(DeckPublicLinkService publicLinkService,
                                FlashcardRepository cardRepository,
                                ObjectMapper objectMapper) {
        this.publicLinkService = publicLinkService;
        this.cardRepository = cardRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping(PATH_PUBLIC_DECK + "/{token}")
    public String view(@PathVariable String token, Model model) {
        FlashcardDeck deck = resolve(token);
        List<CardView> cards = cards(deck);
        model.addAttribute(ATTR_DECK,
                new PublicDeckView(deck.getTitle(), deck.getDescription(), cards.size()));
        model.addAttribute(ATTR_CARDS, cards);
        model.addAttribute("publicToken", token);
        return VIEW_PUBLIC_DECK;
    }

    @GetMapping(PATH_PUBLIC_DECK + "/{token}/flip")
    public String flip(@PathVariable String token, Model model) {
        FlashcardDeck deck = resolve(token);
        List<CardView> cards = cards(deck);
        populatePublicStudy(model, token, deck, cards);
        return "flashcards/flashcard-flip";
    }

    @GetMapping(PATH_PUBLIC_DECK + "/{token}/{mode:learn|test|match|blast|tiles|word-search|word-connect}")
    public String learning(@PathVariable String token,
                           @PathVariable String mode,
                           Model model) {
        FlashcardDeck deck = resolve(token);
        List<CardView> cards = cards(deck);
        populatePublicStudy(model, token, deck, cards);
        model.addAttribute("activeMode", mode);
        model.addAttribute("matchDeckOptionsJson", "[]");
        model.addAttribute("matchSelectedDeckIdsJson", "[]");
        return "flashcards/flashcard-learning";
    }

    private void populatePublicStudy(Model model, String token,
                                     FlashcardDeck deck, List<CardView> cards) {
        model.addAttribute(ATTR_DECK, new DeckDetailView(
                deck.getId(), deck.getTitle(), deck.getDescription(), cards.size(),
                false, false, null, null, List.of()));
        model.addAttribute("cardsJson", toJson(cards));
        model.addAttribute("publicStudy", true);
        model.addAttribute("publicToken", token);
        model.addAttribute("studyMixedDeckIds", List.of());
        model.addAttribute("studySelectedDeckIds", Set.of(deck.getId()));
        model.addAttribute("studySessionTitle", deck.getTitle());
    }

    private FlashcardDeck resolve(String token) {
        try {
            return publicLinkService.resolvePublic(token);
        } catch (EntityNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private List<CardView> cards(FlashcardDeck deck) {
        return cardRepository.findByDeckIdOrderBySortOrderAsc(deck.getId()).stream()
                .map(card -> new CardView(card.getId(), card.getFrontText(), card.getBackText(),
                        card.getFrontImage(), card.getBackImage(), card.getAlternativesJson()))
                .toList();
    }

    private String toJson(List<CardView> cards) {
        try {
            return objectMapper.writeValueAsString(cards);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to render public flashcards", exception);
        }
    }
}
