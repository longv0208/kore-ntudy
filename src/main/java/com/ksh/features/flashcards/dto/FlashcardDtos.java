package com.ksh.features.flashcards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.util.List;

/** View-model, form and API DTOs for the Flashcard feature (KSH-5.x). */
public final class FlashcardDtos {

    private FlashcardDtos() {
        // holder for records
    }

    /** Form payload for creating/editing a deck's metadata. */
    public record DeckForm(
            @NotBlank(message = "Tiêu đề không được để trống")
            @Size(max = 300, message = "Tiêu đề tối đa 300 ký tự")
            String title,
            @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
            String description,
            Long subjectId
    ) {
        public DeckForm(String title, String description) {
            this(title, description, null);
        }

        public static DeckForm empty() {
            return new DeckForm("", "", null);
        }
    }

    /** Active subject shown in the deck create/edit selector. */
    public record SubjectOption(Long id, String code, String name) {
    }

    /**
     * A deck row on the list / class page.
     *
     * @param id         deck id
     * @param title      deck title
     * @param cardCount  number of cards in the deck
     * @param shared     whether the deck is SHARED
     * @param owner      whether the caller owns the deck
     * @param ownerName  owner's full name (shown on shared decks)
     * @param className  target class name (shown on shared decks); null otherwise
     */
    public record DeckSummary(
            Long id, String title, String description, long cardCount,
            boolean shared, boolean owner, String ownerName, String className,
            Long subjectId, String subjectCode, String subjectName
    ) {
    }

    /**
     * A card as rendered in the editor and study views (text-only).
     *
     * @param id    card id (null for a brand-new row)
     * @param front front text
     * @param back  back text
     */
    public record CardView(Long id, String front, String back,
                           String frontImage, String backImage, String alternativesJson) {
        public CardView(Long id, String front, String back) {
            this(id, front, back, null, null, null);
        }
    }

    /** A submitted card item (bulk save); rich fields are optional. */
    public record CardItem(Long id, String front, String back,
                           String frontImage, String backImage, String alternativesJson) {
        public CardItem(Long id, String front, String back) {
            this(id, front, back, null, null, null);
        }
    }

    /** Bulk card-save request body. */
    public record SaveCardsRequest(List<CardItem> cards) {
    }

    /** Creates a deck and its initial cards through the editor JSON workflow. */
    public record CreateDeckRequest(String title, String description, List<CardItem> cards) {
    }

    /** Ordered persisted cards returned to the editor so deferred media can be uploaded. */
    public record DeckSaveResult(Long deckId, List<CardView> cards) {
    }

    /** Recall-rating request body (quality already mapped from the UI button). */
    public record ReviewRatingRequest(int quality) {
    }

    /** A single card row parsed from an imported Excel file (front/back text). */
    public record ImportedCardRow(String front, String back) {
    }

    /** Result of an Excel import: the parsed rows plus their count. */
    public record ImportResult(List<ImportedCardRow> cards, int count) {
    }

    /** A class the owner may share a deck to. */
    public record ClassOption(Long id, String name) {
    }

    /** Editor view-model: the deck plus its current cards + share targets. */
    public record DeckEditorView(Long deckId, String title, String description,
                                 List<CardView> cards, boolean shared, Long classId,
                                 List<ClassOption> shareClasses, Long subjectId) {
    }

    /** Response returned after recording a Smart-Review rating. */
    public record ReviewResult(int dueRemaining, int intervalDays) {
    }

    /** Detail view-model for a single deck (launcher page). */
    public record DeckDetailView(Long id, String title, String description,
                                 long cardCount, boolean owner, boolean shared,
                                 Long classId, String className,
                                 List<ClassOption> shareClasses, String ownerName,
                                 boolean publicLink, String shareToken,
                                 List<Long> sharedClassIds,
                                 List<ClassOption> sharedClasses) {
        public DeckDetailView(Long id, String title, String description,
                              long cardCount, boolean owner, boolean shared,
                              Long classId, String className,
                              List<ClassOption> shareClasses) {
            this(id, title, description, cardCount, owner, shared,
                    classId, className, shareClasses, null, false, null,
                    classId == null ? List.of() : List.of(classId), List.of());
        }
    }

    /** Anonymous, identity-free view of a deck reached by its public token. */
    public record PublicDeckView(String title, String description, long cardCount) {
    }

    /**
     * The two deck sections shown on the student list page. Only the "own decks"
     * section is paginated: {@code ownDecks} is one SSR page (newest-first) that
     * the numbered pager navigates via {@code ?page=N}. Shared decks are returned
     * in full (usually few) and never paginate.
     */
    public record StudentDeckList(Page<DeckSummary> ownDecks,
                                  List<DeckSummary> sharedDecks) {
    }
}
