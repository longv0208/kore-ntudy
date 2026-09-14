package com.ksh.entities;

import com.ksh.features.classes.semester.AcademicSemester;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Transient;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code classes} table.
 *
 * <p>There is no Course dependency and no random class/invite code. Classes
 * are discovered by name and their catalog subject code.
 *
 * <p>{@link SQLRestriction} ensures that every default query filters out
 * soft-deleted records ({@code is_deleted = 0}).
 */
@Entity
@Table(name = "classes")
@SQLRestriction("is_deleted = 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassEntity {

    /** Legacy status retained for historical classes; new classes are ACTIVE. */
    public static final String STATUS_PENDING = "PENDING";
    /** Legacy rejected class retained as history; review/resubmission is retired. */
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    /** Test-fixture compatibility only; V97 removed the persisted random code. */
    @Transient
    private String legacyCode;

    @Column(name = "lecturer_id", nullable = false)
    private Long lecturerId;

    /** Canonical subject catalog row (physical compatibility column name). */
    @Setter
    @Column(name = "subject_id")
    private Long subjectId;

    /** Immutable academic semester assigned automatically when the class is created. */
    @Column(nullable = false, length = 4)
    private String semester;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "max_students")
    private Integer maxStudents;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted")
    private boolean deleted = false;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_note", length = 500)
    private String rejectionNote;

    /**
     * Creates a new class for the create flow.
     * The class is immediately {@link #STATUS_ACTIVE}; no leader review is required.
     * If {@code maxStudents} is {@code null}, it defaults to {@code 100}.
     *
     * @param name        display name of the class
     * @param lecturerId  ID of the assigned lecturer
     * @param createdBy   ID of the user who created this class
     * @param description optional text description
     * @param startDate   scheduled start date
     * @param endDate     scheduled end date
     * @param maxStudents maximum number of enrolled students; {@code null} defaults to 100
     */
    public ClassEntity(String name, Long lecturerId, Long createdBy,
                       String description, LocalDate startDate, LocalDate endDate,
                       Integer maxStudents) {
        this.name = name;
        this.lecturerId = lecturerId;
        this.createdBy = createdBy;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.maxStudents = maxStudents != null ? maxStudents : 100;
        this.status = STATUS_ACTIVE;
        // Directly constructed fixtures and legacy seed paths still receive a
        // historically accurate semester. The normal create flow replaces it
        // with the configured current semester before the entity is persisted.
        this.semester = AcademicSemester.from(
                startDate != null ? startDate : LocalDate.now()).code();
    }

    // ── Business helpers ───────────────────────────────────────────

    /**
     * Updates the editable fields that a lecturer may change via the edit form.
     * {@code maxStudents} is only applied when non-null, preserving the current
     * value if the caller omits it.
     *
     * @param name        new display name
     * @param description new description text
     * @param startDate   new start date
     * @param endDate     new end date
     * @param maxStudents new student cap, or {@code null} to leave unchanged
     */
    public void updateDetails(String name, String description,
                              LocalDate startDate, LocalDate endDate,
                              Integer maxStudents) {
        this.name = name;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        if (maxStudents != null) {
            this.maxStudents = maxStudents;
        }
    }

    /**
     * Assigns the current semester before first persistence. Once a class has
     * an identity, its historical semester is immutable.
     */
    public void assignSemester(String semester) {
        String normalized = AcademicSemester.parse(semester).code();
        if (this.id != null && this.semester != null && !this.semester.isBlank()
                && !this.semester.equals(normalized)) {
            throw new IllegalStateException("Học kỳ của lớp đã được xác lập");
        }
        this.semester = normalized;
    }

    /**
     * Marks this class as soft-deleted.
     * After this call the {@link SQLRestriction} on the entity will exclude
     * this record from all default queries.
     */
    public void softDelete() {
        this.deleted = true;
    }

    public void approve(Long reviewerId, LocalDateTime reviewedAt) {
        requirePending();
        this.status = STATUS_ACTIVE;
        this.approvedBy = reviewerId;
        this.approvedAt = reviewedAt;
        this.rejectionNote = null;
    }

    public void reject(Long reviewerId, String note, LocalDateTime reviewedAt) {
        requirePending();
        this.status = STATUS_REJECTED;
        this.approvedBy = reviewerId;
        this.approvedAt = reviewedAt;
        this.rejectionNote = note == null || note.isBlank() ? null : note.trim();
    }

    /**
     * Reopens a rejected class for leader review after its owner has corrected
     * the submitted details. Active and archived classes never re-enter review.
     *
     * @return {@code true} only when this invocation changed REJECTED to PENDING
     */
    public boolean resubmitForReview() {
        if (!STATUS_REJECTED.equals(status)) {
            return false;
        }
        this.status = STATUS_PENDING;
        this.approvedBy = null;
        this.approvedAt = null;
        this.rejectionNote = null;
        return true;
    }

    public void archive() {
        if (STATUS_ACTIVE.equals(status)) {
            this.status = STATUS_ARCHIVED;
        }
    }

    /** @deprecated Class codes are no longer persisted or used by the product. */
    @Deprecated(forRemoval = true)
    public String getCode() {
        return legacyCode;
    }

    /** @deprecated Kept temporarily so legacy test fixtures can migrate independently. */
    @Deprecated(forRemoval = true)
    public void setCode(String ignored) {
        this.legacyCode = ignored;
    }

    private void requirePending() {
        if (!STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Lớp không còn ở trạng thái chờ duyệt");
        }
    }
}
