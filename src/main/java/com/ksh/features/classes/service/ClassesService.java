package com.ksh.features.classes.service;

import com.ksh.entities.ClassActivity;
import com.ksh.entities.ClassEntity;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.dto.ClassesDtos.ClassRow;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.assignments.repository.AssignmentRepository;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Business service for class CRUD operations on the lecturer-facing screens.
 *
 * <p>Authorization rules (enforced here, NOT in the controller):
 * <ul>
 *   <li>Owner and co-lecturers can access teaching content; subject leaders retain read scope.</li>
 *   <li>Only the immutable owner (or ADMIN) can edit/delete class-owned state.</li>
 *   <li>Authorization violations throw {@link AccessDeniedException} → HTTP 403.</li>
 *   <li>Non-existent or soft-deleted classes throw {@link EntityNotFoundException} → HTTP 404.</li>
 * </ul>
 *
 * <p>Caller identity is supplied directly by controllers from
 * {@code @AuthenticationPrincipal KshUserDetails} as {@code (Long userId, Role role)}.
 * The service does not look up the caller by email — Spring Security has already
 * loaded the user during authentication, so a second SELECT per request would be
 * wasted work.
 *
 * <p>Every mutation (create/update/softDelete) writes one row to
 * {@link ClassActivity} via {@link ClassActivityWriter}. Because service methods
 * are {@code @Transactional}, a failure when inserting the activity record will
 * also roll back the class mutation. The create flow is delegated to a
 * package-private {@link ClassCreator} helper that owns subject validation and
 * the collision-retry loop.
 */
@Service
public class ClassesService {

    private final ClassRepository classRepository;
    private final ClassActivityWriter activityWriter;
    private final DepartmentRepository subjectRepository;
    private final ClassCreator creator;
    private final ClassRoleAccessPolicy accessPolicy;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final AssignmentRepository assignmentRepository;
    private final LessonAttachmentRepository attachmentRepository;

    public ClassesService(ClassRepository classRepository,
                          ClassActivityWriter activityWriter,
                          DepartmentRepository subjectRepository,
                          ClassRoleAccessPolicy accessPolicy,
                          EnrollmentRepository enrollmentRepository,
                          LessonRepository lessonRepository,
                          AssignmentRepository assignmentRepository,
                          LessonAttachmentRepository attachmentRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.classRepository = classRepository;
        this.activityWriter = activityWriter;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
        this.enrollmentRepository = enrollmentRepository;
        this.lessonRepository = lessonRepository;
        this.assignmentRepository = assignmentRepository;
        this.attachmentRepository = attachmentRepository;
        this.creator = new ClassCreator(classRepository, activityWriter,
                subjectRepository, eventPublisher);
    }

    // ───────────────────── Public CRUD API ──────────────────────────

    /**
     * Returns the page of classes visible to the current user.
     * LECTURER → only their own classes.
     * LEADER/ADMIN → all classes that have not been soft-deleted.
     *
     * <p>The gradient assigned to each {@link ClassRow} is derived from the row's
     * position within the CURRENT page (0-based), not the global ranking. Different
     * pages can therefore repeat gradient colours — this is intentional and matches
     * the audit's "good enough" tolerance for the cosmetic ordering of class
     * thumbnails. Pages are otherwise sorted strictly per the supplied {@link Pageable}
     * (typically {@code createdAt DESC}).
     */
    @Transactional(readOnly = true)
    public Page<ClassRow> listForUser(Long userId, Role role, Pageable pageable) {
        Page<ClassEntity> page;
        if (role == Role.LECTURER) {
            page = classRepository.findAllAccessibleToLecturer(userId, pageable);
        } else if (role == Role.LEADER) {
            List<Long> subjectIds = accessPolicy.leaderSubjectIds(userId);
            page = subjectIds.isEmpty()
                    ? Page.empty(pageable)
                    : classRepository.findAllBySubjectIdIn(subjectIds, pageable);
        } else if (role == Role.ADMIN) {
            page = classRepository.findAllBy(pageable);
        } else {
            page = Page.empty(pageable);
        }

        return mapRows(page, pageable);
    }

    /**
     * Returns one lifecycle tab of the class list without mixing archived
     * classes into the operational PENDING/REJECTED/ACTIVE view.
     */
    @Transactional(readOnly = true)
    public Page<ClassRow> listForUserByStatuses(Long userId, Role role,
                                                Collection<String> statuses,
                                                Pageable pageable) {
        if (statuses == null || statuses.isEmpty()) {
            return Page.empty(pageable);
        }

        Page<ClassEntity> page;
        if (role == Role.LECTURER) {
            page = classRepository.findAllAccessibleToLecturerByStatuses(userId, statuses, pageable);
        } else if (role == Role.LEADER) {
            List<Long> subjectIds = accessPolicy.leaderSubjectIds(userId);
            page = subjectIds.isEmpty()
                    ? Page.empty(pageable)
                    : classRepository.findAllBySubjectIdInAndStatusIn(subjectIds, statuses, pageable);
        } else if (role == Role.ADMIN) {
            page = classRepository.findAllByStatusIn(statuses, pageable);
        } else {
            page = Page.empty(pageable);
        }
        return mapRows(page, pageable);
    }

    /** Returns the role-scoped total for a lifecycle tab badge. */
    @Transactional(readOnly = true)
    public long countForUserByStatuses(Long userId, Role role, Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return 0L;
        }
        if (role == Role.LECTURER) {
            return classRepository.countAccessibleToLecturerByStatuses(userId, statuses);
        }
        if (role == Role.LEADER) {
            List<Long> subjectIds = accessPolicy.leaderSubjectIds(userId);
            return subjectIds.isEmpty()
                    ? 0L
                    : classRepository.countBySubjectIdInAndStatusIn(subjectIds, statuses);
        }
        if (role == Role.ADMIN) {
            return classRepository.countByStatusIn(statuses);
        }
        return 0L;
    }

    private Page<ClassRow> mapRows(Page<ClassEntity> page, Pageable pageable) {
        List<ClassEntity> content = page.getContent();
        List<Long> classIds = content.stream().map(ClassEntity::getId).toList();
        Map<Long, Long> studentCounts = new HashMap<>();
        Map<Long, Long> lessonCounts = new HashMap<>();
        Map<Long, Long> assignmentCounts = new HashMap<>();
        Map<Long, Long> materialCounts = new HashMap<>();
        if (!classIds.isEmpty()) {
            enrollmentRepository.countActiveGroupedByClassIds(classIds)
                    .forEach(r -> studentCounts.put(r.getClassId(), r.getCnt()));
            lessonRepository.countLiveGroupedByClassIds(classIds)
                    .forEach(r -> lessonCounts.put(r.getClassId(), r.getCnt()));
            assignmentRepository.countLiveGroupedByClassIds(classIds)
                    .forEach(r -> assignmentCounts.put(r.getClassId(), r.getCnt()));
            attachmentRepository.countGroupedByClassIds(classIds)
                    .forEach(r -> materialCounts.put(r.getClassId(), r.getCnt()));
        }
        Map<Long, String> subjectCodes = new HashMap<>();
        subjectRepository.findAllById(content.stream().map(ClassEntity::getSubjectId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(subject -> subjectCodes.put(subject.getId(), subject.getCode()));
        List<ClassRow> rows = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            ClassEntity entity = content.get(i);
            rows.add(ClassRowMapper.toRow(entity, i,
                    subjectCodes.getOrDefault(entity.getSubjectId(), "—"),
                    studentCounts.getOrDefault(entity.getId(), 0L),
                    lessonCounts.getOrDefault(entity.getId(), 0L),
                    assignmentCounts.getOrDefault(entity.getId(), 0L),
                    materialCounts.getOrDefault(entity.getId(), 0L)));
        }
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /** Loads a class for editing after enforcing authorization. */
    @Transactional(readOnly = true)
    public ClassEntity getEditable(Long id, Long userId, Role role) {
        return loadEditable(id, userId, role);
    }

    /** Loads a class for owner-only administration such as settings or member import. */
    @Transactional(readOnly = true)
    public ClassEntity getOwnerManaged(Long id, Long userId, Role role) {
        return loadOwnerManaged(id, userId, role);
    }

    /** Locks an editable class so sibling append operations share one mutex row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClassEntity getEditableForUpdate(Long id, Long userId, Role role) {
        ClassEntity entity = classRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        if (!isEditableBy(entity, userId, role)) {
            throw new AccessDeniedException("Bạn không có quyền chỉnh sửa lớp này");
        }
        return entity;
    }

    /**
     * Loads a class for the detail view (members, board, ...). Applies the
     * same authorization as {@link #getEditable}: LECTURER may only access
     * their own classes; LEADER may access classes in their resolved department,
     * while ADMIN may access any class. The viewable
     * and editable code paths are kept separate so a future sprint can
     * relax the read-side rule (for example, allowing students enrolled in
     * a class to read the board) without touching the edit-side rule.
     */
    @Transactional(readOnly = true)
    public ClassEntity getViewable(Long id, Long userId, Role role) {
        return loadEditable(id, userId, role);
    }

    /**
     * Creates a new class. Delegates code collision retry, required subject
     * binding and the review notification to {@link ClassCreator}.
     */
    @Transactional
    public ClassEntity create(ClassForm form, Long userId) {
        return creator.create(form, userId);
    }

    /** Updates an existing class. Authorization is enforced; writes an UPDATED activity row with a before/after diff. */
    @Transactional
    public ClassEntity update(Long id, ClassForm form, Long userId, Role role) {
        ClassEntity entity = loadOwnerManaged(id, userId, role);

        Map<String, Object> oldState = ClassRowMapper.snapshot(entity);
        entity.updateDetails(form.name(), form.description(),
                form.startDate(), form.endDate(), form.maxStudents());
        ClassEntity saved = classRepository.save(entity);

        Map<String, Object> newState = ClassRowMapper.snapshot(saved);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("old", oldState);
        diff.put("new", newState);

        activityWriter.write(
                saved.getId(),
                ClassActivity.TYPE_UPDATED,
                "Cập nhật lớp " + saved.getName(),
                diff,
                userId
        );
        return saved;
    }

    /**
     * Explicitly returns a rejected class to the leader approval queue. Editing
     * it does not silently change its lifecycle state; the owner chooses this
     * action only after the rejection has been addressed.
     */
    @Transactional
    public ClassEntity resubmitForReview(Long id, Long userId, Role role) {
        ClassEntity entity = loadOwnerManaged(id, userId, role);
        if (!entity.resubmitForReview()) {
            throw new IllegalStateException("Chỉ lớp bị từ chối mới có thể gửi duyệt lại");
        }
        ClassEntity saved = classRepository.save(entity);
        activityWriter.write(saved.getId(), ClassActivity.TYPE_UPDATED,
                "Gửi duyệt lại lớp " + saved.getName(), userId);
        subjectRepository.findById(saved.getSubjectId())
                .map(subject -> subject.getCode())
                .ifPresent(code -> creator.publishPendingReview(saved, code));
        return saved;
    }

    /** Soft-deletes a class. Authorization is enforced; writes a DELETED activity row. */
    @Transactional
    public void softDelete(Long id, Long userId, Role role) {
        ClassEntity entity = loadOwnerManaged(id, userId, role);

        entity.softDelete();
        classRepository.save(entity);
        activityWriter.write(
                entity.getId(),
                ClassActivity.TYPE_DELETED,
                "Xoá lớp " + entity.getName(),
                userId
        );
    }

    // ──────────────────────── Internal ──────────────────────────────

    /**
     * Returns whether the caller is authorised to edit the given class.
     * LEADER may edit classes in their resolved department; ADMIN may edit any
     * class; LECTURER may only edit classes they own.
     */
    public boolean isEditableBy(ClassEntity clazz, Long userId, Role role) {
        if (role == null) return false;
        return accessPolicy.canAccess(clazz, userId, role);
    }

    /** Loads the class and enforces the editable-by authorisation rule. */
    private ClassEntity loadEditable(Long id, Long userId, Role role) {
        ClassEntity entity = classRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        if (!isEditableBy(entity, userId, role)) {
            throw new AccessDeniedException("Bạn không có quyền chỉnh sửa lớp này");
        }
        return entity;
    }

    /** Loads the class and preserves its immutable owner boundary. */
    private ClassEntity loadOwnerManaged(Long id, Long userId, Role role) {
        ClassEntity entity = classRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        if (!accessPolicy.canManageClass(entity, userId, role)) {
            throw new AccessDeniedException("Chỉ giảng viên chủ lớp mới được quản trị lớp này");
        }
        return entity;
    }
}
