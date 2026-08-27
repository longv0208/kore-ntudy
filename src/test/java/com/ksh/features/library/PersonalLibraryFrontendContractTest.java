package com.ksh.features.library;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Static UI contract for the lecturer-private, owner-scoped material library. */
class PersonalLibraryFrontendContractTest {

    @Test
    void lecturer_navigation_exposes_personal_materials_without_replacing_subject_library()
            throws IOException {
        String header = resource("templates/fragments/app-header.html");

        assertThat(header).contains(
                "@{/lecturer/library/list}",
                "Kho bài giảng",
                "@{/lecturer/library/assets}",
                "Kho tài liệu cá nhân");
        assertThat(count(header, "@{/lecturer/library/assets}"))
                .as("desktop and compact lecturer navigation")
                .isEqualTo(2);
    }

    @Test
    void personal_page_uses_only_owner_scoped_asset_routes_and_complete_states()
            throws IOException {
        String page = resource("templates/library/assets.html");
        String script = resource("static/js/library-assets.js");

        assertThat(page).contains(
                "Kho tài liệu cá nhân",
                "@{/lecturer/library/assets(q=",
                "kind='DOCUMENT'",
                "kind='VIDEO'",
                "role=\"search\"",
                "name=\"q\"",
                "name=\"kind\"",
                "name=\"file\"",
                "accept=\".pdf,.docx,.pptx,.xlsx,.zip,video/mp4,.mp4\"",
                "enctype=\"multipart/form-data\"",
                "@{/lecturer/library/assets/upload}",
                "/lecturer/library/assets/${item.id()}/content",
                "/lecturer/library/assets/${item.id()}/rename",
                "/lecturer/library/assets/${item.id()}/delete",
                "libraryPage == null",
                "libraryPage.empty",
                "aria-live=\"polite\"",
                "@{/lecturer/library/list}",
                "Phân phối bài giảng");
        assertThat(page).doesNotContain(
                "/lecturer/classes/${",
                "attachments/from-library",
                "video-from-library",
                "pdf-from-library");

        assertThat(script).contains(
                "data-library-upload-form",
                "form.checkValidity()",
                "data-library-delete-form",
                "window.confirm");
    }

    @Test
    void canonical_lesson_form_selects_owned_assets_by_id_without_copying_files()
            throws IOException {
        String form = resource("templates/library/lesson-form.html");
        String picker = resource("static/js/library-picker.js");
        String formScript = resource("static/js/library-lesson-form.js");
        String inlineScript = resource("static/js/library-inline.js");

        assertThat(form).contains(
                "data-open-personal-library-picker",
                "data-selected-library-assets",
                "name=\"materialAssetIds\"",
                "materialOptions",
                "#lists.contains(form.materialAssetIds, material.id())",
                "@{/lecturer/library/assets}",
                "/css/library-assets.css",
                "/js/library-picker.js",
                "/js/library-lesson-form.js");
        assertThat(picker).contains(
                "var API = '/lecturer/library/assets/api'",
                "credentials: 'same-origin'",
                "headers: {'Accept': 'application/json'}",
                "selectedIds: new Set()",
                "textContent = item.title",
                "modal.setAttribute('aria-modal', 'true')",
                "modal.showModal()",
                "Không có tài liệu cá nhân phù hợp",
                "Chưa thể tải Kho tài liệu cá nhân")
                .doesNotContain("innerHTML = item", "/lecturer/library/api");
        assertThat(formScript).contains(
                "input.name = 'materialAssetIds'",
                "selectedIds: selectedIds()",
                "data-library-asset-id",
                "row.remove()",
                "window.KshLibraryLessonForm = {init: init}");
        assertThat(inlineScript).contains(
                "window.KshLibraryLessonForm.init(form)");
    }

    @Test
    void share_dialog_keeps_direct_class_share_supplementary_and_owner_scoped()
            throws IOException {
        String page = resource("templates/library/assets.html");
        String script = resource("static/js/library-assets.js");

        assertThat(page).contains(
                "data-personal-library-share",
                "id=\"personalLibraryShareDialog\"",
                "data-share-class-mode",
                "data-class-share-form",
                "name=\"classId\"",
                "data-share-status",
                "@{/lecturer/library/list}",
                "Chỉ lớp này",
                "không đưa vào vùng Bài giảng",
                "Video không thể gắn trực tiếp làm tài liệu bổ sung. Hãy gắn video chính trong Kho bài giảng.",
                "Tệp được tham chiếu từ kho cá nhân, không sao chép dữ liệu.");

        assertThat(script).contains(
                "currentAsset.kind !== 'DOCUMENT'",
                "'/class-targets'",
                "'/share/class'",
                "credentials: 'same-origin'",
                "body.set('classId', classSelect.value)",
                "csrfMeta('_csrf')",
                "csrfMeta('_csrf_header')",
                "'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'",
                "payload.message || 'Đã chia sẻ vào tab Tài liệu của lớp'");

        assertThat(page + script).doesNotContain(
                "name=\"sectionId\"",
                "name=\"lessonId\"",
                "body.set('sectionId'",
                "body.set('lessonId'");

        assertThat(page + script).doesNotContain(
                "video-from-library",
                "pdf-from-library",
                "bindVideo",
                "bindPdf",
                "/content/video",
                "/content/pdf",
                "storage.put",
                "storage.copy");
    }

    @Test
    void canonical_video_tab_selects_owner_video_into_primary_fields_not_material_ids()
            throws IOException {
        String form = resource("templates/library/lesson-form.html");
        String script = resource("static/js/library-lesson-form.js");

        assertThat(form).contains(
                "th:field=\"*{contentType}\" data-library-content-type",
                "data-library-form-tab=\"VIDEO\"",
                "data-library-form-panel=\"VIDEO\"",
                "th:field=\"*{videoProvider}\" data-library-video-provider",
                "th:field=\"*{videoLibraryAssetId}\" data-primary-video-id",
                "data-open-primary-video-picker",
                "data-clear-primary-video",
                "th:field=\"*{videoSummary}\"",
                "không sao chép hoặc upload lại tệp");
        assertThat(form).doesNotContain(
                "th:field=\"*{contentType}\" value=\"RICHTEXT\"");

        String primaryPicker = between(script, "if (videoPicker)", "renderPrimaryVideo(null)");
        assertThat(primaryPicker).contains(
                "kind: 'VIDEO'",
                "item.kind !== 'VIDEO'",
                "videoId.value = String(item.id)",
                "provider.value = 'UPLOAD'",
                "videoUrl.value = ''",
                "selectedIds: primaryVideoId() ? [primaryVideoId()] : []")
                .doesNotContain(
                        "contentType.value = 'VIDEO'",
                        "materialAssetIds",
                        "addAsset(item)");
    }

    @Test
    void class_sidebar_keeps_danh_muc_and_collapses_only_for_five_or_more_chapters()
            throws IOException {
        String lecturerSidebar = resource("templates/fragments/class-sidebar.html");
        String studentSidebar = resource("templates/fragments/student-class-sidebar.html");
        String navigation = resource("static/js/student-lesson-nav.js");

        assertThat(lecturerSidebar).contains(
                "class=\"side-group-label\">Danh mục</span>",
                "data-auto-collapse=${activeTab == 'lessons'}");
        assertThat(studentSidebar).contains(
                "class=\"student-class-menu-summary\"",
                "<span>Danh mục</span>",
                "data-auto-collapse=${active == 'lessons'}");
        assertThat(navigation).contains(
                "function setupAdaptiveClassMenu()",
                "menu.open = chapterCount < 5;");
    }

    private static String resource(String relative) throws IOException {
        return Files.readString(Path.of("src/main/resources").resolve(relative));
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + start.length());
        assertThat(from).as("start marker %s", start).isGreaterThanOrEqualTo(0);
        assertThat(to).as("end marker %s", end).isGreaterThan(from);
        return text.substring(from, to);
    }
}
