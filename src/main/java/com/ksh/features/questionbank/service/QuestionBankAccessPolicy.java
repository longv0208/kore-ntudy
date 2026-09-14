package com.ksh.features.questionbank.service;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.leader.service.LeaderSubjectResolver;
import com.ksh.security.Role;
import org.springframework.stereotype.Component;

/**
 * Resolves subject-scoped access for question bank actions.
 * Subject catalog rows are backed by the canonical {@code subjects} table.
 */
@Component
public class QuestionBankAccessPolicy {

    private final LeaderSubjectResolver leaderSubjectResolver;

    public QuestionBankAccessPolicy(LeaderSubjectResolver leaderSubjectResolver) {
        this.leaderSubjectResolver = leaderSubjectResolver;
    }

    /** Resolves the caller's working subject for question bank access. */
    public Long resolveSubjectId(User user) {
        if (user == null) {
            return null;
        }
        if (user.getRole() == Role.LEADER) {
            Subject subject = leaderSubjectResolver.resolve(user.getId()).orElse(null);
            return subject != null ? subject.getId() : null;
        }
        // subject_id is a landing-page default only. Access is catalog-wide in
        // canAccessSubject, so this value is never an authorization gate.
        if (user.getRole() == Role.LECTURER || user.getRole() == Role.ADMIN) {
            return user.getSubjectId();
        }
        return null;
    }

    /** True when the caller may view or contribute within the given subject. */
    public boolean canAccessSubject(User user, Long subjectId) {
        if (user == null || subjectId == null) {
            return false;
        }
        Role role = user.getRole();
        if (role != Role.LEADER && role != Role.LECTURER && role != Role.ADMIN) {
            return false;
        }
        if (role == Role.LECTURER || role == Role.ADMIN) {
            return true;
        }
        return leaderSubjectResolver.resolveAll(user.getId()).stream()
                .anyMatch(subject -> subjectId.equals(subject.getId()));
    }

    /** True when the caller may curate shared inventory for the given subject. */
    public boolean canCurateSubject(User user, Long subjectId) {
        if (user == null || subjectId == null) {
            return false;
        }
        Role role = user.getRole();
        if (role != Role.LEADER && role != Role.ADMIN) {
            return false;
        }
        if (role == Role.ADMIN) {
            return true;
        }
        return leaderSubjectResolver.resolveAll(user.getId()).stream()
                .anyMatch(subject -> subjectId.equals(subject.getId()));
    }
}
