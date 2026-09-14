package com.ksh.features.leader.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.leader.dto.LeaderDtos.ApprovalQueueView;
import com.ksh.features.leader.dto.LeaderDtos.SubjectSummary;
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
    private final LeaderSubjectResolver resolver;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public LeaderClassApprovalService(LeaderSubjectResolver resolver,
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
        List<Subject> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) return new ApprovalQueueView(null, List.of(), true);
        List<ClassEntity> pending = new ArrayList<>();
        Map<Long, String> subjectCodes = new HashMap<>();
        for (Subject subject : subjects) {
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
        throw new IllegalStateException("Quy trình duyệt lớp đã ngừng sử dụng");
    }

    @Transactional
    public String reject(Long leaderUserId, Long classId, String note) {
        throw new IllegalStateException("Quy trình duyệt lớp đã ngừng sử dụng");
    }

    private ClassEntity loadLockedInLeaderSubject(Long leaderUserId, Long classId) {
        List<Subject> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) throw new AccessDeniedException("Không có môn học");
        ClassEntity clazz = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp"));
        if (subjects.stream().noneMatch(subject -> subject.getId().equals(clazz.getSubjectId()))) {
            throw new AccessDeniedException("Lớp không thuộc môn học của bạn");
        }
        return clazz;
    }

    private static SubjectSummary summary(List<Subject> subjects) {
        Subject first = subjects.get(0);
        return subjects.size() == 1
                ? new SubjectSummary(first.getId(), first.getCode(), first.getName())
                : new SubjectSummary(first.getId(), subjects.size() + " mã môn",
                        "Môn học tiếng Hàn");
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
