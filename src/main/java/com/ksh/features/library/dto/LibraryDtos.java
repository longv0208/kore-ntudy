package com.ksh.features.library.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the lecturer personal file library (SSR page + JSON picker).
 */
public final class LibraryDtos {

    private LibraryDtos() {
    }

    /** One asset row on the SSR library page. */
    public record LibraryAssetRow(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            long sizeBytes,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * Paginated view model for the lecturer-private asset inventory. Counts
     * are owner-scoped and intentionally independent of the active search so
     * the document/video rail remains stable while filtering.
     */
    public record LibraryAssetPageView(
            Page<LibraryAssetRow> page,
            String q,
            String kind,
            long totalCount,
            long documentCount,
            long videoCount
    ) {
    }

    /** Minimal owner-private item returned to reusable asset pickers. */
    public record LibraryAssetPickerItem(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            long sizeBytes
    ) {
    }

    /** JSON page envelope used by lesson/class asset selectors. */
    public record LibraryAssetPickerPage(
            List<LibraryAssetPickerItem> items,
            int page,
            int size,
            int totalPages,
            long totalElements
    ) {
    }

    /** Owner/admin class → section → lesson tree for one personal DOCUMENT. */
    public record PersonalAssetClassTargets(
            Long assetId,
            List<PersonalAssetClassTarget> classes
    ) {
    }

    public record PersonalAssetClassTarget(
            Long id,
            String name,
            String status,
            List<PersonalAssetSectionTarget> sections
    ) {
    }

    public record PersonalAssetSectionTarget(
            Long id,
            String title,
            List<PersonalAssetLessonTarget> lessons
    ) {
    }

    public record PersonalAssetLessonTarget(
            Long id,
            String title,
            String status,
            boolean canonicalSnapshot
    ) {
    }

    /** One editable class row for the library attach wizard. */
    public record AttachTargetClassRow(
            Long id,
            String name,
            String code,
            boolean alreadyDistributed
    ) {
    }

    /** One lesson-template row on the library "Bài giảng" tab. */
    public record LessonTemplateRow(
            Long id,
            String subjectCode,
            int chapterNumber,
            String chapterTitle,
            int lessonNumber,
            String title,
            String contentType,
            Long uploaderUserId,
            String uploaderDisplayName,
            LocalDateTime updatedAt,
            int attachmentCount,
            boolean canManage,
            List<LessonResourceRow> resources
    ) {
    }

    /** One persisted resource currently attached to a Library lesson. */
    public record LessonResourceRow(Long assetId, String kind, String label, String name) {
    }

    /** One chapter in the subject tree, containing its ordered lessons. */
    public record ChapterView(int number, String title, List<LessonTemplateRow> lessons,
                              boolean canManage) {
    }

    /** Paginated SSR view for the templates rail. */
    public record LessonTemplatePageView(
            Page<LessonTemplateRow> page,
            String q,
            Long subjectId,
            String subjectCode,
            String subjectName,
            String subjectDescription,
            List<SubjectContext> subjectOptions,
            List<AttachTargetClassRow> classOptions,
            List<ChapterView> chapters,
            long templateCount,
            boolean libraryLocked,
            boolean canManageLibraryLock
    ) {
    }

    /** Owned material option for Library lesson authoring. */
    public record MaterialOption(Long id, String title, String kind, String mimeType) {
    }

    /** Subject identity shown in the Library selector and authoring form. */
    public record SubjectContext(Long id, String code, String name, String description) {
    }

    /** Result of cloning a template or lesson into a destination section. */
    public record LessonCloneResult(
            Long lessonId,
            Long classId,
            Long sectionId,
            String title
    ) {
    }

    /** Subject with library statistics for the list view. */
    public record SubjectLibraryStats(
            Long id,
            String code,
            String name,
            String description,
            int chapterCount,
            int lessonCount
    ) {
    }
}
