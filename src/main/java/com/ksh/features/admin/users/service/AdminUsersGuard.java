package com.ksh.features.admin.users.service;

import com.ksh.security.Role;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting guards enforced before any admin mutation on a user account.
 *
 * <p>Two guard concerns are encapsulated here:
 * <ul>
 *   <li><b>Self-protection</b> — the authenticated admin SHALL NOT lock,
 *       deactivate, delete, demote-role, or reset-password their own
 *       account from this screen.</li>
 *   <li><b>Last-active-admin protection</b> — the system SHALL refuse any
 *       operation that would leave zero users with
 *       {@code role = ADMIN AND is_active = 1 AND is_locked = 0 AND
 *       is_deleted = 0}.</li>
 * </ul>
 *
 * <p>Race-condition handling: callers that can remove a usable administrator
 * acquire {@code UserRepository.findAdminLifecycleMutexForUpdate} before
 * locking their target. A target-only lock cannot serialize two transactions
 * acting on different administrators.
 *
 * <p>This component is intentionally pure: it never mutates state and never
 * starts its own transaction. That lets {@code AdminUsersGuardTest} unit
 * test every branch with mocked repository calls.
 */
@Component
public class AdminUsersGuard {

    private static final String INVALID_ROLE_TRANSITION_MESSAGE =
            "Không thể chuyển đổi giữa tài khoản học viên, giảng viên và quản trị viên. "
                    + "Chỉ được đổi vai trò Giảng viên ↔ Trưởng môn; trường hợp đổi loại tài khoản phải tạo tài khoản mới.";

    private final UserRepository userRepository;

    public AdminUsersGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Throws if the acting admin is targeting their own account.
     *
     * @param actorId  authenticated admin's user ID
     * @param targetId the user ID being mutated
     * @param action   short description used in the exception message
     *                 (e.g. {@code "deactivate"})
     */
    public void requireNotSelf(Long actorId, Long targetId, String action) {
        if (actorId != null && actorId.equals(targetId)) {
            throw new AccessDeniedException(
                    "Bạn không thể " + action + " chính tài khoản của mình.");
        }
    }

    /**
     * Throws if removing the supplied target from the active-admin pool would
     * empty that pool. The check is meaningful only when the target itself
     * IS an active, unlocked, non-deleted admin — otherwise removing them has no
     * effect on the pool count.
     *
     * @param target the user whose admin status is about to change
     * @param action short description for the error message
     */
    public void requireNotLastActiveAdmin(User target, String action) {
        if (target.getRole() != Role.ADMIN) return;
        if (!target.isActive())             return;
        if (target.isLocked())              return;
        if (target.isDeleted())             return;

        long usableAdmins = userRepository.countActiveAdmins(Role.ADMIN.name());
        if (usableAdmins <= 1) {
            throw new AccessDeniedException(
                    "Không thể " + action + " quản trị viên cuối cùng đang hoạt động.");
        }
    }

    /**
     * Throws if the edit form is moving the supplied admin target to a
     * non-ADMIN role AND the target is currently the last usable admin.
     *
     * @param target  the user being edited
     * @param newRole the role being assigned by the edit form
     */
    public void requireRoleNotDemotingLastAdmin(User target, Role newRole) {
        if (target.getRole() != Role.ADMIN) return;
        if (newRole == Role.ADMIN)          return;
        if (!target.isActive())             return;
        if (target.isLocked())              return;
        if (target.isDeleted())             return;

        long usableAdmins = userRepository.countActiveAdmins(Role.ADMIN.name());
        if (usableAdmins <= 1) {
            throw new AccessDeniedException(
                    "Không thể hạ vai trò của quản trị viên cuối cùng đang hoạt động.");
        }
    }

    /** Student, staff and administrator are distinct account categories. */
    public void requireAllowedRoleTransition(Role currentRole, Role requestedRole) {
        if (currentRole == requestedRole) return;
        boolean staffTransition = (currentRole == Role.LECTURER || currentRole == Role.LEADER)
                && (requestedRole == Role.LECTURER || requestedRole == Role.LEADER);
        if (!staffTransition) {
            throw new InvalidRoleTransitionException(INVALID_ROLE_TRANSITION_MESSAGE);
        }
    }
}
