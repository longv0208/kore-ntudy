package com.ksh.features.library.repository;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.LibraryAsset;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the native live-reference SQL against real soft-deleted parents. */
@SpringBootTest
@Transactional
class LibraryAssetLiveReferenceIntegrationTest {

    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonAttachmentRepository attachmentRepository;
    @Autowired private LessonTemplateRepository templateRepository;

    @Test
    void deletedParentsNoLongerKeepAnOwnedAssetMarkedAsInUse() {
        User owner = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        assertThat(owner.getSubjectId()).isNotNull();

        LibraryAsset classAsset = asset(owner, "class");
        LibraryAsset lessonAsset = asset(owner, "lesson");
        LibraryAsset templateAsset = asset(owner, "template");

        ClassEntity directClass = activeClass(owner, "direct");
        attachmentRepository.saveAndFlush(LessonAttachment.forClassMaterial(
                directClass.getId(), "class.pdf", "library/" + owner.getId() + "/class.pdf",
                "application/pdf", 8L, owner.getId(), classAsset.getId()));

        ClassEntity lessonClass = activeClass(owner, "lesson");
        Section section = sectionRepository.saveAndFlush(new Section(
                lessonClass.getId(), "Chương kiểm thử", (short) 0, owner.getId()));
        Lesson lesson = lessonRepository.saveAndFlush(new Lesson(
                section.getId(), "Bài kiểm thử", (short) 0, owner.getId()));
        attachmentRepository.saveAndFlush(new LessonAttachment(
                lesson.getId(), "lesson.pdf", "library/" + owner.getId() + "/lesson.pdf",
                "application/pdf", 8L, owner.getId(), lessonAsset.getId()));

        LessonTemplate template = new LessonTemplate(owner.getId(), owner.getSubjectId(),
                "Chương kiểm thử", "Bài mẫu kiểm thử", Lesson.CONTENT_TYPE_PDF);
        template.setPdfLibraryAssetId(templateAsset.getId());
        template = templateRepository.saveAndFlush(template);

        assertThat(assetRepository.findReferencedAssetIdsByOwnerId(owner.getId()))
                .contains(classAsset.getId(), lessonAsset.getId(), templateAsset.getId());
        assertThat(assetRepository.countLiveReferences(classAsset.getId())).isEqualTo(1L);
        assertThat(assetRepository.countLiveReferences(lessonAsset.getId())).isEqualTo(1L);
        assertThat(assetRepository.countLiveReferences(templateAsset.getId())).isEqualTo(1L);

        directClass.softDelete();
        classRepository.saveAndFlush(directClass);
        lesson.markDeleted();
        lessonRepository.saveAndFlush(lesson);
        template.markDeleted();
        templateRepository.saveAndFlush(template);

        assertThat(assetRepository.findReferencedAssetIdsByOwnerId(owner.getId()))
                .doesNotContain(classAsset.getId(), lessonAsset.getId(), templateAsset.getId());
        assertThat(assetRepository.countLiveReferences(classAsset.getId())).isZero();
        assertThat(assetRepository.countLiveReferences(lessonAsset.getId())).isZero();
        assertThat(assetRepository.countLiveReferences(templateAsset.getId())).isZero();
        assertThat(assetRepository.findUsagesByOwnerIdAndAssetId(owner.getId(), classAsset.getId()))
                .isEmpty();
        assertThat(assetRepository.findUsagesByOwnerIdAndAssetId(owner.getId(), lessonAsset.getId()))
                .isEmpty();
        assertThat(assetRepository.findUsagesByOwnerIdAndAssetId(owner.getId(), templateAsset.getId()))
                .isEmpty();
    }

    private LibraryAsset asset(User owner, String suffix) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        return assetRepository.saveAndFlush(new LibraryAsset(
                owner.getId(), "Asset " + suffix + " " + token, suffix + ".pdf",
                "library/" + owner.getId() + "/" + token + ".pdf",
                "application/pdf", 8L, LibraryAsset.KIND_DOCUMENT));
    }

    private ClassEntity activeClass(User owner, String suffix) {
        ClassEntity clazz = new ClassEntity("Lớp " + suffix + " " + UUID.randomUUID(),
                owner.getId(), owner.getId(), null, null, null, 20);
        clazz.setSubjectId(owner.getSubjectId());
        clazz.approve(owner.getId(), LocalDateTime.now());
        return classRepository.saveAndFlush(clazz);
    }
}
