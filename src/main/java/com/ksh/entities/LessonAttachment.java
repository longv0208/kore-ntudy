package com.ksh.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code lesson_attachments} table introduced by V15.
 *
 * <p>An attachment belongs either to one {@link Lesson}, or directly to one
 * class when a personal-library document is shared into the class Materials
 * tab. Class-level rows are library-backed references and never own the blob.
 *
 * <p>Plain getters (no Lombok {@code @Data}) to avoid the equals/hashCode
 * pitfalls flagged in the project conventions.
 */
@Entity
@Table(name = "lesson_attachments")
public class LessonAttachment {

    public static final String ORIGIN_CLASS_PRIVATE = "CLASS_PRIVATE";
    public static final String ORIGIN_CANONICAL_TEMPLATE = "CANONICAL_TEMPLATE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    /**
     * Optional FK to {@code library_assets}. When set the attachment is a
     * reference — cascade/delete must not remove the library blob on disk.
     */
    @Column(name = "library_asset_id")
    private Long libraryAssetId;

    /**
     * Provenance boundary used when a canonical lesson snapshot is refreshed.
     * Class-private rows survive that refresh; canonical rows are replaced from
     * the source template.
     */
    @Column(name = "origin_scope", nullable = false, length = 24)
    private String originScope = ORIGIN_CLASS_PRIVATE;

    /** JPA-only constructor; do not call from application code. */
    protected LessonAttachment() {
    }

    /**
     * Creates a new attachment row ready to persist.
     *
     * @param lessonId         owning lesson id
     * @param originalFilename filename as uploaded by the client (metadata only)
     * @param storedPath       relative path under the upload root
     * @param mimeType         resolved MIME type (from extension whitelist)
     * @param sizeBytes        file size in bytes
     * @param uploadedBy       id of the lecturer who uploaded the file
     */
    public LessonAttachment(Long lessonId, String originalFilename, String storedPath,
                            String mimeType, long sizeBytes, Long uploadedBy) {
        this(lessonId, originalFilename, storedPath, mimeType, sizeBytes, uploadedBy, null);
    }

    /**
     * Creates a library-backed or one-off attachment row.
     *
     * @param libraryAssetId optional library asset id (null for classic uploads)
     */
    public LessonAttachment(Long lessonId, String originalFilename, String storedPath,
                            String mimeType, long sizeBytes, Long uploadedBy,
                            Long libraryAssetId) {
        this(lessonId, originalFilename, storedPath, mimeType, sizeBytes,
                uploadedBy, libraryAssetId, ORIGIN_CLASS_PRIVATE);
    }

    /** Creates an attachment with explicit canonical/class-private provenance. */
    public LessonAttachment(Long lessonId, String originalFilename, String storedPath,
                            String mimeType, long sizeBytes, Long uploadedBy,
                            Long libraryAssetId, String originScope) {
        validateOriginScope(originScope, libraryAssetId);
        this.lessonId = lessonId;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.libraryAssetId = libraryAssetId;
        this.originScope = originScope;
    }

    /** Creates a no-copy personal-library reference for a class Materials tab. */
    public static LessonAttachment forClassMaterial(Long classId,
                                                    String originalFilename,
                                                    String storedPath,
                                                    String mimeType,
                                                    long sizeBytes,
                                                    Long uploadedBy,
                                                    Long libraryAssetId) {
        if (classId == null || libraryAssetId == null) {
            throw new IllegalArgumentException("Class materials require class and library asset ids");
        }
        LessonAttachment attachment = new LessonAttachment(
                null, originalFilename, storedPath, mimeType, sizeBytes,
                uploadedBy, libraryAssetId, ORIGIN_CLASS_PRIVATE);
        attachment.classId = classId;
        return attachment;
    }

    @PrePersist
    void onPersist() {
        validateOriginScope(originScope, libraryAssetId);
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
    }

    private static void validateOriginScope(String originScope, Long libraryAssetId) {
        if (!ORIGIN_CLASS_PRIVATE.equals(originScope)
                && !ORIGIN_CANONICAL_TEMPLATE.equals(originScope)) {
            throw new IllegalArgumentException("Unknown lesson attachment origin: " + originScope);
        }
        if (ORIGIN_CANONICAL_TEMPLATE.equals(originScope) && libraryAssetId == null) {
            throw new IllegalArgumentException(
                    "Canonical lesson attachments must reference a library asset");
        }
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public Long getClassId() {
        return classId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public Long getLibraryAssetId() {
        return libraryAssetId;
    }

    public String getOriginScope() {
        return originScope;
    }

    public boolean isClassPrivate() {
        return ORIGIN_CLASS_PRIVATE.equals(originScope);
    }

    public boolean isCanonicalTemplate() {
        return ORIGIN_CANONICAL_TEMPLATE.equals(originScope);
    }

    /** True when this row references a personal library asset (no owned blob). */
    public boolean isLibraryBacked() {
        return libraryAssetId != null;
    }
}
