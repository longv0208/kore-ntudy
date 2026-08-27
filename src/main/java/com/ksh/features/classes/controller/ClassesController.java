package com.ksh.features.classes.controller;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.controller.support.ClassDetailModelSupport;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.dto.ClassesDtos.ClassRow;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.classes.service.ClassJoinRequestQuickViewService;
import com.ksh.features.classes.service.JoinClassService;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.security.Roles;
import com.ksh.security.KshUserDetails;
import jakarta.validation.Valid;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import static com.ksh.common.IConstant.*;
import static com.ksh.features.classes.controller.support.ClassDetailModelSupport.classUrl;

/**
 * Controller for the lecturer class CRUD screens (list, create, edit, delete).
 * Only LECTURER, LEADER, and ADMIN roles may access these endpoints (see {@link Roles}).
 *
 * <p>Exposed endpoints:
 * <ul>
 *   <li>{@code GET  /lecturer/classes}             — list all classes for the current user</li>
 *   <li>{@code GET  /lecturer/classes/new}         — render the create-class form</li>
 *   <li>{@code POST /lecturer/classes}             — submit the create-class form</li>
 *   <li>{@code GET  /lecturer/classes/{id}}        — redirect to lessons</li>
 *   <li>{@code GET  /lecturer/classes/{id}/edit}   — render the edit-class form</li>
 *   <li>{@code POST /lecturer/classes/{id}}        — submit the edit-class form</li>
 *   <li>{@code POST /lecturer/classes/{id}/delete} — soft-delete after confirm modal</li>
 * </ul>
 *
 * <p>Sidebar tabs (lessons/members/materials/settings/...) live on
 * {@link ClassDetailController}. Validation errors render inline beneath each
 * field via {@code th:errors}; the service layer enforces owner authorization.
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassesController {

    private static final String TAB_CURRENT = "current";
    private static final String TAB_ARCHIVED = "archived";
    private static final List<String> CURRENT_STATUSES = List.of(
            ClassEntity.STATUS_PENDING,
            ClassEntity.STATUS_REJECTED,
            ClassEntity.STATUS_ACTIVE);
    private static final List<String> ARCHIVED_STATUSES = List.of(ClassEntity.STATUS_ARCHIVED);

    private final ClassesService classesService;
    private final DepartmentRepository subjectRepository;
    private final ClassJoinRequestQuickViewService quickJoinRequests;
    private final JoinClassService joinClassService;

    public ClassesController(ClassesService classesService,
                             DepartmentRepository subjectRepository,
                             ClassJoinRequestQuickViewService quickJoinRequests,
                             JoinClassService joinClassService) {
        this.classesService = classesService;
        this.subjectRepository = subjectRepository;
        this.quickJoinRequests = quickJoinRequests;
        this.joinClassService = joinClassService;
    }

    /**
     * Lists all classes owned by or accessible to the authenticated user.
     *
     * <p>Pagination defaults: 20 rows per page, sorted by {@code createdAt DESC}.
     * Clients can override via {@code ?page=N&size=M&sort=...} query parameters.
     */
    @GetMapping("/classes")
    public String list(@AuthenticationPrincipal KshUserDetails user,
                       @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "createdAt",
                               direction = Sort.Direction.DESC) Pageable pageable,
                       @RequestParam(defaultValue = TAB_CURRENT) String tab,
                       Model model) {
        String selectedTab = TAB_ARCHIVED.equalsIgnoreCase(tab) ? TAB_ARCHIVED : TAB_CURRENT;
        List<String> selectedStatuses = TAB_ARCHIVED.equals(selectedTab)
                ? ARCHIVED_STATUSES : CURRENT_STATUSES;
        Page<ClassRow> page = classesService.listForUserByStatuses(
                user.getId(), user.getRole(), selectedStatuses, pageable);
        if (page.getTotalPages() > 0 && pageable.getPageNumber() >= page.getTotalPages()) {
            Pageable lastPage = PageRequest.of(
                    page.getTotalPages() - 1, pageable.getPageSize(), pageable.getSort());
            page = classesService.listForUserByStatuses(
                    user.getId(), user.getRole(), selectedStatuses, lastPage);
        }
        // Keep the existing template loop driven by ${classes} (a List). The Page
        // object is exposed separately as ${classesPage} for the pagination block.
        model.addAttribute(ATTR_CLASSES, page.getContent());
        model.addAttribute(ATTR_CLASSES_PAGE, page);
        model.addAttribute("selectedTab", selectedTab);
        model.addAttribute("currentClassCount", TAB_CURRENT.equals(selectedTab)
                ? page.getTotalElements()
                : classesService.countForUserByStatuses(user.getId(), user.getRole(), CURRENT_STATUSES));
        model.addAttribute("archivedClassCount", TAB_ARCHIVED.equals(selectedTab)
                ? page.getTotalElements()
                : classesService.countForUserByStatuses(user.getId(), user.getRole(), ARCHIVED_STATUSES));
        model.addAttribute("pendingJoinRequests", quickJoinRequests.forOwnedClasses(
                page.getContent().stream().map(ClassRow::id).toList(), user.getId()));
        return VIEW_CLASS_MANAGE;
    }

    /** Approves a pending student without forcing the owner into the Members tab. */
    @PostMapping("/classes/{id}/join-requests/{studentId}/approve")
    public String approveJoinRequest(@PathVariable Long id,
                                     @PathVariable Long studentId,
                                     @AuthenticationPrincipal KshUserDetails user,
                                     RedirectAttributes ra) {
        try {
            joinClassService.approve(id, studentId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOIN_APPROVED);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_JOIN_APPROVE_FAILED + ex.getMessage());
        }
        return "redirect:" + URL_CLASSES_LIST;
    }

    /**
     * Renders the create-class form.
     * Preserves a previously bound {@code form} flash attribute on validation redirect.
     */
    @GetMapping("/classes/new")
    public String createForm(Model model) {
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.empty());
        }
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
        addSubjectOptions(model);
        return VIEW_CLASS_FORM;
    }

    /**
     * Handles create-class form submission.
     * Re-renders the form with inline errors on validation failure;
     * redirects to the class list with a success flash message on success.
     */
    @PostMapping("/classes")
    public String create(@Valid @ModelAttribute("form") ClassForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
            addSubjectOptions(model);
            return VIEW_CLASS_FORM;
        }
        try {
            classesService.create(form, user.getId());
        } catch (IllegalArgumentException exception) {
            result.rejectValue("subjectId", "subject.invalid", exception.getMessage());
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
            addSubjectOptions(model);
            return VIEW_CLASS_FORM;
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_CREATED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    /**
     * Renders the edit-class form for an existing class.
     * Only the immutable class owner (or ADMIN) may access this endpoint; the service
     * layer enforces the ownership check and throws if unauthorized.
     */
    @GetMapping("/classes/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal KshUserDetails user,
                           Model model) {
        ClassEntity entity = classesService.getOwnerManaged(id, user.getId(), user.getRole());
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.fromEntity(entity));
        }
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
        model.addAttribute(ATTR_CLASS_ID, id);
        addSubjectOptions(model);
        return VIEW_CLASS_FORM;
    }

    /**
     * Handles edit-class form submission.
     * Re-renders the form with inline errors on validation failure;
     * redirects to the class list with a success flash message on success.
     */
    @PostMapping("/classes/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") ClassForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            rebindDateRangeError(result);
            model.addAttribute(ATTR_MODE, MODE_EDIT);
            model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
            model.addAttribute(ATTR_CLASS_ID, id);
            addSubjectOptions(model);
            return VIEW_CLASS_FORM;
        }
        classesService.update(id, form, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_UPDATED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    /** Returns a corrected REJECTED class to the leader approval queue. */
    @PostMapping("/classes/{id}/resubmit")
    public String resubmitForReview(@PathVariable Long id,
                                    @AuthenticationPrincipal KshUserDetails user,
                                    RedirectAttributes ra) {
        try {
            classesService.resubmitForReview(id, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã gửi lại lớp để chờ duyệt");
        } catch (IllegalStateException exception) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, exception.getMessage());
        }
        return "redirect:" + URL_CLASSES_LIST;
    }

    /** Soft-deletes a class after the user confirms the action via the confirm modal. */
    @PostMapping("/classes/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails user,
                         RedirectAttributes ra) {
        classesService.softDelete(id, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_DELETED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    /** Redirects the root class-detail URL straight to lessons. */
    @GetMapping("/classes/{id}")
    public String detailRoot(@PathVariable Long id) {
        return "redirect:" + classUrl(id) + "/" + TAB_LESSONS;
    }

    /**
     * Rebinds a cross-field date-range validation error to the {@code endDate} field.
     *
     * <p>{@code @AssertTrue isDateRangeValid()} produces a global error whose field name
     * is {@code dateRangeValid}. This method promotes it to a field error on {@code endDate}
     * so the Thymeleaf template can render it inline beneath the correct input.
     */
    private void rebindDateRangeError(BindingResult result) {
        result.getFieldErrors("dateRangeValid").forEach(err ->
                result.rejectValue("endDate", "dateRange.invalid", err.getDefaultMessage())
        );
    }

    private void addSubjectOptions(Model model) {
        model.addAttribute("subjectOptions", subjectRepository.findByActiveTrueOrderByNameAsc());
    }
}
