package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.notifications.entity.NotificationType;
import com.ksh.features.notifications.service.NotificationService;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JoinClassServiceTest {

    private static final Long CLASS_ID = 9L;
    private static final Long USER_ID = 200L;
    private static final Long OWNER_ID = 42L;

    private EnrollmentRepository enrollmentRepository;
    private ClassRepository classRepository;
    private ClassActivityWriter activityWriter;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private ClassesService classesService;
    private JoinClassService service;

    @BeforeEach
    void setUp() {
        enrollmentRepository = mock(EnrollmentRepository.class);
        classRepository = mock(ClassRepository.class);
        activityWriter = mock(ClassActivityWriter.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        classesService = mock(ClassesService.class);
        service = new JoinClassService(enrollmentRepository, classRepository,
                activityWriter, userRepository, notificationService, classesService);
        when(classRepository.findByIdForUpdate(CLASS_ID)).thenReturn(Optional.of(activeClass()));
    }

    @Test
    void capacityIsCheckedAgainWhenOwnerApproves() {
        ClassEntity clazz = activeClass();
        Enrollment pending = Enrollment.createPending(
                student(), CLASS_ID, Enrollment.JoinedVia.REQUEST, null);
        when(classesService.getEditable(CLASS_ID, OWNER_ID, Role.LECTURER)).thenReturn(clazz);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));
        when(enrollmentRepository.countActiveByClassIdForUpdate(CLASS_ID)).thenReturn(100L);

        assertThatThrownBy(() -> service.approve(
                CLASS_ID, USER_ID, OWNER_ID, Role.LECTURER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đủ sĩ số");
        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_PENDING);
    }

    @Test
    void onlyPrimaryOwnerCanApproveEvenWhenCoLecturerCanEdit() {
        ClassEntity clazz = activeClass();
        when(classesService.getEditable(CLASS_ID, 99L, Role.LECTURER)).thenReturn(clazz);

        assertThatThrownBy(() -> service.approve(CLASS_ID, USER_ID, 99L, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownerApprovalActivatesPendingEnrollment() {
        ClassEntity clazz = activeClass();
        Enrollment pending = Enrollment.createPending(
                student(), CLASS_ID, Enrollment.JoinedVia.REQUEST, null);
        when(classesService.getEditable(CLASS_ID, OWNER_ID, Role.LECTURER)).thenReturn(clazz);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));
        when(enrollmentRepository.countActiveByClassIdForUpdate(CLASS_ID)).thenReturn(0L);

        service.approve(CLASS_ID, USER_ID, OWNER_ID, Role.LECTURER);

        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_ACTIVE);
        verify(enrollmentRepository).save(pending);
    }

    @Test
    void rejectionChangesPendingRequestAndNotifiesTheStudent() {
        ClassEntity clazz = activeClass();
        Enrollment pending = Enrollment.createPending(
                student(), CLASS_ID, Enrollment.JoinedVia.REQUEST, null);
        when(classesService.getEditable(CLASS_ID, OWNER_ID, Role.LECTURER)).thenReturn(clazz);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));

        assertThat(service.reject(CLASS_ID, USER_ID, OWNER_ID, Role.LECTURER))
                .isSameAs(clazz);

        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_REJECTED);
        verify(enrollmentRepository).save(pending);
        verify(notificationService).create(eq(USER_ID), any(), any(),
                eq(NotificationType.JOIN_REJECTED), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
    }

    @Test
    void activeStudentCanLeaveAndTheirEnrollmentBecomesRemoved() {
        ClassEntity clazz = activeClass();
        Enrollment active = Enrollment.createPending(
                student(), CLASS_ID, Enrollment.JoinedVia.REQUEST, null);
        active.activateFromPending();
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(active));
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));

        assertThat(service.leave(CLASS_ID, USER_ID)).isSameAs(clazz);

        assertThat(active.getStatus()).isEqualTo(Enrollment.STATUS_REMOVED);
        verify(enrollmentRepository).save(active);
    }

    @Test
    void studentSelfJoinIsAlwaysDeniedWithoutRepositoryMutation() {
        assertThatThrownBy(() -> service.requestJoin(CLASS_ID, USER_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("giảng viên phải thêm sinh viên");

        verify(classRepository, never()).findById(any());
        verify(enrollmentRepository, never()).save(any());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    private static ClassEntity activeClass() {
        ClassEntity clazz = new ClassEntity("Demo", OWNER_ID, OWNER_ID,
                null, null, null, 100);
        ReflectionTestUtils.setField(clazz, "id", CLASS_ID);
        clazz.approve(OWNER_ID, LocalDateTime.now());
        return clazz;
    }

    private static User student() {
        User user = new User() {};
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "email", "student@example.test");
        ReflectionTestUtils.setField(user, "fullName", "Student");
        ReflectionTestUtils.setField(user, "passwordHash", "x");
        return user;
    }
}
