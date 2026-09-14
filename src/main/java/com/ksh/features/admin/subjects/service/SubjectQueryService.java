package com.ksh.features.admin.subjects.service;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.dto.SubjectActivityRow;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectFilter;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectForm;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectOption;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectRow;
import com.ksh.features.admin.subjects.dto.SubjectDtos.LeaderCandidate;
import com.ksh.features.admin.subjects.repository.SubjectActivityRepository;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only subject queries for admin list/form screens.
 * Mutations live on {@link SubjectService}.
 */
@Service
public class SubjectQueryService {

    static final Set<Role> LEADER_ELIGIBLE = Set.of(Role.LECTURER, Role.LEADER);

    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final SubjectActivityRepository activityRepository;

    public SubjectQueryService(SubjectRepository subjectRepository,
                                  UserRepository userRepository,
                                  SubjectActivityRepository activityRepository) {
        this.subjectRepository = subjectRepository;
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
    }

    @Transactional(readOnly = true)
    public List<SubjectRow> list() {
        return list(SubjectFilter.empty());
    }

    /**
     * Lists subjects with optional search / status / sort applied in-memory.
     * Subject volume is small, so a full load keeps the path simple.
     */
    @Transactional(readOnly = true)
    public List<SubjectRow> list(SubjectFilter filter) {
        SubjectFilter f = filter == null ? SubjectFilter.empty() : filter;
        List<Subject> subjects = subjectRepository.findAllByOrderByNameAsc();
        Map<Long, String> leaderNames = loadLeaderNames(subjects);
        List<SubjectRow> rows = new ArrayList<>(subjects.size());
        for (Subject d : subjects) {
            if (!matchesQuery(d, f.q()) || !matchesStatus(d, f.status())) {
                continue;
            }
            String leaderLabel = d.getLeaderUserId() == null
                    ? null
                    : leaderNames.getOrDefault(d.getLeaderUserId(), "—");
            rows.add(new SubjectRow(
                    d.getId(), d.getCode(), d.getName(), d.getDescription(),
                    d.isActive(), d.getLeaderUserId(), leaderLabel, d.getCreatedAt()));
        }
        rows.sort(comparatorFor(f.sort()));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<SubjectOption> options() {
        return subjectRepository.findAllByOrderByNameAsc().stream()
                .map(d -> new SubjectOption(d.getId(), d.getCode(), d.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SubjectOption> activeOptions() {
        return subjectRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(d -> new SubjectOption(d.getId(), d.getCode(), d.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubjectForm loadForm(Long id) {
        Subject d = subjectRepository.findById(id).orElse(null);
        if (d == null) {
            return null;
        }
        return new SubjectForm(
                d.getName(), d.getCode(), d.getDescription(),
                d.isActive(), d.getLeaderUserId());
    }

    /** Active LECTURER/LEADER users eligible to become subject leader. */
    @Transactional(readOnly = true)
    public List<LeaderCandidate> leaderCandidates() {
        return userRepository.findByRoleInAndActiveTrueOrderByFullNameAsc(LEADER_ELIGIBLE).stream()
                .map(u -> new LeaderCandidate(
                        u.getId(), u.getFullName(), u.getEmail(), u.getRole().name()))
                .toList();
    }

    /** Paged audit history for the subject detail history tab. */
    @Transactional(readOnly = true)
    public Page<SubjectActivityRow> listActivities(Long subjectId, Pageable pageable) {
        return activityRepository.findActivitiesForSubject(subjectId, pageable);
    }

    /** Batch-loads full names for distinct non-null leader user ids. */
    private Map<Long, String> loadLeaderNames(List<Subject> subjects) {
        List<Long> ids = subjects.stream()
                .map(Subject::getLeaderUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            names.put(u.getId(), u.getFullName());
        }
        return names;
    }

    private static boolean matchesQuery(Subject d, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        String name = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
        String code = d.getCode() == null ? "" : d.getCode().toLowerCase(Locale.ROOT);
        return name.contains(needle) || code.contains(needle);
    }

    private static boolean matchesStatus(Subject d, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        // Whitelist: only active / inactive filter values are meaningful.
        if ("active".equalsIgnoreCase(status)) {
            return d.isActive();
        }
        if ("inactive".equalsIgnoreCase(status)) {
            return !d.isActive();
        }
        return true;
    }

    private static Comparator<SubjectRow> comparatorFor(String sort) {
        String key = (sort == null || sort.isBlank()) ? "name,asc" : sort;
        return switch (key) {
            case "name,desc" -> Comparator.comparing(
                    SubjectRow::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).reversed();
            case "code,asc" -> Comparator.comparing(
                    SubjectRow::code, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "code,desc" -> Comparator.comparing(
                    SubjectRow::code, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).reversed();
            case "createdAt,asc" -> Comparator.comparing(
                    SubjectRow::createdAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "createdAt,desc" -> Comparator.comparing(
                    SubjectRow::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
            default -> Comparator.comparing(
                    SubjectRow::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
    }
}
