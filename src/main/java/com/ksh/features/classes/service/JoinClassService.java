package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.notifications.entity.NotificationType;
import com.ksh.features.notifications.service.NotificationService;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecturer-controlled membership approval and student leave flow. */
@Service
public class JoinClassService {

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ClassesService classesService;
    private final JoinAuditWriter auditWriter;

    public JoinClassService(EnrollmentRepository enrollmentRepository,
                            ClassRepository classRepository,
                            ClassActivityWriter activityWriter,
                            UserRepository userRepository,
                            NotificationService notificationService,
                            ClassesService classesService) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.classesService = classesService;
        this.auditWriter = new JoinAuditWriter(activityWriter);
    }

    public sealed interface JoinResult permits Success, AlreadyJoined, PendingRequested {}
    public record Success(ClassEntity clazz) implements JoinResult {}
    public record AlreadyJoined(ClassEntity clazz) implements JoinResult {}
    public record PendingRequested(ClassEntity clazz, boolean alreadyPending)
            implements JoinResult {}

    /** Legacy seam retained fail-closed; learner self-enrollment is disabled. */
    @Transactional
    public JoinResult requestJoin(Long classId, Long userId) {
        throw new AccessDeniedException(
                "Sinh viên không thể tự gửi yêu cầu tham gia lớp; giảng viên phải thêm sinh viên.");
    }

    @Transactional
    public ClassEntity leave(Long classId, Long userId) {
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy enrollment"));
        if (Enrollment.STATUS_REMOVED.equals(row.getStatus())) {
            throw new EntityNotFoundException("Không tìm thấy enrollment");
        }
        if (Enrollment.STATUS_COMPLETED.equals(row.getStatus())) {
            throw new IllegalStateException("Không thể rời lớp đã hoàn thành");
        }
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        row.markRemoved();
        enrollmentRepository.save(row);
        auditWriter.writeLeave(clazz, userId);
        return clazz;
    }

    @Transactional
    public ClassEntity approve(Long classId, Long studentUserId, Long actorId, Role actorRole) {
        classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        ClassEntity clazz = requireOwner(classId, actorId, actorRole);
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(studentUserId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy yêu cầu tham gia"));
        if (!Enrollment.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("Yêu cầu không ở trạng thái chờ duyệt");
        }
        enforceCapacity(clazz);
        row.activateFromPending();
        enrollmentRepository.save(row);
        emitApprovedNotifications(clazz, studentUserId);
        return clazz;
    }

    @Transactional
    public ClassEntity reject(Long classId, Long studentUserId, Long actorId, Role actorRole) {
        ClassEntity clazz = requireOwner(classId, actorId, actorRole);
        Enrollment row = enrollmentRepository.findByUserIdAndClassId(studentUserId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy yêu cầu tham gia"));
        if (!Enrollment.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("Yêu cầu không ở trạng thái chờ duyệt");
        }
        row.markRejected();
        enrollmentRepository.save(row);
        emitRejectedNotification(clazz, studentUserId);
        return clazz;
    }

    private void enforceCapacity(ClassEntity clazz) {
        Integer cap = clazz.getMaxStudents();
        if (cap == null) return;
        classRepository.findByIdForUpdate(clazz.getId());
        if (enrollmentRepository.countActiveByClassIdForUpdate(clazz.getId()) >= cap) {
            throw new IllegalStateException("Lớp đã đủ sĩ số");
        }
    }

    private ClassEntity requireOwner(Long classId, Long actorId, Role actorRole) {
        ClassEntity clazz = classesService.getEditable(classId, actorId, actorRole);
        if (!clazz.getLecturerId().equals(actorId)) {
            throw new AccessDeniedException("Chỉ giảng viên chủ lớp mới được duyệt yêu cầu");
        }
        return clazz;
    }

    private void emitApprovedNotifications(ClassEntity clazz, Long studentUserId) {
        try {
            notificationService.create(studentUserId, "Yêu cầu tham gia được duyệt",
                    "Bạn đã được duyệt vào lớp \"" + clazz.getName() + "\".",
                    NotificationType.JOIN_APPROVED, NotificationType.REF_CLASS, clazz.getId());
            notificationService.create(studentUserId, "Đã tham gia lớp",
                    "Bạn đã được thêm vào lớp \"" + clazz.getName() + "\".",
                    NotificationType.CLASS_ENROLLED, NotificationType.REF_CLASS, clazz.getId());
        } catch (Exception ignored) {
            // Notification failure must not roll back enrollment state.
        }
    }

    private void emitRejectedNotification(ClassEntity clazz, Long studentUserId) {
        try {
            notificationService.create(studentUserId, "Yêu cầu tham gia bị từ chối",
                    "Yêu cầu tham gia lớp \"" + clazz.getName() + "\" chưa được chấp nhận.",
                    NotificationType.JOIN_REJECTED, NotificationType.REF_CLASS, clazz.getId());
        } catch (Exception ignored) {
            // Notification failure must not roll back enrollment state.
        }
    }
}
