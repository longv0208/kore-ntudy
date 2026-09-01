package com.ksh.features.tests.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LecturerTestCatalogUiContractTest {

    @Test
    void lecturerCatalogKeepsWideMetricsFiltersAndDenseRealDataTable() throws IOException {
        String template = read("src/main/resources/templates/tests/lecturer-list.html");

        assertThat(template)
                .contains("tst-catalog-hero")
                .contains("tst-metric-grid")
                .contains("testMetrics.totalTests()")
                .contains("testMetrics.publishedTests()")
                .contains("testMetrics.draftTests()")
                .contains("testMetrics.totalQuestions()")
                .contains("name=\"status\"")
                .contains("name=\"type\"")
                .contains("name=\"classId\"")
                .contains("exam.durationMinutes()")
                .contains("exam.updatedAt()")
                .contains("exam.canManage()")
                .contains("Đề dùng chung")
                .contains("th:if=\"${exam.type() != 'PRACTICE'}\" th:href=\"@{|/lecturer/tests/${exam.id()}/preview|}\"")
                .contains("exam.status() == 'PUBLISHED' and exam.type() != 'PRACTICE'")
                .contains("Mở ngân hàng câu hỏi")
                .contains("Tạo bài test");
    }

    @Test
    void practiceRowsDoNotOfferUnsupportedPreviewOrDistributionActions()
            throws IOException {
        String list = read("src/main/resources/templates/tests/lecturer-list.html");
        String form = read("src/main/resources/templates/tests/lecturer-form.html");

        assertThat(list)
                .contains("th:if=\"${exam.type() != 'PRACTICE'}\" th:href=\"@{|/lecturer/tests/${exam.id()}/preview|}\"")
                .contains("th:if=\"${exam.status() == 'PUBLISHED' and exam.type() != 'PRACTICE'}\" th:href=\"@{|/lecturer/tests/${exam.id()}/distribute|}\"")
                .contains("class=\"is-wide\" th:if=\"${exam.status() == 'PUBLISHED' and exam.type() != 'PRACTICE'}\"");
        assertThat(form)
                .contains("th:if=\"${examForm == null or examForm.type() != 'PRACTICE'}\"")
                .contains("'/preview'}\">Xem trước</a>");
    }

    @Test
    void selectingATestOpensAnInlineRightDrawerWhileRouteActionsRemainAvailable()
            throws IOException {
        String template = read("src/main/resources/templates/tests/lecturer-list.html");
        String script = read("src/main/resources/static/js/test-lecturer-list.js");
        String css = read("src/main/resources/static/css/test-catalog.css");

        assertThat(template)
                .contains("data-test-detail")
                .contains("testDetailTemplate-")
                .contains("id=\"testDetailDrawer\"")
                .contains("role=\"dialog\"")
                .contains("/edit?tab=monitor")
                .contains("/edit?tab=submissions")
                .contains("/distribute");
        assertThat(script)
                .contains("template.content.cloneNode(true)")
                .contains("drawer.hidden = false")
                .contains("document.body.classList.add('tst-drawer-open')")
                .contains("event.key === 'Escape'");
        assertThat(css)
                .contains("justify-content: flex-end")
                .contains(".tst-drawer-panel")
                .contains("transform: translateX(100%)")
                .contains(".tst-detail-drawer.is-open .tst-drawer-panel");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
