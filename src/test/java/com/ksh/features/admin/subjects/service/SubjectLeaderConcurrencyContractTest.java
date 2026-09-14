package com.ksh.features.admin.subjects.service;

import com.ksh.entities.Subject;
import com.ksh.entities.SystemSetting;
import com.ksh.entities.User;
import com.ksh.entities.UserFactory;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectForm;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.admin.settings.repository.SystemSettingsRepository;
import com.ksh.features.admin.permissions.service.PermissionResolver;
import com.ksh.features.profile.service.SessionRevocationService;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectLeaderConcurrencyContractTest {

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubjectAuditWriter auditWriter;

    @Mock
    private SystemSettingsRepository systemSettingsRepository;

    @Mock
    private SessionRevocationService sessionRevocationService;

    @Mock
    private PermissionResolver permissionResolver;

    @Test
    void assignmentLocksAnchorThenSubjectThenAffectedUsersInAscendingIdOrder() {
        Subject subject = subject(10L, 30L);
        User candidate = user(20L, Role.LECTURER, null);
        User previous = user(30L, Role.LEADER, 10L);
        stubAnchor();
        when(subjectRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(subject));
        when(userRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(candidate));
        when(userRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(previous));
        when(subjectRepository.existsByLeaderUserId(30L)).thenReturn(false);

        service().assignLeader(10L, 20L, 99L);

        InOrder order = inOrder(
                systemSettingsRepository, subjectRepository, userRepository);
        order.verify(systemSettingsRepository).findBySettingKeyForUpdate(
                SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY);
        order.verify(subjectRepository).findByIdForUpdate(10L);
        order.verify(userRepository).findByIdForUpdate(20L);
        order.verify(userRepository).findByIdForUpdate(30L);
        assertThat(subject.getLeaderUserId()).isEqualTo(20L);
        assertThat(candidate.getRole()).isEqualTo(Role.LEADER);
        assertThat(candidate.getSubjectId()).isEqualTo(10L);
        assertThat(previous.getRole()).isEqualTo(Role.LECTURER);
        var accessOrder = inOrder(permissionResolver, sessionRevocationService);
        accessOrder.verify(permissionResolver).evictUser(30L);
        accessOrder.verify(sessionRevocationService).revokeAllSessions(30L);
        accessOrder.verify(permissionResolver).evictUser(20L);
        accessOrder.verify(sessionRevocationService).revokeAllSessions(20L);
    }

    @Test
    void candidateAlreadyLeadingAnotherSubjectCanCurateThisSubjectToo() {
        Subject subject = subject(10L, null);
        User candidate = user(20L, Role.LEADER, 11L);
        stubAnchor();
        when(subjectRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(subject));
        when(userRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(candidate));
        service().assignLeader(10L, 20L, 99L);

        assertThat(subject.getLeaderUserId()).isEqualTo(20L);
        assertThat(candidate.getRole()).isEqualTo(Role.LEADER);
        assertThat(candidate.getSubjectId()).isEqualTo(10L);
        verify(subjectRepository).saveAndFlush(subject);
        verify(userRepository).save(candidate);
    }

    @Test
    void unchangedPointerStillLocksAndRepairsTheUserSideOfTheInvariant() {
        Subject subject = subject(10L, 20L);
        User driftedLeader = user(20L, Role.LECTURER, 99L);
        stubAnchor();
        when(subjectRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(subject));
        when(userRepository.findByIdForUpdate(20L))
                .thenReturn(Optional.of(driftedLeader));
        service().assignLeader(10L, 20L, 99L);

        assertThat(driftedLeader.getRole()).isEqualTo(Role.LEADER);
        assertThat(driftedLeader.getSubjectId()).isEqualTo(10L);
        verify(userRepository).findByIdForUpdate(20L);
        verify(userRepository).save(driftedLeader);
    }

    @Test
    void missingAnchorFailsClosedBeforeSubjectOrUserRead() {
        when(systemSettingsRepository.findBySettingKeyForUpdate(
                SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignLeader(10L, 20L, 99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY);

        verifyNoInteractions(subjectRepository, userRepository, auditWriter);
    }

    @Test
    void editPathAcquiresAnchorBeforeLockingItsSubject() {
        Subject subject = subject(10L, null);
        stubAnchor();
        when(subjectRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(subject));
        when(subjectRepository.existsByCodeAndIdNot("CNTT", 10L))
                .thenReturn(false);
        SubjectForm unchanged = new SubjectForm(
                "Công nghệ thông tin", "CNTT", null, true, null);

        service().update(10L, unchanged, 99L);

        InOrder order = inOrder(systemSettingsRepository, subjectRepository);
        order.verify(systemSettingsRepository).findBySettingKeyForUpdate(
                SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY);
        order.verify(subjectRepository).findByIdForUpdate(10L);
    }

    private SubjectService service() {
        return new SubjectService(
                subjectRepository,
                userRepository,
                auditWriter,
                systemSettingsRepository,
                sessionRevocationService,
                permissionResolver);
    }

    private void stubAnchor() {
        when(systemSettingsRepository.findBySettingKeyForUpdate(
                SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY))
                .thenReturn(Optional.of(new SystemSetting(
                        SubjectService.LEADER_ASSIGNMENT_LOCK_SETTING_KEY,
                        "",
                        "AI")));
    }

    private static Subject subject(Long id, Long leaderUserId) {
        Subject subject = new Subject(
                "Công nghệ thông tin", "CNTT", null, true);
        ReflectionTestUtils.setField(subject, "id", id);
        subject.assignLeader(leaderUserId);
        return subject;
    }

    private static User user(Long id, Role role, Long subjectId) {
        User user = UserFactory.newAdminCreated(
                "user-" + id + "@ksh.test",
                "unused-test-hash",
                "User " + id,
                role,
                true,
                null,
                null);
        ReflectionTestUtils.setField(user, "id", id);
        user.setSubjectId(subjectId);
        return user;
    }
}
