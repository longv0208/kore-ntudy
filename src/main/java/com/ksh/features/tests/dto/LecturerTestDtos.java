package com.ksh.features.tests.dto;

import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Lecturer-facing DTOs: exam authoring form, exam list, live monitor snapshot,
 * and submissions overview. Records only — no entity leaves the service layer.
 */
public final class LecturerTestDtos {

    private LecturerTestDtos() {
        // holder for records
    }

    // ── Authoring form ───────────────────────────────────────────────

    /** A class the lecturer may attach an exam to. */
    public record ClassOption(Long id, String name, Long subjectId) {
    }

    /** One subject available when an independent Test Bank item is authored. */
    public record SubjectOption(Long id, String code, String name) {
    }

    /** An option row inside the question builder. */
    public record OptionForm(Long id, String content, boolean correct) {
    }

    /** A question row inside the question builder. */
    public record QuestionForm(Long id, String type, String content, String explanation,
                               BigDecimal points, List<OptionForm> options) {
    }

    /** The full create/edit exam form payload (JSON-bound from the builder). */
    public record ExamForm(Long id, String title, String description, Long subjectId, Long classId,
                           String type, String status, String timeMode,
                           Integer durationMinutes, LocalDateTime startAt, LocalDateTime endAt,
                           BigDecimal passingScore, boolean shuffleQuestions, boolean shuffleOptions,
                           String mediaType, String mediaUrl,
                           List<QuestionForm> questions, boolean questionBankLocked) {
    }

    /** One option copied from an approved bank item. */
    public record BankOptionSnapshot(String content, boolean correct) {
    }

    /** Snapshot payload returned by the approved bank picker query. */
    public record BankItemSnapshot(Long id, String subjectCode, String questionType,
                                   String content, String explanation,
                                   List<BankOptionSnapshot> options) {
    }

    /** Chosen approved bank item ids to snapshot into a test (insert-from-bank). */
    public record BankInsertRequest(List<Long> itemIds) {
    }

    /** Result of an insert-from-bank operation: how many questions were copied. */
    public record BankInsertResult(int insertedCount) {
    }

    /** One ACTIVE class eligible to receive a published test snapshot. */
    public record TestDistributionTarget(Long id, String name) {
    }

    /** Read model for the published-test distribution screen. */
    public record TestDistributionView(Long testId, String title, String subjectCode,
                                       List<TestDistributionTarget> targetClasses) {
    }

    /** Atomic result of copying one published test to selected classes. */
    public record TestDistributionResult(List<Long> testIds) {
        public int distributedCount() {
            return testIds.size();
        }
    }

    /** Save response: the persisted exam id + where to go next. */
    public record SaveResult(Long id) {
    }

    // ── Exam list ────────────────────────────────────────────────────

    /** A row on the lecturer exam list. */
    public record LecturerExamRow(Long id, String title, String description,
                                  String type, String status, String className,
                                  int totalQuestions, Integer durationMinutes,
                                  LocalDateTime createdAt, LocalDateTime updatedAt,
                                  LocalDateTime endAt, boolean canManage) {
    }

    /** Real, permission-scoped counters shown above the lecturer test catalog. */
    public record LecturerTestMetrics(long totalTests, long publishedTests,
                                      long draftTests, long totalQuestions) {
    }

    /** Validated filter state for lecturer-facing exam lists. */
    public record ExamFilter(String keyword, String status, String type, Long classId) {
        private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
        private static final Set<String> TYPES = Set.of("MOCK", "MODULE", "PRACTICE");

        public static ExamFilter of(String keyword, String status, String type, Long classId,
                                    List<ClassOption> allowedClasses) {
            Long safeClassId = classId != null && allowedClasses.stream()
                    .anyMatch(option -> option.id().equals(classId)) ? classId : null;
            return new ExamFilter(keyword == null ? "" : keyword.trim(),
                    status != null && STATUSES.contains(status) ? status : null,
                    type != null && TYPES.contains(type) ? type : null, safeClassId);
        }

        public boolean isEmpty() {
            return keyword.isEmpty() && status == null && type == null && classId == null;
        }
    }

    /** Minimal exam header for the monitor page (avoids leaking the entity). */
    public record ExamHeader(Long id, String title, String status, String timeMode,
                             LocalDateTime endAt, Integer totalQuestions) {
    }

    // ── Live monitor ─────────────────────────────────────────────────

    /** One student's live state in the monitor. */
    public record MonitorStudentRow(String name, String email, String state,
                                    LocalDateTime lastActivity, boolean active) {
    }

    /** The monitor snapshot returned as JSON to the polling client. */
    public record MonitorSnapshot(int submittedCount, int inProgressCount, int activeCount,
                                  String examStatus, Long remainingSeconds,
                                  List<MonitorStudentRow> students) {
    }

    // ── Submissions overview ─────────────────────────────────────────

    /** One attempt row on the submissions screen. */
    public record SubmissionRow(Long attemptId, String studentName, String email,
                                BigDecimal score, Integer correctCount, Integer totalQuestions,
                                LocalDateTime submittedAt, int attemptCount, boolean late) {
    }

    /** Summary stats + paginated attempts for the submissions screen. */
    public record SubmissionsView(Long testId, String title, int submittedCount, int lateCount,
                                  String examStatus, Page<SubmissionRow> attempts, String query) {
    }
}
