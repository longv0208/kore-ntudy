package com.ksh.features.tests.support;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.service.ClassRoleAccessPolicy;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.repository.TestAttemptRepository;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAccessResolverLeaderScopeTest {

    private static final Long USER_ID = 7L;
    private static final Long TEST_ID = 11L;
    private static final Long CLASS_ID = 13L;

    private final TestRepository testRepository = mock(TestRepository.class);
    private final EnrollmentRepository enrollmentRepository = mock(EnrollmentRepository.class);
    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ClassRoleAccessPolicy classAccessPolicy = mock(ClassRoleAccessPolicy.class);
    private final Test exam = mock(Test.class);
    private final ClassEntity clazz = mock(ClassEntity.class);
    private final User actor = mock(User.class);
    private TestAccessResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TestAccessResolver(
                testRepository,
                mock(TestAttemptRepository.class),
                enrollmentRepository,
                classRepository,
                userRepository,
                classAccessPolicy);
        when(testRepository.findById(TEST_ID)).thenReturn(Optional.of(exam));
        when(exam.isDeleted()).thenReturn(false);
        when(exam.isPractice()).thenReturn(false);
        when(exam.getClassId()).thenReturn(CLASS_ID);
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(actor));
    }

    @org.junit.jupiter.api.Test
    void sameDepartmentLeaderMayManageDirectTestId() {
        when(actor.getRole()).thenReturn(Role.LEADER);
        when(classAccessPolicy.canAccess(clazz, USER_ID, Role.LEADER)).thenReturn(true);

        assertThat(resolver.requireManageable(TEST_ID, USER_ID)).isSameAs(exam);
    }

    @org.junit.jupiter.api.Test
    void crossDepartmentLeaderCannotManageDirectTestId() {
        when(actor.getRole()).thenReturn(Role.LEADER);
        when(classAccessPolicy.canAccess(clazz, USER_ID, Role.LEADER)).thenReturn(false);

        assertThatThrownBy(() -> resolver.requireManageable(TEST_ID, USER_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @org.junit.jupiter.api.Test
    void adminMayManageOrdinaryTestWithoutClassLookup() {
        when(actor.getRole()).thenReturn(Role.ADMIN);

        assertThat(resolver.requireManageable(TEST_ID, USER_ID)).isSameAs(exam);

        verify(classRepository, never()).findById(CLASS_ID);
    }

    @org.junit.jupiter.api.Test
    void lecturerCreatorRetainsOwnerScopedAccess() {
        when(actor.getRole()).thenReturn(Role.LECTURER);
        when(exam.getCreatedBy()).thenReturn(USER_ID);

        assertThat(resolver.requireManageable(TEST_ID, USER_ID)).isSameAs(exam);

        verify(classAccessPolicy, never()).canAccess(clazz, USER_ID, Role.LECTURER);
    }

    @org.junit.jupiter.api.Test
    void adminScopeDoesNotBroadenStudentPracticeOwnership() {
        when(actor.getRole()).thenReturn(Role.ADMIN);
        when(exam.isPractice()).thenReturn(true);
        when(exam.getCreatedBy()).thenReturn(99L);

        assertThatThrownBy(() -> resolver.requireManageable(TEST_ID, USER_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(classAccessPolicy, never()).canAccess(clazz, USER_ID, Role.ADMIN);
    }

    @org.junit.jupiter.api.Test
    void publishedSharedBankSourceDoesNotRequireCreatorOrClassOwnership() {
        when(exam.isPublished()).thenReturn(true);

        assertThat(resolver.requireSharedTestBankSource(
                TEST_ID, USER_ID, Role.LECTURER, false)).isSameAs(exam);
        assertThat(resolver.requirePreviewableFromTestBank(
                TEST_ID, USER_ID, Role.LEADER)).isSameAs(exam);

        verify(classRepository, never()).findById(CLASS_ID);
        verify(classAccessPolicy, never()).canAccess(clazz, USER_ID, Role.LECTURER);
    }

    @org.junit.jupiter.api.Test
    void sharedPreviewRequiresAnAuthenticatedEducatorAndNeverIncludesPractice() {
        when(exam.isPublished()).thenReturn(true);

        assertThatThrownBy(() -> resolver.requirePreviewableFromTestBank(
                TEST_ID, null, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolver.requirePreviewableFromTestBank(
                TEST_ID, USER_ID, Role.STUDENT))
                .isInstanceOf(AccessDeniedException.class);

        when(exam.isPractice()).thenReturn(true);
        when(exam.getCreatedBy()).thenReturn(USER_ID);
        assertThatThrownBy(() -> resolver.requirePreviewableFromTestBank(
                TEST_ID, USER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).findById(CLASS_ID);
        verify(classAccessPolicy, never()).canAccess(clazz, USER_ID, Role.LECTURER);
    }

    @org.junit.jupiter.api.Test
    void managementRoleRejectsNullActorBeforeRepositoryLookup() {
        assertThatThrownBy(() -> resolver.managementRole(null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage(TestAccessResolver.NF_MSG);

        verify(userRepository, never()).findById(null);
    }

    @org.junit.jupiter.api.Test
    void leaderClassPickerIncludesAllAssignedSubjects() {
        ClassEntity firstSubjectClass = mock(ClassEntity.class);
        ClassEntity secondSubjectClass = mock(ClassEntity.class);
        when(classAccessPolicy.leaderSubjectIds(USER_ID)).thenReturn(List.of(3L, 4L));
        when(classRepository.findAllBySubjectIdInOrderByCreatedAtDesc(List.of(3L, 4L)))
                .thenReturn(List.of(firstSubjectClass, secondSubjectClass));

        assertThat(resolver.manageableClasses(USER_ID, Role.LEADER))
                .containsExactly(firstSubjectClass, secondSubjectClass);

        verify(classRepository, never()).findAllByOrderByCreatedAtDesc();
        verify(classRepository, never()).findAllByLecturerIdOrderByCreatedAtDesc(USER_ID);
    }

    @org.junit.jupiter.api.Test
    void adminClassPickerUsesGlobalClassScope() {
        ClassEntity anyClass = mock(ClassEntity.class);
        when(classRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(anyClass));

        assertThat(resolver.manageableClasses(USER_ID, Role.ADMIN))
                .containsExactly(anyClass);
    }

    @org.junit.jupiter.api.Test
    void lecturerClassPickerRemainsOwnerScoped() {
        ClassEntity ownedClass = mock(ClassEntity.class);
        when(classRepository.findAllAccessibleToLecturerOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(ownedClass));

        assertThat(resolver.manageableClasses(USER_ID, Role.LECTURER))
                .containsExactly(ownedClass);
    }

    @org.junit.jupiter.api.Test
    void studentCannotOpenPublishedExamAfterItsClassWasDeleted() {
        Enrollment enrollment = mock(Enrollment.class);
        when(exam.isPublished()).thenReturn(true);
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.empty());
        when(enrollment.getStatus()).thenReturn(Enrollment.STATUS_ACTIVE);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(enrollment));

        assertThatThrownBy(() -> resolver.requireViewable(TEST_ID, USER_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(TestAccessResolver.NF_MSG);
    }
}
