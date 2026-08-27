package com.ksh.features.library.controller;

import com.ksh.features.library.dto.LibraryDtos.LessonTemplatePageView;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.features.library.service.LessonTemplateService;
import com.ksh.features.storage.profile.StorageProfileException;
import com.ksh.security.Roles;
import com.ksh.security.KshUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

import static com.ksh.common.IConstant.ATTR_FLASH_ERROR;
import static com.ksh.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ksh.common.IConstant.ATTR_LIBRARY_CLASS_OPTIONS;
import static com.ksh.common.IConstant.ATTR_LIBRARY_PAGE;
import static com.ksh.common.IConstant.ATTR_LIBRARY_QUERY;
import static com.ksh.common.IConstant.ATTR_LIBRARY_SIZE;
import static com.ksh.common.IConstant.ATTR_LIBRARY_TEMPLATE_COUNT;
import static com.ksh.common.IConstant.BASE_LECTURER;
import static com.ksh.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ksh.common.IConstant.MSG_GENERIC_RETRY;
import static com.ksh.common.IConstant.MSG_STORAGE_PROFILE_UNAVAILABLE;
import static com.ksh.common.IConstant.MSG_TEMPLATE_DELETED;
import static com.ksh.common.IConstant.PATH_LIBRARY;
import static com.ksh.common.IConstant.URL_LIBRARY;
import static com.ksh.common.IConstant.VIEW_LIBRARY;

/**
 * Canonical subject lesson authoring and class distribution in Library.
 */
@Controller
@RequestMapping(BASE_LECTURER + PATH_LIBRARY + "/templates")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonTemplateController {

    private static final Logger log = LoggerFactory.getLogger(LessonTemplateController.class);
    private static final String REDIRECT_TEMPLATES =
            "redirect:" + URL_LIBRARY + "/templates";

    private final LessonTemplateService templateService;

    public LessonTemplateController(LessonTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public String page(@RequestParam(name = "subjectId", required = false) Long subjectId,
                       @RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size",
                               defaultValue = "" + DEFAULT_LIBRARY_PAGE_SIZE) int size,
                       @AuthenticationPrincipal KshUserDetails user,
                       Model model) {
        LessonTemplatePageView view = templateService.list(
                user.getId(), user.getRole(), subjectId, q, page, size);
        model.addAttribute(ATTR_LIBRARY_PAGE, view.page());
        model.addAttribute(ATTR_LIBRARY_QUERY, view.q());
        model.addAttribute(ATTR_LIBRARY_SIZE, view.page().getSize());
        model.addAttribute(ATTR_LIBRARY_CLASS_OPTIONS, view.classOptions());
        model.addAttribute(ATTR_LIBRARY_TEMPLATE_COUNT, view.templateCount());
        model.addAttribute("librarySubjectId", view.subjectId());
        model.addAttribute("librarySubjectCode", view.subjectCode());
        model.addAttribute("librarySubjectName", view.subjectName());
        model.addAttribute("librarySubjectDescription", view.subjectDescription());
        model.addAttribute("librarySubjectOptions", view.subjectOptions());
        model.addAttribute("libraryChapters", view.chapters());
        model.addAttribute("libraryLocked", view.libraryLocked());
        model.addAttribute("libraryCanManageLock", view.canManageLibraryLock());
        return VIEW_LIBRARY;
    }

    @PostMapping("/subjects/{subjectId}/lock")
    public String setSubjectLibraryLock(@PathVariable Long subjectId,
                                        @RequestParam boolean locked,
                                        @AuthenticationPrincipal KshUserDetails user,
                                        RedirectAttributes ra) {
        try {
            templateService.setSubjectLibraryLocked(
                    user.getId(), user.getRole(), subjectId, locked);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, locked
                    ? "Đã khóa khung chương trình; nội dung hiện chuyển sang chỉ đọc"
                    : "Đã mở khóa khung chương trình; có thể tiếp tục biên soạn");
        } catch (AccessDeniedException | IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectTemplates(subjectId);
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(name = "subjectId", required = false) Long subjectId,
                             @RequestParam(name = "newChapter", defaultValue = "false") boolean newChapter,
                             @RequestParam(name = "chapterNumber", required = false) Integer chapterNumber,
                             @AuthenticationPrincipal KshUserDetails user, Model model) {
        populateForm(model, templateService.loadForm(
                user.getId(), user.getRole(), null, subjectId, newChapter, chapterNumber), user);
        return "library/lesson-form";
    }

    @PostMapping("/subjects/{subjectId}/chapters/{chapterNumber}/rename")
    @ResponseBody
    public Map<String, Object> renameChapter(@PathVariable Long subjectId,
                                             @PathVariable int chapterNumber,
                                             @RequestParam String title,
                                             @AuthenticationPrincipal KshUserDetails user) {
        templateService.renameChapter(user.getId(), user.getRole(), subjectId,
                chapterNumber, title);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/rename")
    @ResponseBody
    public Map<String, Object> renameLesson(@PathVariable Long id,
                                            @RequestParam String title,
                                            @AuthenticationPrincipal KshUserDetails user) {
        templateService.renameLesson(user.getId(), user.getRole(), id, title);
        return Map.of("ok", true);
    }

    @PostMapping("/subjects/{subjectId}/chapters/reorder")
    @ResponseBody
    public Map<String, Object> reorderChapters(@PathVariable Long subjectId,
                                               @RequestParam(name = "chapterNumbers") List<Integer> chapterNumbers,
                                               @AuthenticationPrincipal KshUserDetails user) {
        templateService.reorderChapters(user.getId(), user.getRole(), subjectId,
                chapterNumbers);
        return Map.of("ok", true);
    }

    @PostMapping("/subjects/{subjectId}/chapters/{chapterNumber}/delete")
    public String deleteChapter(@PathVariable Long subjectId,
                                @PathVariable int chapterNumber,
                                @AuthenticationPrincipal KshUserDetails user,
                                RedirectAttributes ra) {
        try {
            templateService.softDeleteChapter(user.getId(), user.getRole(), subjectId,
                    chapterNumber);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã xoá chương và đánh lại số thứ tự");
        } catch (AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return redirectTemplates(subjectId);
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectTemplates(subjectId);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal KshUserDetails user, Model model) {
        populateForm(model, templateService.loadForm(
                user.getId(), user.getRole(), id, null), user);
        return "library/lesson-form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") LessonTemplateForm form,
                       BindingResult result,
                       @AuthenticationPrincipal KshUserDetails user,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateForm(model, form, user);
            return "library/lesson-form";
        }
        try {
            templateService.saveForm(user.getId(), user.getRole(), form);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã lưu bài học trong Library");
            return redirectTemplates(form.getSubjectId());
        } catch (AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return redirectTemplates(form.getSubjectId());
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return form.getId() == null
                    ? "redirect:" + URL_LIBRARY + "/templates/new?subjectId=" + form.getSubjectId()
                    : "redirect:" + URL_LIBRARY + "/templates/" + form.getId() + "/edit";
        } catch (RuntimeException ex) {
            log.error("Failed to save Library lesson for user {}", user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
            return form.getId() == null
                    ? "redirect:" + URL_LIBRARY + "/templates/new?subjectId=" + form.getSubjectId()
                    : "redirect:" + URL_LIBRARY + "/templates/" + form.getId() + "/edit";
        }
    }

    /** Saves the compact in-page editor without redirecting away from Library. */
    @PostMapping("/inline")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveInline(
            @Valid @ModelAttribute("form") LessonTemplateForm form,
            BindingResult result,
            @AuthenticationPrincipal KshUserDetails user) {
        if (result.hasErrors()) {
            String message = result.getAllErrors().isEmpty() ? "Dữ liệu bài học chưa hợp lệ"
                    : result.getAllErrors().get(0).getDefaultMessage();
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("ok", false, "message", message));
        }
        try {
            var saved = templateService.saveForm(user.getId(), user.getRole(), form);
            return ResponseEntity.ok(Map.of("ok", true, "id", saved.id(),
                    "message", "Đã lưu bài học và tài nguyên"));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("ok", false, "message", ex.getMessage()));
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("ok", false, "message", ex.getMessage()));
        } catch (StorageProfileException ex) {
            log.warn("Library upload storage is unavailable for user {}: {}",
                    user.getId(), ex.errorCode());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "message", MSG_STORAGE_PROFILE_UNAVAILABLE));
        } catch (RuntimeException ex) {
            log.error("Failed to save inline Library lesson for user {}", user.getId(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "message", MSG_GENERIC_RETRY));
        }
    }

    @PostMapping("/{id}/distribute")
    public String distribute(@PathVariable Long id,
                             @RequestParam(name = "classIds", required = false) List<Long> classIds,
                             @AuthenticationPrincipal KshUserDetails user,
                             RedirectAttributes ra) {
        try {
            int count = templateService.distribute(
                    id, classIds, user.getId(), user.getRole()).size();
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    "Đã phân phối bài học tới " + count + " lớp");
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to distribute Library lesson {} for user {}",
                    id, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT_TEMPLATES;
    }

    @PostMapping("/subjects/{subjectId}/distribute")
    public String distributeSubject(@PathVariable Long subjectId,
                                    @RequestParam(name = "classIds", required = false) List<Long> classIds,
                                    @AuthenticationPrincipal KshUserDetails user,
                                    RedirectAttributes ra) {
        try {
            List<com.ksh.features.library.dto.LibraryDtos.LessonCloneResult> results =
                    templateService.distributeSubject(subjectId, classIds,
                            user.getId(), user.getRole());
            long classCount = results.stream().map(result -> result.classId()).distinct().count();
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    "Đã phân phối toàn bộ " + results.size() + " bài học tới " + classCount + " lớp");
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to distribute Library subject {} for user {}",
                    subjectId, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return redirectTemplates(subjectId);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails user,
                         RedirectAttributes ra) {
        try {
            templateService.softDelete(user.getId(), user.getRole(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_TEMPLATE_DELETED);
        } catch (AccessDeniedException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete template {} for user {}", id, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT_TEMPLATES;
    }

    @PostMapping("/{id}/resources/{assetId}/delete")
    public String detachResource(@PathVariable Long id,
                                 @PathVariable Long assetId,
                                 @RequestParam Long subjectId,
                                 @AuthenticationPrincipal KshUserDetails user,
                                 RedirectAttributes ra) {
        try {
            templateService.detachResource(user.getId(), user.getRole(), id, assetId);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã gỡ tài nguyên và cập nhật các lớp đã phân phối");
        } catch (AccessDeniedException | IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to detach Library asset {} from template {}", assetId, id, ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return redirectTemplates(subjectId);
    }

    private void populateForm(Model model, LessonTemplateForm form, KshUserDetails user) {
        model.addAttribute("form", form);
        model.addAttribute("materialOptions", templateService.materialOptions(user.getId()));
        model.addAttribute("librarySubject",
                templateService.subjectContext(user.getId(), user.getRole(), form.getSubjectId()));
        model.addAttribute("librarySubjectOptions",
                templateService.subjectOptions(user.getId(), user.getRole()));
    }

    private static String redirectTemplates(Long subjectId) {
        return subjectId == null ? REDIRECT_TEMPLATES
                : REDIRECT_TEMPLATES + "?subjectId=" + subjectId;
    }
}
