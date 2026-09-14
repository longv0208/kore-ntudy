package com.ksh.features.lessons.support;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.service.ClassRoleAccessPolicy;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Shared class-access policy for student-facing lesson list and detail views.
 *
 * <p>The rule: ADMIN bypasses enrollment globally, a LEADER bypasses enrollment
 * only inside their resolved subject, the owning lecturer passes, and
 * otherwise an ACTIVE enrollment is required. Any other caller collapses to a
 * single {@link EntityNotFoundException} with the canonical message so class
 * existence is never leaked.
 *
 * <p>This is intentionally separate from {@link LessonAccessResolver}, which
 * owns only lesson resolution + the PUBLISHED gate; access policy stays here so
 * each caller can order the two gates as its no-leak contract requires.
 */
@Component
public class ClassAccessPolicy {

    private static final String NF_MSG = "Class not found or not accessible";

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRoleAccessPolicy classRoleAccessPolicy;

    public ClassAccessPolicy(EnrollmentRepository enrollmentRepository,
                             ClassRoleAccessPolicy classRoleAccessPolicy) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRoleAccessPolicy = classRoleAccessPolicy;
    }

    /** ADMIN is global; leaders and teaching staff use the canonical class scope. */
    public boolean isPrivileged(ClassEntity clazz, Long userId, Role role) {
        return role == Role.ADMIN
                || ((role == Role.LEADER || role == Role.LECTURER)
                    && classRoleAccessPolicy.canAccess(clazz, userId, role));
    }

    /**
     * Admits the caller to the given live class or throws a no-leak 404.
     * ADMIN and subject-scoped LEADER callers bypass enrollment so they can
     * inspect the lesson; the owning lecturer passes, otherwise an ACTIVE
     * enrollment is required.
     *
     * @throws EntityNotFoundException when no rule admits the caller
     */
    public void requireViewAccess(ClassEntity clazz, Long userId, Role role) {
        // ADMIN is global; LEADER may open a live class only in their subject.
        if (isPrivileged(clazz, userId, role)) {
            return;
        }
        boolean enrolled = enrollmentRepository
                .findByUserIdAndClassId(userId, clazz.getId())
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .isPresent();
        if (!enrolled) {
            throw new EntityNotFoundException(NF_MSG);
        }
    }
}
