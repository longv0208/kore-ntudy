package com.ksh.features.flashcards.support;

import com.ksh.entities.Enrollment;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardDeckRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves a deck for a caller and enforces the flashcard authorization policy
 * (mirrors {@code LessonAccessResolver}).
 *
 * <ul>
 *   <li><b>OWNER</b> — full CRUD.</li>
 *   <li><b>SHARED_MEMBER</b> — an ACTIVE-enrolled member of a SHARED deck's
 *       class may view/study but not edit.</li>
 *   <li><b>NONE</b> — anyone else.</li>
 * </ul>
 *
 * <p>Read/study paths collapse NONE to a single {@link EntityNotFoundException}
 * so deck existence is never leaked; owner-only mutations attempted by a
 * non-owner raise {@link AccessDeniedException} (403).
 */
@Component
public class DeckAccessResolver {

    /** Canonical not-found message; identical for every inaccessible deck. */
    public static final String NF_MSG = "Deck not found or not accessible";

    /** Access level of a caller for a given deck. */
    public enum DeckAccess { OWNER, SHARED_MEMBER, NONE }

    /** A resolved deck plus the caller's access level. */
    public record ResolvedDeck(FlashcardDeck deck, DeckAccess access) {
        public boolean isOwner() {
            return access == DeckAccess.OWNER;
        }
    }

    private final FlashcardDeckRepository deckRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;

    public DeckAccessResolver(FlashcardDeckRepository deckRepository,
                              EnrollmentRepository enrollmentRepository,
                              ClassRepository classRepository) {
        this.deckRepository = deckRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
    }

    /**
     * Resolves the deck and the caller's access level. Never throws — returns
     * {@link DeckAccess#NONE} for callers with no relationship to the deck.
     *
     * @throws EntityNotFoundException if the deck does not exist or is deleted
     */
    public ResolvedDeck resolve(Long deckId, Long userId) {
        FlashcardDeck deck = deckRepository.findById(deckId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        // Guard the persistence-context cache: a just-soft-deleted deck may still
        // be returned by findById within the same tx even though @SQLRestriction
        // filters it at the DB level.
        if (deck.isDeleted()) {
            throw new EntityNotFoundException(NF_MSG);
        }
        if (deck.getOwnerId().equals(userId)) {
            return new ResolvedDeck(deck, DeckAccess.OWNER);
        }
        if (isSharedMember(deck, userId)) {
            return new ResolvedDeck(deck, DeckAccess.SHARED_MEMBER);
        }
        return new ResolvedDeck(deck, DeckAccess.NONE);
    }

    /**
     * Returns the deck when the caller may view/study it (OWNER or
     * SHARED_MEMBER); otherwise raises 404 so existence is not leaked.
     */
    public FlashcardDeck requireViewable(Long deckId, Long userId) {
        ResolvedDeck resolved = resolve(deckId, userId);
        if (resolved.access() == DeckAccess.NONE) {
            throw new EntityNotFoundException(NF_MSG);
        }
        return resolved.deck();
    }

    /**
     * Returns the deck when the caller is its owner; a non-owner referencing an
     * existing deck gets 403, matching the spec's "owner-only actions attempted
     * by a non-owner MUST return 403".
     */
    public FlashcardDeck requireOwner(Long deckId, Long userId) {
        ResolvedDeck resolved = resolve(deckId, userId);
        if (!resolved.isOwner()) {
            throw new AccessDeniedException(NF_MSG);
        }
        return resolved.deck();
    }

    /** SHARED deck whose classes include one where the caller is ACTIVE-enrolled. */
    private boolean isSharedMember(FlashcardDeck deck, Long userId) {
        if (!deck.isShared()) return false;
        Set<Long> classIds = new LinkedHashSet<>();
        if (deck.getSharedClassIds() != null) classIds.addAll(deck.getSharedClassIds());
        if (classIds.isEmpty() && deck.getClassId() != null) classIds.add(deck.getClassId());
        for (Long classId : classIds) {
            boolean liveClass = classRepository.findById(classId)
                    .filter(clazz -> !clazz.isDeleted())
                    .isPresent();
            if (liveClass && enrollmentRepository.findByUserIdAndClassId(userId, classId)
                    .map(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
