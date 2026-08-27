package com.ksh.features.library.controller;

import com.ksh.features.classes.service.ClassMaterialsService;
import com.ksh.features.classes.service.ClassMaterialsService.ClassMaterialRow;
import com.ksh.features.library.dto.LibraryDtos.PersonalAssetClassTargets;
import com.ksh.features.library.service.PersonalLibraryClassTargetService;
import com.ksh.security.KshUserDetails;
import com.ksh.security.Role;
import com.ksh.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalLibraryClassShareControllerTest {

    @Mock private PersonalLibraryClassTargetService targetService;
    @Mock private ClassMaterialsService materialsService;
    @Mock private KshUserDetails user;

    private PersonalLibraryClassShareController controller;

    @BeforeEach
    void setUp() {
        controller = new PersonalLibraryClassShareController(
                targetService, materialsService);
    }

    @Test
    void route_is_lecturer_only_and_never_accepts_owner_id() {
        assertThat(PersonalLibraryClassShareController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/lecturer/library/assets");
        assertThat(PersonalLibraryClassShareController.class
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo(Roles.PREAUTH_LECTURER_OR_ABOVE);
    }

    @Test
    void class_targets_are_derived_from_principal_and_asset_path_id() {
        stubLecturer();
        var expected = new PersonalAssetClassTargets(11L, List.of());
        when(targetService.targets(7L, Role.LECTURER, 11L)).thenReturn(expected);

        var response = controller.targets(11L, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(targetService).targets(7L, Role.LECTURER, 11L);
    }

    @Test
    void supplementary_share_returns_stable_success_envelope() {
        stubLecturer();
        ClassMaterialRow row = new ClassMaterialRow(
                9L, "Tài liệu riêng", "private.pdf", "application/pdf", 42L,
                LocalDateTime.of(2026, 8, 11, 21, 30),
                "/api/classes/1/materials/9/download");
        when(materialsService.shareFromLibrary(
                1L, 11L, 7L, Role.LECTURER)).thenReturn(row);

        var response = controller.shareIntoClass(11L, 1L, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("ok", true)
                .containsEntry("material", row);
        verify(materialsService).shareFromLibrary(
                1L, 11L, 7L, Role.LECTURER);
    }

    @Test
    void cross_owner_asset_is_json_404() {
        stubLecturer();
        when(materialsService.shareFromLibrary(
                1L, 99L, 7L, Role.LECTURER))
                .thenThrow(new EntityNotFoundException("Không tìm thấy học liệu"));

        var response = controller.shareIntoClass(99L, 1L, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("ok", false)
                .containsEntry("message", "Không tìm thấy học liệu");
    }

    @Test
    void duplicate_share_is_json_409_instead_of_generic_500() {
        stubLecturer();
        when(materialsService.shareFromLibrary(
                1L, 11L, 7L, Role.LECTURER))
                .thenThrow(new IllegalStateException(
                        "Tài liệu này đã có trong tab Tài liệu của lớp"));

        var response = controller.shareIntoClass(11L, 1L, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("ok", false)
                .containsEntry("message",
                        "Tài liệu này đã có trong tab Tài liệu của lớp");
    }

    private void stubLecturer() {
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.LECTURER);
    }
}
