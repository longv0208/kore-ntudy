package com.ksh.features.library.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.LibraryAsset;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.library.dto.LibraryDtos.LessonTemplateRow;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration contracts for the subject Library hierarchy and distribution. */
@SpringBootTest
@Transactional
class LessonTemplateServiceTest {

    @Autowired private LessonTemplateService templateService;
    @Autowired private LessonTemplateRepository templateRepository;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;

    private User lecturer;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        assertThat(lecturer.getSubjectId()).as("seeded lecturer subject").isNotNull();
    }

    @Test
    void saveForm_persists_subject_chapter_lesson_hierarchy() {
        LessonTemplateRow row = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 2", "Bài kính ngữ"));

        LessonTemplate saved = templateRepository.findById(row.id()).orElseThrow();
        assertThat(saved.getSubjectId()).isEqualTo(lecturer.getSubjectId());
        assertThat(saved.getChapterOrder()).isEqualTo(2);
        assertThat(saved.getChapterTitle()).isEqualTo("Chương 2 · Vận dụng");
        assertThat(saved.getTitle()).startsWith("Bài ").endsWith(" · Bài kính ngữ");
        assertThat(saved.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(row.subjectCode()).isNotBlank();
        assertThat(row.uploaderUserId()).isEqualTo(lecturer.getId());
        assertThat(row.uploaderDisplayName()).isEqualTo(lecturer.getFullName());
    }

    @Test
    void distribute_creates_published_snapshot_in_each_same_subject_class() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 1", "Bài phân phối"));
        ClassEntity first = activeClass("Library A");
        ClassEntity second = activeClass("Library B");

        var results = templateService.distribute(template.id(),
                List.of(first.getId(), second.getId()), lecturer.getId(), Role.LECTURER);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> {
            Lesson lesson = lessonRepository.findById(result.lessonId()).orElseThrow();
            assertThat(lesson.getStatus()).isEqualTo(Lesson.STATUS_PUBLISHED);
            assertThat(lesson.getSourceLessonTemplateId()).isEqualTo(template.id());
        });
        assertThat(sectionRepository.findByClassIdOrderByDisplayOrderAsc(first.getId()))
                .extracting(section -> section.getTitle())
                .containsExactly("Chương 1 · Nền tảng");
    }

    @Test
    void distribute_again_refreshes_the_exact_snapshot_in_place() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 1", "Bài duy nhất"));
        ClassEntity clazz = activeClass("Library duplicate");

        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER).get(0).lessonId();

        var redistributed = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER);

        assertThat(redistributed).singleElement()
                .extracting(result -> result.lessonId()).isEqualTo(lessonId);
        assertThat(lessonRepository.findBySourceLessonTemplateIdOrderByIdAsc(template.id()))
                .singleElement().extracting(Lesson::getId).isEqualTo(lessonId);
    }

    @Test
    void distribute_rejects_class_that_is_still_awaiting_approval() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 1", "Bài chờ duyệt"));
        ClassEntity pending = new ClassEntity("Library pending", lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        pending.setSubjectId(lecturer.getSubjectId());
        ClassEntity savedPending = classRepository.saveAndFlush(pending);

        assertThatThrownBy(() -> templateService.distribute(template.id(),
                List.of(savedPending.getId()), lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đang sử dụng");
    }

    @Test
    void chapter_rename_and_reorder_mutate_only_the_actor_owned_rows() {
        int chapterNumber = 97;
        LessonTemplateRow foreign = templateService.saveForm(
                lecturer.getId(), Role.LECTURER,
                richtextForm("Chương " + chapterNumber, "Bài của giảng viên"));
        User admin = userRepository.findByEmailIgnoreCase("admin@ksh.edu.vn").orElseThrow();
        LessonTemplate owned = templateRepository.saveAndFlush(new LessonTemplate(
                admin.getId(), lecturer.getSubjectId(), chapterNumber,
                "Chương 97 · Của quản trị viên", 997,
                "Bài 997 · Của quản trị viên", Lesson.CONTENT_TYPE_RICHTEXT));

        templateService.renameChapter(admin.getId(), Role.ADMIN,
                lecturer.getSubjectId(), chapterNumber, "Chương riêng đã đổi tên");

        assertThat(templateRepository.findById(owned.getId()).orElseThrow().getChapterTitle())
                .isEqualTo("Chương 97 · Chương riêng đã đổi tên");
        assertThat(templateRepository.findById(foreign.id()).orElseThrow().getChapterTitle())
                .isEqualTo("Chương 97 · Nội dung chương 97");

        templateService.reorderChapters(admin.getId(), Role.ADMIN,
                lecturer.getSubjectId(), List.of(chapterNumber));

        assertThat(templateRepository.findById(owned.getId()).orElseThrow().getChapterOrder())
                .isEqualTo(1);
        assertThat(templateRepository.findById(foreign.id()).orElseThrow().getChapterOrder())
                .isEqualTo(chapterNumber);
    }

    @Test
    void rename_keeps_distributed_snapshot_immutable_until_explicit_redistribution() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER,
                richtextForm("Chương 96", "Tên phân phối ban đầu"));
        ClassEntity clazz = activeClass("Library rename provenance");
        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER).get(0).lessonId();

        templateService.renameLesson(lecturer.getId(), Role.LECTURER,
                template.id(), "Tên canonical sau khi đổi");

        assertThat(lessonRepository.findById(lessonId).orElseThrow().getTitle())
                .endsWith("· Tên phân phối ban đầu");

        var redistributed = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER);

        assertThat(redistributed).singleElement()
                .extracting(result -> result.lessonId()).isEqualTo(lessonId);
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getTitle())
                .endsWith("· Tên canonical sau khi đổi");
        assertThat(lessonRepository.findBySourceLessonTemplateIdOrderByIdAsc(template.id()))
                .extracting(Lesson::getId)
                .containsExactly(lessonId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_distribution_creates_one_exact_template_snapshot() throws Exception {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER,
                richtextForm("Chương 98", "Phân phối đồng thời"));
        ClassEntity clazz = activeClass("Library concurrent provenance");
        sectionRepository.saveAndFlush(new Section(
                clazz.getId(), "Chương 98 · Nội dung chương 98",
                (short) 0, lecturer.getId()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> distributeAfterBarrier(
                    template.id(), clazz.getId(), ready, start));
            Future<Boolean> second = executor.submit(() -> distributeAfterBarrier(
                    template.id(), clazz.getId(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)))
                    .containsExactly(true, true);
            assertThat(lessonRepository.findBySourceLessonTemplateIdOrderByIdAsc(template.id()))
                    .hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            classRepository.deleteById(clazz.getId());
            templateRepository.deleteById(template.id());
        }
    }

    @Test
    void video_summary_is_normalized_and_only_reaches_class_on_redistribution() {
        LessonTemplateForm create = richtextForm("Chương 93", "Video phản xạ giao tiếp");
        create.setVideoUrl("https://www.youtube.com/watch?v=kshVideo93");
        create.setVideoSummary("  Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.  ");

        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, create);

        LessonTemplate savedTemplate = templateRepository.findById(template.id()).orElseThrow();
        assertThat(savedTemplate.getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");
        assertThat(templateService.loadForm(lecturer.getId(), Role.LECTURER,
                template.id(), lecturer.getSubjectId()).getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");
        assertThat(templateRepository.searchOwnedSubject(
                lecturer.getId(), lecturer.getSubjectId(), "phản xạ giao tiếp",
                PageRequest.of(0, 20)).getContent())
                .extracting(LessonTemplate::getId)
                .contains(template.id());
        assertThat(templateService.list(lecturer.getId(), Role.LECTURER,
                lecturer.getSubjectId(), "phản xạ giao tiếp", 0, 20).page().getContent())
                .extracting(LessonTemplateRow::id)
                .contains(template.id());

        ClassEntity clazz = activeClass("Library video summary");
        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER).get(0).lessonId();
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");

        LessonTemplateForm edit = templateService.loadForm(lecturer.getId(), Role.LECTURER,
                template.id(), lecturer.getSubjectId());
        edit.setVideoSummary("  Phiên bản cập nhật: luyện nghe và trả lời trong 45 giây.  ");
        templateService.saveForm(lecturer.getId(), Role.LECTURER, edit);

        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");
        templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER);
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Phiên bản cập nhật: luyện nghe và trả lời trong 45 giây.");

        LessonTemplateForm clear = templateService.loadForm(lecturer.getId(), Role.LECTURER,
                template.id(), lecturer.getSubjectId());
        clear.setVideoUrl("   ");
        clear.setVideoSummary("Tóm tắt mồ côi không được phép lưu");
        templateService.saveForm(lecturer.getId(), Role.LECTURER, clear);

        assertThat(templateRepository.findById(template.id()).orElseThrow().getVideoSummary())
                .isNull();
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Phiên bản cập nhật: luyện nghe và trả lời trong 45 giây.");
        templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER);
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary()).isNull();
    }

    @Test
    void richtext_template_keeps_owned_uploaded_video_as_video_tab_not_attachment() {
        LibraryAsset video = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Video hội thoại riêng", "hoi-thoai.mp4",
                "library/" + lecturer.getId() + "/hoi-thoai.mp4",
                "video/mp4", 2_048L, LibraryAsset.KIND_VIDEO));
        LessonTemplateForm create = richtextForm(
                "Chương 95", "Nội dung và video cùng một bài");
        create.setVideoProvider("UPLOAD");
        create.setVideoLibraryAssetId(video.getId());
        create.setVideoSummary("Luyện hội thoại theo nội dung bài học.");

        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, create);
        LessonTemplate saved = templateRepository.findById(template.id()).orElseThrow();

        assertThat(saved.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(saved.getContentRichtext()).contains("Nội dung");
        assertThat(saved.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(saved.getVideoLibraryAssetId()).isEqualTo(video.getId());
        assertThat(saved.getVideoUrl()).isEqualTo(video.getStoredPath());

        LessonTemplateForm edit = templateService.loadForm(
                lecturer.getId(), Role.LECTURER, template.id(), lecturer.getSubjectId());
        assertThat(edit.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(edit.getVideoLibraryAssetId()).isEqualTo(video.getId());
        assertThat(edit.getVideoUrl()).isEmpty();
        assertThat(edit.getMaterialAssetIds()).doesNotContain(video.getId());

        ClassEntity clazz = activeClass("Library richtext uploaded video");
        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER).get(0).lessonId();
        Lesson lesson = lessonRepository.findById(lessonId).orElseThrow();
        assertThat(lesson.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(lesson.getContentRichtext()).contains("Nội dung");
        assertThat(lesson.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(lesson.getVideoLibraryAssetId()).isEqualTo(video.getId());
        assertThat(lesson.getVideoUrl()).isEqualTo(video.getStoredPath());
        assertThat(lesson.getVideoSummary())
                .isEqualTo("Luyện hội thoại theo nội dung bài học.");
    }

    @Test
    void editing_template_does_not_overwrite_same_title_lesson_without_provenance() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER,
                richtextForm("Chương 94", "Bài trùng tên nhưng độc lập"));
        LessonTemplate canonical = templateRepository.findById(template.id()).orElseThrow();
        ClassEntity clazz = activeClass("Library provenance guard");
        Section section = sectionRepository.saveAndFlush(new Section(
                clazz.getId(), canonical.getChapterTitle(), (short) 0, lecturer.getId()));
        Lesson directLesson = new Lesson(section.getId(), canonical.getTitle(),
                (short) 0, lecturer.getId());
        directLesson.updateContent("<p>Nội dung do lớp tự soạn</p>");
        directLesson.publish();
        Long directLessonId = lessonRepository.saveAndFlush(directLesson).getId();

        LessonTemplateForm edit = templateService.loadForm(lecturer.getId(), Role.LECTURER,
                template.id(), lecturer.getSubjectId());
        edit.setContentRichtext("<p>Nội dung canonical đã cập nhật</p>");
        templateService.saveForm(lecturer.getId(), Role.LECTURER, edit);

        Lesson unchanged = lessonRepository.findById(directLessonId).orElseThrow();
        assertThat(unchanged.getSourceLessonTemplateId()).isNull();
        assertThat(unchanged.getContentRichtext()).isEqualTo("<p>Nội dung do lớp tự soạn</p>");
    }

    @Test
    void library_is_a_subject_wide_canonical_hierarchy_not_an_owner_only_list() {
        LessonTemplateRow created = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 92", "Bài dùng chung"));
        User admin = userRepository.findByEmailIgnoreCase("admin@ksh.edu.vn").orElseThrow();

        var view = templateService.list(admin.getId(), Role.ADMIN,
                lecturer.getSubjectId(), "Bài dùng chung", 0, 20);

        assertThat(view.page().getContent())
                .extracting(LessonTemplateRow::id)
                .contains(created.id());
        assertThat(view.page().getContent().stream()
                .filter(row -> row.id().equals(created.id()))
                .findFirst().orElseThrow())
                .satisfies(row -> {
                    assertThat(row.canManage()).isFalse();
                    assertThat(row.uploaderUserId()).isEqualTo(lecturer.getId());
                    assertThat(row.uploaderDisplayName()).isEqualTo(lecturer.getFullName());
                });
    }

    @Test
    void insert_into_earlier_chapter_shifts_global_lesson_numbers() {
        LessonTemplateRow chapterOneFirst = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 90", "Một"));
        LessonTemplateRow chapterOneSecond = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 90", "Hai"));
        LessonTemplateRow chapterTwoFirst = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 91", "Ba"));
        int beforeInsert = templateRepository.findById(chapterTwoFirst.id()).orElseThrow()
                .getDisplayOrder();

        LessonTemplateRow inserted = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, richtextForm("Chương 90", "Chèn sau bài 2"));

        LessonTemplate first = templateRepository.findById(chapterOneFirst.id()).orElseThrow();
        LessonTemplate second = templateRepository.findById(chapterOneSecond.id()).orElseThrow();
        LessonTemplate third = templateRepository.findById(inserted.id()).orElseThrow();
        LessonTemplate shifted = templateRepository.findById(chapterTwoFirst.id()).orElseThrow();
        assertThat(List.of(first.getDisplayOrder(), second.getDisplayOrder(),
                third.getDisplayOrder(), shifted.getDisplayOrder()))
                .containsExactly(beforeInsert - 2, beforeInsert - 1, beforeInsert, beforeInsert + 1);
        assertThat(third.getTitle()).startsWith("Bài " + beforeInsert + " ·");
        assertThat(shifted.getTitle()).startsWith("Bài " + (beforeInsert + 1) + " ·");
    }

    private LessonTemplateForm richtextForm(String chapter, String title) {
        LessonTemplateForm form = new LessonTemplateForm();
        int chapterNumber = Integer.parseInt(chapter.replaceAll("\\D+", ""));
        form.setChapterNumber(chapterNumber);
        form.setChapterTitle("Nội dung chương " + chapterNumber);
        form.setTitle(title);
        form.setContentType(Lesson.CONTENT_TYPE_RICHTEXT);
        form.setContentRichtext("<p>Nội dung</p>");
        return form;
    }

    private boolean distributeAfterBarrier(Long templateId, Long classId,
                                           CountDownLatch ready,
                                           CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent distribution barrier timed out");
        }
        try {
            templateService.distribute(templateId, List.of(classId),
                    lecturer.getId(), Role.LECTURER);
            return true;
        } catch (IllegalArgumentException duplicate) {
            return false;
        }
    }

    private ClassEntity activeClass(String name) {
        ClassEntity clazz = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        clazz.setCode("L" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        clazz.setSubjectId(lecturer.getSubjectId());
        clazz.approve(lecturer.getId(), LocalDateTime.now());
        return classRepository.saveAndFlush(clazz);
    }
}
