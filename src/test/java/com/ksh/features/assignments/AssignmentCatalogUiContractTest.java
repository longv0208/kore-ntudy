package com.ksh.features.assignments;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentCatalogUiContractTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/assignments");
    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void lecturer_catalog_uses_responsive_list_detail_workspace_instead_of_wide_table()
            throws IOException {
        String template = Files.readString(TEMPLATES.resolve("lecturer-list.html"));
        String css = Files.readString(STATIC.resolve("css/assignments.css"));

        assertThat(template).contains(
                "asgn-catalog-toolbar",
                "asgn-folder-pane",
                "asgn-list-pane",
                "asgn-detail-pane",
                "data-detail-submissions-link",
                "data-detail-publish-form",
                "data-detail-close-form");
        assertThat(template).doesNotContain("<table", "overflow-x:auto");
        assertThat(css).contains(
                ".asgn-workspace{display:grid",
                "@media(max-width:1200px)",
                "@media(max-width:920px)");
    }

    @Test
    void student_catalog_is_searchable_and_detail_renders_result_on_the_same_page()
            throws IOException {
        String template = Files.readString(TEMPLATES.resolve("student-list.html"));
        String detail = Files.readString(TEMPLATES.resolve("student-detail.html"));

        assertThat(template).contains(
                "data-assignment-search",
                "data-assignment-status",
                "data-assignment-sort",
                "asgn-student-list",
                "Xem kết quả đã khóa");
        assertThat(template).doesNotContain("<table", "overflow-x:auto");
        assertThat(detail).contains(
                "asgn-summary-card",
                "asgn-submission-card",
                "asgn-result-card",
                "Nhận xét của giảng viên");
        assertThat(detail).doesNotContain("asgn-lock-notice", "/feedback", "experience-polish.css");
    }

    @Test
    void catalog_script_filters_sorts_and_updates_lecturer_detail_actions() throws IOException {
        String script = Files.readString(STATIC.resolve("js/assignments.js"));
        String css = Files.readString(STATIC.resolve("css/assignments.css"));

        assertThat(script).contains(
                "data-assignment-status-shortcut",
                "data-assignment-sort",
                "data-detail-submissions-link",
                "data-detail-edit-link",
                "data-detail-publish-form",
                "data-detail-close-form",
                "syncVisibleSelection()",
                "const firstVisible = rows.find((row) => !row.hidden)",
                "detail.hidden = true",
                "status.dispatchEvent(new Event('change', { bubbles: true }))");
        assertThat(css)
                .contains("@media(max-width:620px)", ".asgn-catalog-toolbar select{min-height:42px}")
                .doesNotContain(".asgn-catalog-toolbar select{display:none}");
    }
}
