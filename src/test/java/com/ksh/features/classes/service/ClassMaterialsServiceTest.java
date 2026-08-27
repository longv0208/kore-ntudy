package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.LibraryAsset;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.features.library.service.LibraryService;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassMaterialsServiceTest {

    @Mock private ClassesService classesService;
    @Mock private ClassRepository classRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private LessonAttachmentRepository attachmentRepository;
    @Mock private LibraryAssetRepository assetRepository;
    @Mock private LibraryService libraryService;
    @Mock private ClassEntity clazz;
    @Mock private LibraryAsset asset;

    private ClassMaterialsService service;

    @BeforeEach
    void setUp() {
        service = new ClassMaterialsService(classesService, classRepository,
                enrollmentRepository, attachmentRepository, assetRepository, libraryService);
    }

    @Test
    void share_creates_direct_class_reference_without_a_lesson_or_blob_copy() {
        when(classesService.getOwnerManaged(5L, 7L, Role.LECTURER)).thenReturn(clazz);
        when(clazz.getStatus()).thenReturn(ClassEntity.STATUS_ACTIVE);
        when(libraryService.getOwnedAssetForUpdate(7L, 11L)).thenReturn(asset);
        when(asset.getKind()).thenReturn(LibraryAsset.KIND_DOCUMENT);
        when(asset.getOriginalFilename()).thenReturn("tai-lieu.pdf");
        when(asset.getTitle()).thenReturn("Tài liệu ôn tập");
        when(asset.getMimeType()).thenReturn("application/pdf");
        when(asset.getSizeBytes()).thenReturn(2048L);
        when(libraryService.requireOwnedStorageKey(7L, asset))
                .thenReturn("library/7/tai-lieu.pdf");
        when(attachmentRepository.save(any(LessonAttachment.class))).thenAnswer(invocation -> {
            LessonAttachment saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 19L);
            return saved;
        });

        var row = service.shareFromLibrary(5L, 11L, 7L, Role.LECTURER);

        ArgumentCaptor<LessonAttachment> captor = ArgumentCaptor.forClass(LessonAttachment.class);
        verify(attachmentRepository).save(captor.capture());
        assertThat(captor.getValue().getClassId()).isEqualTo(5L);
        assertThat(captor.getValue().getLessonId()).isNull();
        assertThat(captor.getValue().getLibraryAssetId()).isEqualTo(11L);
        assertThat(row.downloadUrl()).isEqualTo("/api/classes/5/materials/19/download");
    }

    @Test
    void duplicate_class_share_is_rejected() {
        when(classesService.getOwnerManaged(5L, 7L, Role.LECTURER)).thenReturn(clazz);
        when(clazz.getStatus()).thenReturn(ClassEntity.STATUS_ACTIVE);
        when(libraryService.getOwnedAssetForUpdate(7L, 11L)).thenReturn(asset);
        when(asset.getKind()).thenReturn(LibraryAsset.KIND_DOCUMENT);
        when(libraryService.requireOwnedStorageKey(7L, asset)).thenReturn("library/7/a.pdf");
        when(attachmentRepository.existsByClassIdAndLibraryAssetId(5L, 11L)).thenReturn(true);

        assertThatThrownBy(() -> service.shareFromLibrary(5L, 11L, 7L, Role.LECTURER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đã có");
    }

    @Test
    void active_student_can_download_only_material_scoped_to_their_class() {
        Enrollment enrollment = org.mockito.Mockito.mock(Enrollment.class);
        LessonAttachment attachment = LessonAttachment.forClassMaterial(
                5L, "a.pdf", "library/7/a.pdf", "application/pdf", 123L, 7L, 11L);
        ReflectionTestUtils.setField(attachment, "id", 19L);
        when(classRepository.findById(5L)).thenReturn(Optional.of(clazz));
        when(clazz.getStatus()).thenReturn(ClassEntity.STATUS_ACTIVE);
        when(enrollmentRepository.findByUserIdAndClassId(9L, 5L))
                .thenReturn(Optional.of(enrollment));
        when(enrollment.getStatus()).thenReturn(Enrollment.STATUS_ACTIVE);
        when(attachmentRepository.findByIdAndClassId(19L, 5L))
                .thenReturn(Optional.of(attachment));
        when(libraryService.requireReferencedStorageKey(11L, "library/7/a.pdf"))
                .thenReturn("library/7/a.pdf");

        var handle = service.download(5L, 19L, 9L, Role.STUDENT);

        assertThat(handle.storageKey()).isEqualTo("library/7/a.pdf");
    }
}
