package com.ksh.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code subjects} catalog table.
 *
 * <p>Leader assignment is stored as a scalar {@code leaderUserId} (not a
 * {@code @ManyToOne}) to keep the entity simple and avoid lazy-loading loops.
 * Role promotion/demotion for the leader lives in the subject catalog service.
 */
@Entity
@Table(name = "subjects")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "leader_user_id")
    private Long leaderUserId;

    /**
     * Prevents further authoring of the canonical curriculum while keeping
     * existing versions available for class distribution.
     */
    @Column(name = "library_locked", nullable = false)
    private boolean libraryLocked;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected Department() {
    }

    /**
     * Creates a new subject ready to be persisted.
     *
     * @param name        display name
     * @param code        unique short code
     * @param description optional free-text description
     * @param active      whether the subject is active on creation
     */
    public Department(String name, String code, String description, boolean active) {
        this.name = name;
        this.code = code;
        this.description = description;
        this.active = active;
    }

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

    /** Applies edited identity fields. Caller validates uniqueness first. */
    public void applyEdit(String name, String code, String description, boolean active) {
        this.name = name;
        this.code = code;
        this.description = description;
        this.active = active;
    }

    /** Sets or clears the subject leader user id. */
    public void assignLeader(Long leaderUserId) {
        this.leaderUserId = leaderUserId;
    }

    /** Locks or unlocks canonical Library authoring for this subject. */
    public void setLibraryLocked(boolean libraryLocked) {
        this.libraryLocked = libraryLocked;
    }

    /** Flips the active flag and returns the new state. */
    public boolean toggleActive() {
        this.active = !this.active;
        return this.active;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Long getLeaderUserId() {
        return leaderUserId;
    }

    public boolean isLibraryLocked() {
        return libraryLocked;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
