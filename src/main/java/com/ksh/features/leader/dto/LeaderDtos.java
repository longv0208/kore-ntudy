package com.ksh.features.leader.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for LEADER department product screens.
 */
public final class LeaderDtos {

    private LeaderDtos() {
    }

    public record DepartmentSummary(Long id, String code, String name) {
    }

    public record DashboardKpis(
            long classCount,
            long lecturerCount,
            long studentCount,
            long approvedQuestionCount
    ) {
    }

    public record RecentClassRow(
            Long id,
            String name,
            String code,
            String status,
            String lecturerName,
            LocalDateTime createdAt
    ) {
    }

    public record DashboardView(
            DepartmentSummary department,
            DashboardKpis kpis,
            List<RecentClassRow> recentClasses,
            boolean emptyDepartment
    ) {
    }

    public record PendingClassRow(Long classId, String className, String subjectCode,
                                  String lecturerName, String lecturerEmail,
                                  LocalDateTime requestedAt) {
    }

    public record ApprovalQueueView(DepartmentSummary department,
                                    List<PendingClassRow> pendingClasses,
                                    boolean emptyDepartment) {
    }

    public record LecturerOption(Long id, String fullName, String email) {
    }

    public record AssignClassRow(
            Long classId,
            String className,
            String subjectCode,
            Long subjectId,
            Long lecturerId,
            String lecturerName,
            List<Long> coLecturerIds,
            List<String> coLecturerNames
    ) {
    }

    public record AssignView(
            DepartmentSummary department,
            List<AssignClassRow> classRows,
            List<LecturerOption> lecturers,
            boolean emptyDepartment
    ) {
    }

    public record ReportClassRow(
            Long classId,
            String className,
            String subjectCode,
            long activeEnrollments,
            BigDecimal avgTestScore,
            BigDecimal avgAssignmentScore
    ) {
    }

    public record ReportView(
            DepartmentSummary department,
            List<ReportClassRow> rows,
            boolean emptyDepartment
    ) {
    }

    public record QuestionBankTestRow(
            Long testId,
            String title,
            String className,
            String type,
            String status
    ) {
    }

    public record QuestionBankTestsView(
            DepartmentSummary department,
            List<QuestionBankTestRow> tests,
            boolean emptyDepartment
    ) {
    }
}
