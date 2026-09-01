package com.ksh.features.library.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean inUse
    ) {
        public String extension() {
            if (originalFilename == null) return "";
            int dot = originalFilename.lastIndexOf('.');
            if (dot < 0 || dot == originalFilename.length() - 1) return "";
            return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        public String formatLabel() {
            String extension = extension();
            if (!extension.isBlank()) return extension.toUpperCase(Locale.ROOT);
            return switch (mimeFamily()) {
                case "excel" -> "XLSX";
                case "word" -> "DOCX";
                case "powerpoint" -> "PPTX";
                case "pdf" -> "PDF";
                case "video" -> "VIDEO";
                case "image" -> "IMAGE";
                case "archive" -> "ZIP";
                default -> "FILE";
            };
        }

        public String formatClass() {
            String extensionClass = switch (extension()) {
                case "xls", "xlsx", "csv" -> "excel";
                case "doc", "docx" -> "word";
                case "ppt", "pptx" -> "powerpoint";
                case "pdf" -> "pdf";
                case "mp4", "mov", "webm", "m4v" -> "video";
                case "png", "jpg", "jpeg", "gif", "webp", "svg" -> "image";
                case "zip", "rar", "7z" -> "archive";
                default -> "";
            };
            if (!extensionClass.isBlank()) return extensionClass;
            String mimeClass = mimeFamily();
            return "file".equals(mimeClass) && "VIDEO".equals(kind) ? "video" : mimeClass;
        }

        /** Compact product-style mark rendered inside the coloured file tile. */
        public String iconLabel() {
            return switch (formatClass()) {
                case "excel" -> "X";
                case "word" -> "W";
                case "powerpoint" -> "P";
                case "pdf" -> "PDF";
                case "video" -> "\u25b6";
                case "image" -> "IMG";
                case "archive" -> "ZIP";
                default -> "FILE";
            };
        }

        private String mimeFamily() {
            String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
            if (normalized.contains("spreadsheet") || normalized.contains("excel")
                    || normalized.equals("text/csv")) return "excel";
            if (normalized.contains("wordprocessingml") || normalized.contains("msword")) {
                return "word";
            }
            if (normalized.contains("presentationml") || normalized.contains("powerpoint")) {
                return "powerpoint";
            }
            if (normalized.equals("application/pdf")) return "pdf";
            if (normalized.startsWith("video/")) return "video";
            if (normalized.startsWith("image/")) return "image";
            if (normalized.contains("zip") || normalized.contains("rar")
                    || normalized.contains("7z")) return "archive";
            return "file";
        }
    }

    /**
     * Paginated view model for the lecturer-private asset inventory. Counts
     * are owner-scoped and intentionally independent of the active search so
     * the document/video rail remains stable while filtering.
     */
    public record LibraryAssetPageView(
            Page<LibraryAssetRow> page,
            List<LibraryAssetRow> recentlyUpdated,
            String q,
            String kind,
            String view,
            long totalCount,
            long documentCount,
            long videoCount,
            long inUseCount,
            long recentCount
    ) {
    }

    /**
     * Owner-private detail payload used by the asset drawer. URLs are
     * server-generated so the browser never has to infer an unsafe asset id or
     * guess where a durable reference lives.
     */
    public record LibraryAssetDetail(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            String formatLabel,
            String formatClass,
            long sizeBytes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String previewUrl,
            String contentUrl,
            String downloadUrl,
            List<LibraryAssetUsage> usages
    ) {
        public boolean inUse() {
            return usages != null && !usages.isEmpty();
        }
    }

    /** One exact, durable place that currently references an owned asset. */
    public record LibraryAssetUsage(
            String type,
            String title,
            String subtitle,
            String url,
            String placement,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * Safe server-side preview model. Browser-native media uses contentUrl;
     * OOXML/text previews carry bounded, escaped cells or paragraphs.
     */
    public record LibraryAssetPreview(
            Long id,
            String title,
            String originalFilename,
            String mimeType,
            String formatLabel,
            String formatClass,
            String previewKind,
            String contentUrl,
            String downloadUrl,
            String sheetName,
            List<List<String>> tableRows,
            List<String> textBlocks,
            String message
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
            boolean canManageStructure,
            boolean canAddResources,
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
