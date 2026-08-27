package com.ksh.features.classes;

import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NonPracticeLearningUiContractTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void impacted_learning_pages_share_the_scoped_light_palette() throws IOException {
        for (String template : new String[]{
                "tests/lecturer-list.html",
                "questionbank/form.html",
                "library/index.html",
                "library/lesson-form.html",
                "student/my-classes.html"}) {
            assertThat(Files.readString(TEMPLATES.resolve(template)))
                    .contains("/css/learning-ui.css");
        }

        String css = Files.readString(STATIC.resolve("css/learning-ui.css"));
        assertThat(css).contains(
                "--learn-primary: #367fa9",
                "--learn-primary-soft: #eaf6fc",
                ".library-media-row",
                ".tst-table-wrap");
        assertThat(css).doesNotContain(".pr-page", ".practice-");
    }

    @Test
    void enhanced_dropdowns_keep_native_submission_and_accessible_keyboard_controls()
            throws IOException {
        String script = Files.readString(STATIC.resolve("js/learning-select.js"));
        String css = Files.readString(STATIC.resolve("css/learning-ui.css"));
        String forms = Files.readString(TEMPLATES.resolve("questionbank/form.html"))
                + Files.readString(TEMPLATES.resolve("library/lesson-form.html"))
                + Files.readString(TEMPLATES.resolve("tests/lecturer-list.html"));

        assertThat(forms).contains("data-ksh-select", "<select");
        assertThat(script).contains(
                "document.createElement('button')",
                "setAttribute('role', 'listbox')",
                "document.body.appendChild(menu)",
                "wrapper.contains(target) || menu.contains(target)",
                "window.KshLearningSelect = { mount: mount }",
                "ksh:detail-tab-loaded",
                "dataset.kshSelectReady === 'true'",
                "event.key === 'Escape'",
                "event.key !== 'ArrowDown'",
                "event.key !== 'Enter'",
                "event.stopPropagation()",
                ".filter(function (button) { return !button.hidden; })",
                "select.getAttribute('aria-labelledby')",
                "select.getAttribute('aria-label')",
                "labelCopy.querySelectorAll('select, input, textarea, button, .ksh-select')",
                "select.dispatchEvent(new Event('change'",
                "select.setAttribute('aria-hidden', 'true')");
        assertThat(script).doesNotContain("<dropdown", "<select-wrapper");
        assertThat(script).contains(
                "input[data-link-filter]",
                "input[data-item-filter]",
                "item.hidden = Boolean(needle)");
        assertThat(css).contains(
                ".ksh-select-option[hidden]",
                ".ksh-checklist-options > label[hidden]",
                ".library-subject-list > a[hidden]",
                ".qb-subject-rail > a[hidden]",
                "display: none !important");
    }

    @Test
    void dictionary_deck_picker_opens_above_panel_and_can_create_a_personal_deck()
            throws IOException {
        String dictionaryScript = Files.readString(STATIC.resolve("js/korean-dictionary.js"));
        String selectScript = Files.readString(STATIC.resolve("js/learning-select.js"));
        String dictionaryCss = Files.readString(STATIC.resolve("css/korean-dictionary.css"));

        assertThat(dictionaryScript).contains(
                "data-ksh-select-menu-class=\"kdict-select-menu\"",
                "+ Tạo bộ thẻ mới…",
                "newDeckTitle",
                "Tạo bộ và lưu flashcard",
                "deckCreated");
        assertThat(selectScript).contains(
                "select.dataset.kshSelectMenuClass",
                "menu.classList.add.apply");
        assertThat(dictionaryCss).contains(
                ".kdict-panel{position:fixed;z-index:1210",
                ".kdict-select-menu{z-index:1240!important}",
                ".kdict-create-deck[hidden]{display:none}");
    }

    @Test
    void student_classes_use_separate_compact_views_and_render_one_header() throws IOException {
        String classes = Files.readString(TEMPLATES.resolve("student/my-classes.html"));
        String lessons = Files.readString(TEMPLATES.resolve("student/class-lessons.html"));

        assertThat(classes).contains(
                "Lớp của tôi",
                "Danh sách lớp đang mở",
                "classesTab == 'mine'",
                "classesTab == 'open'",
                "catalog-pagination");
        assertThat(lessons).contains(
                "<th:block th:if=\"${!teachingView}\">",
                "<th:block th:if=\"${teachingView}\">");
        assertThat(lessons).doesNotContain(
                "<header th:if=\"${!teachingView}\" th:replace",
                "<header th:if=\"${teachingView}\" th:replace");
    }

    @Test
    void question_bank_and_library_expose_one_canonical_numeric_hierarchy()
            throws IOException {
        String library = Files.readString(TEMPLATES.resolve("library/index.html"));
        String questionBank = Files.readString(TEMPLATES.resolve("questionbank/list.html"));
        String workspace = Files.readString(STATIC.resolve("js/question-bank-workspace.js"));

        assertThat(library).contains(
                "chapter.number()",
                "item.lessonNumber()",
                "library-lesson-list");
        assertThat(questionBank).contains(
                "value=\"SUBJECT\"",
                "value=\"CHAPTER\"",
                "value=\"LESSON\"",
                "chapterOptions",
                "lessonOptions");
        assertThat(workspace).contains("control.disabled = !active");
    }

    @Test
    void leader_assignment_is_compact_searchable_and_preserves_multi_lecturer_selection()
            throws IOException {
        String template = Files.readString(TEMPLATES.resolve("leader/assign.html"));

        assertThat(template).contains(
                "leader-assignment-list",
                "<details class=\"leader-assignment-row\"",
                "data-item-filter=\"#leader-assignment-list\"",
                "type=\"checkbox\" name=\"lecturerIds\"");
        assertThat(template).doesNotContain("<table", "leader-colecturer-cell");
    }

    @Test
    void lesson_authoring_uses_one_fixed_context_with_inline_content_and_optional_resources()
            throws IOException {
        String template = Files.readString(TEMPLATES.resolve("library/lesson-form.html"));
        String script = Files.readString(STATIC.resolve("js/library-lesson-form.js"));
        String css = Files.readString(STATIC.resolve("css/learning-ui.css"));

        assertThat(template).contains(
                "data-inline-action=",
                "th:field=\"*{subjectId}\"",
                "th:field=\"*{chapterNumber}\"",
                "th:field=\"*{lessonNumber}\"",
                "th:field=\"*{title}\"",
                "th:field=\"*{contentType}\" data-library-content-type",
                "data-library-form-tab=\"CONTENT\"",
                "data-library-form-tab=\"VIDEO\"",
                "data-library-form-tab=\"ATTACHMENTS\"",
                "data-library-richtext-value",
                "data-library-richtext-editor",
                "th:field=\"*{videoUrl}\"",
                "data-library-file-dropzone",
                "th:field=\"*{materialUploads}\" multiple");
        assertThat(template).doesNotContain(
                "library-subject-banner",
                "library-fixed-subject",
                "library-numbered-input",
                "data-content-section=");

        var document = Jsoup.parse(template);
        for (String name : List.of("CONTENT", "VIDEO", "ATTACHMENTS")) {
            Element tab = document.selectFirst("[role=tab][data-library-form-tab=" + name + "]");
            Element panel = document.selectFirst("[role=tabpanel][data-library-form-panel=" + name + "]");
            assertThat(tab).as("authoring tab %s", name).isNotNull();
            assertThat(panel).as("authoring panel %s", name).isNotNull();
            assertThat(tab.id()).as("tab id for %s", name).isNotBlank();
            assertThat(panel.id()).as("panel id for %s", name).isNotBlank();
            assertThat(tab.attr("aria-controls")).isEqualTo(panel.id());
            assertThat(panel.attr("aria-labelledby")).isEqualTo(tab.id());
        }
        assertThat(script).contains(
                "tab.tabIndex = active ? 0 : -1",
                "event.key === 'ArrowRight'",
                "event.key === 'ArrowLeft'",
                "event.key === 'Home'",
                "event.key === 'End'",
                "activeTab.focus()");
        assertThat(css).contains(
                ".library-form-section[hidden] { display: none !important; }");
    }

    @Test
    void class_detail_exposes_the_teaching_team_and_keeps_join_approval_owner_only()
            throws IOException {
        String sidebar = Files.readString(TEMPLATES.resolve("fragments/class-sidebar.html"));
        String members = Files.readString(TEMPLATES.resolve("classes/detail-members.html"));

        assertThat(sidebar).contains(
                "Đội ngũ giảng dạy",
                "classTeachingTeam.owner()",
                "classTeachingTeam.coLecturers()",
                "class=\"side-foot\" th:if=\"${isPrimaryClassOwner}\"");
        assertThat(sidebar).doesNotContain("classViewerRole");
        assertThat(members).contains(
                "classTeachingTeam.owner().name()",
                "classTeachingTeam.coLecturers()",
                "GV chủ lớp",
                "Giảng viên đồng giảng",
                "th:if=\"${isPrimaryClassOwner}\"",
                "GV chủ lớp xử lý");
    }

    @Test
    void class_detail_navigation_stays_role_correct_and_student_members_can_message_co_lecturers()
            throws IOException {
        String lecturerSidebar = Files.readString(TEMPLATES.resolve("fragments/class-sidebar.html"));
        String studentSidebar = Files.readString(TEMPLATES.resolve("fragments/student-class-sidebar.html"));
        String studentMembers = Files.readString(TEMPLATES.resolve("student/class-members.html"));
        String lessonController = Files.readString(Path.of(
                "src/main/java/com/ksh/features/lessons/controller/LessonsTabController.java"));
        String memberService = Files.readString(Path.of(
                "src/main/java/com/ksh/features/student/service/StudentClassDetailService.java"));

        assertThat(lecturerSidebar)
                .contains("/lessons", "/materials")
                .doesNotContain(">Bảng tin<", ">Lịch học<", ">Nhóm học tập<", ">Vai trò lớp<");
        assertThat(studentSidebar)
                .contains("/lessons|}", "/members|}", "/materials|}")
                .doesNotContain(">Bảng tin<", ">Lịch học<", ">Nhóm học tập<", ">Tin nhắn<");
        assertThat(lessonController)
                .contains("return \"student/class-lessons\"", "lessonBasePath")
                .doesNotContain("redirect:/my/classes/");
        assertThat(memberService).contains(
                "coLecturerRepository.findAllByClassId",
                "Giảng viên đồng giảng");
        assertThat(studentMembers).contains(
                "th:action=\"@{/my/messages/new}\"",
                "member.canMessage()",
                "Nhắn tin");
    }
}
