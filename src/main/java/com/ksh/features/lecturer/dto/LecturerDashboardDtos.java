package com.ksh.features.lecturer.dto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static com.ksh.common.IConstant.DEFAULT_TEACHING_PAGE_SIZE;

/** DTOs for the lecturer teaching dashboard (KSH-9.1). */
public final class LecturerDashboardDtos {

    private LecturerDashboardDtos() {
        // holder
    }

    /** Four KPI numbers shown on the teaching dashboard summary cards. */
    public record TeachingStats(
            long totalClasses,
            long totalStudents,
            long activeClasses,
            int avgCompletionPercent
    ) {
        /** Zeroed stats used when the lecturer has no classes in scope. */
        public static TeachingStats empty() {
            return new TeachingStats(0, 0, 0, 0);
        }
    }

    /** One class row in the teaching dashboard table. */
    public record ClassDashboardRow(
            Long id,
            String name,
            String code,
            String semester,
            String status,
            long studentCount,
            int avgPercent
    ) {
        /**
         * Returns the Vietnamese display label for the class status.
         *
         * @return human-readable status, or the raw value when unknown
         */
        public String displayStatus() {
            if (status == null) {
                return "—";
            }
            return switch (status) {
                case "PENDING" -> "Chờ duyệt";
                case "REJECTED" -> "Bị từ chối";
                case "UPCOMING" -> "Sắp khai giảng";
                case "ACTIVE" -> "Đang hoạt động";
                case "ARCHIVED" -> "Đã lưu trữ";
                case "COMPLETED" -> "Đã kết thúc";
                case "CANCELLED" -> "Đã huỷ";
                default -> status;
            };
        }

        /** Stable term code shown beside the subject code; legacy rows fail soft. */
        public String displaySemester() {
            return semester == null || semester.isBlank() ? "—" : semester;
        }
    }

    /**
     * Teaching-dashboard payload: full-scope KPIs plus a searched/paginated
     * class table window (summary ignores {@code q}/page).
     */
    public record TeachingDashboardView(
            TeachingStats stats,
            Page<ClassDashboardRow> classes
    ) {
        /** Empty dashboard payload (no classes in scope). */
        public static TeachingDashboardView empty() {
            // Keep page size aligned with the dashboard default for empty renders.
            return new TeachingDashboardView(
                    TeachingStats.empty(),
                    new PageImpl<>(List.of(),
                            PageRequest.of(0, DEFAULT_TEACHING_PAGE_SIZE), 0));
        }
    }
}
