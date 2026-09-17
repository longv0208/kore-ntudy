package com.ksh.features.flashcards.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ksh.features.flashcards.entity.FlashcardReview;
import com.ksh.features.flashcards.repository.FlashcardReviewRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for {@link SmartReviewService}: due selection, upsert,
 *  scheduling, and per-user isolation. */
@SpringBootTest
@Transactional
class SmartReviewServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private CardService cardService;
    @Autowired private SmartReviewService smartReviewService;
    @Autowired private FlashcardReviewRepository reviewRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User member;
    private ClassEntity clazz;
    private Long deckId;
    private Long card1;
    private Long card2;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();
        member = userRepository.findByEmailIgnoreCase("sv02@ksh.edu.vn").orElseThrow();
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        clazz = saveClass(lecturer, "Review class", "RVCLS");
        enroll(owner);
        enroll(member);

        deckId = deckService.createDeck(owner.getId(), new DeckForm("Ôn tập", null));
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "c1", "b1"),
                new CardItem(null, "c2", "b2")));
        deckService.share(deckId, owner.getId(), clazz.getId());
        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        card1 = cards.get(0).id();
        card2 = cards.get(1).id();
    }

    @Test
    void new_cards_are_all_due() {
        assertThat(smartReviewService.getDueCards(deckId, owner.getId())).hasSize(2);
    }

    @Test
    void rating_upserts_single_row_and_removes_from_due() {
        smartReviewService.recordRating(card1, owner.getId(), 4);
        smartReviewService.recordRating(card1, owner.getId(), 5);

        // Exactly one review row for (owner, card1) despite two ratings.
        assertThat(reviewRepository.findByUserIdAndFlashcardId(owner.getId(), card1)).isPresent();
        long rows = reviewRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(owner.getId()) && r.getFlashcardId().equals(card1))
                .count();
        assertThat(rows).isEqualTo(1);

        // card1 no longer due (scheduled for the future); card2 still due.
        assertThat(smartReviewService.getDueCards(deckId, owner.getId()))
                .extracting(CardView::id).containsExactly(card2);
    }

    @Test
    void rating_persists_scheduler_state_and_future_due_date() {
        java.time.LocalDateTime before = java.time.LocalDateTime.now();

        smartReviewService.recordRating(card1, owner.getId(), 4);

        FlashcardReview review = reviewRepository
                .findByUserIdAndFlashcardId(owner.getId(), card1).orElseThrow();
        assertThat(review.getQuality()).isEqualTo(4);
        assertThat(review.getRepetitions()).isEqualTo(1);
        assertThat(review.getIntervalDays()).isEqualTo(1);
        assertThat(review.getReviewedAt()).isNotNull();
        assertThat(review.getNextReviewAt()).isAfter(before.plusHours(23));
        assertThat(review.getNextReviewAt())
                .isBefore(before.plusDays(1).plusMinutes(1));
    }

    @Test
    void rating_rejects_quality_outside_sm2_bounds_without_persisting() {
        assertThatThrownBy(() -> smartReviewService.recordRating(card1, owner.getId(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> smartReviewService.recordRating(card1, owner.getId(), 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reviewRepository.findByUserIdAndFlashcardId(owner.getId(), card1))
                .isEmpty();
    }

    @Test
    void rating_accepts_sm2_quality_boundaries() {
        smartReviewService.recordRating(card1, owner.getId(), 0);
        smartReviewService.recordRating(card2, owner.getId(), 5);

        assertThat(reviewRepository.findByUserIdAndFlashcardId(owner.getId(), card1))
                .get().extracting(FlashcardReview::getQuality).isEqualTo(0);
        assertThat(reviewRepository.findByUserIdAndFlashcardId(owner.getId(), card2))
                .get().extracting(FlashcardReview::getQuality).isEqualTo(5);
    }

    @Test
    void non_owner_cannot_rate_private_card_and_no_review_is_persisted() {
        Long privateDeckId = deckService.createDeck(owner.getId(), new DeckForm("Riêng tư", null));
        cardService.replaceCards(privateDeckId, owner.getId(),
                List.of(new CardItem(null, "private-front", "private-back")));
        Long privateCardId = cardService.getEditorView(privateDeckId, owner.getId())
                .cards().get(0).id();

        assertThatThrownBy(() -> smartReviewService.recordRating(
                privateCardId, member.getId(), 4))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(reviewRepository.findByUserIdAndFlashcardId(member.getId(), privateCardId))
                .isEmpty();
        assertThat(reviewRepository.findAll()).noneMatch(
                review -> review.getFlashcardId().equals(privateCardId));
    }

    @Test
    void poor_recall_keeps_interval_at_one() {
        smartReviewService.recordRating(card1, owner.getId(), 1);
        FlashcardReview r = reviewRepository
                .findByUserIdAndFlashcardId(owner.getId(), card1).orElseThrow();
        assertThat(r.getIntervalDays()).isEqualTo(1);
        assertThat(r.getRepetitions()).isZero();
    }

    @Test
    void successive_good_ratings_grow_interval() {
        smartReviewService.recordRating(card1, owner.getId(), 4); // interval 1
        smartReviewService.recordRating(card1, owner.getId(), 4); // interval 6
        smartReviewService.recordRating(card1, owner.getId(), 4); // interval 15
        FlashcardReview r = reviewRepository
                .findByUserIdAndFlashcardId(owner.getId(), card1).orElseThrow();
        assertThat(r.getIntervalDays()).isEqualTo(15);
        assertThat(r.getEasinessFactor()).isGreaterThanOrEqualTo(1.30);
    }

    @Test
    void review_state_is_isolated_per_user() {
        smartReviewService.recordRating(card1, owner.getId(), 4);

        // Owner's card1 is scheduled away; the member still sees it as due.
        assertThat(smartReviewService.getDueCards(deckId, owner.getId()))
                .extracting(CardView::id).doesNotContain(card1);
        assertThat(smartReviewService.getDueCards(deckId, member.getId()))
                .extracting(CardView::id).contains(card1, card2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void enroll(User u) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.REQUEST, null));
    }

    private ClassEntity saveClass(User lecturer, String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
