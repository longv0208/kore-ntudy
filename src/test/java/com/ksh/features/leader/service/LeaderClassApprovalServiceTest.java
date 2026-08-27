package com.ksh.features.leader.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.notifications.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderClassApprovalServiceTest {

    @Mock private LeaderDepartmentResolver resolver;
    @Mock private ClassRepository classRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    @Test
    void queue_uses_latest_review_request_and_includes_lecturer_contact() {
        Department subject = mock(Department.class);
        ClassEntity clazz = mock(ClassEntity.class);
        User lecturer = mock(User.class);
        LocalDateTime resubmittedAt = LocalDateTime.of(2026, 8, 26, 22, 30);
        when(resolver.resolveAll(13L)).thenReturn(List.of(subject));
        when(subject.getId()).thenReturn(37L);
        when(subject.getCode()).thenReturn("KE11");
        when(subject.getName()).thenReturn("Korean");
        when(classRepository.findAllBySubjectIdAndStatusOrderByUpdatedAtDescIdDesc(
                37L, ClassEntity.STATUS_PENDING)).thenReturn(List.of(clazz));
        when(clazz.getId()).thenReturn(58L);
        when(clazz.getName()).thenReturn("Lớp gửi lại");
        when(clazz.getSubjectId()).thenReturn(37L);
        when(clazz.getLecturerId()).thenReturn(2L);
        when(clazz.getUpdatedAt()).thenReturn(resubmittedAt);
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(lecturer));
        when(lecturer.getId()).thenReturn(2L);
        when(lecturer.getFullName()).thenReturn("Giảng Viên Test");
        when(lecturer.getEmail()).thenReturn("lecturer@ksh.edu.vn");

        var view = new LeaderClassApprovalService(
                resolver, classRepository, userRepository, notificationService).load(13L);

        assertThat(view.pendingClasses()).singleElement().satisfies(row -> {
            assertThat(row.classId()).isEqualTo(58L);
            assertThat(row.lecturerEmail()).isEqualTo("lecturer@ksh.edu.vn");
            assertThat(row.requestedAt()).isEqualTo(resubmittedAt);
        });
        verify(classRepository).findAllBySubjectIdAndStatusOrderByUpdatedAtDescIdDesc(
                37L, ClassEntity.STATUS_PENDING);
    }
}
