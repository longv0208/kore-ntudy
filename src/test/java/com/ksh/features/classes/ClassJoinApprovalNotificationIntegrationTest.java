package com.ksh.features.classes;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.service.JoinClassService;
import com.ksh.features.notifications.entity.Notification;
import com.ksh.features.notifications.entity.NotificationType;
import com.ksh.features.notifications.repository.NotificationRepository;
import com.ksh.features.notifications.service.NotificationService;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration boundary: class catalog + enrollment lifecycle + notification
 * persistence. This deliberately uses the real Spring services and MySQL
 * repositories rather than mocking either downstream module.
 */
@SpringBootTest
@Transactional
class ClassJoinApprovalNotificationIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private JoinClassService joinClassService;

    private User owner;
    private User student;
    private ClassEntity activeClass;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        student = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();

        activeClass = new ClassEntity(
                "IT-CJNA-" + System.nanoTime(), owner.getId(), owner.getId(),
                "Transactional integration fixture", null, null, 20);
        activeClass.setSubjectId(owner.getSubjectId());
        activeClass.approve(owner.getId(), LocalDateTime.now());
        activeClass = classRepository.saveAndFlush(activeClass);
    }

    @Test
    void lecturer_approval_of_existing_pending_enrollment_persists_membership_and_notifications() {
        long studentUnreadBefore = notificationService.unreadCount(student.getId());

        Enrollment pending = Enrollment.createPending(
                student, activeClass.getId(), Enrollment.JoinedVia.IMPORT, null);
        enrollmentRepository.saveAndFlush(pending);
        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_PENDING);

        joinClassService.approve(activeClass.getId(), student.getId(), owner.getId(), Role.LECTURER);

        Enrollment active = enrollmentRepository.findByUserIdAndClassId(student.getId(), activeClass.getId())
                .orElseThrow();
        assertThat(active.getStatus()).isEqualTo(Enrollment.STATUS_ACTIVE);
        assertThat(notificationService.unreadCount(student.getId())).isEqualTo(studentUnreadBefore + 2);
        assertThat(notificationsFor(student.getId()).stream().map(Notification::getType))
                .contains(NotificationType.JOIN_APPROVED, NotificationType.CLASS_ENROLLED);
    }

    private List<Notification> notificationsFor(Long recipientId) {
        return notificationRepository.findAll().stream()
                .filter(notification -> recipientId.equals(notification.getUserId()))
                .filter(notification -> activeClass.getId().equals(notification.getReferenceId()))
                .toList();
    }
}
