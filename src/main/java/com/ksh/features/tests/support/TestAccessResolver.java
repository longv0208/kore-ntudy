package com.ksh.features.tests.support;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.service.ClassRoleAccessPolicy;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.entity.TestAttempt;
import com.ksh.features.tests.repository.TestAttemptRepository;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves a {@link Test} for a caller and enforces the exam authorization
 * policy (mirrors {@code DeckAccessResolver}).
 *
 * <ul>
 *   <li><b>Student</b> may take an exam iff it is PUBLISHED + not deleted + they
 *       are ACTIVE-enrolled in its class, OR they own the PRACTICE test.
 *       Otherwise 404 (existence never leaked).</li>
 *   <li><b>Test management</b> follows the canonical class policy: ADMIN is
 *       global, LEADER is department-scoped, and LECTURER is owner-scoped.
 *       A lecturer also retains access to an exam they created, matching the
 *       pre-department behavior. Student-owned PRACTICE tests remain isolated
 *       from these elevated class-management rules.</li>
 *   <li>Attempt/review access is strictly per-user.</li>
 * </ul>
 */
@Component
public class TestAccessResolver {

    /** Canonical not-found message; identical for every inaccessible exam. */
    public static final String NF_MSG = "Không tìm thấy bài test hoặc bạn không có quyền truy cập";
    /** Canonical attempt not-found message. */
    public static final String ATTEMPT_NF_MSG = "Không tìm thấy lượt làm bài";

    private final TestRepository testRepository;
    private final TestAttemptRepository attemptRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final ClassRoleAccessPolicy classAccessPolicy;

    public TestAccessResolver(TestRepository testRepository,
                              TestAttemptRepository attemptRepository,
                              EnrollmentRepository enrollmentRepository,
                              ClassRepository classRepository,
                              UserRepository userRepository,
                              ClassRoleAccessPolicy classAccessPolicy) {
        this.testRepository = testRepository;
        this.attemptRepository = attemptRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.classAccessPolicy = classAccessPolicy;
    }

    /**
     * Returns the exam if the student may take/view it; otherwise 404 so
     * existence is not leaked.
     */
    public Test requireAttemptable(Long testId, Long userId) {
        return requireViewable(testId, userId);
    }

    /**
     * Authorizes the read-only student landing page. Scheduling and attempt
     * limits are enforced only when the student explicitly starts the exam.
     */
    public Test requireViewable(Long testId, Long userId) {
        Test test = loadOrNotFound(testId);
        return requireStudentAccess(test, userId);
    }

    /** Returns and locks an accessible exam while a new attempt is created. */
    public Test requireAttemptableForUpdate(Long testId, Long userId) {
        Test test = testRepository.findByIdForUpdate(testId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        if (test.isDeleted()) {
            throw new EntityNotFoundException(NF_MSG);
        }
        return requireStudentAccess(test, userId);
    }

    private Test requireStudentAccess(Test test, Long userId) {
        // Own PRACTICE test: always accessible to its owner.
        if (test.isPractice() && userId.equals(test.getCreatedBy())) {
            return test;
        }
        // Published class exam: accessible to ACTIVE-enrolled students.
        if (test.isPublished() && isActiveEnrolled(userId, test.getClassId())) {
            return test;
        }
        throw new EntityNotFoundException(NF_MSG);
    }

    /**
     * Returns the exam if the actor may manage it under their current persisted
     * role. A denied actor referencing an existing exam gets 403, while a
     * missing/deleted exam gets 404.
     */
    public Test requireManageable(Long testId, Long userId) {
        Test test = loadOrNotFound(testId);
        return requireManageable(test, userId, managementRole(userId));
    }

    /**
     * Role-aware variant for callers that already carry the authenticated role.
     * The role is still evaluated by the canonical class policy.
     */
    public Test requireManageable(Long testId, Long userId, Role role) {
        Test test = loadOrNotFound(testId);
        return requireManageable(test, userId, role);
    }

    /**
     * Returns and locks an owned exam for a transaction that mutates its question bank.
     */
    public Test requireManageableForUpdate(Long testId, Long userId) {
        return requireManageableForUpdate(testId, userId, managementRole(userId));
    }

    /** Role-aware locking variant for test-management mutations. */
    public Test requireManageableForUpdate(Long testId, Long userId, Role role) {
        Test test = testRepository.findByIdForUpdate(testId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        if (test.isDeleted()) {
            throw new EntityNotFoundException(NF_MSG);
        }
        return requireManageable(test, userId, role);
    }

    /**
     * Resolves a non-Practice test as a shared Test Bank source.  Publishing a
     * test makes its read/distribution contract available to every educator;
     * it deliberately does <em>not</em> grant editing, monitoring, deletion or
     * question-bank mutation rights.  Those operations must continue to call
     * {@link #requireManageable(Long, Long, Role)}.
     *
     * <p>The caller's target classes are still checked independently before a
     * copy is made.  This method only opens the source snapshot.
     */
    public Test requireSharedTestBankSource(Long testId, Long userId, Role role,
                                            boolean forUpdate) {
        if (userId == null || !isEducatorRole(role)) {
            throw new AccessDeniedException(NF_MSG);
        }
        Test test = forUpdate
                ? testRepository.findByIdForUpdate(testId)
                    .orElseThrow(() -> new EntityNotFoundException(NF_MSG))
                : loadOrNotFound(testId);
        if (test.isDeleted()) {
            throw new EntityNotFoundException(NF_MSG);
        }
        if (test.isPractice()) {
            throw new AccessDeniedException(NF_MSG);
        }
        return test;
    }

    /**
     * A published, non-Practice Test Bank entry is previewable by every
     * educator. Draft and archived entries keep the normal management scope.
     */
    public Test requirePreviewableFromTestBank(Long testId, Long userId, Role role) {
        Test test = loadOrNotFound(testId);
        if (userId != null && !test.isPractice() && test.isPublished()
                && isEducatorRole(role)) {
            return test;
        }
        return requireManageable(test, userId, role);
    }

    private Test requireManageable(Test test, Long userId, Role role) {
        // Do not broaden management of student-owned Practice data.
        if (test.isPractice()) {
            if (userId != null && userId.equals(test.getCreatedBy())) {
                return test;
            }
            throw new AccessDeniedException(NF_MSG);
        }
        if (role == Role.ADMIN) {
            return test;
        }
        // Preserve creator ownership for ordinary lecturer-authored exams.
        if (role == Role.LECTURER && userId != null && userId.equals(test.getCreatedBy())) {
            return test;
        }
        if (role == Role.LEADER && test.getSubjectId() != null
                && classAccessPolicy.leaderSubjectIds(userId).contains(test.getSubjectId())) {
            return test;
        }
        if (test.getClassId() != null) {
            ClassEntity clazz = classRepository.findById(test.getClassId()).orElse(null);
            if (clazz != null && classAccessPolicy.canAccess(clazz, userId, role)) {
                return test;
            }
        }
        throw new AccessDeniedException(NF_MSG);
    }

    private static boolean isEducatorRole(Role role) {
        return role == Role.LECTURER || role == Role.LEADER || role == Role.ADMIN;
    }

    /**
     * Current persisted management role. This fallback keeps non-MVC callers
     * (including AI authoring) on the same policy without consulting a hidden
     * Spring Security context.
     */
    public Role managementRole(Long userId) {
        Role role = userRepository.findById(userId)
                .map(user -> user.getRole())
                .orElseThrow(() -> new AccessDeniedException(NF_MSG));
        if (role != Role.LECTURER && role != Role.LEADER && role != Role.ADMIN) {
            throw new AccessDeniedException(NF_MSG);
        }
        return role;
    }

    /** Classes available in the lecturer test picker/list for the supplied role. */
    public List<ClassEntity> manageableClasses(Long userId, Role role) {
        if (role == Role.ADMIN) {
            return classRepository.findAllByOrderByCreatedAtDesc();
        }
        if (role == Role.LEADER) {
            List<Long> subjectIds = classAccessPolicy.leaderSubjectIds(userId);
            return subjectIds.isEmpty()
                    ? List.of()
                    : classRepository.findAllBySubjectIdInOrderByCreatedAtDesc(subjectIds);
        }
        if (role == Role.LECTURER) {
            return classRepository.findAllAccessibleToLecturerOrderByCreatedAtDesc(userId);
        }
        return List.of();
    }

    /**
     * Whether the actor may author an independent Test Bank item for a subject.
     * A subject is the stable authoring scope; a class is only an optional first
     * recipient. This mirrors Question Bank access without coupling the Test
     * feature to a controller-only model.
     */
    public boolean canManageSubject(Long userId, Role role, Long subjectId) {
        if (userId == null || role == null || subjectId == null) {
            return false;
        }
        if (role == Role.ADMIN || role == Role.LECTURER) {
            return true;
        }
        return role == Role.LEADER
                && classAccessPolicy.leaderSubjectIds(userId).contains(subjectId);
    }

    /** Loads a class and enforces the same ADMIN/LEADER/LECTURER scope. */
    public ClassEntity requireManageableClass(Long classId, Long userId, Role role) {
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        if (!classAccessPolicy.canAccess(clazz, userId, role)) {
            throw new AccessDeniedException(NF_MSG);
        }
        return clazz;
    }

    /** Returns the caller's own attempt; otherwise 404 (never leaks another user's). */
    public TestAttempt requireOwnAttempt(Long attemptId, Long userId) {
        return attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(ATTEMPT_NF_MSG));
    }

    /**
     * Returns and locks the caller's attempt for a lifecycle mutation.
     *
     * <p>The caller must already be inside a transaction. Holding this lock
     * through grading prevents two submit requests from both observing
     * {@code IN_PROGRESS}, and prevents a heartbeat from racing finalization.
     */
    public TestAttempt requireOwnAttemptForUpdate(Long attemptId, Long userId) {
        return attemptRepository.findByIdAndUserIdForUpdate(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(ATTEMPT_NF_MSG));
    }

    /** Loads an attempt for a lecturer who owns the given exam (submissions review). */
    public TestAttempt requireAttemptForManageable(Long testId, Long attemptId, Long userId) {
        requireManageable(testId, userId);
        TestAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(ATTEMPT_NF_MSG));
        if (!attempt.getTestId().equals(testId)) {
            throw new EntityNotFoundException(ATTEMPT_NF_MSG);
        }
        return attempt;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Test loadOrNotFound(Long testId) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        // Guard the persistence-context cache against a just-soft-deleted test.
        if (test.isDeleted()) {
            throw new EntityNotFoundException(NF_MSG);
        }
        return test;
    }

    private boolean isActiveEnrolled(Long userId, Long classId) {
        if (classId == null) return false;
        boolean liveClass = classRepository.findById(classId)
                .filter(clazz -> !clazz.isDeleted())
                .isPresent();
        if (!liveClass) return false;
        return enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .map(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElse(false);
    }

}
