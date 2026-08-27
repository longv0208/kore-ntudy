package com.ksh.features.flashcards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JPA entity mapping the {@code flashcard_decks} table (KSH-5.x).
 *
 * <p>A deck is a personal collection of two-sided cards owned by a student.
 * A fresh deck is {@link #VISIBILITY_PRIVATE}; the owner may switch it to
 * {@link #VISIBILITY_SHARED} targeting one or more of their classes so enrolled
 * classmates can view/study it. {@code OFFICIAL} decks are out of scope this
 * change but the value is kept for schema fidelity.
 *
 * <p>{@link SQLRestriction} filters soft-deleted rows out of every default
 * query, mirroring {@link com.ksh.entities.ClassEntity}. No {@code @Data} —
 * explicit getters and business helpers only.
 */
@Entity
@Table(name = "flashcard_decks")
@SQLRestriction("is_deleted = 0")
public class FlashcardDeck {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_SHARED = "SHARED";
    public static final String VISIBILITY_OFFICIAL = "OFFICIAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Legacy single-class pointer retained for backward-compatible migrations.
     * New code uses {@link #sharedClassIds}; it may contain the first shared class.
     */
    @Column(name = "class_id")
    private Long classId;

    /** Class targets stored inline as JSON to keep the flashcard schema single-table. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shared_class_ids", columnDefinition = "json")
    private Set<Long> sharedClassIds = new LinkedHashSet<>();

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** Optional active subject from the canonical subjects catalog. */
    @Column(name = "subject_id")
    private Long subjectId;

    @Column(nullable = false, length = 20)
    private String visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "share_token", length = 40)
    private String shareToken;

    @Column(name = "is_public", nullable = false)
    private boolean publicLink = false;

    /** JPA-only constructor; do not call from application code. */
    protected FlashcardDeck() {
    }

    /**
     * Creates a new PRIVATE deck ready to persist.
     *
     * @param ownerId     creator/owner id
     * @param title       trimmed, non-blank title
     * @param description optional description (may be null/blank)
     */
    public FlashcardDeck(Long ownerId, String title, String description) {
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
        this.visibility = VISIBILITY_PRIVATE;
        this.classId = null;
        this.deleted = false;
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Business helpers ───────────────────────────────────────────────

    /** Updates editable metadata; caller passes a trimmed, non-blank title. */
    public void updateMetadata(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /** Assigns or clears the optional subject classification. */
    public void assignSubject(Long subjectId) {
        this.subjectId = subjectId;
    }

    /** Adds a class target while preserving any classes already shared. */
    public void shareTo(Long classId) {
        this.visibility = VISIBILITY_SHARED;
        if (this.sharedClassIds == null) this.sharedClassIds = new LinkedHashSet<>();
        this.sharedClassIds.add(classId);
        if (this.classId == null) this.classId = classId;
    }

    /** Removes one class target and returns to PRIVATE after the last target. */
    public void unshareFrom(Long classId) {
        if (this.sharedClassIds == null) this.sharedClassIds = new LinkedHashSet<>();
        this.sharedClassIds.remove(classId);
        if (this.sharedClassIds.isEmpty()) {
            unshare();
        } else if (classId != null && classId.equals(this.classId)) {
            this.classId = this.sharedClassIds.iterator().next();
        }
    }

    /** Reverts the deck to PRIVATE and clears its target class. */
    public void unshare() {
        this.visibility = VISIBILITY_PRIVATE;
        this.classId = null;
        if (this.sharedClassIds == null) this.sharedClassIds = new LinkedHashSet<>();
        else this.sharedClassIds.clear();
    }

    /** Marks the deck soft-deleted; excluded from all default queries. */
    public void markDeleted() {
        this.deleted = true;
    }

    /** Enables the public link, retaining an existing token when available. */
    public void enablePublicLink(String freshToken) {
        if (shareToken == null) {
            shareToken = freshToken;
        }
        publicLink = true;
    }

    /** Disables anonymous access without invalidating an already distributed URL. */
    public void disablePublicLink() {
        publicLink = false;
    }

    /** Replaces a leaked token; the caller decides whether access stays enabled. */
    public void regeneratePublicToken(String freshToken) {
        shareToken = freshToken;
    }

    public boolean isShared() {
        return VISIBILITY_SHARED.equals(visibility);
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Long getClassId() {
        return classId;
    }

    public Set<Long> getSharedClassIds() {
        return sharedClassIds == null ? Set.of() : Set.copyOf(sharedClassIds);
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public String getVisibility() {
        return visibility;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public String getShareToken() {
        return shareToken;
    }

    public boolean isPublicLink() {
        return publicLink;
    }
}
