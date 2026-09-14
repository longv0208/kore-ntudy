package com.ksh.features.leader.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.leader.dto.LeaderDtos.DashboardKpis;
import com.ksh.features.leader.dto.LeaderDtos.DashboardView;
import com.ksh.features.leader.dto.LeaderDtos.SubjectSummary;
import com.ksh.features.leader.dto.LeaderDtos.RecentClassRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates subject-scoped KPIs and recent classes for the LEADER dashboard.
 */
@Service
public class LeaderDashboardService {

    private static final int RECENT_LIMIT = 5;

    private final LeaderSubjectResolver resolver;
    private final ClassRepository classRepository;
    private final JdbcTemplate jdbc;

    public LeaderDashboardService(LeaderSubjectResolver resolver,
                                ClassRepository classRepository,
                                JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DashboardView load(Long leaderUserId) {
        List<Subject> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) {
            return new DashboardView(null, new DashboardKpis(0, 0, 0, 0), List.of(), true);
        }
        long classCount = 0;
        long lecturerCount = 0;
        long studentCount = 0;
        long approvedQuestionCount = 0;
        List<ClassEntity> recent = new ArrayList<>();
        Map<Long, String> subjectCodes = new HashMap<>();
        for (Subject subject : subjects) {
            Long subjectId = subject.getId();
            subjectCodes.put(subjectId, subject.getCode());
            classCount += classRepository.countBySubjectId(subjectId);
            lecturerCount += countOrZero(
                    "SELECT COUNT(*) FROM users WHERE is_deleted = 0 AND is_active = 1 "
                            + "AND subject_id = ? AND role IN ('LECTURER','LEADER')",
                    subjectId);
            studentCount += countOrZero(
                    "SELECT COUNT(DISTINCT e.user_id) FROM enrollments e "
                            + "INNER JOIN classes c ON c.id = e.class_id "
                            + "WHERE e.status = 'ACTIVE' AND c.is_deleted = 0 AND c.subject_id = ?",
                    subjectId);
            approvedQuestionCount += countOrZero(
                    "SELECT COUNT(*) FROM question_bank_items WHERE subject_id = ? AND workflow_status = 'APPROVED'",
                    subjectId);
            recent.addAll(classRepository.findAllBySubjectId(subjectId,
                    PageRequest.of(0, RECENT_LIMIT,
                            Sort.by(Sort.Direction.DESC, "createdAt"))).getContent());
        }
        recent.sort(java.util.Comparator.comparing(ClassEntity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        if (recent.size() > RECENT_LIMIT) recent = new ArrayList<>(recent.subList(0, RECENT_LIMIT));
        Map<Long, String> lecturerNames = loadLecturerNames(recent);
        List<RecentClassRow> rows = new ArrayList<>(recent.size());
        for (ClassEntity c : recent) {
            rows.add(new RecentClassRow(
                    c.getId(), c.getName(), subjectCodes.get(c.getSubjectId()), c.getStatus(),
                    lecturerNames.getOrDefault(c.getLecturerId(), "—"),
                    c.getCreatedAt()));
        }

        return new DashboardView(
                summary(subjects),
                new DashboardKpis(classCount, lecturerCount, studentCount, approvedQuestionCount),
                rows,
                false);
    }

    private static SubjectSummary summary(List<Subject> subjects) {
        Subject first = subjects.get(0);
        return subjects.size() == 1
                ? new SubjectSummary(first.getId(), first.getCode(), first.getName())
                : new SubjectSummary(first.getId(), subjects.size() + " mã môn",
                        "Môn học tiếng Hàn");
    }

    private Map<Long, String> loadLecturerNames(List<ClassEntity> classes) {
        Map<Long, String> names = new HashMap<>();
        for (ClassEntity c : classes) {
            if (c.getLecturerId() != null && !names.containsKey(c.getLecturerId())) {
                List<String> found = jdbc.query(
                        "SELECT full_name FROM users WHERE id = ?",
                        (rs, i) -> rs.getString(1),
                        c.getLecturerId());
                if (!found.isEmpty()) {
                    names.put(c.getLecturerId(), found.get(0));
                }
            }
        }
        return names;
    }

    private long countOrZero(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }
}
