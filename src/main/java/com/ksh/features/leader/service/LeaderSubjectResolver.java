package com.ksh.features.leader.service;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the working subject for a LEADER user.
 *
 * <p>Preference order: subject where {@code leader_user_id} matches the user,
 * otherwise the subject referenced by {@code users.subject_id} when it
 * exists (and preferably is active).
 */
@Service
public class LeaderSubjectResolver {

    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    public LeaderSubjectResolver(SubjectRepository subjectRepository,
                                  UserRepository userRepository) {
        this.subjectRepository = subjectRepository;
        this.userRepository = userRepository;
    }

    /**
     * @param userId current authenticated LEADER user id
     * @return resolved subject, or empty when neither rule matches
     */
    @Transactional(readOnly = true)
    public Optional<Subject> resolve(Long userId) {
        return resolveAll(userId).stream().findFirst();
    }

    /**
     * All active subject catalog rows curated by this leader account.
     *
     * <p>Legacy data can use both {@code subjects.leader_user_id} and
     * {@code users.subject_id}. Keep the primary subject alongside explicit
     * assignments so it does not disappear when another assignment exists.
     */
    @Transactional(readOnly = true)
    public List<Subject> resolveAll(Long userId) {
        List<Subject> assigned = subjectRepository
                .findByLeaderUserIdOrderByCodeAsc(userId).stream()
                .filter(Subject::isActive)
                .toList();
        List<Subject> resolved = new ArrayList<>(assigned);
        userRepository.findById(userId)
                .map(User::getSubjectId)
                .filter(id -> id != null)
                .flatMap(subjectRepository::findById)
                .filter(Subject::isActive)
                .filter(primary -> resolved.stream()
                        .noneMatch(current -> current.getId().equals(primary.getId())))
                .ifPresent(resolved::add);
        return List.copyOf(resolved);
    }
}
