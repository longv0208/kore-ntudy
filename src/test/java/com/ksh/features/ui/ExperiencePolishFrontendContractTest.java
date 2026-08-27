package com.ksh.features.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExperiencePolishFrontendContractTest {

    private static final Path STYLES =
            Path.of("src/main/resources/static/css/experience-polish.css");

    private static final List<Path> POLISHED_TEMPLATES = List.of(
            Path.of("src/main/resources/templates/tests/lecturer-list.html"),
            Path.of("src/main/resources/templates/classes/detail-settings.html"),
            Path.of("src/main/resources/templates/classes/detail-progress.html"),
            Path.of("src/main/resources/templates/classes/detail-tests.html"),
            Path.of("src/main/resources/templates/tests/detail.html"),
            Path.of("src/main/resources/templates/tests/result.html"),
            Path.of("src/main/resources/templates/tests/review.html"),
            Path.of("src/main/resources/templates/leader/dashboard.html"),
            Path.of("src/main/resources/templates/leader/assign.html"),
            Path.of("src/main/resources/templates/leader/report.html")
    );

    @Test
    void selected_learning_workspaces_load_the_shared_polish_layer() throws IOException {
        for (Path template : POLISHED_TEMPLATES) {
            assertThat(Files.readString(template, StandardCharsets.UTF_8))
                    .as("shared polish link in %s", template)
                    .contains("@{/css/experience-polish.css}", "ux-polish");
        }
    }

    @Test
    void shared_styles_flatten_nested_surfaces_and_protect_narrow_layouts() throws IOException {
        String styles = Files.readString(STYLES, StandardCharsets.UTF_8);

        assertThat(styles)
                .contains("#classEditForm .detail-card + .detail-card")
                .contains(".student-assignment-panel > :is(")
                .contains(".ux-test-detail .td-grid")
                .contains(".ux-leader-report .admin-list-panel")
                .contains("overflow-x: auto")
                .contains("@media (max-width: 560px)");
    }

    @Test
    void answer_review_renders_sanitized_rich_explanations_without_literal_markup() throws IOException {
        String review = Files.readString(
                Path.of("src/main/resources/templates/tests/review.html"),
                StandardCharsets.UTF_8
        );

        assertThat(review)
                .contains("class=\"rv-explanation-content\"")
                .contains("th:utext=\"${q.explanation()}\"")
                .doesNotContain("th:text=\"'Giải thích: ' + ${q.explanation()}\"");
    }
}
