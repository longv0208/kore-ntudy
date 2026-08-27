package com.ksh.features.student.dto;

import java.time.LocalDateTime;

/** View-model + form DTOs for the student-facing {@code /my/classes} surface. */
public class StudentClassesDtos {

    /**
     * A single row rendered on {@code GET /my/classes}.
     *
     * @param classId        target class id (used by the leave form)
     * @param className      class name as shown in the row title
     * @param classCode      subject catalog code (legacy accessor name)
     * @param lecturerName   lecturer's full name for the row subtitle
     * @param joinedAt       when the student joined (timestamp shown)
     * @param avatarGradient CSS gradient string used by the card visual
     */
    public record EnrolledClassRow(
            Long classId,
            String className,
            String classCode,
            String lecturerName,
            LocalDateTime joinedAt,
            String avatarGradient
    ) {
        /** Two-letter capitalized abbreviation of the class name. */
        public String thumbLabel() {
            if (className == null || className.isBlank()) return "?";
            String trimmed = className.trim();
            int end = Math.min(2, trimmed.length());
            return trimmed.substring(0, end).toUpperCase();
        }
    }

    public record CatalogClassRow(
            Long classId,
            String className,
            String subjectCode,
            String subjectName,
            String lecturerName,
            String lecturerEmail,
            boolean alreadyRequested,
            boolean alreadyEnrolled
    ) {
    }
}
