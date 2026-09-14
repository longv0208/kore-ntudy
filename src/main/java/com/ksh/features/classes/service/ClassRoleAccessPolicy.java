package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import com.ksh.features.leader.service.LeaderSubjectResolver;
import com.ksh.security.Role;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

/** Canonical policy for class collaboration and immutable ownership boundaries. */
@Component
public class ClassRoleAccessPolicy {

    private final LeaderSubjectResolver leaderSubjectResolver;
    private final ClassCoLecturerRepository coLecturerRepository;

    public ClassRoleAccessPolicy(LeaderSubjectResolver leaderSubjectResolver,
                                 ClassCoLecturerRepository coLecturerRepository) {
        this.leaderSubjectResolver = leaderSubjectResolver;
        this.coLecturerRepository = coLecturerRepository;
    }

    public boolean canAccess(ClassEntity clazz, Long userId, Role role) {
        if (clazz == null || userId == null || role == null) {
            return false;
        }
        if (role == Role.ADMIN) {
            return true;
        }
        if (role == Role.LECTURER) {
            return userId.equals(clazz.getLecturerId())
                    || coLecturerRepository.existsByClassIdAndLecturerId(clazz.getId(), userId);
        }
        if (role == Role.LEADER) {
            return leaderSubjectResolver.resolveAll(userId).stream()
                    .anyMatch(subject -> subject.getId().equals(clazz.getSubjectId()));
        }
        return false;
    }

    /**
     * Returns whether the caller may mutate owner-managed class state.
     * Co-lecturers and subject leaders deliberately do not inherit ownership.
     */
    public boolean canManageClass(ClassEntity clazz, Long userId, Role role) {
        if (clazz == null || userId == null || role == null) {
            return false;
        }
        return role == Role.ADMIN || userId.equals(clazz.getLecturerId());
    }

    public Optional<Long> leaderSubjectId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return leaderSubjectResolver.resolve(userId).map(subject -> subject.getId());
    }

    /** All active subject ids curated by this leader. */
    public List<Long> leaderSubjectIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return leaderSubjectResolver.resolveAll(userId).stream()
                .map(subject -> subject.getId())
                .toList();
    }
}
