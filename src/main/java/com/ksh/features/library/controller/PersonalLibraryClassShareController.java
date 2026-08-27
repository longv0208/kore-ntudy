package com.ksh.features.library.controller;

import com.ksh.features.classes.service.ClassMaterialsService;
import com.ksh.features.classes.service.ClassMaterialsService.ClassMaterialRow;
import com.ksh.features.library.dto.LibraryDtos.PersonalAssetClassTargets;
import com.ksh.features.library.service.PersonalLibraryClassTargetService;
import com.ksh.security.KshUserDetails;
import com.ksh.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static com.ksh.common.IConstant.MSG_GENERIC_RETRY;

/** Safe, no-copy sharing of a personal DOCUMENT as a supplementary class file. */
@RestController
@RequestMapping("/lecturer/library/assets")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class PersonalLibraryClassShareController {

    private static final Logger log =
            LoggerFactory.getLogger(PersonalLibraryClassShareController.class);
    private static final String SUCCESS_MESSAGE =
            "Đã chia sẻ vào tab Tài liệu của lớp";

    private final PersonalLibraryClassTargetService targetService;
    private final ClassMaterialsService materialsService;

    public PersonalLibraryClassShareController(
            PersonalLibraryClassTargetService targetService,
            ClassMaterialsService materialsService) {
        this.targetService = targetService;
        this.materialsService = materialsService;
    }

    /** Read-only target discovery; never creates a section or lesson. */
    @GetMapping(value = "/{assetId}/class-targets",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> targets(
            @PathVariable Long assetId,
            @AuthenticationPrincipal KshUserDetails user) {
        try {
            PersonalAssetClassTargets targets = targetService.targets(
                    user.getId(), user.getRole(), assetId);
            return ResponseEntity.ok(targets);
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, "Bạn không có quyền xem lớp đích");
        } catch (EntityNotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to load class targets for personal asset {}", assetId, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, MSG_GENERIC_RETRY);
        }
    }

    /**
     * Binds one owned personal document to the standalone Materials tab of one
     * owned live class. No lesson id is accepted by this contract.
     */
    @PostMapping(value = "/{assetId}/share/class",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> shareIntoClass(
            @PathVariable Long assetId,
            @RequestParam Long classId,
            @AuthenticationPrincipal KshUserDetails user) {
        try {
            ClassMaterialRow material = materialsService.shareFromLibrary(
                    classId, assetId, user.getId(), user.getRole());
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", SUCCESS_MESSAGE,
                    "material", material));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            return error(HttpStatus.CONFLICT, ex.getMessage());
        } catch (AccessDeniedException ex) {
            return error(HttpStatus.FORBIDDEN, "Bạn không có quyền chia sẻ vào lớp này");
        } catch (EntityNotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (ResponseStatusException ex) {
            return error(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason());
        } catch (RuntimeException ex) {
            log.error("Failed to share personal asset {} into class {}",
                    assetId, classId, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, MSG_GENERIC_RETRY);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status,
                                                              String message) {
        return ResponseEntity.status(status).body(Map.of(
                "ok", false,
                "message", message == null || message.isBlank()
                        ? MSG_GENERIC_RETRY : message));
    }
}
