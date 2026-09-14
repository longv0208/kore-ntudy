package com.ksh.features.student.controller;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.progress.repository.LearningProgressRepository;
import com.ksh.features.progress.service.LearningProgressService;
import com.ksh.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@link StudentLessonsController}. Exercises the
 * security filter chain, controller wiring, and template selection
 * end-to-end via MockMvc.
 *
 * <p>Uses pre-seeded users from migrations (V5 for {@code student@ksh.edu.vn}
 * and V8 for {@code sv01@ksh.edu.vn}) because {@code @WithUserDetails}
 * resolves the principal before {@code @BeforeEach} runs, so users
 * created in setup are not visible to {@code UserDetailsService}.
 *
 * <p>The single-template refactor folded the standalone lesson-detail
 * view into the 3-column list template; tests here also cover the
 * inline viewer dispatch per {@code contentType}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentLessonsControllerTest {

    // Seeded by V5 — the enrolled-student account for tests.
    private static final String STUDENT_EMAIL = "student@ksh.edu.vn";
    // Seeded by V8 — the not-enrolled student account.
    private static final String OUTSIDER_EMAIL = "sv01@ksh.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonAttachmentRepository lessonAttachmentRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private LearningProgressRepository progressRepository;

    // Spy (not mock): STUDENT tests keep the real progress write; only the
    // moderator test verifies recordOpened is never invoked (D7 guard).
    @MockitoSpyBean private LearningProgressService learningProgressService;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section1;
    private Lesson defaultLesson;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        student = userRepository.findByEmailIgnoreCase(STUDENT_EMAIL).orElseThrow();
        clazz = saveClass("Controller class", lecturer.getId(), "STCTLC");
        section1 = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        Lesson l = new Lesson(section1.getId(), "Bài 1", (short) 0, lecturer.getId());
        l.updateContent("<p>Body</p>");
        l.publish();
        defaultLesson = lessonRepository.saveAndFlush(l);
        // Enroll the seeded primary student into this fresh class.
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.REQUEST, null));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_as_enrolled_student_returns_200_with_view_and_model() throws Exception {
        var result = mockMvc.perform(get(url(clazz.getId(), null)))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andExpect(model().attributeExists("view"))
                .andExpect(model().attributeExists("activeSectionId"))
                .andReturn();

        ClassLessonsView view = (ClassLessonsView) result.getModelAndView()
                .getModel().get("view");
        assertThat(view).isNotNull();
        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.sections()).hasSize(1);
        assertThat(view.sections().get(0).lessons()).hasSize(1);
    }

    @Test
    @WithUserDetails(OUTSIDER_EMAIL)
    void get_as_not_enrolled_student_returns_404() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void get_as_anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), null)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_with_invalid_section_renders_with_default_active_section() throws Exception {
        var result = mockMvc.perform(get(url(clazz.getId(), 999999L)))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andReturn();

        // Invalid section param falls back to the first section (D7).
        Long active = (Long) result.getModelAndView().getModel().get("activeSectionId");
        assertThat(active).isEqualTo(section1.getId());
    }

    // ── Inline lesson detail (single-template refactor) ───────────────

    /** RICHTEXT lessons render the sanitised body inside the article wrapper. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void class_lessons_renders_richtext_viewer_when_type_is_RICHTEXT() throws Exception {
        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andExpect(model().attributeExists("lessonDetail"))
                // Article wrapper is the contract used by the RICHTEXT branch.
                .andExpect(content().string(containsString("<article class=\"student-lesson-detail-content\"")))
                // Body must be unescaped (th:utext) — proven by the raw <p> tag.
                .andExpect(content().string(containsString("<p>Body</p>")));

        // The open write now returns the checklist view; the normal route must
        // not repeat enrollment/access/applicability queries via getEngagement.
        verify(learningProgressService).recordOpened(
                clazz.getId(), defaultLesson.getId(), student.getId());
        verify(learningProgressService, never()).getEngagement(
                clazz.getId(), defaultLesson.getId(), student.getId());
    }

    /** PDF lessons render a PDF.js &lt;iframe&gt; plus a download fallback link. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void class_lessons_renders_pdf_viewer_when_type_is_PDF() throws Exception {
        Lesson pdfLesson = new Lesson(section1.getId(), "Bài PDF", (short) 1, lecturer.getId());
        pdfLesson.publish();
        pdfLesson = lessonRepository.saveAndFlush(pdfLesson);
        LessonAttachment main = lessonAttachmentRepository.saveAndFlush(new LessonAttachment(
                pdfLesson.getId(), "main.pdf", "stored/main.pdf",
                "application/pdf", 4096L, lecturer.getId()));
        pdfLesson.switchContentTypeTo("PDF");
        pdfLesson.setPdfAttachmentId(main.getId());
        pdfLesson = lessonRepository.saveAndFlush(pdfLesson);

        // Fallback download link still points at the raw stream endpoint.
        String expectedDownloadUrl = "/api/lessons/" + pdfLesson.getId()
                + "/attachments/" + main.getId() + "/download";
        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), pdfLesson.getId())))
                .andExpect(status().isOk())
                // Viewer is now a PDF.js iframe, not a browser-native <embed>.
                .andExpect(content().string(containsString("class=\"lesson-pdf-iframe\"")))
                // iframe src is the file-viewer page carrying the real PDF filename.
                .andExpect(content().string(containsString("/file-viewer?type=pdf")))
                .andExpect(content().string(containsString("main.pdf")))
                .andExpect(content().string(containsString(expectedDownloadUrl)));
    }

    /** VIDEO/YOUTUBE lessons render an iframe pointing at the embed URL. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void class_lessons_renders_video_iframe_when_provider_is_YOUTUBE() throws Exception {
        Lesson videoLesson = new Lesson(section1.getId(), "Bài YT", (short) 2, lecturer.getId());
        videoLesson.publish();
        videoLesson = lessonRepository.saveAndFlush(videoLesson);
        videoLesson.switchContentTypeTo("VIDEO");
        videoLesson.setVideoProvider("YOUTUBE");
        videoLesson.setVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        videoLesson.setVideoSummary("Luyện nghe lời chào và phản xạ trong lớp học.");
        videoLesson = lessonRepository.saveAndFlush(videoLesson);

        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), videoLesson.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<iframe")))
                .andExpect(content().string(containsString("youtube.com/embed/dQw4w9WgXcQ")))
                .andExpect(content().string(containsString("student-lesson-video-summary")))
                .andExpect(content().string(containsString(
                        "Luyện nghe lời chào và phản xạ trong lớp học.")));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void selected_lesson_renders_exactly_three_accessible_content_tabs_without_description_block()
            throws Exception {
        lessonAttachmentRepository.saveAndFlush(new LessonAttachment(
                defaultLesson.getId(),
                "Bảng chữ cái Hangeul.pdf",
                "stored/hangeul-reference.pdf",
                "application/pdf",
                12_288L,
                lecturer.getId()));

        var response = mockMvc.perform(get(urlWithLesson(
                        clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Document page = Jsoup.parse(response);
        Element tabList = page.selectFirst(".student-lesson-tabs[role=tablist]");
        assertThat(tabList).isNotNull();

        Elements tabs = tabList.select(".student-lesson-tab[role=tab]");
        assertThat(tabs).hasSize(3);
        assertThat(tabs.eachText()).containsExactly(
                "Nội dung bài học",
                "Video",
                "Tài liệu đính kèm");

        assertAccessibleTabPair(page, "lesson-tab-content", "lesson-panel-content");
        assertAccessibleTabPair(page, "lesson-tab-video", "lesson-panel-video");
        assertAccessibleTabPair(page, "lesson-tab-attachments", "lesson-panel-attachments");

        assertThat(page.select(".student-lesson-panel[role=tabpanel]")).hasSize(3);
        assertThat(page.select(".student-lessons-desc")).isEmpty();
        assertThat(page.text()).doesNotContain("Bài giảng không có mô tả nào");
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void student_sidebar_collapses_active_lesson_category_and_embeds_accessible_outline()
            throws Exception {
        var response = mockMvc.perform(get(urlWithLesson(
                        clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Document page = Jsoup.parse(response);
        Element sidebar = page.selectFirst("aside.student-lessons-sidebar");
        assertThat(sidebar).isNotNull();

        Element lessonMenu = sidebar.selectFirst("details.student-class-menu");
        assertThat(lessonMenu).isNotNull();
        assertThat(lessonMenu.hasAttr("open"))
                .as("a short course keeps the Danh mục group open")
                .isTrue();

        Element summary = lessonMenu.selectFirst("summary");
        assertThat(summary).isNotNull();
        assertThat(summary.text()).contains("Danh mục");
        assertThat(summary.select("svg[aria-hidden=true]")).hasSize(1);

        Element outline = sidebar.selectFirst(".student-lessons-outline");
        assertThat(outline).isNotNull();
        Element selectedLesson = outline.selectFirst("a[href='"
                + urlWithLesson(clazz.getId(), section1.getId(), defaultLesson.getId())
                + "']");
        assertThat(selectedLesson).isNotNull();
        assertThat(selectedLesson.text()).contains("Bài 1");
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void completion_is_rendered_as_a_student_post_control()
            throws Exception {
        String studentHtml = mockMvc.perform(get(urlWithLesson(
                        clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Document studentPage = Jsoup.parse(studentHtml);
        Element completion = studentPage.selectFirst("form.student-lesson-completion-form");
        assertThat(completion).isNotNull();
        assertThat(completion.attr("method")).isEqualToIgnoringCase("post");
        assertThat(completion.attr("action")).isEqualTo(
                "/my/classes/" + clazz.getId()
                        + "/lessons/" + defaultLesson.getId()
                        + "/progress/toggle");
        Element sectionInput = completion.selectFirst("input[name=section]");
        assertThat(sectionInput).isNotNull();
        assertThat(sectionInput.attr("value"))
                .isEqualTo(section1.getId().toString());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void teaching_view_does_not_render_student_completion_mutation() throws Exception {
        String lecturerUrl = "/lecturer/classes/" + clazz.getId() + "/lessons"
                + "?section=" + section1.getId()
                + "&lesson=" + defaultLesson.getId();
        String lecturerHtml = mockMvc.perform(get(lecturerUrl))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andExpect(model().attribute("teachingView", true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Document lecturerPage = Jsoup.parse(lecturerHtml);
        assertThat(lecturerPage.select(".student-lesson-completion-form")).isEmpty();
        assertThat(lecturerPage.select("form[action*='/progress/toggle']")).isEmpty();
    }

    // ── Moderator opens a lesson: no progress side-effect (design D7) ──

    /**
     * A moderator (owning lecturer, admitted via the widened D7 gate but not
     * enrolled) may open the lesson to moderate its thread, yet must NOT
     * accrue a learning-progress row — and the open must not emit a WARN.
     */
    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void owning_lecturer_is_redirected_to_role_correct_lesson_shell_without_progress_side_effect() throws Exception {
        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes/" + clazz.getId() + "/lessons"
                        + "?section=" + section1.getId() + "&lesson=" + defaultLesson.getId()));

        // The D7 guard short-circuits before recordOpened for non-students.
        // This is the assertion that locks the guard: reverting it lets the
        // controller call recordOpened for the moderator and fails here.
        verify(learningProgressService, never()).recordOpened(anyLong(), anyLong(), anyLong());

        // Invariant kept for defence in depth: even if recordOpened were
        // reached, its gate throws before persisting, so no row exists.
        assertThat(progressRepository
                .findByUserIdAndLessonId(lecturer.getId(), defaultLesson.getId()))
                .isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String url(Long classId, Long sectionParam) {
        String base = "/my/classes/" + classId + "/lessons";
        return sectionParam == null ? base : base + "?section=" + sectionParam;
    }

    private static String urlWithLesson(Long classId, Long sectionId, Long lessonId) {
        return "/my/classes/" + classId + "/lessons"
                + "?section=" + sectionId + "&lesson=" + lessonId;
    }

    private static void assertAccessibleTabPair(
            Document page, String tabId, String panelId) {
        Element tab = page.getElementById(tabId);
        assertThat(tab).isNotNull();
        assertThat(tab.attr("role")).isEqualTo("tab");
        assertThat(tab.attr("aria-controls")).isEqualTo(panelId);
        assertThat(tab.hasAttr("aria-selected")).isTrue();

        Element panel = page.getElementById(panelId);
        assertThat(panel).isNotNull();
        assertThat(panel.attr("role")).isEqualTo("tabpanel");
        assertThat(panel.attr("aria-labelledby")).isEqualTo(tabId);
        assertThat(panel.hasClass("student-lesson-panel")).isTrue();
    }

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity entity = new ClassEntity(name, lecturerId, lecturerId,
                null, null, null, 100);
        entity.setSubjectId(lecturer.getSubjectId());

        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
