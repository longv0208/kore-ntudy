package com.ksh.features.classes.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StudentClassesFrontendContractTest {

    private static final Path TEMPLATE =
            Path.of("src/main/resources/templates/student/my-classes.html");
    private static final Path STYLES =
            Path.of("src/main/resources/static/css/class-workspace.css");
    private static final Path SCRIPT =
            Path.of("src/main/resources/static/js/student-classes.js");
    private static final Path CLASS_LESSONS_TEMPLATE =
            Path.of("src/main/resources/templates/student/class-lessons.html");
    private static final Path CLASS_LESSONS_STYLES =
            Path.of("src/main/resources/static/css/student-lessons.css");

    @Test
    void student_workspace_uses_the_shared_semester_first_layout() throws IOException {
        String template = Files.readString(TEMPLATE);
        String styles = Files.readString(STYLES);

        assertThat(template).contains("<main class=\"class-page my-classes-shell class-workspace\">");
        assertThat(styles)
                .contains(".class-page.class-workspace {")
                .contains(".cw-table-student {")
                .contains("[data-semester^=\"SP\"]")
                .contains("[data-semester^=\"SU\"]")
                .contains("[data-semester^=\"FA\"]");
    }

    @Test
    void server_search_and_semester_groups_replace_grid_and_client_sort() throws IOException {
        String template = Files.readString(TEMPLATE);
        String script = Files.readString(SCRIPT);

        assertThat(template)
                .contains("id=\"active-class-list\"")
                .contains("class=\"class-row cw-row student-class-row\"")
                .contains("th:each=\"group, groupStat : ${semesterGroups}\"")
                .contains("id=\"studentClassQuery\"")
                .doesNotContain("id=\"viewToggle\"", "Thùng rác", "Sắp xếp");
        assertThat(script)
                .contains("[data-action=\"leave-class\"]", ".copy-code")
                .doesNotContain("getElementById('viewToggle')", "sortRows(", "applyFilter(");
    }

    @Test
    void empty_class_keeps_the_shared_sidebar_and_only_replaces_lesson_content() throws IOException {
        String template = Files.readString(CLASS_LESSONS_TEMPLATE);
        String styles = Files.readString(CLASS_LESSONS_STYLES);

        assertThat(template)
                .contains("class=\"student-lessons-grid\"")
                .contains("? ' is-empty'")
                .contains("classSidebar('lessons'")
                .contains("student-lessons-empty-main")
                .contains("Bạn vẫn có thể dùng các mục khác ở thanh bên.")
                .doesNotContain("class=\"student-lessons-grid\"\n       th:unless=\"${#lists.isEmpty(view.sections())}\"");
        assertThat(styles)
                .contains(".student-lessons-grid.is-empty {")
                .contains("grid-template-columns: 260px minmax(0, 1fr);")
                .contains(".student-lessons-empty-main {");
    }
}
