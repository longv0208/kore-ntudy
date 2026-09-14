package com.ksh.features.admin.subjects.service;

import com.ksh.entities.Subject;
import com.ksh.entities.SubjectActivity;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectForm;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.admin.settings.repository.SystemSettingsRepository;
import com.ksh.features.admin.permissions.service.PermissionResolver;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.profile.service.SessionRevocationService;
import com.ksh.common.TransactionLifecycle;
import com.ksh.security.Role;
import com.ksh.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Write-side subject mutations: create/update/toggle and leader assignment
 * with promote/demote rules. Audit rows go through {@link SubjectAuditWriter}.
 * Read queries live on {@link SubjectQueryService}.
 */
@Service
public class SubjectService {

    static final String MSG_NOT_FOUND = "Không tìm thấy môn học";
    static final String MSG_CODE_EXISTS = "Mã môn đã tồn tại";
    static final String MSG_LEADER_NOT_FOUND = "Không tìm thấy người dùng để gán trưởng môn";
    static final String MSG_LEADER_INELIGIBLE =
            "Trưởng môn phải là giảng viên hoặc trưởng môn đang hoạt động";
    static final String MSG_LEADER_ALREADY_ASSIGNED =
            "Người dùng này đang là trưởng của một môn học khác";

    /**
     * V1 guarantees this legacy placeholder row and the multi-provider feature
     * leaves its value unused. Locking it gives leader assignment a database
     * mutex even when the subjects table is empty, without a schema change.
     */
    public static final String LEADER_ASSIGNMENT_LOCK_SETTING_KEY = "ai.provider";

    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final SubjectAuditWriter auditWriter;
    private final SystemSettingsRepository systemSettingsRepository;
    private final SessionRevocationService sessionRevocationService;
    private final PermissionResolver permissionResolver;

    public SubjectService(SubjectRepository subjectRepository,
                             UserRepository userRepository,
                             SubjectAuditWriter auditWriter,
                             SystemSettingsRepository systemSettingsRepository,
                             SessionRevocationService sessionRevocationService,
                             PermissionResolver permissionResolver) {
        this.subjectRepository = subjectRepository;
        this.userRepository = userRepository;
        this.auditWriter = auditWriter;
        this.systemSettingsRepository = systemSettingsRepository;
        this.sessionRevocationService = sessionRevocationService;
        this.permissionResolver = permissionResolver;
    }

    @Transactional
    public String create(SubjectForm form, Long actorId) {
        String code = normalizeCode(form.code());
        if (subjectRepository.existsByCode(code)) {
            throw new SubjectValidationException(MSG_CODE_EXISTS);
        }
        Subject entity = new Subject(
                form.name().trim(),
                code,
                StringUtils.blankToNull(form.description()),
                form.active());
        if (form.leaderUserId() != null) {
            lockLeaderAssignmentAnchor();
        }
        Subject saved = subjectRepository.save(entity);
        auditWriter.write(saved.getId(), SubjectActivity.TYPE_CREATED,
                "Tạo môn học " + saved.getName() + " (" + saved.getCode() + ")",
                null, actorId);

        if (form.leaderUserId() != null) {
            applyLeaderAssignment(saved, form.leaderUserId(), actorId);
            subjectRepository.save(saved);
        }
        return saved.getName();
    }

    @Transactional
    public void update(Long id, SubjectForm form, Long actorId) {
        // The edit form can replace or clear a leader. Anchor first so every
        // leader mutation follows one global lock order.
        lockLeaderAssignmentAnchor();
        Subject entity = subjectRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new SubjectValidationException(MSG_NOT_FOUND));
        String code = normalizeCode(form.code());
        if (subjectRepository.existsByCodeAndIdNot(code, id)) {
            throw new SubjectValidationException(MSG_CODE_EXISTS);
        }

        Map<String, Object> changes = diffFields(entity, form, code);
        boolean activeChanged = entity.isActive() != form.active();

        entity.applyEdit(
                form.name().trim(),
                code,
                StringUtils.blankToNull(form.description()),
                form.active());
        applyLeaderAssignment(entity, form.leaderUserId(), actorId);
        subjectRepository.save(entity);

        // Identity/description changes → UPDATED (active handled as dedicated type).
        Map<String, Object> fieldChanges = new LinkedHashMap<>(changes);
        fieldChanges.remove("active");
        if (!fieldChanges.isEmpty()) {
            auditWriter.write(entity.getId(), SubjectActivity.TYPE_UPDATED,
                    "Cập nhật thông tin môn học",
                    auditWriter.serialize(fieldChanges), actorId);
        }
        // Visibility flip on the edit form (list toggle endpoint also writes this type).
        if (activeChanged) {
            boolean nowActive = form.active();
            auditWriter.write(entity.getId(),
                    nowActive ? SubjectActivity.TYPE_ACTIVATED : SubjectActivity.TYPE_DEACTIVATED,
                    nowActive ? "Hiện môn học" : "Ẩn môn học",
                    null, actorId);
        }
    }

    @Transactional
    public boolean toggleActive(Long id, Long actorId) {
        Subject entity = subjectRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new SubjectValidationException(MSG_NOT_FOUND));
        boolean now = entity.toggleActive();
        subjectRepository.save(entity);
        auditWriter.write(entity.getId(),
                now ? SubjectActivity.TYPE_ACTIVATED : SubjectActivity.TYPE_DEACTIVATED,
                now ? "Hiện môn học" : "Ẩn môn học",
                null, actorId);
        return now;
    }

    /**
     * Assigns or clears the subject leader with promote/demote side effects.
     *
     * @param subjectId target subject
     * @param leaderUserId   new leader, or null to unassign
     * @param actorId      admin performing the action (audit)
     */
    @Transactional
    public void assignLeader(Long subjectId, Long leaderUserId, Long actorId) {
        lockLeaderAssignmentAnchor();
        Subject entity = subjectRepository.findByIdForUpdate(subjectId)
                .orElseThrow(() -> new SubjectValidationException(MSG_NOT_FOUND));
        applyLeaderAssignment(entity, leaderUserId, actorId);
        subjectRepository.save(entity);
    }

    private void applyLeaderAssignment(Subject entity, Long newLeaderUserId, Long actorId) {
        Long oldLeaderId = entity.getLeaderUserId();
        if (oldLeaderId == null && newLeaderUserId == null) {
            return;
        }

        Map<Long, User> affectedUsers = lockAffectedUsers(oldLeaderId, newLeaderUserId);
        String newLeaderEmail = null;
        User candidate = null;
        if (newLeaderUserId != null) {
            candidate = Optional.ofNullable(affectedUsers.get(newLeaderUserId))
                    .orElseThrow(() -> new SubjectValidationException(MSG_LEADER_NOT_FOUND));
            if (!candidate.isActive() || candidate.isDeleted()
                    || !SubjectQueryService.LEADER_ELIGIBLE.contains(candidate.getRole())) {
                throw new SubjectValidationException(MSG_LEADER_INELIGIBLE);
            }
            // One Korean subject leader may curate multiple subject-code rows.
        }

        if (Objects.equals(oldLeaderId, newLeaderUserId)) {
            // A nominal no-op still repairs/checks both sides of the invariant.
            // This closes legacy drift where the subject pointer survived an
            // out-of-band user role/subject edit.
            if (candidate != null
                    && (candidate.getRole() != Role.LEADER
                    || !Objects.equals(candidate.getSubjectId(), entity.getId()))) {
                candidate.promoteToLeader(entity.getId());
                userRepository.save(candidate);
                Long repairedLeaderId = candidate.getId();
                TransactionLifecycle.afterCommit(() -> {
                    permissionResolver.evictUser(repairedLeaderId);
                    sessionRevocationService.revokeAllSessions(repairedLeaderId);
                });
            }
            return;
        }

        if (candidate != null) {
            candidate.promoteToLeader(entity.getId());
            userRepository.save(candidate);
            newLeaderEmail = candidate.getEmail();
        }

        entity.assignLeader(newLeaderUserId);
        // Flush leader_user_id before demote check so DB no longer lists the old leader.
        subjectRepository.saveAndFlush(entity);

        // Demote the previous leader only when they lead no other subject.
        if (oldLeaderId != null && !oldLeaderId.equals(newLeaderUserId)) {
            demoteIfNoLongerLeader(affectedUsers.get(oldLeaderId));
        }

        List<Long> changedPrincipalIds = Stream.of(oldLeaderId, newLeaderUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        TransactionLifecycle.afterCommit(() -> changedPrincipalIds.forEach(userId -> {
            permissionResolver.evictUser(userId);
            sessionRevocationService.revokeAllSessions(userId);
        }));

        if (entity.getId() != null) {
            if (newLeaderUserId == null) {
                auditWriter.write(entity.getId(), SubjectActivity.TYPE_LEADER_CLEARED,
                        "Bỏ gán trưởng môn", null, actorId);
            } else {
                auditWriter.write(entity.getId(), SubjectActivity.TYPE_LEADER_ASSIGNED,
                        "Gán trưởng môn: " + (newLeaderEmail != null ? newLeaderEmail : newLeaderUserId),
                        null, actorId);
            }
        }
    }

    /**
     * Locks every affected user in ascending ID order after the global anchor
     * and target subject have been locked. Missing former leaders are
     * tolerated (for legacy/soft-deleted data); a missing new leader is rejected
     * by the caller before mutation.
     */
    private Map<Long, User> lockAffectedUsers(Long oldLeaderId, Long newLeaderUserId) {
        List<Long> ids = Stream.of(oldLeaderId, newLeaderUserId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, User> locked = new HashMap<>();
        for (Long id : ids) {
            userRepository.findByIdForUpdate(id)
                    .ifPresent(user -> locked.put(id, user));
        }
        return locked;
    }

    private void demoteIfNoLongerLeader(User user) {
        if (user == null || subjectRepository.existsByLeaderUserId(user.getId())) {
            return;
        }
        if (user.getRole() == Role.LEADER) {
            user.demoteFromLeaderToLecturer();
            userRepository.save(user);
        }
    }

    /**
     * Serializes all leader-pointer and role/subject mutations across app
     * nodes. Failing closed is intentional: continuing without the V1 seed row
     * would silently reintroduce the race.
     */
    private void lockLeaderAssignmentAnchor() {
        systemSettingsRepository
                .findBySettingKeyForUpdate(LEADER_ASSIGNMENT_LOCK_SETTING_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing subject leader assignment lock row: "
                                + LEADER_ASSIGNMENT_LOCK_SETTING_KEY));
    }

    /** Builds a map of changed identity fields for UPDATED audit metadata. */
    private static Map<String, Object> diffFields(Subject entity, SubjectForm form, String code) {
        Map<String, Object> changes = new LinkedHashMap<>();
        String newName = form.name().trim();
        String newDesc = StringUtils.blankToNull(form.description());
        if (!Objects.equals(entity.getName(), newName)) {
            changes.put("name", Map.of("from", nullToEmpty(entity.getName()), "to", newName));
        }
        if (!Objects.equals(entity.getCode(), code)) {
            changes.put("code", Map.of("from", nullToEmpty(entity.getCode()), "to", code));
        }
        if (!Objects.equals(entity.getDescription(), newDesc)) {
            changes.put("description", true);
        }
        if (entity.isActive() != form.active()) {
            changes.put("active", Map.of("from", entity.isActive(), "to", form.active()));
        }
        return changes;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeCode(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }
}
