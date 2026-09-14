package com.ksh.features.admin.subjects.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * DTOs for the {@code /admin/subjects} screen.
 */
public final class SubjectDtos {

    private SubjectDtos() {
    }

    /**
     * Optional list filters bound from GET query params.
     * Blank values mean "no filter" / default sort.
     */
    public record SubjectFilter(String q, String status, String sort) {

        public static SubjectFilter empty() {
            return new SubjectFilter(null, null, null);
        }
    }

    /**
     * List-row projection for the subjects table.
     *
     * @param leaderLabel display name of the assigned leader, or null when unassigned
     */
    public record SubjectRow(
            Long id,
            String code,
            String name,
            String description,
            boolean active,
            Long leaderUserId,
            String leaderLabel,
            LocalDateTime createdAt
    ) {
    }

    /**
     * Create/edit form. Leader assignment is a separate action on the list/form.
     */
    public record SubjectForm(
            @NotBlank(message = "Tên môn học không được để trống")
            @Size(max = 200, message = "Tên môn học tối đa 200 ký tự")
            String name,

            @NotBlank(message = "Mã môn học không được để trống")
            @Size(max = 20, message = "Mã môn học tối đa 20 ký tự")
            String code,

            @Size(max = 65535, message = "Mô tả quá dài")
            String description,

            boolean active,

            Long leaderUserId
    ) {
        public static SubjectForm empty() {
            return new SubjectForm(null, null, null, true, null);
        }
    }

    /** Dropdown option for leader candidate picker. */
    public record LeaderCandidate(Long id, String fullName, String email, String role) {
    }

    /** Lightweight option for admin user-form subject dropdown. */
    public record SubjectOption(Long id, String code, String name) {
    }
}
