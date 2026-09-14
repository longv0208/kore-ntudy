package com.ksh.features.lessons.support;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.service.ClassRoleAccessPolicy;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ClassAccessPolicyLeaderSubjectTest {

    @Test
    void foreignSubjectLeaderCannotBypassEnrollment() {
        EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
        ClassRoleAccessPolicy rolePolicy = mock(ClassRoleAccessPolicy.class);
        ClassEntity clazz = mock(ClassEntity.class);
        when(clazz.getId()).thenReturn(9L);
        when(clazz.getLecturerId()).thenReturn(42L);
        when(rolePolicy.canAccess(clazz, 7L, Role.LEADER)).thenReturn(false);
        ClassAccessPolicy access = new ClassAccessPolicy(enrollments, rolePolicy);

        assertThatThrownBy(() -> access.requireViewAccess(
                clazz, 7L, Role.LEADER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void sameSubjectLeaderCanInspectWithoutEnrollment() {
        EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
        ClassRoleAccessPolicy rolePolicy = mock(ClassRoleAccessPolicy.class);
        ClassEntity clazz = mock(ClassEntity.class);
        when(clazz.getLecturerId()).thenReturn(42L);
        when(rolePolicy.canAccess(clazz, 7L, Role.LEADER)).thenReturn(true);
        ClassAccessPolicy access = new ClassAccessPolicy(enrollments, rolePolicy);

        access.requireViewAccess(clazz, 7L, Role.LEADER);
        verifyNoInteractions(enrollments);
    }
}
