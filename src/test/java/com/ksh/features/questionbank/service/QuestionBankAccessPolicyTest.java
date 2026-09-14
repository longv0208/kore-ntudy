package com.ksh.features.questionbank.service;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.leader.service.LeaderSubjectResolver;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for subject-scoped question bank access.
 */
class QuestionBankAccessPolicyTest {

    private final LeaderSubjectResolver leaderSubjectResolver = mock(LeaderSubjectResolver.class);
    private final QuestionBankAccessPolicy policy = new QuestionBankAccessPolicy(leaderSubjectResolver);

    @Test
    void lecturer_can_contribute_to_any_subject_but_cannot_curate() {
        User lecturer = user(Role.LECTURER, 10L, 5L);

        assertThat(policy.resolveSubjectId(lecturer)).isEqualTo(5L);
        assertThat(policy.canAccessSubject(lecturer, 5L)).isTrue();
        assertThat(policy.canAccessSubject(lecturer, 7L)).isTrue();
        assertThat(policy.canCurateSubject(lecturer, 5L)).isFalse();
    }

    @Test
    void leader_uses_resolved_working_subject_for_access_and_curation() {
        User leader = user(Role.LEADER, 11L, 99L);
        Subject subject = subject(5L, leader.getId());
        when(leaderSubjectResolver.resolve(leader.getId())).thenReturn(Optional.of(subject));
        when(leaderSubjectResolver.resolveAll(leader.getId())).thenReturn(List.of(subject));

        assertThat(policy.resolveSubjectId(leader)).isEqualTo(5L);
        assertThat(policy.canAccessSubject(leader, 5L)).isTrue();
        assertThat(policy.canCurateSubject(leader, 5L)).isTrue();
        assertThat(policy.canAccessSubject(leader, 6L)).isFalse();
    }

    @Test
    void admin_can_access_and_curate_the_full_subject_catalog() {
        User admin = user(Role.ADMIN, 12L, 8L);

        assertThat(policy.canAccessSubject(admin, 8L)).isTrue();
        assertThat(policy.canCurateSubject(admin, 8L)).isTrue();
        assertThat(policy.canAccessSubject(admin, 9L)).isTrue();
        assertThat(policy.canCurateSubject(admin, 9L)).isTrue();
    }

    private static User user(Role role, Long id, Long subjectId) {
        try {
            Constructor<User> ctor = User.class.getDeclaredConstructor(
                    String.class, String.class, String.class, Role.class,
                    boolean.class, boolean.class, boolean.class, boolean.class,
                    String.class, String.class);
            ctor.setAccessible(true);
            User user = ctor.newInstance("u@example.com", "hash", "User", role,
                    true, true, false, false, null, null);
            user.setSubjectId(subjectId);
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
            return user;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Subject subject(Long id, Long leaderUserId) {
        try {
            Subject subject = new Subject("CNTT", "CNTT", null, true);
            subject.assignLeader(leaderUserId);
            Field idField = Subject.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(subject, id);
            return subject;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
