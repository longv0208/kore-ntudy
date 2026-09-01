package com.ksh.features.questionbank.controller;

import com.ksh.features.questionbank.dto.QuestionBankItemForm;
import com.ksh.features.questionbank.service.QuestionBankItemService;
import com.ksh.features.questionbank.service.QuestionBankValidationException;
import com.ksh.features.questionbank.service.QuestionBankTestGenerationService;
import com.ksh.security.Roles;
import com.ksh.security.KshUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ksh.common.IConstant.ATTR_FORM;
import static com.ksh.common.IConstant.ATTR_MODE;
import static com.ksh.common.IConstant.ATTR_QB_DETAIL;
import static com.ksh.common.IConstant.ATTR_QB_EMPTY_DEPARTMENT;
import static com.ksh.common.IConstant.ATTR_QB_ITEMS;
import static com.ksh.common.IConstant.ATTR_QB_QUERY;
import static com.ksh.common.IConstant.ATTR_QB_SELECTED_STATUS;
import static com.ksh.common.IConstant.BASE_LECTURER_QUESTION_BANK;
import static com.ksh.common.IConstant.MODE_CREATE;
import static com.ksh.common.IConstant.MODE_EDIT;
import static com.ksh.common.IConstant.MSG_QB_DRAFT_SAVED;
import static com.ksh.common.IConstant.MSG_QB_RESUBMITTED;
import static com.ksh.common.IConstant.MSG_QB_SUBMITTED;
import static com.ksh.common.IConstant.MSG_QB_UPDATED;
import static com.ksh.common.IConstant.URL_LECTURER_QUESTION_BANK;
import static com.ksh.common.IConstant.VIEW_QB_DETAIL;
import static com.ksh.common.IConstant.VIEW_QB_FORM;
import static com.ksh.common.IConstant.VIEW_QB_LIST;

/** Lecturer contribution screens for the subject-scoped shared question bank. */
@Controller
@RequestMapping(BASE_LECTURER_QUESTION_BANK)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerQuestionBankController {

    private final QuestionBankItemService itemService;
    private final QuestionBankTestGenerationService generationService;

    public LecturerQuestionBankController(QuestionBankItemService itemService,
                                          QuestionBankTestGenerationService generationService) {
        this.itemService = itemService;
        this.generationService = generationService;
    }

    @GetMapping
    public String list(@RequestParam(name = "subjectId", required = false) Long subjectId,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "bankStatus", defaultValue = "ALL") String bankStatus,
                       @RequestParam(name = "sort", defaultValue = "UPDATED_DESC") String sort,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size", defaultValue = "25") int size,
                       @AuthenticationPrincipal KshUserDetails user,
                       Model model) {
        String effectiveStatus = normalizeStatus(status);
        boolean emptyDepartment = !itemService.hasSubject(user.getId(), user.getRole());
        model.addAttribute(ATTR_QB_EMPTY_DEPARTMENT, emptyDepartment);
        model.addAttribute("subjectOptions", itemService.subjectOptions(user.getId(), user.getRole()));
        model.addAttribute(ATTR_QB_SELECTED_STATUS, effectiveStatus);
        model.addAttribute(ATTR_QB_QUERY, q);
        if (emptyDepartment) {
            model.addAttribute("catalogMode", true);
            model.addAttribute("subjectCatalog", java.util.List.of());
            model.addAttribute("catalogMetrics",
                    new com.ksh.features.questionbank.dto.QuestionBankViews.CatalogMetrics(0, 0, 0, 0));
            model.addAttribute("selectedBankStatus", "ALL");
            model.addAttribute("selectedCatalogSort", "UPDATED_DESC");
            model.addAttribute(ATTR_QB_ITEMS, java.util.List.of());
            return VIEW_QB_LIST;
        }
        if (subjectId == null) {
            var catalog = itemService.subjectCatalogView(
                    user.getId(), user.getRole(), q, bankStatus, sort);
            model.addAttribute("catalogMode", true);
            model.addAttribute("subjectCatalog", catalog.rows());
            model.addAttribute("catalogMetrics", catalog.metrics());
            model.addAttribute("selectedBankStatus", normalizeBankStatus(bankStatus));
            model.addAttribute("selectedCatalogSort", normalizeCatalogSort(sort));
            model.addAttribute(ATTR_QB_ITEMS, java.util.List.of());
            return VIEW_QB_LIST;
        }
        model.addAttribute("catalogMode", false);
        Long selectedSubjectId = subjectId;
        model.addAttribute("selectedSubjectId", selectedSubjectId);
        model.addAttribute("workspace",
                itemService.workspaceSummary(user.getId(), user.getRole(), selectedSubjectId, q));
        model.addAttribute("lessonOptions", itemService.lessonOptions(user.getId(), user.getRole()));
        model.addAttribute("chapterOptions", itemService.chapterOptions(
                user.getId(), user.getRole(), selectedSubjectId));
        model.addAttribute("generatorClasses", generationService.eligibleClasses(
                user.getId(), user.getRole(), selectedSubjectId));
        var itemPage = itemService.page(user.getId(), user.getRole(), selectedSubjectId,
                effectiveStatus, q, page, size);
        model.addAttribute("itemPage", itemPage);
        model.addAttribute(ATTR_QB_ITEMS,
                itemPage.getContent());
        return VIEW_QB_LIST;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ALL";
        }
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "DRAFT", "REVIEW", "APPROVED", "REJECTED", "ARCHIVED" -> normalized;
            default -> "ALL";
        };
    }

    private static String normalizeBankStatus(String status) {
        if (status == null) return "ALL";
        return switch (status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "READY", "EMPTY" -> status.trim().toUpperCase(java.util.Locale.ROOT);
            default -> "ALL";
        };
    }

    private static String normalizeCatalogSort(String sort) {
        if (sort == null) return "UPDATED_DESC";
        return switch (sort.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CODE_ASC", "QUESTIONS_DESC" -> sort.trim().toUpperCase(java.util.Locale.ROOT);
            default -> "UPDATED_DESC";
        };
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(name = "subjectId", required = false) Long subjectId,
                             @AuthenticationPrincipal KshUserDetails user, Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM,
                    itemService.newForm(user.getId(), user.getRole(), subjectId));
        }
        populateForm(model, user, MODE_CREATE);
        return VIEW_QB_FORM;
    }

    @PostMapping("/generate-test")
    public String generateTest(@RequestParam Long subjectId,
                               @RequestParam(name = "title", required = false) String title,
                               @RequestParam(name = "scope", defaultValue = "SUBJECT") String scope,
                               @RequestParam(name = "lessonTemplateId", required = false) Long lessonTemplateId,
                               @RequestParam(name = "questionCount", defaultValue = "10") Integer questionCount,
                               @RequestParam(name = "classIds", required = false) java.util.List<Long> classIds,
                               @AuthenticationPrincipal KshUserDetails user,
                               RedirectAttributes ra) {
        try {
            var result = generationService.generate(user.getId(), user.getRole(), subjectId,
                    title, scope, lessonTemplateId, questionCount, classIds);
            String message = "Đã tạo và lưu đề " + result.questionCount() + " câu vào Kho bài test";
            if (result.distributedCount() > 0) {
                message += ", đồng thời phân phối tới " + result.distributedCount() + " lớp";
            }
            ra.addFlashAttribute("flashSuccess", message);
            return "redirect:/lecturer/tests";
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            ra.addFlashAttribute("flashError", ex.getMessage());
            ra.addAttribute("subjectId", subjectId);
            return redirectList();
        }
    }

    /** Backward-compatible entry point used by controller unit tests and callers
     * that do not preselect a subject. */
    public String createForm(KshUserDetails user, Model model) {
        return createForm(null, user, model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute(ATTR_FORM) QuestionBankItemForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        form.ensureMinOptions(4);
        if (result.hasErrors()) {
            populateForm(model, user, MODE_CREATE);
            return VIEW_QB_FORM;
        }
        try {
            Long id = itemService.save(user.getId(), user.getRole(), form);
            ra.addFlashAttribute("flashSuccess",
                    "REVIEW".equalsIgnoreCase(form.getWorkflowAction())
                            ? MSG_QB_SUBMITTED
                            : MSG_QB_DRAFT_SAVED);
            return redirectDetail(id);
        } catch (QuestionBankValidationException ex) {
            model.addAttribute("flashError", ex.getMessage());
            populateForm(model, user, MODE_CREATE);
            return VIEW_QB_FORM;
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        try {
            model.addAttribute(ATTR_QB_DETAIL, itemService.detail(user.getId(), user.getRole(), id));
            return VIEW_QB_DETAIL;
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute("flashError", ex.getMessage());
            return redirectList();
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal KshUserDetails user,
                           Model model,
                           RedirectAttributes ra) {
        try {
            if (!model.containsAttribute(ATTR_FORM)) {
                model.addAttribute(ATTR_FORM, itemService.loadForm(user.getId(), user.getRole(), id));
            }
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            ra.addFlashAttribute("flashError", ex.getMessage());
            return redirectList();
        }
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute(ATTR_FORM) QuestionBankItemForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        form.setId(id);
        form.ensureMinOptions(4);
        if (result.hasErrors()) {
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        }
        try {
            itemService.save(user.getId(), user.getRole(), form);
            ra.addFlashAttribute("flashSuccess",
                    "REVIEW".equalsIgnoreCase(form.getWorkflowAction())
                            ? MSG_QB_RESUBMITTED
                            : MSG_QB_UPDATED);
            return redirectDetail(id);
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            model.addAttribute("flashError", ex.getMessage());
            populateForm(model, user, MODE_EDIT);
            return VIEW_QB_FORM;
        }
    }

    private void populateForm(Model model, KshUserDetails user, String mode) {
        model.addAttribute(ATTR_MODE, mode);
        boolean emptyDepartment = !itemService.hasSubject(user.getId(), user.getRole());
        model.addAttribute(ATTR_QB_EMPTY_DEPARTMENT, emptyDepartment);
        model.addAttribute("subjectOptions", itemService.subjectOptions(user.getId(), user.getRole()));
        model.addAttribute("lessonOptions", itemService.lessonOptions(user.getId(), user.getRole()));
    }

    private static String redirectList() {
        return "redirect:" + URL_LECTURER_QUESTION_BANK;
    }

    private static String redirectDetail(Long id) {
        return "redirect:" + URL_LECTURER_QUESTION_BANK + "/" + id;
    }
}
