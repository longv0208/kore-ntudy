package com.ksh.features.admin.users.service;

import com.ksh.common.TransactionLifecycle;
import com.ksh.entities.User;
import com.ksh.entities.UserActivity;
import com.ksh.entities.UserFactory;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.admin.subjects.service.SubjectService;
import com.ksh.features.admin.subjects.service.SubjectValidationException;
import com.ksh.features.admin.permissions.service.PermissionResolver;
import com.ksh.features.admin.settings.repository.SystemSettingsRepository;
import com.ksh.features.admin.users.dto.CreateUserForm;
import com.ksh.features.admin.users.dto.EditUserForm;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.admin.users.imports.service.ActivationMailComposer;
import com.ksh.features.auth.service.CredentialRotationService;
import com.ksh.features.profile.service.SessionRevocationService;
import com.ksh.security.Role;
import com.ksh.utils.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Create + update operations for the {@code /admin/users} screen.
 *
 * <p>Pulled out of the original {@code AdminUsersService} during the C.2
 * structural split. Handles persistence + audit for new-user creation and
 * admin-editable field updates (including allowed role changes).
 * Last-active-admin / self-role-change guards are delegated to
 * {@link AdminUsersGuard}; audit writes funnel through
 * {@link AdminUsersAuditWriter}.
 *
 * <p>Every mutation method is {@code @Transactional} and writes exactly one
 * row in {@code user_activities} per business mutation. If the audit insert
 * fails, the transaction rolls back. Mutations on {@link User} go through
 * the entity's business methods to keep the entity surface narrow.
 */
@Service
public class AdminUsersWriteService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUsersGuard guard;
    private final AdminUsersAuditWriter auditWriter;
    private final SubjectRepository subjectRepository;
    private final SystemSettingsRepository systemSettingsRepository;
    private final SessionRevocationService sessionRevocationService;
    private final CredentialRotationService credentialRotationService;
    private final PermissionResolver permissionResolver;
    private final ActivationMailComposer activationMailComposer;

    public AdminUsersWriteService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  AdminUsersGuard guard,
                                  AdminUsersAuditWriter auditWriter,
                                  SubjectRepository subjectRepository,
                                  SystemSettingsRepository systemSettingsRepository,
                                  SessionRevocationService sessionRevocationService,
                                  CredentialRotationService credentialRotationService,
                                  PermissionResolver permissionResolver,
                                  ActivationMailComposer activationMailComposer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.guard = guard;
        this.auditWriter = auditWriter;
        this.subjectRepository = subjectRepository;
        this.systemSettingsRepository = systemSettingsRepository;
        this.sessionRevocationService = sessionRevocationService;
        this.credentialRotationService = credentialRotationService;
        this.permissionResolver = permissionResolver;
        this.activationMailComposer = activationMailComposer;
    }

    /**
     * Persists a new user, BCrypt-encoding the chosen temporary password.
     * Email is normalised before the uniqueness check. Throws
     * {@link EmailAlreadyUsedException} if the email already exists.
     */
    @Transactional
    public User create(CreateUserForm form, Long actingUserId) {
        requireValidPassword(form.password(), "Mật khẩu tạm thời");
        String email = normalizeEmail(form.email());
        userRepository.findFirstByEmailIgnoreCase(email).ifPresent(u -> {
            throw new EmailAlreadyUsedException(email);
        });

        User u = UserFactory.newAdminCreated(
                email,
                passwordEncoder.encode(form.password()),
                form.fullName(),
                form.role(),
                form.emailVerified(),
                StringUtils.blankToNull(form.phone()),
                StringUtils.blankToNull(form.bio())
        );
        u.setSubjectId(form.subjectId());
        User saved = userRepository.save(u);

        auditWriter.write(saved.getId(), UserActivity.TYPE_CREATED,
                "Tạo tài khoản " + saved.getEmail(), null, actingUserId);
        return saved;
    }

    /** Updates the admin-editable fields of a user. */
    @Transactional
    public List<String> update(Long id, EditUserForm form, Long actingUserId) {
        lockLeaderAssignmentAnchor();
        User target = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
        requireLeaderAssignmentPreserved(target, form);

        // Self-role-change is forbidden.
        if (actingUserId != null && actingUserId.equals(target.getId())
                && form.role() != target.getRole()) {
            guard.requireNotSelf(actingUserId, target.getId(), "thay đổi vai trò");
        }

        // Last-admin demote guard runs only when the role is actually changing
        // away from ADMIN. It is safe to call even when role is unchanged; the
        // guard's own short-circuit handles it.
        guard.requireRoleNotDemotingLastAdmin(target, form.role());

        // Validate the general account-category invariant only after the more
        // specific security guards above. Self/last-admin attempts must remain
        // hard 403 denials instead of being downgraded to a form validation 200.
        guard.requireAllowedRoleTransition(target.getRole(), form.role());

        String newEmail = normalizeEmail(form.email());
        userRepository.findFirstByEmailIgnoreCaseAndIdNot(newEmail, target.getId())
                .ifPresent(other -> {
                    throw new EmailAlreadyUsedException(newEmail);
                });

        Map<String, Object> oldState = snapshot(target);

        Role oldRole = target.getRole();
        String oldEmail = target.getEmail();
        Long oldSubjectId = target.getSubjectId();
        target.updateAdminFields(
                newEmail,
                form.fullName(),
                form.role(),
                form.emailVerified(),
                form.phone(),
                form.bio(),
                form.subjectId()
        );
        User saved = userRepository.save(target);

        Map<String, Object> newState = snapshot(saved);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("old", oldState);
        diff.put("new", newState);
        auditWriter.write(saved.getId(), UserActivity.TYPE_UPDATED,
                "Cập nhật tài khoản " + saved.getEmail(),
                auditWriter.serialize(diff), actingUserId);

        // Role-change activity is recorded as a separate row so audit consumers
        // can filter on `type = ROLE_CHANGED` without parsing UPDATED metadata.
        if (oldRole != saved.getRole()) {
            Map<String, Object> rolePayload = new LinkedHashMap<>();
            rolePayload.put("oldRole", oldRole.name());
            rolePayload.put("newRole", saved.getRole().name());
            auditWriter.write(saved.getId(), UserActivity.TYPE_ROLE_CHANGED,
                    "Đổi vai trò: " + oldRole + " → " + saved.getRole(),
                    auditWriter.serialize(rolePayload), actingUserId);
        }

        if (!Objects.equals(oldEmail, saved.getEmail())
                || oldRole != saved.getRole()
                || !Objects.equals(oldSubjectId, saved.getSubjectId())) {
            Long changedUserId = saved.getId();
            boolean roleChanged = oldRole != saved.getRole();
            TransactionLifecycle.afterCommit(() -> {
                if (roleChanged) {
                    permissionResolver.evictUser(changedUserId);
                }
                sessionRevocationService.revokeAllSessions(changedUserId);
            });
        }

        if (!Objects.equals(oldEmail, saved.getEmail())) {
            credentialRotationService.invalidateRecoveryTokens(saved.getId());
            if (saved.isPendingActivation()) {
                activationMailComposer.issueAndQueue(saved, actingUserId);
            }
        }

        return List.of();
    }

    // ── Internals ─────────────────────────────────────────────────

    private static String normalizeEmail(String raw) {
        if (raw == null) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireValidPassword(String password, String fieldName) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(fieldName + " không được để trống");
        }
        if (password.length() < 6 || password.length() > 64) {
            throw new IllegalArgumentException(fieldName + " phải có từ 6 đến 64 ký tự");
        }
    }

    /**
     * A user referenced by {@code subjects.leader_user_id} must be reassigned
     * or cleared from the Subject screen before their role/subject can be
     * changed here. The shared anchor is acquired before the user row so this
     * check cannot race the subject assignment workflow or invert lock order.
     */
    private void requireLeaderAssignmentPreserved(User target, EditUserForm form) {
        subjectRepository.findFirstByLeaderUserId(target.getId())
                .ifPresent(subject -> {
                    boolean preserved = form.role() == Role.LEADER
                            && Objects.equals(form.subjectId(), subject.getId());
                    if (!preserved) {
                        throw new SubjectValidationException(
                                "Người dùng đang là trưởng môn "
                                        + subject.getName()
                                        + ". Hãy đổi hoặc gỡ trưởng môn tại màn hình Môn học trước.");
                    }
                });
    }

    private void lockLeaderAssignmentAnchor() {
        systemSettingsRepository.findBySettingKeyForUpdate(
                        SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing subject leader assignment lock row: "
                                + SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY));
    }

    private static Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole() != null ? u.getRole().name() : null);
        m.put("emailVerified", u.isEmailVerified());
        m.put("phone", u.getPhone());
        m.put("bio", u.getBio());
        m.put("subjectId", u.getSubjectId());
        return m;
    }
}
