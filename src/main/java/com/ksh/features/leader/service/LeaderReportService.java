package com.ksh.features.leader.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.leader.dto.LeaderDtos.SubjectSummary;
import com.ksh.features.leader.dto.LeaderDtos.ReportClassRow;
import com.ksh.features.leader.dto.LeaderDtos.ReportView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the subject class comparison report for LEADER.
 */
@Service
public class LeaderReportService {

    private final LeaderSubjectResolver resolver;
    private final ClassRepository classRepository;
    private final JdbcTemplate jdbc;

    public LeaderReportService(LeaderSubjectResolver resolver,
                             ClassRepository classRepository,
                             JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReportView load(Long leaderUserId) {
        List<Subject> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) {
            return new ReportView(null, List.of(), true);
        }
        List<ClassEntity> classes = new ArrayList<>();
        java.util.Map<Long, String> subjectCodes = new java.util.HashMap<>();
        for (Subject subject : subjects) {
            subjectCodes.put(subject.getId(), subject.getCode());
            classes.addAll(classRepository.findAllBySubjectIdOrderByCreatedAtDesc(subject.getId()));
        }
        classes.sort(java.util.Comparator.comparing(ClassEntity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        List<ReportClassRow> rows = new ArrayList<>(classes.size());
        for (ClassEntity c : classes) {
            long enrollments = countActiveEnrollments(c.getId());
            BigDecimal avgTest = avgTestScore(c.getId());
            BigDecimal avgAsg = avgAssignmentScore(c.getId());
            rows.add(new ReportClassRow(
                    c.getId(), c.getName(), subjectCodes.get(c.getSubjectId()),
                    enrollments, avgTest, avgAsg));
        }
        Subject first = subjects.get(0);
        SubjectSummary summary = subjects.size() == 1
                ? new SubjectSummary(first.getId(), first.getCode(), first.getName())
                : new SubjectSummary(first.getId(), subjects.size() + " mã môn",
                        "Môn học tiếng Hàn");
        return new ReportView(summary, rows, false);
    }

    private long countActiveEnrollments(Long classId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE class_id = ? AND status = 'ACTIVE'",
                Long.class, classId);
        return n == null ? 0L : n;
    }

    private BigDecimal avgTestScore(Long classId) {
        return jdbc.query(
                "SELECT AVG(ta.score) AS avg_score FROM test_attempts ta "
                        + "INNER JOIN tests t ON t.id = ta.test_id "
                        + "WHERE t.class_id = ? AND t.is_deleted = 0 "
                        + "AND ta.status IN ('SUBMITTED','TIMED_OUT') AND ta.score IS NOT NULL",
                rs -> rs.next() ? rs.getBigDecimal("avg_score") : null,
                classId);
    }

    private BigDecimal avgAssignmentScore(Long classId) {
        return jdbc.query(
                "SELECT AVG(af.score) AS avg_score FROM assignment_feedback af "
                        + "INNER JOIN assignment_submissions s ON s.id = af.submission_id "
                        + "INNER JOIN assignments a ON a.id = s.assignment_id "
                        + "WHERE a.class_id = ? AND a.is_deleted = 0",
                rs -> rs.next() ? rs.getBigDecimal("avg_score") : null,
                classId);
    }
}
