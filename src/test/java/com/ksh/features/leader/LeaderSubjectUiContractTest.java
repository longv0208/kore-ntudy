package com.ksh.features.leader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderSubjectUiContractTest {

    private static final Path DASHBOARD =
            Path.of("src/main/resources/templates/leader/dashboard.html");
    private static final Path REPORT =
            Path.of("src/main/resources/templates/leader/report.html");
    private static final Path SIDEBAR =
            Path.of("src/main/resources/templates/fragments/leader-sidebar.html");
    private static final Path STYLES =
            Path.of("src/main/resources/static/css/leader-subject.css");

    @Test
    void dashboard_uses_one_kpi_strip_and_one_table_surface() throws IOException {
        String dashboard = Files.readString(DASHBOARD, StandardCharsets.UTF_8);
        String styles = Files.readString(STYLES, StandardCharsets.UTF_8);

        assertThat(dashboard)
                .contains("class=\"leader-page leader-dashboard-page\"")
                .contains("aria-label=\"Tổng quan môn học\"")
                .contains("leader-kpi-icon--blue")
                .contains("leader-kpi-icon--violet")
                .contains("leader-kpi-icon--green")
                .contains("leader-kpi-icon--orange")
                .contains("class=\"leader-table-shell\"")
                .contains("class=\"leader-data-table\"")
                .doesNotContain("class=\"admin-list-panel\"")
                .doesNotContain("experience-polish.css");

        assertThat(styles)
                .contains(".leader-dashboard-page .leader-kpi-grid")
                .contains(".leader-table-shell")
                .contains(".leader-data-table")
                .contains("box-shadow: none");
    }

    @Test
    void report_is_a_flat_real_data_table() throws IOException {
        String report = Files.readString(REPORT, StandardCharsets.UTF_8);

        assertThat(report)
                .contains("class=\"leader-page leader-report-page\"")
                .contains("class=\"leader-report-section\"")
                .contains("class=\"leader-data-table leader-report-table\"")
                .contains("row.avgTestScore()")
                .contains("row.avgAssignmentScore()")
                .doesNotContain("class=\"admin-list-panel\"")
                .doesNotContain("experience-polish.css");
    }

    @Test
    void leader_sidebar_starts_with_dashboard_and_uses_lucide_style_icons() throws IOException {
        String sidebar = Files.readString(SIDEBAR, StandardCharsets.UTF_8);

        assertThat(sidebar.indexOf("@{/leader}"))
                .isLessThan(sidebar.indexOf("@{/leader/assign}"));
        assertThat(sidebar)
                .contains("<svg class=\"ico\"")
                .contains("Dashboard")
                .doesNotContain("Duyệt lớp", "@{/leader/approvals}")
                .contains("Báo cáo");
    }
}
