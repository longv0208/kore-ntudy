package com.ksh.features.flashcards.service;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for {@link CardService}: bulk save, validation, authz. */
@SpringBootTest
@Transactional
class CardServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private CardService cardService;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User other;
    private Long deckId;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();
        other = userRepository.findByEmailIgnoreCase("sv02@ksh.edu.vn").orElseThrow();
        deckId = deckService.createDeck(owner.getId(), new DeckForm("Bộ thẻ", null));
    }

    @Test
    void bulk_save_persists_cards_in_order() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "front A", "back A"),
                new CardItem(null, "front B", "back B")));

        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).front()).isEqualTo("front A");
        assertThat(cards.get(1).back()).isEqualTo("back B");
    }

    @Test
    void blank_side_rejected_and_leaves_existing_unchanged() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "keep", "keep back")));

        assertThatThrownBy(() -> cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "ok", "ok"),
                new CardItem(null, "   ", "missing front"))))
                .isInstanceOf(IllegalArgumentException.class);

        // The original single card survives the aborted save.
        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).front()).isEqualTo("keep");
    }

    @Test
    void blank_back_rejected_and_leaves_existing_unchanged() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "keep front", "keep back")));

        assertThatThrownBy(() -> cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "valid front", "   "))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(cardService.getEditorView(deckId, owner.getId()).cards())
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.front()).isEqualTo("keep front");
                    assertThat(card.back()).isEqualTo("keep back");
                });
    }

    @Test
    void editing_kept_card_preserves_its_id() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "orig", "orig back")));
        Long cardId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();

        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(cardId, "edited", "edited back")));

        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo(cardId);
        assertThat(cards.get(0).front()).isEqualTo("edited");
    }

    @Test
    void non_owner_save_denied() {
        assertThatThrownBy(() -> cardService.replaceCards(deckId, other.getId(), List.of(
                new CardItem(null, "x", "y"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void removed_card_is_deleted() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "a", "a"),
                new CardItem(null, "b", "b")));
        Long keepId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();

        // Save only the first card → the second is dropped.
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(keepId, "a", "a")));

        assertThat(cardService.getEditorView(deckId, owner.getId()).cards()).hasSize(1);
    }

    @Test
    void null_and_empty_submission_clear_the_deck() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "one", "1"),
                new CardItem(null, "two", "2")));

        assertThat(cardService.replaceCards(deckId, owner.getId(), null)).isEmpty();
        assertThat(cardService.getEditorView(deckId, owner.getId()).cards()).isEmpty();
        assertThat(cardService.replaceCards(deckId, owner.getId(), List.of())).isEmpty();
    }

    @Test
    void replace_diffs_kept_new_removed_cards_and_preserves_submitted_order() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "A", "a"),
                new CardItem(null, "B", "b"),
                new CardItem(null, "C", "c")));
        List<CardView> initial = cardService.getEditorView(deckId, owner.getId()).cards();
        Long aId = initial.get(0).id();
        Long bId = initial.get(1).id();
        Long cId = initial.get(2).id();

        List<CardView> saved = cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(cId, "C edited", "c2"),
                new CardItem(null, "D", "d"),
                new CardItem(aId, "A edited", "a2")));

        assertThat(saved).extracting(CardView::front)
                .containsExactly("C edited", "D", "A edited");
        assertThat(saved).extracting(CardView::id)
                .containsExactly(cId, saved.get(1).id(), aId);
        assertThat(saved.get(1).id()).isNotEqualTo(bId);
        assertThat(cardService.getEditorView(deckId, owner.getId()).cards())
                .extracting(CardView::id).containsExactly(cId, saved.get(1).id(), aId);
    }

    @Test
    void duplicate_existing_id_is_applied_once_with_last_submitted_values() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "original", "back")));
        Long cardId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();

        List<CardView> result = cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(cardId, "first", "first back"),
                new CardItem(cardId, "last", "last back")));

        // The current contract treats a repeated existing id as an in-place
        // update; the last occurrence wins and only one row remains persisted.
        assertThat(result).hasSize(2);
        assertThat(result).extracting(CardView::id).containsExactly(cardId, cardId);
        assertThat(cardService.getEditorView(deckId, owner.getId()).cards())
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.id()).isEqualTo(cardId);
                    assertThat(card.front()).isEqualTo("last");
                    assertThat(card.back()).isEqualTo("last back");
                });
    }

    @Test
    void foreign_card_id_never_updates_foreign_deck_row() {
        Long foreignDeckId = deckService.createDeck(owner.getId(), new DeckForm("Foreign", null));
        cardService.replaceCards(foreignDeckId, owner.getId(), List.of(
                new CardItem(null, "foreign", "foreign back")));
        Long foreignCardId = cardService.getEditorView(foreignDeckId, owner.getId()).cards().get(0).id();

        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(foreignCardId, "local", "local back")));

        CardView local = cardService.getEditorView(deckId, owner.getId()).cards().get(0);
        CardView foreign = cardService.getEditorView(foreignDeckId, owner.getId()).cards().get(0);
        assertThat(local.id()).isNotEqualTo(foreignCardId);
        assertThat(local.front()).isEqualTo("local");
        assertThat(foreign.id()).isEqualTo(foreignCardId);
        assertThat(foreign.front()).isEqualTo("foreign");
    }
}
