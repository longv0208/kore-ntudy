package com.ksh.features.library.service;

import com.ksh.entities.Department;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.LessonTemplateAttachment;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.lessons.service.LessonActivityWriter;
import com.ksh.features.lessons.service.LessonsReorderService;
import com.ksh.features.lessons.service.SectionsService;
import com.ksh.features.library.repository.LessonTemplateAttachmentRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static com.ksh.common.IConstant.VIDEO_PROVIDER_UPLOAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Edit-form mapping for a canonical owner-private storage-backed video. */
@ExtendWith(MockitoExtension.class)
class LessonTemplateStorageVideoEditTest {

    @Mock private LessonTemplateRepository templateRepository;
    @Mock private LessonTemplateAttachmentRepository templateAttachmentRepository;
    @Mock private LibraryAssetRepository assetRepository;
    @Mock private LibraryService libraryService;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonAttachmentRepository attachmentRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private ClassRepository classRepository;
    @Mock private LessonsReorderService reorderService;
    @Mock private SectionsService sectionsService;
    @Mock private ClassesService classesService;
    @Mock private LessonActivityWriter activityWriter;
    @Mock private LibrarySubjectResolver subjectResolver;
    @Mock private UserRepository userRepository;

    @InjectMocks private LessonTemplateService service;

    @Test
    void loadForm_preserves_uploaded_video_as_primary_without_generic_attachment_duplication() {
        LessonTemplate template = new LessonTemplate(
                7L, 55L, 2, "Chương 2 · Hội thoại", 3,
                "Bài 3 · Video giao tiếp", Lesson.CONTENT_TYPE_VIDEO);
        ReflectionTestUtils.setField(template, "id", 99L);
        template.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
        template.setVideoLibraryAssetId(42L);
        template.setVideoUrl("library/7/private-video.mp4");
        template.setVideoSummary("Luyện nghe và trả lời trong 45 giây.");
        LessonTemplateAttachment supplementary = new LessonTemplateAttachment(
                99L, 88L, "worksheet.pdf", "application/pdf", 123L, 0);

        when(templateRepository.findById(99L))
                .thenReturn(Optional.of(template));
        when(subjectResolver.require(7L, Role.LEADER, 55L))
                .thenReturn(mock(Department.class));
        when(templateAttachmentRepository.findByTemplateIdOrderByDisplayOrderAsc(99L))
                .thenReturn(List.of(supplementary));

        var form = service.loadForm(7L, Role.LEADER, 99L, 55L);

        assertThat(form.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_VIDEO);
        assertThat(form.getVideoProvider()).isEqualTo(VIDEO_PROVIDER_UPLOAD);
        assertThat(form.getVideoLibraryAssetId()).isEqualTo(42L);
        assertThat(form.getVideoUrl())
                .as("an internal object key must not be posted through the external URL field")
                .isEmpty();
        assertThat(form.getVideoSummary()).isEqualTo("Luyện nghe và trả lời trong 45 giây.");
        assertThat(form.getMaterialAssetIds())
                .as("only supplementary assets belong in materialAssetIds")
                .containsExactly(88L)
                .doesNotContain(42L);
    }
}
