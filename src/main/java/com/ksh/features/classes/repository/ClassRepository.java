package com.ksh.features.classes.repository;

import com.ksh.entities.ClassEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

/**
 * Spring Data JPA repository for {@link ClassEntity}.
 *
 * <p>Because the entity is annotated with {@code @SQLRestriction("is_deleted = 0")},
 * every query issued through this repository automatically excludes soft-deleted
 * records without any additional filter in the calling code.
 */
public interface ClassRepository extends JpaRepository<ClassEntity, Long> {

    /**
     * Locks the class row for admission decisions. Acquiring this lock before
     * any ordinary read makes the subsequent capacity count observe approvals
     * committed by an earlier transaction under MySQL REPEATABLE READ.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ClassEntity c WHERE c.id = :id")
    Optional<ClassEntity> findByIdForUpdate(@Param("id") Long id);

    List<ClassEntity> findAllByLecturerIdOrderByCreatedAtDesc(Long lecturerId);

    List<ClassEntity> findAllByOrderByCreatedAtDesc();

    /**
     * Returns the (non-deleted) classes owned by the supplied lecturer.
     *
     * <p>The {@code @SQLRestriction("is_deleted = 0")} on {@link ClassEntity}
     * already filters out soft-deleted rows, so this method surfaces ONLY the
     * live ownership relationships used by lifecycle and ownership guards.
     */
    List<ClassEntity> findAllByLecturerId(Long lecturerId);

    /**
     * Paginated variant of {@link #findAllByLecturerId(Long)} used by the
     * lecturer class list to avoid loading the entire owned-class set into
     * memory.
     *
     * <p>Soft-deleted rows are filtered automatically by the entity's
     * {@code @SQLRestriction}. Sort direction is supplied by the caller via
     * {@link Pageable}; controllers typically pass
     * {@code Sort.by(Direction.DESC, "createdAt")}.
     */
    Page<ClassEntity> findAllByLecturerId(Long lecturerId, Pageable pageable);

    /** Classes where the lecturer is either the immutable owner or a co-lecturer. */
    @Query("""
            SELECT c
            FROM ClassEntity c
            WHERE c.lecturerId = :lecturerId
               OR c.id IN (
                    SELECT cc.classId
                    FROM ClassCoLecturer cc
                    WHERE cc.lecturerId = :lecturerId
               )
            """)
    Page<ClassEntity> findAllAccessibleToLecturer(@Param("lecturerId") Long lecturerId,
                                                   Pageable pageable);

    /** Owner/co-lecturer scope restricted to the lifecycle states selected by the list tab. */
    @Query("""
            SELECT c
            FROM ClassEntity c
            WHERE c.status IN :statuses
              AND (c.lecturerId = :lecturerId
                   OR c.id IN (
                        SELECT cc.classId
                        FROM ClassCoLecturer cc
                        WHERE cc.lecturerId = :lecturerId
                   ))
            """)
    Page<ClassEntity> findAllAccessibleToLecturerByStatuses(
            @Param("lecturerId") Long lecturerId,
            @Param("statuses") Collection<String> statuses,
            Pageable pageable);

    /** Count counterpart used for the current/archived tab badges. */
    @Query("""
            SELECT COUNT(c)
            FROM ClassEntity c
            WHERE c.status IN :statuses
              AND (c.lecturerId = :lecturerId
                   OR c.id IN (
                        SELECT cc.classId
                        FROM ClassCoLecturer cc
                        WHERE cc.lecturerId = :lecturerId
                   ))
            """)
    long countAccessibleToLecturerByStatuses(
            @Param("lecturerId") Long lecturerId,
            @Param("statuses") Collection<String> statuses);

    /** Non-paginated owner/co-lecturer scope used by class-backed authoring pickers. */
    @Query("""
            SELECT c
            FROM ClassEntity c
            WHERE c.lecturerId = :lecturerId
               OR c.id IN (
                    SELECT cc.classId
                    FROM ClassCoLecturer cc
                    WHERE cc.lecturerId = :lecturerId
               )
            ORDER BY c.createdAt DESC
            """)
    List<ClassEntity> findAllAccessibleToLecturerOrderByCreatedAtDesc(
            @Param("lecturerId") Long lecturerId);

    /**
     * Paginated variant of the all-non-deleted query used by LEADER / ADMIN
     * viewing the lecturer class list. The {@code @SQLRestriction} on
     * {@link ClassEntity} keeps soft-deleted rows out of the result.
     *
     * <p>The derived-query name {@code findAllBy} resolves to "select all
     * entities" because there are no property predicates after the
     * {@code By}; Spring Data interprets this as the no-arg find-all that
     * accepts a {@link Pageable}, equivalent to {@code findAll(Pageable)} but
     * with an explicit method on the repository for clarity.
     */
    Page<ClassEntity> findAllBy(Pageable pageable);

    /** Paginated lifecycle filter for the administrator class list. */
    Page<ClassEntity> findAllByStatusIn(Collection<String> statuses, Pageable pageable);

    long countByStatusIn(Collection<String> statuses);

    /**
     * Returns the distinct lecturer ids that teach any of the given classes.
     * Used by messaging's recipient gate to map a student's ACTIVE-enrollment
     * classes to the lecturers eligible to be messaged. Soft-deleted classes are
     * excluded by the entity's {@code @SQLRestriction}. Returns empty when
     * {@code classIds} is empty.
     *
     * @param classIds the classes to resolve lecturers from
     * @return distinct lecturer ids teaching those classes
     */
    @Query("SELECT DISTINCT c.lecturerId FROM ClassEntity c WHERE c.id IN :classIds")
    List<Long> findLecturerIdsForClasses(@Param("classIds") Collection<Long> classIds);

    /**
     * Returns the distinct ids of (non-deleted) classes taught by the given
     * lecturer. Used by messaging's recipient gate (lecturer → the students in
     * their classes).
     *
     * @param lecturerId the lecturer's user id
     * @return distinct class ids taught by that lecturer
     */
    @Query(value = """
            SELECT DISTINCT c.id
            FROM classes c
            LEFT JOIN class_co_lecturers cc ON cc.class_id = c.id
            WHERE c.is_deleted = 0
              AND (c.lecturer_id = :lecturerId OR cc.lecturer_id = :lecturerId)
            """, nativeQuery = true)
    List<Long> findClassIdsForLecturer(@Param("lecturerId") Long lecturerId);

    /** Non-deleted classes owned by a department, newest first. */
    List<ClassEntity> findAllBySubjectIdOrderByCreatedAtDesc(Long subjectId);

    /** Non-deleted classes owned by any subject curated by a multi-subject leader. */
    List<ClassEntity> findAllBySubjectIdInOrderByCreatedAtDesc(Collection<Long> subjectIds);

    List<ClassEntity> findAllBySubjectIdAndStatusOrderByCreatedAtDesc(
            Long subjectId, String status);

    /** Review queue ordering: an explicit resubmission must return to the top. */
    List<ClassEntity> findAllBySubjectIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long subjectId, String status);

    /** Paginated department-scoped class list. */
    Page<ClassEntity> findAllBySubjectId(Long subjectId, Pageable pageable);

    /** Paginated multi-subject class list for a leader. */
    Page<ClassEntity> findAllBySubjectIdIn(Collection<Long> subjectIds, Pageable pageable);

    /** Department scope plus lifecycle tab filter for subject leaders. */
    Page<ClassEntity> findAllBySubjectIdInAndStatusIn(Collection<Long> subjectIds,
                                                       Collection<String> statuses,
                                                       Pageable pageable);

    long countBySubjectIdInAndStatusIn(Collection<Long> subjectIds,
                                        Collection<String> statuses);

    long countBySubjectId(Long subjectId);

    List<ClassEntity> findAllByStatusAndEndDateLessThanEqual(String status, LocalDate endDate);

    List<ClassEntity> findAllByStatusOrderByCreatedAtDesc(String status);

    /** Searchable, paginated ACTIVE catalog for student discovery. */
    @Query("""
            SELECT c FROM ClassEntity c, Department s
            WHERE c.subjectId = s.id
              AND c.status = :status
              AND (:query = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(s.code) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY c.createdAt DESC
            """)
    Page<ClassEntity> searchActiveCatalog(@Param("status") String status,
                                          @Param("query") String query,
                                          Pageable pageable);
}
