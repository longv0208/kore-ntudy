package com.ksh.features.tests.repository;

import com.ksh.features.tests.entity.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Test}. The entity's {@code @SQLRestriction("is_deleted
 * = 0")} already filters soft-deleted rows from every query below.
 */
public interface TestRepository extends JpaRepository<Test, Long> {

    /** Serializes mutations that can change an exam's question-bank shape. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Test t where t.id = :id")
    Optional<Test> findByIdForUpdate(@Param("id") Long id);

    /** Published exams for the given classes (student list / readiness pool). */
    List<Test> findByClassIdInAndStatusOrderByUpdatedAtDesc(Collection<Long> classIds, String status);

    /** Published exams for the given classes limited to the given types (readiness pool). */
    List<Test> findByClassIdInAndStatusAndTypeInOrderByUpdatedAtDesc(
            Collection<Long> classIds, String status, Collection<String> types);

    /** The caller's own tests of a given type (own PRACTICE tests). */
    List<Test> findByCreatedByAndTypeOrderByUpdatedAtDesc(Long createdBy, String type);

    /**
     * Exams the lecturer owns — created by them OR belonging to a class they
     * lead. {@code classIds} must be non-empty (callers pass a sentinel when the
     * lecturer leads no class) so the JPQL {@code IN} clause stays valid.
     */
    @Query("SELECT t FROM Test t WHERE t.createdBy = :userId OR t.classId IN :classIds")
    Page<Test> findOwnedByLecturer(@Param("userId") Long userId,
                                    @Param("classIds") Collection<Long> classIds,
                                    Pageable pageable);

    @Query("SELECT t FROM Test t WHERE "
            + "(:admin = true OR (:includeCreated = true AND t.createdBy = :userId) OR t.classId IN :classIds OR t.subjectId IN :subjectIds)"
            + " AND (:admin = false OR t.type <> 'PRACTICE')"
            + " AND (:classId IS NULL OR t.classId = :classId)"
            + " AND (:status IS NULL OR t.status = :status)"
            + " AND (:type IS NULL OR t.type = :type)"
            + " AND LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Test> searchManageable(@Param("userId") Long userId,
                                @Param("classIds") Collection<Long> classIds,
                                @Param("subjectIds") Collection<Long> subjectIds,
                                @Param("admin") boolean admin,
                                @Param("includeCreated") boolean includeCreated,
                                @Param("classId") Long classId,
                                @Param("keyword") String keyword,
                                @Param("status") String status,
                                @Param("type") String type,
                                Pageable pageable);

    /**
     * Exams attached to classes inside one LEADER department scope.
     * The collection must be non-empty; callers use a sentinel when needed.
     */
    Page<Test> findByClassIdIn(Collection<Long> classIds, Pageable pageable);

    /**
     * Global ADMIN test-management list. PRACTICE rows stay out of the lecturer
     * management area so this broader role does not change Practice ownership.
     */
    Page<Test> findByTypeNot(String type, Pageable pageable);

    /** Exams belonging to a single class (class-detail "Bài test" tab). */
    Page<Test> findByClassId(Long classId, Pageable pageable);

    /** All non-Practice tests in one class, used to build the derived gradebook. */
    @Query("SELECT t FROM Test t WHERE t.classId = :classId AND t.type <> 'PRACTICE' ORDER BY t.updatedAt DESC")
    List<Test> findGradebookTestsByClassId(@Param("classId") Long classId);

    /** Prevents accidental duplicate snapshots when a distribution is retried. */
    boolean existsByClassIdAndTitleIgnoreCase(Long classId, String title);

    /**
     * Published exams in a single class whose title contains {@code title}
     * (case-insensitive), newest-updated first. Backs the class-scoped student
     * tests page ({@code GET /my/classes/{classId}/tests}); an empty title
     * matches everything.
     */
    Page<Test> findByClassIdAndStatusAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
            Long classId, String status, String title, Pageable pageable);
}
