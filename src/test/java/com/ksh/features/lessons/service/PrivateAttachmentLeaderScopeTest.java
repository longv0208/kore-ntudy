package com.ksh.features.lessons.service;

import com.ksh.entities.Lesson;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.Section;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.library.service.LibraryService;
import com.ksh.features.storage.ObjectStorage;
import com.ksh.features.upload.LessonAttachmentStorageService;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PrivateAttachmentLeaderScopeTest {

    @Test
    void foreignSubjectLeaderIsDeniedBeforePrivateObjectIsOpened() {
        LessonAttachmentRepository attachments = mock(LessonAttachmentRepository.class);
        LessonRepository lessons = mock(LessonRepository.class);
        SectionRepository sections = mock(SectionRepository.class);
        ObjectStorage objects = mock(ObjectStorage.class);
        ClassesService classes = mock(ClassesService.class);
        LessonAttachment attachment = mock(LessonAttachment.class);
        Lesson lesson = mock(Lesson.class);
        Section section = mock(Section.class);
        when(attachments.findByIdAndLessonId(3L, 2L)).thenReturn(Optional.of(attachment));
        when(lessons.findById(2L)).thenReturn(Optional.of(lesson));
        when(lesson.getSectionId()).thenReturn(4L);
        when(sections.findById(4L)).thenReturn(Optional.of(section));
        when(section.getClassId()).thenReturn(5L);
        doThrow(new AccessDeniedException("foreign subject"))
                .when(classes).getEditable(5L, 7L, Role.LEADER);

        LessonAttachmentsService service = new LessonAttachmentsService(
                attachments, lessons, sections,
                mock(LessonAttachmentStorageService.class), objects,
                mock(LibraryService.class), classes,
                mock(LessonsReorderService.class), mock(EnrollmentRepository.class),
                mock(ClassRepository.class),
                mock(LessonActivityWriter.class), mock(LessonContentTypeSwitcher.class));

        assertThatThrownBy(() -> service.download(2L, 3L, 7L, Role.LEADER))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(objects);
    }
}
