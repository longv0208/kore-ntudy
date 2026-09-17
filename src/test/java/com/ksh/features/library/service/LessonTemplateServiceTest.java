package com.ksh.features.library.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.LibraryAsset;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import jakarta.persistence.EntityNotFoundException;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.library.dto.LibraryDtos.LessonTemplateRow;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.features.library.repository.LessonTemplateAttachmentRepository;
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
    @Autowired private LessonTemplateAttachmentRepository templateAttachmentRepository;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;

    private User lecturer;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("kor_leader@ksh.edu.vn").orElseThrow();
        assertThat(lecturer.getSubjectId()).as("seeded lecturer subject").isNotNull();
    }

    @Test
    void saveForm_persists_subject_chapter_lesson_hierarchy() {
        LessonTemplateRow row = templateService.saveForm(
                lecturer.getId(), Role.LEADER, richtextForm("Chương 2", "Bài kính ngữ"));

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
                lecturer.getId(), Role.LEADER, richtextForm("Chương 1", "Bài phân phối"));
        ClassEntity first = activeClass("Library A");
        ClassEntity second = activeClass("Library B");

        var results = templateService.distribute(template.id(),
                List.of(first.getId(), second.getId()), lecturer.getId(), Role.LEADER);

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
                lecturer.getId(), Role.LEADER, richtextForm("Chương 1", "Bài duy nhất"));
        ClassEntity clazz = activeClass("Library duplicate");

        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER).get(0).lessonId();

        var redistributed = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER);

        assertThat(redistributed).singleElement()
                .extracting(result -> result.lessonId()).isEqualTo(lessonId);
        assertThat(lessonRepository.findBySourceLessonTemplateIdOrderByIdAsc(template.id()))
                .singleElement().extracting(Lesson::getId).isEqualTo(lessonId);
    }

    @Test
    void distribute_rejects_historical_pending_class() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LEADER, richtextForm("Chương 1", "Bài chờ duyệt"));
        ClassEntity pending = new ClassEntity("Library pending", lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        pending.setSubjectId(lecturer.getSubjectId());
        org.springframework.test.util.ReflectionTestUtils.setField(
                pending, "status", ClassEntity.STATUS_PENDING);
        ClassEntity savedPending = classRepository.saveAndFlush(pending);

        assertThatThrownBy(() -> templateService.distribute(template.id(),
                List.of(savedPending.getId()), lecturer.getId(), Role.LEADER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đang sử dụng");
    }

    @Test
    void admin_cannot_rename_or_reorder_even_its_own_library_rows() {
        int chapterNumber = 97;
        LessonTemplateRow foreign = templateService.saveForm(
                lecturer.getId(), Role.LEADER,
                richtextForm("Chương " + chapterNumber, "Bài của giảng viên"));
        User admin = userRepository.findByEmailIgnoreCase("admin@ksh.edu.vn").orElseThrow();
        LessonTemplate owned = templateRepository.saveAndFlush(new LessonTemplate(
                admin.getId(), lecturer.getSubjectId(), chapterNumber,
                "Chương 97 · Của quản trị viên", 997,
                "Bài 997 · Của quản trị viên", Lesson.CONTENT_TYPE_RICHTEXT));

        assertThatThrownBy(() -> templateService.renameChapter(admin.getId(), Role.ADMIN,
                lecturer.getSubjectId(), chapterNumber, "Chương riêng đã đổi tên"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        assertThat(templateRepository.findById(owned.getId()).orElseThrow().getChapterTitle())
                .isEqualTo("Chương 97 · Của quản trị viên");
        assertThat(templateRepository.findById(foreign.id()).orElseThrow().getChapterTitle())
                .isEqualTo("Chương 97 · Nội dung chương 97");

        assertThatThrownBy(() -> templateService.reorderChapters(admin.getId(), Role.ADMIN,
                lecturer.getSubjectId(), List.of(chapterNumber)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        assertThat(templateRepository.findById(owned.getId()).orElseThrow().getChapterOrder())
                .isEqualTo(chapterNumber);
        assertThat(templateRepository.findById(foreign.id()).orElseThrow().getChapterOrder())
                .isEqualTo(chapterNumber);
    }

    @Test
    void rename_keeps_distributed_snapshot_immutable_until_explicit_redistribution() {
        LessonTemplateRow template = templateService.saveForm(
                lecturer.getId(), Role.LEADER,
                richtextForm("Chương 96", "Tên phân phối ban đầu"));
        ClassEntity clazz = activeClass("Library rename provenance");
        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER).get(0).lessonId();

        templateService.renameLesson(lecturer.getId(), Role.LEADER,
                template.id(), "Tên canonical sau khi đổi");

        assertThat(lessonRepository.findById(lessonId).orElseThrow().getTitle())
                .endsWith("· Tên phân phối ban đầu");

        var redistributed = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER);

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
                lecturer.getId(), Role.LEADER,
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
                lecturer.getId(), Role.LEADER, create);

        LessonTemplate savedTemplate = templateRepository.findById(template.id()).orElseThrow();
        assertThat(savedTemplate.getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");
        assertThat(templateService.loadForm(lecturer.getId(), Role.LEADER,
                template.id(), lecturer.getSubjectId()).getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");
        assertThat(templateRepository.searchOwnedSubject(
                lecturer.getId(), lecturer.getSubjectId(), "phản xạ giao tiếp",
                PageRequest.of(0, 20)).getContent())
                .extracting(LessonTemplate::getId)
                .contains(template.id());
        assertThat(templateService.list(lecturer.getId(), Role.LEADER,
                lecturer.getSubjectId(), "phản xạ giao tiếp", 0, 20).page().getContent())
                .extracting(LessonTemplateRow::id)
                .contains(template.id());

        ClassEntity clazz = activeClass("Library video summary");
        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER).get(0).lessonId();
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");

        LessonTemplateForm edit = templateService.loadForm(lecturer.getId(), Role.LEADER,
                template.id(), lecturer.getSubjectId());
        edit.setVideoSummary("  Phiên bản cập nhật: luyện nghe và trả lời trong 45 giây.  ");
        templateService.saveForm(lecturer.getId(), Role.LEADER, edit);

        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Hội thoại chào hỏi và phản xạ giao tiếp trong lớp học.");
        templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER);
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Phiên bản cập nhật: luyện nghe và trả lời trong 45 giây.");

        LessonTemplateForm clear = templateService.loadForm(lecturer.getId(), Role.LEADER,
                template.id(), lecturer.getSubjectId());
        clear.setVideoUrl("   ");
        clear.setVideoSummary("Tóm tắt mồ côi không được phép lưu");
        templateService.saveForm(lecturer.getId(), Role.LEADER, clear);

        assertThat(templateRepository.findById(template.id()).orElseThrow().getVideoSummary())
                .isNull();
        assertThat(lessonRepository.findById(lessonId).orElseThrow().getVideoSummary())
                .isEqualTo("Phiên bản cập nhật: luyện nghe và trả lời trong 45 giây.");
        templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER);
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
                lecturer.getId(), Role.LEADER, create);
        LessonTemplate saved = templateRepository.findById(template.id()).orElseThrow();

        assertThat(saved.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(saved.getContentRichtext()).contains("Nội dung");
        assertThat(saved.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(saved.getVideoLibraryAssetId()).isEqualTo(video.getId());
        assertThat(saved.getVideoUrl()).isEqualTo(video.getStoredPath());

        LessonTemplateForm edit = templateService.loadForm(
                lecturer.getId(), Role.LEADER, template.id(), lecturer.getSubjectId());
        assertThat(edit.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(edit.getVideoLibraryAssetId()).isEqualTo(video.getId());
        assertThat(edit.getVideoUrl()).isEmpty();
        assertThat(edit.getMaterialAssetIds()).doesNotContain(video.getId());

        ClassEntity clazz = activeClass("Library richtext uploaded video");
        Long lessonId = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LEADER).get(0).lessonId();
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
                lecturer.getId(), Role.LEADER,
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

        LessonTemplateForm edit = templateService.loadForm(lecturer.getId(), Role.LEADER,
                template.id(), lecturer.getSubjectId());
        edit.setContentRichtext("<p>Nội dung canonical đã cập nhật</p>");
        templateService.saveForm(lecturer.getId(), Role.LEADER, edit);

        Lesson unchanged = lessonRepository.findById(directLessonId).orElseThrow();
        assertThat(unchanged.getSourceLessonTemplateId()).isNull();
        assertThat(unchanged.getContentRichtext()).isEqualTo("<p>Nội dung do lớp tự soạn</p>");
    }

    @Test
    void library_is_a_subject_wide_canonical_hierarchy_not_an_owner_only_list() {
        LessonTemplateRow created = templateService.saveForm(
                lecturer.getId(), Role.LEADER, richtextForm("Chương 92", "Bài dùng chung"));
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
                lecturer.getId(), Role.LEADER, richtextForm("Chương 90", "Một"));
        LessonTemplateRow chapterOneSecond = templateService.saveForm(
                lecturer.getId(), Role.LEADER, richtextForm("Chương 90", "Hai"));
        LessonTemplateRow chapterTwoFirst = templateService.saveForm(
                lecturer.getId(), Role.LEADER, richtextForm("Chương 91", "Ba"));
        int beforeInsert = templateRepository.findById(chapterTwoFirst.id()).orElseThrow()
                .getDisplayOrder();

        LessonTemplateRow inserted = templateService.saveForm(
                lecturer.getId(), Role.LEADER, richtextForm("Chương 90", "Chèn sau bài 2"));

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

    @Test
    void saveForm_create_update_sanitises_markup_and_preserves_metadata() {
        LessonTemplateForm create = richtextForm("Chương 87", "Bài metadata");
        create.setContentRichtext("<p>Được giữ</p><script>alert(1)</script>"
                + "<img src=\"javascript:alert(2)\" onclick=\"x()\">");

        LessonTemplateRow row = templateService.saveForm(
                lecturer.getId(), Role.LEADER, create);
        LessonTemplate before = templateRepository.findById(row.id()).orElseThrow();
        Long ownerId = before.getOwnerId();
        Long subjectId = before.getSubjectId();
        int chapter = before.getChapterOrder();
        int displayOrder = before.getDisplayOrder();
        LocalDateTime createdAt = before.getCreatedAt();

        assertThat(before.getContentRichtext()).contains("<p>Được giữ</p>")
                .doesNotContain("<script>", "onclick", "javascript:");

        LessonTemplateForm edit = templateService.loadForm(
                lecturer.getId(), Role.LEADER, row.id(), subjectId);
        edit.setContentRichtext("<p>Cập nhật an toàn</p><script>bad()</script>");
        templateService.saveForm(lecturer.getId(), Role.LEADER, edit);

        LessonTemplate after = templateRepository.findById(row.id()).orElseThrow();
        assertThat(after.getId()).isEqualTo(row.id());
        assertThat(after.getOwnerId()).isEqualTo(ownerId);
        assertThat(after.getSubjectId()).isEqualTo(subjectId);
        assertThat(after.getChapterOrder()).isEqualTo(chapter);
        assertThat(after.getDisplayOrder()).isEqualTo(displayOrder);
        assertThat(after.getCreatedAt()).isEqualTo(createdAt);
        assertThat(after.getContentRichtext()).isEqualTo("<p>Cập nhật an toàn</p>");
    }

    @Test
    void saveForm_accepts_media_url_and_rejects_unsafe_video_url() {
        LessonTemplateForm create = richtextForm("Chương 86", "Bài media URL");
        create.setVideoUrl("  https://www.youtube.com/watch?v=media86  ");
        create.setVideoSummary("  Tóm tắt media  ");

        LessonTemplateRow row = templateService.saveForm(
                lecturer.getId(), Role.LEADER, create);
        LessonTemplate saved = templateRepository.findById(row.id()).orElseThrow();
        assertThat(saved.getVideoProvider()).isEqualTo("YOUTUBE");
        assertThat(saved.getVideoUrl()).isEqualTo("https://www.youtube.com/watch?v=media86");
        assertThat(saved.getVideoSummary()).isEqualTo("Tóm tắt media");

        LessonTemplateForm unsafe = templateService.loadForm(
                lecturer.getId(), Role.LEADER, row.id(), lecturer.getSubjectId());
        unsafe.setVideoUrl("https://evil.example/video");
        assertThatThrownBy(() -> templateService.saveForm(
                lecturer.getId(), Role.LEADER, unsafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YouTube hoặc Vimeo");
    }

    @Test
    void saveForm_rejects_foreign_missing_and_mixed_asset_ids_before_create() {
        User foreignOwner = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn")
                .orElseThrow();
        LibraryAsset own = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Own guide", "own.pdf", "library/own.pdf",
                "application/pdf", 10L, LibraryAsset.KIND_DOCUMENT));
        LibraryAsset foreign = assetRepository.saveAndFlush(new LibraryAsset(
                foreignOwner.getId(), "Foreign guide", "foreign.pdf", "library/foreign.pdf",
                "application/pdf", 11L, LibraryAsset.KIND_DOCUMENT));
        long before = templateRepository.count();

        LessonTemplateForm foreignForm = richtextForm("Chương 85", "Bài foreign");
        foreignForm.setMaterialAssetIds(List.of(foreign.getId()));
        assertThatThrownBy(() -> templateService.saveForm(
                lecturer.getId(), Role.LEADER, foreignForm))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(templateRepository.count()).isEqualTo(before);

        LessonTemplateForm missingForm = richtextForm("Chương 84", "Bài missing");
        missingForm.setMaterialAssetIds(List.of(999_999_999L));
        assertThatThrownBy(() -> templateService.saveForm(
                lecturer.getId(), Role.LEADER, missingForm))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(templateRepository.count()).isEqualTo(before);

        LessonTemplateForm mixedForm = richtextForm("Chương 83", "Bài mixed");
        mixedForm.setMaterialAssetIds(List.of(own.getId(), foreign.getId()));
        assertThatThrownBy(() -> templateService.saveForm(
                lecturer.getId(), Role.LEADER, mixedForm))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(templateRepository.count()).isEqualTo(before);
    }

    @Test
    void saveForm_update_preserves_library_video_and_material_metadata() {
        LibraryAsset video = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Video metadata", "metadata.mp4",
                "library/metadata.mp4", "video/mp4", 20L, LibraryAsset.KIND_VIDEO));
        LibraryAsset material = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Material metadata", "metadata.pdf",
                "library/metadata.pdf", "application/pdf", 21L, LibraryAsset.KIND_DOCUMENT));
        LessonTemplateForm create = richtextForm("Chương 82", "Bài có tài nguyên");
        create.setVideoLibraryAssetId(video.getId());
        create.setVideoSummary("Tóm tắt ban đầu");
        create.setMaterialAssetIds(List.of(material.getId()));

        LessonTemplateRow row = templateService.saveForm(
                lecturer.getId(), Role.LEADER, create);
        LessonTemplate before = templateRepository.findById(row.id()).orElseThrow();
        LocalDateTime createdAt = before.getCreatedAt();

        LessonTemplateForm edit = templateService.loadForm(
                lecturer.getId(), Role.LEADER, row.id(), lecturer.getSubjectId());
        edit.setContentRichtext("<p>Nội dung mới</p>");
        templateService.saveForm(lecturer.getId(), Role.LEADER, edit);

        LessonTemplate after = templateRepository.findById(row.id()).orElseThrow();
        assertThat(after.getCreatedAt()).isEqualTo(createdAt);
        assertThat(after.getOwnerId()).isEqualTo(lecturer.getId());
        assertThat(after.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(after.getVideoLibraryAssetId()).isEqualTo(video.getId());
        assertThat(after.getVideoUrl()).isEqualTo(video.getStoredPath());
        assertThat(after.getVideoSummary()).isEqualTo("Tóm tắt ban đầu");
        assertThat(templateAttachmentRepository
                .findByTemplateIdOrderByDisplayOrderAsc(row.id()))
                .singleElement()
                .satisfies(attachment -> {
                    assertThat(attachment.getLibraryAssetId()).isEqualTo(material.getId());
                    assertThat(attachment.getOriginalFilename()).isEqualTo("metadata.pdf");
                    assertThat(attachment.getDisplayOrder()).isZero();
                });
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
                    lecturer.getId(), Role.LEADER);
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

        return classRepository.saveAndFlush(clazz);
    }
}
