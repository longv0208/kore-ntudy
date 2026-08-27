package com.ksh.features.lessons.repository;

import com.ksh.entities.LessonAttachment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link LessonAttachment}.
 *
 * <p>Attachments are hard-deleted (no {@code is_deleted} flag), so all
 * queries operate on the full table without a {@code @SQLRestriction} filter.
 */
public interface LessonAttachmentRepository extends JpaRepository<LessonAttachment, Long> {

    /** Lesson-level and direct class-material counts grouped by class. */
    @Query(value = """
            SELECT grouped.class_id AS classId, SUM(grouped.cnt) AS cnt
            FROM (
                SELECT s.class_id, COUNT(*) AS cnt
                FROM lesson_attachments a
                JOIN lessons l ON l.id = a.lesson_id AND l.is_deleted = 0
                JOIN sections s ON s.id = l.section_id
                WHERE s.class_id IN (:classIds)
                GROUP BY s.class_id
                UNION ALL
                SELECT a.class_id, COUNT(*) AS cnt
                FROM lesson_attachments a
                WHERE a.class_id IN (:classIds)
                GROUP BY a.class_id
            ) grouped
            GROUP BY grouped.class_id
            """, nativeQuery = true)
    List<ClassCount> countGroupedByClassIds(@Param("classIds") Collection<Long> classIds);

    interface ClassCount {
        Long getClassId();
        Long getCnt();
    }

    /** Lists attachments of a lesson ordered by upload time (oldest first). */
    List<LessonAttachment> findByLessonIdOrderByUploadedAtAsc(Long lessonId);

    /** Loads an attachment scoped by lesson to harden the URL hierarchy. */
    Optional<LessonAttachment> findByIdAndLessonId(Long id, Long lessonId);

    /** Duplicate guard for no-copy personal/canonical library bindings. */
    boolean existsByLessonIdAndLibraryAssetId(Long lessonId, Long libraryAssetId);

    /** Direct class Materials rows, newest first. */
    List<LessonAttachment> findByClassIdOrderByUploadedAtDescIdDesc(Long classId);

    /** URL-scope guard for a class material. */
    Optional<LessonAttachment> findByIdAndClassId(Long id, Long classId);

    /** Idempotency guard for one personal asset shared to one class. */
    boolean existsByClassIdAndLibraryAssetId(Long classId, Long libraryAssetId);

    /** Canonical subset replaced during an exact-provenance snapshot refresh. */
    List<LessonAttachment> findByLessonIdAndOriginScopeOrderByUploadedAtAsc(
            Long lessonId, String originScope);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LessonAttachment a WHERE a.id = :id")
    Optional<LessonAttachment> findByIdForUpdate(@Param("id") Long id);

    /**
     * Bulk-deletes every attachment row for the given lesson. The on-disk
     * files must be removed separately by the caller — see
     * {@code LessonAttachmentsService.deleteAllByLesson}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LessonAttachment a WHERE a.lessonId = :lessonId")
    int deleteByLessonId(@Param("lessonId") Long lessonId);

    /** Deletes one provenance family while preserving sibling attachment rows. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM LessonAttachment a
            WHERE a.lessonId = :lessonId AND a.originScope = :originScope
            """)
    int deleteByLessonIdAndOriginScope(@Param("lessonId") Long lessonId,
                                       @Param("originScope") String originScope);
}
