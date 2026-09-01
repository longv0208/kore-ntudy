package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.ClassGradient;
import com.ksh.features.classes.dto.ClassesDtos.ClassRow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless mapping helpers shared by {@link ClassesService} for projecting
 * {@link ClassEntity} into list rows and capturing before/after snapshots
 * for the {@code TYPE_UPDATED} audit metadata.
 *
 * <p>Extracted during the file-size refactor; no Spring component needed
 * because everything is a pure function over the entity.
 */
final class ClassRowMapper {

    private ClassRowMapper() {
        // utility class
    }

    /**
     * Projects a {@link ClassEntity} into a list-row DTO. The gradient is
     * derived from {@code index} so different pages can repeat colours —
     * intentional and matches the audit's "good enough" tolerance for the
     * cosmetic ordering of class thumbnails.
     *
     * @param displayCode stable display code shown under the class name
     */
    static ClassRow toRow(ClassEntity e, int index, String displayCode,
                          long studentCount, long lectureCount,
                          long assignmentCount, long materialCount) {
        return toRow(e, index, displayCode, studentCount, lectureCount,
                assignmentCount, materialCount, "—");
    }

    static ClassRow toRow(ClassEntity e, int index, String displayCode,
                          long studentCount, long lectureCount,
                          long assignmentCount, long materialCount, String lecturerName) {
        String createdAtIso = e.getCreatedAt() != null ? e.getCreatedAt().toString() : "";
        String code = displayCode != null && !displayCode.isBlank() ? displayCode : "—";
        return new ClassRow(
                e.getId(),
                e.getName(),
                code,
                ClassGradient.forIndex(index).css(),
                Math.toIntExact(studentCount), Math.toIntExact(lectureCount),
                Math.toIntExact(assignmentCount), Math.toIntExact(materialCount),
                createdAtIso,
                e.getStatus(),
                e.getSemester(),
                lecturerName,
                e.getUpdatedAt() == null ? "—" : e.getUpdatedAt().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        );
    }

    /**
     * Captures the editable fields of {@code entity} as an insertion-ordered
     * map suitable for the {@code old}/{@code new} sides of an update audit.
     */
    static Map<String, Object> snapshot(ClassEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("startDate", e.getStartDate() != null ? e.getStartDate().toString() : null);
        m.put("endDate", e.getEndDate() != null ? e.getEndDate().toString() : null);
        m.put("maxStudents", e.getMaxStudents());
        m.put("status", e.getStatus());
        m.put("semester", e.getSemester());
        return m;
    }
}
