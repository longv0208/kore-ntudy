package com.ksh.features.library.service;

import com.ksh.entities.LibraryAsset;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.features.library.repository.LibraryAssetRepository.AssetUsageProjection;
import com.ksh.features.upload.LibraryStorageService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Owner-isolation and reference guards with mocked storage (never real R2). */
@ExtendWith(MockitoExtension.class)
class LibraryPersonalAssetsServiceTest {

    @Mock private LibraryAssetRepository repository;
    @Mock private LibraryStorageService storage;
    @Mock private LibraryAsset asset;
    @Mock private AssetUsageProjection usage;

    private LibraryService service;

    @BeforeEach
    void setUp() {
        service = new LibraryService(repository, storage);
    }

    @Test
    void picker_query_is_always_scoped_to_authenticated_owner() {
        when(asset.getId()).thenReturn(11L);
        when(asset.getTitle()).thenReturn("Bài giảng riêng");
        when(asset.getOriginalFilename()).thenReturn("private.pdf");
        when(asset.getKind()).thenReturn(LibraryAsset.KIND_DOCUMENT);
        when(asset.getMimeType()).thenReturn("application/pdf");
        when(asset.getSizeBytes()).thenReturn(100L);
        when(repository.searchOwned(eq(7L), eq("riêng"),
                eq(LibraryAsset.KIND_DOCUMENT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset)));

        var page = service.listForPicker(7L, " riêng ", "document", 0, 12);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(11L);
            assertThat(item.originalFilename()).isEqualTo("private.pdf");
        });
        verify(repository, never()).findAll();
    }

    @Test
    void invalid_kind_filter_does_not_broaden_to_all_assets() {
        when(repository.searchOwned(eq(7L), eq(null), eq("__INVALID_KIND__"),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        var page = service.listForPicker(7L, "", "GLOBAL", 0, 12);

        assertThat(page.items()).isEmpty();
        verify(repository).searchOwned(eq(7L), eq(null), eq("__INVALID_KIND__"),
                any(Pageable.class));
    }

    @Test
    void recent_strip_uses_the_latest_owner_assets_not_the_filtered_inventory_page() {
        when(asset.getId()).thenReturn(11L);
        when(repository.findReferencedAssetIdsByOwnerId(7L)).thenReturn(List.of(11L));
        when(repository.searchOwnedByIds(eq(7L), eq(List.of(11L)), eq("needle"),
                eq(LibraryAsset.KIND_DOCUMENT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset)));
        when(repository.findTop5ByOwnerIdOrderByUpdatedAtDescIdDesc(7L))
                .thenReturn(List.of(asset));

        var page = service.list(7L, "needle", "DOCUMENT", "USED", 2, 12);

        assertThat(page.page().getContent()).extracting(row -> row.id()).containsExactly(11L);
        assertThat(page.recentlyUpdated()).extracting(row -> row.id()).containsExactly(11L);
        verify(repository).findTop5ByOwnerIdOrderByUpdatedAtDescIdDesc(7L);
    }

    @Test
    void cross_owner_lookup_fails_closed_as_not_found() {
        when(repository.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedAsset(7L, 99L))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository).findByIdAndOwnerId(99L, 7L);
        verify(repository, never()).findById(99L);
    }

    @Test
    void rename_and_delete_share_the_same_owner_scoped_write_lock() {
        when(repository.findByIdAndOwnerIdForUpdate(11L, 7L))
                .thenReturn(Optional.of(asset));
        when(repository.save(asset)).thenReturn(asset);
        when(repository.countLiveReferences(11L)).thenReturn(1L);

        service.rename(7L, 11L, "  Tài liệu đã đổi tên  ");
        assertThatThrownBy(() -> service.delete(7L, 11L))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, times(2)).findByIdAndOwnerIdForUpdate(11L, 7L);
        verify(repository, never()).findByIdAndOwnerId(11L, 7L);
        verify(asset).rename("Tài liệu đã đổi tên");
    }

    @Test
    void delete_is_blocked_by_canonical_lesson_template_reference() {
        when(repository.findByIdAndOwnerIdForUpdate(11L, 7L))
                .thenReturn(Optional.of(asset));
        when(repository.countLiveReferences(11L)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(7L, 11L))
                .isInstanceOf(IllegalStateException.class);

        verify(asset, never()).markDeleted();
        verify(storage, never()).delete(any());
    }

    @Test
    void unreferenced_delete_soft_deletes_row_and_only_its_owned_key() {
        when(repository.findByIdAndOwnerIdForUpdate(11L, 7L))
                .thenReturn(Optional.of(asset));
        when(asset.getOwnerId()).thenReturn(7L);
        when(asset.getStoredPath()).thenReturn("library/7/private.pdf");
        when(storage.requireOwnedKey(7L, "library/7/private.pdf"))
                .thenReturn("library/7/private.pdf");

        service.delete(7L, 11L);

        verify(asset).markDeleted();
        verify(repository).save(asset);
        verify(storage).delete("library/7/private.pdf");
    }

    @Test
    void content_handle_rejects_tampered_cross_prefix_key() {
        when(repository.findByIdAndOwnerId(11L, 7L)).thenReturn(Optional.of(asset));
        when(asset.getOwnerId()).thenReturn(7L);
        when(asset.getStoredPath()).thenReturn("library/8/private.pdf");
        when(storage.requireOwnedKey(7L, "library/8/private.pdf"))
                .thenThrow(new IllegalArgumentException("invalid"));

        assertThatThrownBy(() -> service.contentHandle(7L, 11L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void detail_returns_exact_lesson_location_and_real_placement() {
        when(repository.findByIdAndOwnerId(11L, 7L)).thenReturn(Optional.of(asset));
        when(asset.getId()).thenReturn(11L);
        when(asset.getTitle()).thenReturn("Đề cương");
        when(asset.getOriginalFilename()).thenReturn("outline.pdf");
        when(asset.getKind()).thenReturn(LibraryAsset.KIND_DOCUMENT);
        when(asset.getMimeType()).thenReturn("application/pdf");
        when(asset.getSizeBytes()).thenReturn(2048L);
        when(usage.getUsageType()).thenReturn("LESSON_ATTACHMENT");
        when(usage.getClassId()).thenReturn(31L);
        when(usage.getClassName()).thenReturn("KOR111 - LinhND");
        when(usage.getSectionId()).thenReturn(41L);
        when(usage.getSectionTitle()).thenReturn("Chương 3");
        when(usage.getLessonId()).thenReturn(51L);
        when(usage.getLessonTitle()).thenReturn("Dao động điều hòa - Bài 4");
        when(usage.getPlacement()).thenReturn("Tài liệu đính kèm");
        when(repository.findUsagesByOwnerIdAndAssetId(7L, 11L))
                .thenReturn(List.of(usage));

        var detail = service.detail(7L, 11L);

        assertThat(detail.previewUrl()).isEqualTo(
                "/lecturer/library/assets/11/preview");
        assertThat(detail.inUse()).isTrue();
        assertThat(detail.usages()).singleElement().satisfies(location -> {
            assertThat(location.title()).isEqualTo("Dao động điều hòa - Bài 4");
            assertThat(location.subtitle()).isEqualTo("KOR111 - LinhND · Chương 3");
            assertThat(location.placement()).isEqualTo("Tài liệu đính kèm");
            assertThat(location.url()).isEqualTo(
                    "/lecturer/classes/31/lessons?section=41&lesson=51");
        });
        verify(repository).findUsagesByOwnerIdAndAssetId(7L, 11L);
    }

    @Test
    void detail_never_queries_usage_locations_for_a_cross_owner_id() {
        when(repository.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(7L, 99L))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository, never()).findUsagesByOwnerIdAndAssetId(7L, 99L);
    }
}
