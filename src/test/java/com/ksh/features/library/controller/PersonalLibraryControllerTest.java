package com.ksh.features.library.controller;

import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPickerPage;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetDetail;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPreview;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ksh.features.library.service.LibraryService;
import com.ksh.features.library.service.PersonalLibraryAssetPreviewService;
import com.ksh.features.storage.ObjectStorage;
import com.ksh.security.KshUserDetails;
import com.ksh.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Controller contract with mocked services/object storage; never calls R2. */
@ExtendWith(MockitoExtension.class)
class PersonalLibraryControllerTest {

    @Mock private LibraryService libraryService;
    @Mock private ObjectStorage objectStorage;
    @Mock private PersonalLibraryAssetPreviewService previewService;
    @Mock private KshUserDetails user;

    private PersonalLibraryController controller;

    @BeforeEach
    void setUp() {
        controller = new PersonalLibraryController(
                libraryService, objectStorage, previewService);
    }

    @Test
    void route_is_lecturer_only_and_separate_from_shared_library_root() {
        RequestMapping mapping = PersonalLibraryController.class
                .getAnnotation(RequestMapping.class);
        PreAuthorize authorization = PersonalLibraryController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(mapping.value()).containsExactly("/lecturer/library/assets");
        assertThat(authorization.value()).isEqualTo(Roles.PREAUTH_LECTURER_OR_ABOVE);
    }

    @Test
    void picker_derives_owner_from_principal() {
        when(user.getId()).thenReturn(7L);
        LibraryAssetPickerPage expected = new LibraryAssetPickerPage(
                List.of(), 0, 12, 0, 0);
        when(libraryService.listForPicker(7L, "", "DOCUMENT", 0, 12))
                .thenReturn(expected);

        var result = controller.picker("", "DOCUMENT", 0, 12, user);

        assertThat(result).isSameAs(expected);
        verify(libraryService).listForPicker(7L, "", "DOCUMENT", 0, 12);
    }

    @Test
    void details_are_owner_scoped_and_publish_server_generated_preview_url() {
        when(user.getId()).thenReturn(7L);
        LibraryAssetDetail detail = new LibraryAssetDetail(
                11L, "Bảng điểm", "scores.xlsx", "DOCUMENT",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "XLSX", "excel", 128L, null, null,
                "/lecturer/library/assets/11/preview",
                "/lecturer/library/assets/11/content",
                "/lecturer/library/assets/11/content?download=true", List.of());
        when(libraryService.detail(7L, 11L)).thenReturn(detail);

        var response = controller.details(11L, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(detail);
        assertThat(response.getBody().previewUrl()).endsWith("/11/preview");
        verify(libraryService).detail(7L, 11L);
    }

    @Test
    void preview_uses_real_viewer_model_instead_of_the_download_response() {
        when(user.getId()).thenReturn(7L);
        LibraryAssetPreview preview = new LibraryAssetPreview(
                11L, "Bảng điểm", "scores.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "XLSX", "excel", "SPREADSHEET",
                "/lecturer/library/assets/11/content",
                "/lecturer/library/assets/11/content?download=true",
                "Điểm", List.of(List.of("Họ tên", "Điểm")), List.of(), null);
        when(previewService.load(7L, 11L)).thenReturn(preview);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.preview(11L, user, model);

        assertThat(view).isEqualTo("library/asset-preview");
        assertThat(model.get("assetPreview")).isSameAs(preview);
        verify(previewService).load(7L, 11L);
        verifyNoInteractions(objectStorage);
    }

    @Test
    void cross_owner_content_not_found_never_reaches_object_storage() {
        when(user.getId()).thenReturn(7L);
        when(libraryService.contentHandle(7L, 99L))
                .thenThrow(new EntityNotFoundException("not found"));

        var response = controller.content(99L, false, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(objectStorage);
    }

    @Test
    void computer_upload_is_owned_by_principal_and_redirects_to_private_inventory()
            throws Exception {
        when(user.getId()).thenReturn(7L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "slide.pdf", "application/pdf",
                new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31});
        when(libraryService.upload(7L, file, "DOCUMENT"))
                .thenReturn(new LibraryAssetRow(
                        1L, "slide.pdf", "slide.pdf", "DOCUMENT",
                        "application/pdf", file.getSize(), null, null, false));

        String result = controller.upload(
                file, "DOCUMENT", user, new RedirectAttributesModelMap());

        assertThat(result).isEqualTo("redirect:/lecturer/library/assets");
        verify(libraryService).upload(7L, file, "DOCUMENT");
    }
}
