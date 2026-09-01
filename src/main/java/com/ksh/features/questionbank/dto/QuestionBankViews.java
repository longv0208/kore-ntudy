package com.ksh.features.questionbank.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only DTOs for subject-scoped shared question pages. */
public final class QuestionBankViews {

    private QuestionBankViews() {
    }

    /** One distinct contributor shown in the LEADER management-screen filter. */
    public record ContributorOption(Long id, String name) {
    }

    /** Workflow-status tallies for the LEADER management-screen stat header. */
    public record StatusCounts(long review, long approved, long rejected,
                               long archived, long total) {
    }

    public record SubjectOption(Long id, String code, String name, String description) {
    }

    /** One subject row on the lecturer Question Bank entry catalog. */
    public record SubjectCatalogRow(Long id, String code, String name, String description,
                                    long chapterCount, long lessonCount, long questionCount,
                                    LocalDateTime lastUpdatedAt) {
    }

    /** Real totals shown above the subject catalog. */
    public record CatalogMetrics(long activeSubjectCount, long chapterCount,
                                 long lessonCount, long questionCount) {
    }

    /** One bounded read model for the catalog header, filters and table. */
    public record SubjectCatalogView(CatalogMetrics metrics,
                                     List<SubjectCatalogRow> rows) {
    }

    public record LessonOption(Long id, Long subjectId, String subjectCode,
                               String chapterTitle, String lessonTitle) {
    }

    /** One canonical Library chapter represented by its first lesson id. */
    public record ChapterOption(Long lessonId, int chapterOrder, String chapterTitle) {
    }

    public record ItemRow(Long id, String contentPreview, String questionType,
                          String workflowStatus, String subjectCode,
                          Long lessonTemplateId, int chapterOrder, int lessonOrder,
                          String chapterTitle, String lessonTitle,
                          Long contributorId, String contributorName, LocalDateTime updatedAt,
                          boolean editable, boolean reviewable,
                          boolean librarySourceAvailable) {
    }

    public record QuestionGroup(Long lessonTemplateId, String chapterTitle,
                                String lessonTitle, List<ItemRow> items) {
    }

    public record WorkspaceView(SubjectOption subject,
                                List<QuestionGroup> approvedGroups,
                                List<QuestionGroup> pendingGroups,
                                long approvedCount, long pendingCount) {
    }

    /** Subject-wide review payload used by the leader inbox. */
    public record SubjectReviewView(String subjectCode, String subjectName,
                                    List<ItemDetail> items, StatusCounts statusCounts,
                                    List<ContributorOption> contributors) {
    }

    public record OptionView(String content, boolean correct) {
    }

    public record ItemDetail(Long id, String questionType, String workflowStatus,
                             String content, String contentPreview, String explanation, String reviewNote,
                             String subjectCode, Long contributorId, String contributorName,
                             String reviewerName, LocalDateTime reviewedAt,
                             LocalDateTime approvedAt, LocalDateTime updatedAt,
                             List<OptionView> options, boolean editable,
                             boolean reviewable, boolean archivable, boolean unarchivable) {
    }
}
