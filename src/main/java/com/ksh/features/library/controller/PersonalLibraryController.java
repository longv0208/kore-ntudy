package com.ksh.features.library.controller;

import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPageView;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetDetail;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPickerPage;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ksh.features.library.service.LibraryService;
import com.ksh.features.library.service.LibraryService.OwnedAssetContent;
import com.ksh.features.library.service.PersonalLibraryAssetPreviewService;
import com.ksh.features.storage.ObjectStorage;
import com.ksh.features.storage.StoredObject;
import com.ksh.features.storage.StoredObjectResource;
import com.ksh.security.KshUserDetails;
import com.ksh.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.ksh.common.IConstant.ATTR_FLASH_ERROR;
import static com.ksh.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ksh.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ksh.common.IConstant.MSG_ATTACHMENT_TOO_LARGE;
import static com.ksh.common.IConstant.MSG_GENERIC_RETRY;

/**
 * Owner-private file inventory kept separate from the subject-wide canonical
 * lesson library. Every lookup derives the owner from the authenticated
 * principal; no endpoint accepts an owner id from the client.
 */
@Controller
@RequestMapping("/lecturer/library/assets")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class PersonalLibraryController {

    private static final Logger log = LoggerFactory.getLogger(PersonalLibraryController.class);
    private static final String VIEW = "library/assets";
    private static final String REDIRECT = "redirect:/lecturer/library/assets";

    private final LibraryService libraryService;
    private final ObjectStorage objectStorage;
    private final PersonalLibraryAssetPreviewService previewService;

    public PersonalLibraryController(LibraryService libraryService,
                                     ObjectStorage objectStorage,
                                     PersonalLibraryAssetPreviewService previewService) {
        this.libraryService = libraryService;
        this.objectStorage = objectStorage;
        this.previewService = previewService;
    }

    @GetMapping
    public String page(@RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "kind", defaultValue = "") String kind,
                       @RequestParam(name = "view", defaultValue = "ALL") String assetView,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size",
                               defaultValue = "" + DEFAULT_LIBRARY_PAGE_SIZE) int size,
                       @AuthenticationPrincipal KshUserDetails user,
                       Model model) {
        LibraryAssetPageView pageView = libraryService.list(
                user.getId(), q, kind, assetView, page, size);
        model.addAttribute("libraryPage", pageView.page());
        model.addAttribute("libraryRecentAssets", pageView.recentlyUpdated());
        model.addAttribute("libraryQuery", pageView.q());
        model.addAttribute("libraryKind", pageView.kind());
        model.addAttribute("libraryView", pageView.view());
        model.addAttribute("librarySize", pageView.page().getSize());
        model.addAttribute("libraryTotalCount", pageView.totalCount());
        model.addAttribute("libraryDocumentCount", pageView.documentCount());
        model.addAttribute("libraryVideoCount", pageView.videoCount());
        model.addAttribute("libraryInUseCount", pageView.inUseCount());
        model.addAttribute("libraryRecentCount", pageView.recentCount());
        return VIEW;
    }

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public LibraryAssetPickerPage picker(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "kind", defaultValue = "") String kind,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size",
                    defaultValue = "" + DEFAULT_LIBRARY_PAGE_SIZE) int size,
            @AuthenticationPrincipal KshUserDetails user) {
        return libraryService.listForPicker(user.getId(), q, kind, page, size);
    }

    /** Owner-private drawer metadata with exact lesson/template reference URLs. */
    @GetMapping(value = "/{id}/details", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<LibraryAssetDetail> details(
            @PathVariable Long id,
            @AuthenticationPrincipal KshUserDetails user) {
        try {
            return ResponseEntity.ok(libraryService.detail(user.getId(), id));
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Real read-only viewer. Browser-native formats are embedded inline;
     * supported Office/text formats receive a bounded server-side preview.
     */
    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Long id,
                          @AuthenticationPrincipal KshUserDetails user,
                          Model model) {
        try {
            model.addAttribute("assetPreview", previewService.load(user.getId(), id));
            return "library/asset-preview";
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(name = "kind", required = false) String kind,
                         @AuthenticationPrincipal KshUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            LibraryAssetRow row = libraryService.upload(user.getId(), file, kind);
            redirectAttributes.addFlashAttribute(
                    ATTR_FLASH_SUCCESS, "Đã tải lên kho cá nhân: " + row.title());
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (MaxUploadSizeExceededException ex) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_ATTACHMENT_TOO_LARGE);
        } catch (IOException ex) {
            log.error("Failed to store personal library asset for user {}", user.getId(), ex);
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        } catch (RuntimeException ex) {
            log.error("Unexpected personal library upload error for user {}", user.getId(), ex);
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT;
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable Long id,
                         @RequestParam("title") String title,
                         @AuthenticationPrincipal KshUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            libraryService.rename(user.getId(), id, title);
            redirectAttributes.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã đổi tên tài liệu");
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to rename personal library asset {} for user {}",
                    id, user.getId(), ex);
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            libraryService.delete(user.getId(), id);
            redirectAttributes.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã xoá tài liệu khỏi kho cá nhân");
        } catch (IllegalStateException | IllegalArgumentException | EntityNotFoundException ex) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete personal library asset {} for user {}",
                    id, user.getId(), ex);
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT;
    }

    /** Streams an object only after an owner-scoped row and key-prefix check. */
    @GetMapping("/{id}/content")
    @ResponseBody
    public ResponseEntity<Resource> content(
            @PathVariable Long id,
            @RequestParam(name = "download", defaultValue = "false") boolean download,
            @AuthenticationPrincipal KshUserDetails user) {
        OwnedAssetContent handle;
        try {
            handle = libraryService.contentHandle(user.getId(), id);
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            return ResponseEntity.notFound().build();
        }

        StoredObject object;
        try {
            if (!objectStorage.exists(handle.storageKey())) {
                return ResponseEntity.notFound().build();
            }
            object = objectStorage.open(handle.storageKey());
        } catch (IOException ex) {
            log.error("Failed to open personal library object {}", handle.storageKey(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException ex) {
            log.error("Personal library storage unavailable for {}", handle.storageKey(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        ContentDisposition disposition = download
                ? ContentDisposition.attachment()
                    .filename(safeFilename(handle.originalFilename()), StandardCharsets.UTF_8)
                    .build()
                : ContentDisposition.inline()
                    .filename(safeFilename(handle.originalFilename()), StandardCharsets.UTF_8)
                    .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(parseMime(handle.mimeType()));
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set("X-Content-Type-Options", "nosniff");
        long length = object.contentLength() >= 0
                ? object.contentLength() : handle.sizeBytes();
        if (length >= 0) headers.setContentLength(length);
        return new ResponseEntity<>(
                new StoredObjectResource(object, handle.storageKey()), headers, HttpStatus.OK);
    }

    private static String safeFilename(String filename) {
        return filename == null || filename.isBlank() ? "material" : filename;
    }

    private static MediaType parseMime(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
