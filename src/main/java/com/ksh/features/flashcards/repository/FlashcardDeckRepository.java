package com.ksh.features.flashcards.repository;

import com.ksh.features.flashcards.entity.FlashcardDeck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link FlashcardDeck}. The entity's
 * {@code @SQLRestriction("is_deleted = 0")} already filters soft-deleted rows
 * from every query below, so callers never see deleted decks.
 */
public interface FlashcardDeckRepository extends JpaRepository<FlashcardDeck, Long> {

    /** Non-deleted decks owned by the caller, newest-updated first. */
    List<FlashcardDeck> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    Optional<FlashcardDeck> findFirstByOwnerIdAndTitleOrderByIdAsc(Long ownerId, String title);

    /** Resolves an anonymous public link; soft-deleted rows remain filtered. */
    Optional<FlashcardDeck> findByShareToken(String shareToken);

    /**
     * One page of the caller's non-deleted own decks. Ordering is governed by the
     * {@link Pageable}'s sort (newest-first by created_at + id in the service). The
     * entity's {@code @SQLRestriction} already excludes soft-deleted decks, so this
     * needs no explicit deleted filter.
     */
    Page<FlashcardDeck> findByOwnerId(Long ownerId, Pageable pageable);

    /** Searches owned decks by deck title or canonical subject code/name. */
    @Query("""
            SELECT d FROM FlashcardDeck d
            WHERE d.ownerId = :ownerId
              AND (:keyword = ''
                   OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR EXISTS (SELECT s.id FROM Department s
                              WHERE s.id = d.subjectId
                                AND (LOWER(s.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                     OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')))))
            """)
    Page<FlashcardDeck> searchOwned(@Param("ownerId") Long ownerId,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    /**
     * SHARED decks targeting any of the given classes, excluding the caller's
     * own decks (those already appear in the "own" list). Newest-updated first.
     */
    List<FlashcardDeck> findByVisibilityOrderByUpdatedAtDesc(String visibility);

    /** Searches class-shared decks by deck title or canonical subject code/name. */
    @Query("""
            SELECT d FROM FlashcardDeck d
            WHERE d.visibility = :visibility
              AND d.classId IN :classIds
              AND d.ownerId <> :ownerId
              AND (:keyword = ''
                   OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR EXISTS (SELECT s.id FROM Department s
                              WHERE s.id = d.subjectId
                                AND (LOWER(s.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                     OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')))))
            ORDER BY d.updatedAt DESC
            """)
    List<FlashcardDeck> searchShared(@Param("visibility") String visibility,
                                     @Param("classIds") Collection<Long> classIds,
                                     @Param("ownerId") Long ownerId,
                                     @Param("keyword") String keyword);

    /** SHARED decks targeting a single class (class-page surface). */
    List<FlashcardDeck> findByVisibilityAndClassIdOrderByUpdatedAtDesc(
            String visibility, Long classId);
}
