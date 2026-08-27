package com.ksh.features.classes.controller;

import com.ksh.features.classes.service.ClassMaterialsService;
import com.ksh.features.classes.service.ClassMaterialsService.DownloadHandle;
import com.ksh.features.storage.ObjectStorage;
import com.ksh.features.storage.StoredObject;
import com.ksh.features.storage.StoredObjectResource;
import com.ksh.security.KshUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Streams a class-level library document after class membership/role checks. */
@RestController
public class ClassMaterialsApiController {

    private static final Logger log = LoggerFactory.getLogger(ClassMaterialsApiController.class);
    private final ClassMaterialsService materialsService;
    private final ObjectStorage objectStorage;

    public ClassMaterialsApiController(ClassMaterialsService materialsService,
                                       ObjectStorage objectStorage) {
        this.materialsService = materialsService;
        this.objectStorage = objectStorage;
    }

    @GetMapping("/api/classes/{classId}/materials/{materialId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(@PathVariable Long classId,
                                             @PathVariable Long materialId,
                                             @AuthenticationPrincipal KshUserDetails user) {
        final DownloadHandle handle;
        try {
            handle = materialsService.download(classId, materialId,
                    user.getId(), user.getRole());
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            if (!objectStorage.exists(handle.storageKey())) {
                return ResponseEntity.notFound().build();
            }
            StoredObject object = objectStorage.open(handle.storageKey());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(safeFilename(handle.originalFilename()), StandardCharsets.UTF_8)
                    .build());
            headers.setContentType(parseMime(handle.mimeType()));
            long length = object.contentLength() >= 0
                    ? object.contentLength() : handle.sizeBytes();
            if (length >= 0) headers.setContentLength(length);
            return new ResponseEntity<>(
                    new StoredObjectResource(object, handle.storageKey()), headers, HttpStatus.OK);
        } catch (IOException ex) {
            log.error("Failed to stream class material {}", materialId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static String safeFilename(String name) {
        return name == null || name.isBlank() ? "material" : name;
    }

    private static MediaType parseMime(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
