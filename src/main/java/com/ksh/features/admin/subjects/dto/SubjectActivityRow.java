package com.ksh.features.admin.subjects.dto;

import java.time.LocalDateTime;

/** One row of administrative audit history for a subject. */
public record SubjectActivityRow(
        Long id,
        String type,
        String message,
        String actorEmail,
        LocalDateTime createdAt
) {
}
