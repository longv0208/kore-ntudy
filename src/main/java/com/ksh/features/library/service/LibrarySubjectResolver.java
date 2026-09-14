package com.ksh.features.library.service;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.leader.service.LeaderSubjectResolver;
import com.ksh.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Resolves the active subject catalog row backing a lecturer's Library. */
@Component
public class LibrarySubjectResolver {

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;
    private final LeaderSubjectResolver leaderResolver;

    public LibrarySubjectResolver(UserRepository userRepository,
                                  SubjectRepository subjectRepository,
                                  LeaderSubjectResolver leaderResolver) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.leaderResolver = leaderResolver;
    }

    public List<Subject> allowed(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Bạn chưa được gán mã môn"));
        if (actor.getRole() != role) {
            throw new AccessDeniedException("Bạn chưa được gán mã môn");
        }
        if (role == Role.LEADER) {
            List<Subject> assigned = leaderResolver.resolveAll(userId);
            if (assigned.isEmpty()) {
                throw new AccessDeniedException("Bạn chưa được gán mã môn");
            }
            return assigned;
        }
        if (role != Role.LECTURER && role != Role.ADMIN) {
            throw new AccessDeniedException("Bạn không có quyền truy cập kho học liệu");
        }
        return subjectRepository.findByActiveTrueOrderByNameAsc().stream()
                .sorted(Comparator.comparing(Subject::getCode,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Subject require(Long userId, Role role) {
        return require(userId, role, null);
    }

    public Subject require(Long userId, Role role, Long requestedSubjectId) {
        List<Subject> allowed = allowed(userId, role);
        if (allowed.isEmpty()) {
            throw new AccessDeniedException("Chưa có mã môn đang hoạt động");
        }
        if (requestedSubjectId == null) {
            User actor = userRepository.findById(userId)
                    .orElseThrow(() -> new AccessDeniedException("Bạn chưa được gán mã môn"));
            if (actor.getSubjectId() != null) {
                return allowed.stream()
                        .filter(subject -> actor.getSubjectId().equals(subject.getId()))
                        .findFirst()
                        .orElse(allowed.get(0));
            }
            return allowed.get(0);
        }
        return allowed.stream()
                .filter(subject -> requestedSubjectId.equals(subject.getId()))
                .findFirst()
                .filter(Subject::isActive)
                .orElseThrow(() -> new AccessDeniedException(
                        "Mã môn không hoạt động hoặc ngoài phạm vi của bạn"));
    }
}
