package com.ksh.features.library.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.LibraryAsset;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.service.LessonAttachmentsService;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit redistribution replaces canonical material and preserves explicit class share. */
@SpringBootTest
@Transactional
class LessonTemplatePrivateShareRefreshTest {

    @Autowired private LessonTemplateService templateService;
    @Autowired private LessonAttachmentsService attachmentsService;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private LessonAttachmentRepository attachmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;

    private User lecturer;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
    }

    @Test
    void explicit_redistribution_keeps_class_private_and_replaces_canonical_rows() {
        LibraryAsset canonicalAsset = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Tài liệu chuẩn", "canonical.pdf",
                "library/" + lecturer.getId() + "/canonical.pdf",
                "application/pdf", 10L, LibraryAsset.KIND_DOCUMENT));
        LibraryAsset privateAsset = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Tài liệu riêng", "private.pdf",
                "library/" + lecturer.getId() + "/private.pdf",
                "application/pdf", 20L, LibraryAsset.KIND_DOCUMENT));

        LessonTemplateForm form = new LessonTemplateForm();
        form.setChapterNumber(98);
        form.setChapterTitle("Nguồn gốc tài liệu");
        form.setTitle("Bảo toàn tài liệu riêng");
        form.setContentType(Lesson.CONTENT_TYPE_RICHTEXT);
        form.setContentRichtext("<p>Phiên bản 1</p>");
        form.setMaterialAssetIds(List.of(canonicalAsset.getId()));
        var template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, form);

        ClassEntity clazz = new ClassEntity(
                "Lớp provenance tài liệu", lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        clazz.setSubjectId(lecturer.getSubjectId());
        clazz.approve(lecturer.getId(), LocalDateTime.now());
        clazz = classRepository.saveAndFlush(clazz);

        var clone = templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER).get(0);
        Long lessonId = clone.lessonId();
        Long sectionId = clone.sectionId();
        var privateRow = attachmentsService.bindAttachmentFromLibrary(
                clazz.getId(), sectionId, lessonId, privateAsset.getId(),
                lecturer.getId(), Role.LECTURER);

        List<LessonAttachment> before = attachmentRepository
                .findByLessonIdOrderByUploadedAtAsc(lessonId);
        Long oldCanonicalId = before.stream()
                .filter(LessonAttachment::isCanonicalTemplate)
                .map(LessonAttachment::getId)
                .findFirst().orElseThrow();

        LessonTemplateForm edit = templateService.loadForm(
                lecturer.getId(), Role.LECTURER, template.id(), lecturer.getSubjectId());
        edit.setContentRichtext("<p>Phiên bản 2</p>");
        templateService.saveForm(lecturer.getId(), Role.LECTURER, edit);

        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId))
                .extracting(LessonAttachment::getId)
                .contains(oldCanonicalId, privateRow.id());

        templateService.distribute(template.id(), List.of(clazz.getId()),
                lecturer.getId(), Role.LECTURER);

        List<LessonAttachment> after = attachmentRepository
                .findByLessonIdOrderByUploadedAtAsc(lessonId);
        assertThat(after).hasSize(2);
        assertThat(after).filteredOn(LessonAttachment::isClassPrivate)
                .extracting(LessonAttachment::getId)
                .containsExactly(privateRow.id());
        assertThat(after).filteredOn(LessonAttachment::isCanonicalTemplate)
                .extracting(LessonAttachment::getLibraryAssetId)
                .containsExactly(canonicalAsset.getId());
        assertThat(after).extracting(LessonAttachment::getId)
                .doesNotContain(oldCanonicalId);
    }
}
