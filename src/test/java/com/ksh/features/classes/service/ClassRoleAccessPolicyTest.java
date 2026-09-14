package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.features.leader.service.LeaderSubjectResolver;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassRoleAccessPolicyTest {

    private final LeaderSubjectResolver resolver = mock(LeaderSubjectResolver.class);
    private final ClassCoLecturerRepository coLecturerRepository =
            mock(ClassCoLecturerRepository.class);
    private final ClassRoleAccessPolicy policy =
            new ClassRoleAccessPolicy(resolver, coLecturerRepository);

    @Test
    void leaderIsLimitedToAllAssignedSubjects() {
        Subject subject = mock(Subject.class);
        Subject secondSubject = mock(Subject.class);
        when(subject.getId()).thenReturn(10L);
        when(secondSubject.getId()).thenReturn(12L);
        when(resolver.resolveAll(7L)).thenReturn(List.of(subject, secondSubject));
        ClassEntity same = classEntity(42L, 10L);
        ClassEntity second = classEntity(42L, 12L);
        ClassEntity foreign = classEntity(42L, 11L);

        assertThat(policy.canAccess(same, 7L, Role.LEADER)).isTrue();
        assertThat(policy.canAccess(second, 7L, Role.LEADER)).isTrue();
        assertThat(policy.canAccess(foreign, 7L, Role.LEADER)).isFalse();
    }

    @Test
    void adminIsGlobalAndLecturerIsOwnerScoped() {
        ClassEntity clazz = classEntity(42L, 10L);

        assertThat(policy.canAccess(clazz, 1L, Role.ADMIN)).isTrue();
        assertThat(policy.canAccess(clazz, 42L, Role.LECTURER)).isTrue();
        assertThat(policy.canAccess(clazz, 99L, Role.LECTURER)).isFalse();
    }

    @Test
    void lecturerCanAccessAsCoLecturerWithoutBecomingOwner() {
        ClassEntity clazz = classEntity(42L, 10L);
        when(clazz.getId()).thenReturn(5L);
        when(coLecturerRepository.existsByClassIdAndLecturerId(5L, 99L)).thenReturn(true);

        assertThat(policy.canAccess(clazz, 99L, Role.LECTURER)).isTrue();
        assertThat(policy.canManageClass(clazz, 99L, Role.LECTURER)).isFalse();
        assertThat(policy.canManageClass(clazz, 42L, Role.LECTURER)).isTrue();
        assertThat(clazz.getLecturerId()).isEqualTo(42L);
    }

    @Test
    void leaderSubjectScopeDoesNotTransferClassOwnership() {
        Subject subject = mock(Subject.class);
        when(subject.getId()).thenReturn(10L);
        when(resolver.resolveAll(7L)).thenReturn(List.of(subject));
        ClassEntity clazz = classEntity(42L, 10L);

        assertThat(policy.canAccess(clazz, 7L, Role.LEADER)).isTrue();
        assertThat(policy.canManageClass(clazz, 7L, Role.LEADER)).isFalse();
        assertThat(policy.canManageClass(clazz, 1L, Role.ADMIN)).isTrue();
    }

    private static ClassEntity classEntity(Long lecturerId, Long subjectId) {
        ClassEntity clazz = mock(ClassEntity.class);
        when(clazz.getLecturerId()).thenReturn(lecturerId);
        when(clazz.getSubjectId()).thenReturn(subjectId);
        return clazz;
    }
}
