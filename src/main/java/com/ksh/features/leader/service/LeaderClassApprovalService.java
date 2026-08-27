package com.ksh.features.leader.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.leader.dto.LeaderDtos.ApprovalQueueView;
import com.ksh.features.leader.dto.LeaderDtos.DepartmentSummary;
import com.ksh.features.leader.dto.LeaderDtos.PendingClassRow;
import com.ksh.features.notifications.entity.NotificationType;
import com.ksh.features.notifications.service.NotificationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LeaderClassApprovalService {
    private final LeaderDepartmentResolver resolver;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public LeaderClassApprovalService(LeaderDepartmentResolver resolver,
                                      ClassRepository classRepository,
                                      UserRepository userRepository,
                                      NotificationService notificationService) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public ApprovalQueueView load(Long leaderUserId) {
        List<Department> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) return new ApprovalQueueView(null, List.of(), true);
        List<ClassEntity> pending = new ArrayList<>();
        Map<Long, String> subjectCodes = new HashMap<>();
        for (Department subject : subjects) {
            subjectCodes.put(subject.getId(), subject.getCode());
            pending.addAll(classRepository.findAllBySubjectIdAndStatusOrderByUpdatedAtDescIdDesc(
                    subject.getId(), ClassEntity.STATUS_PENDING));
        }
        pending.sort((left, right) -> reviewRequestedAt(right)
                .compareTo(reviewRequestedAt(left)));
        Map<Long, LecturerContact> contacts = new HashMap<>();
        userRepository.findAllById(pending.stream().map(ClassEntity::getLecturerId)
                        .distinct().toList())
                .forEach(user -> contacts.put(user.getId(),
                        new LecturerContact(user.getFullName(), user.getEmail())));
        List<PendingClassRow> rows = pending.stream().map(clazz -> new PendingClassRow(
                clazz.getId(), clazz.getName(), subjectCodes.get(clazz.getSubjectId()),
                contacts.getOrDefault(clazz.getLecturerId(), LecturerContact.EMPTY).name(),
                contacts.getOrDefault(clazz.getLecturerId(), LecturerContact.EMPTY).email(),
                reviewRequestedAt(clazz))).toList();
        return new ApprovalQueueView(summary(subjects), rows, false);
    }

    @Transactional
    public String approve(Long leaderUserId, Long classId) {
        ClassEntity clazz = loadLockedInLeaderDepartment(leaderUserId, classId);
        clazz.approve(leaderUserId, LocalDateTime.now());
        classRepository.save(clazz);
        notifyOutcome(clazz, NotificationType.CLASS_APPROVED, "Lớp đã được duyệt",
                "Lớp \"" + clazz.getName() + "\" đã được duyệt. Sinh viên có thể tham gia.");
        return clazz.getName();
    }

    @Transactional
    public String reject(Long leaderUserId, Long classId, String note) {
        ClassEntity clazz = loadLockedInLeaderDepartment(leaderUserId, classId);
        clazz.reject(leaderUserId, note, LocalDateTime.now());
        classRepository.save(clazz);
        String body = "Lớp \"" + clazz.getName() + "\" đã bị từ chối."
                + (clazz.getRejectionNote() == null ? "" : " Lý do: " + clazz.getRejectionNote());
        notifyOutcome(clazz, NotificationType.CLASS_REJECTED, "Lớp bị từ chối", body);
        return clazz.getName();
    }

    private ClassEntity loadLockedInLeaderDepartment(Long leaderUserId, Long classId) {
        List<Department> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) throw new AccessDeniedException("Không có bộ môn");
        ClassEntity clazz = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp"));
        if (subjects.stream().noneMatch(subject -> subject.getId().equals(clazz.getSubjectId()))) {
            throw new AccessDeniedException("Lớp không thuộc bộ môn của bạn");
        }
        return clazz;
    }

    private static DepartmentSummary summary(List<Department> subjects) {
        Department first = subjects.get(0);
        return subjects.size() == 1
                ? new DepartmentSummary(first.getId(), first.getCode(), first.getName())
                : new DepartmentSummary(first.getId(), subjects.size() + " mã môn",
                        "Bộ môn tiếng Hàn");
    }

    private static LocalDateTime reviewRequestedAt(ClassEntity clazz) {
        LocalDateTime value = clazz.getUpdatedAt() != null
                ? clazz.getUpdatedAt() : clazz.getCreatedAt();
        return value == null ? LocalDateTime.MIN : value;
    }

    private record LecturerContact(String name, String email) {
        private static final LecturerContact EMPTY = new LecturerContact("—", "—");
    }

    private void notifyOutcome(ClassEntity clazz, String type, String title, String body) {
        try {
            notificationService.create(clazz.getLecturerId(), title, body,
                    type, NotificationType.REF_CLASS, clazz.getId());
        } catch (RuntimeException ignored) {
            // Notification must never roll back a completed review transition.
        }
    }
}
