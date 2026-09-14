package com.ksh.features.admin.subjects.repository;

import com.ksh.entities.Subject;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Subject}.
 */
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    /** Locks one subject row for state mutations such as toggles and leader assignment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Subject d WHERE d.id = :id")
    Optional<Subject> findByIdForUpdate(@Param("id") Long id);

    List<Subject> findAllByOrderByNameAsc();

    List<Subject> findByActiveTrueOrderByNameAsc();

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    Optional<Subject> findFirstByLeaderUserId(Long leaderUserId);

    List<Subject> findByLeaderUserIdOrderByCodeAsc(Long leaderUserId);

    boolean existsByLeaderUserId(Long leaderUserId);

    /**
     * Enforces the product rule that one user can lead at most one subject.
     * Leader mutations are serialized by the service before this predicate runs.
     */
    boolean existsByLeaderUserIdAndIdNot(Long leaderUserId, Long id);

    long countByLeaderUserId(Long leaderUserId);
}
