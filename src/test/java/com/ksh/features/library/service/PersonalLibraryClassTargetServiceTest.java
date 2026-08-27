package com.ksh.features.library.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.LibraryAsset;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalLibraryClassTargetServiceTest {

    @Mock private LibraryService libraryService;
    @Mock private ClassRepository classRepository;
    @Mock private LibraryAsset asset;
    @Mock private ClassEntity clazz;

    @Test
    void lecturer_targets_only_owned_active_classes_and_never_loads_lessons() {
        PersonalLibraryClassTargetService service = new PersonalLibraryClassTargetService(
                libraryService, classRepository);
        when(libraryService.getOwnedAsset(7L, 11L)).thenReturn(asset);
        when(asset.getId()).thenReturn(11L);
        when(asset.getKind()).thenReturn(LibraryAsset.KIND_DOCUMENT);
        when(libraryService.requireOwnedStorageKey(7L, asset))
                .thenReturn("library/7/private.pdf");
        when(classRepository.findAllByLecturerIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(clazz));
        when(clazz.getId()).thenReturn(1L);
        when(clazz.getName()).thenReturn("Lớp riêng");
        when(clazz.getStatus()).thenReturn(ClassEntity.STATUS_ACTIVE);
        var targets = service.targets(7L, Role.LECTURER, 11L);

        assertThat(targets.assetId()).isEqualTo(11L);
        assertThat(targets.classes()).singleElement().satisfies(classTarget -> {
            assertThat(classTarget.id()).isEqualTo(1L);
            assertThat(classTarget.sections()).isEmpty();
        });
        verify(classRepository, never()).findAllByOrderByCreatedAtDesc();
        verify(classRepository).findAllByLecturerIdOrderByCreatedAtDesc(7L);
        verify(classRepository, never()).save(clazz);
    }

    @Test
    void rejected_and_archived_classes_are_not_share_targets() {
        PersonalLibraryClassTargetService service = new PersonalLibraryClassTargetService(
                libraryService, classRepository);
        when(libraryService.getOwnedAsset(7L, 11L)).thenReturn(asset);
        when(asset.getId()).thenReturn(11L);
        when(asset.getKind()).thenReturn(LibraryAsset.KIND_DOCUMENT);
        when(libraryService.requireOwnedStorageKey(7L, asset))
                .thenReturn("library/7/private.pdf");

        when(classRepository.findAllByLecturerIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(clazz));
        when(clazz.getStatus()).thenReturn(ClassEntity.STATUS_REJECTED);

        var targets = service.targets(7L, Role.LECTURER, 11L);

        assertThat(targets.classes()).isEmpty();
    }
}
