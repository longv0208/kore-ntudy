package com.ksh.features.classes.controller;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.controller.support.ClassDetailModelSupport;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.dto.ClassesDtos.ClassRow;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.classes.service.ClassJoinRequestQuickViewService;
import com.ksh.features.classes.service.JoinClassService;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.semester.AcademicSemesterService;
import com.ksh.features.classes.semester.AcademicSemester;
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
    private static final String TAB_PENDING = "pending";
    private static final String TAB_REJECTED = "rejected";
    private static final String TAB_ARCHIVED = "archived";

    private final ClassesService classesService;
    private final SubjectRepository subjectRepository;
    private final ClassJoinRequestQuickViewService quickJoinRequests;
    private final JoinClassService joinClassService;
    private final AcademicSemesterService semesterService;

    public ClassesController(ClassesService classesService,
                             SubjectRepository subjectRepository,
                             ClassJoinRequestQuickViewService quickJoinRequests,
                             JoinClassService joinClassService,
                             AcademicSemesterService semesterService) {
        this.classesService = classesService;
        this.subjectRepository = subjectRepository;
        this.quickJoinRequests = quickJoinRequests;
        this.joinClassService = joinClassService;
        this.semesterService = semesterService;
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
                       @RequestParam(defaultValue = "") String semester,
                       @RequestParam(defaultValue = "") String subjectCode,
                       @RequestParam(name = "q", defaultValue = "") String query,
                       Model model) {
        String selectedTab = normalizeTab(tab);
        List<String> selectedStatuses = statusesForTab(selectedTab);
        Page<ClassRow> page = classesService.listForUserByStatusesAndFilters(
                user.getId(), user.getRole(), selectedStatuses,
                semester, subjectCode, query, pageable);
        if (page.getTotalPages() > 0 && pageable.getPageNumber() >= page.getTotalPages()) {
            Pageable lastPage = PageRequest.of(
                    page.getTotalPages() - 1, pageable.getPageSize(), pageable.getSort());
            page = classesService.listForUserByStatusesAndFilters(
                    user.getId(), user.getRole(), selectedStatuses,
                    semester, subjectCode, query, lastPage);
        }
        // Keep the existing template loop driven by ${classes} (a List). The Page
        // object is exposed separately as ${classesPage} for the pagination block.
        model.addAttribute(ATTR_CLASSES, page.getContent());
        java.util.Map<String, List<ClassRow>> groups = new java.util.TreeMap<>((a,b) ->
                AcademicSemester.parse(b).compareTo(AcademicSemester.parse(a)));
        page.getContent().forEach(row -> groups.computeIfAbsent(row.semester(),
                ignored -> new java.util.ArrayList<>()).add(row));
        model.addAttribute("semesterGroups", groups.entrySet().stream().map(entry ->
                new SemesterGroup(entry.getKey(), AcademicSemester.parse(entry.getKey()).displayName(),
                        entry.getValue(), entry.getValue().stream().mapToInt(ClassRow::studentCount).sum(),
                        entry.getValue().stream().mapToInt(ClassRow::lectureCount).sum(),
                        entry.getValue().stream().mapToInt(ClassRow::assignmentCount).sum(),
                        entry.getValue().stream().mapToInt(ClassRow::materialCount).sum())).toList());
        model.addAttribute(ATTR_CLASSES_PAGE, page);
        model.addAttribute("selectedTab", selectedTab);
        var overview = classesService.overview(user.getId(), user.getRole(), semester, subjectCode, query);
        var statusCounts = classesService.statusCounts(
                user.getId(), user.getRole(), semester, subjectCode, query);
        model.addAttribute("classOverview", overview);
        model.addAttribute("currentClassCount", statusCounts.active());
        model.addAttribute("pendingClassCount", statusCounts.pending());
        model.addAttribute("rejectedClassCount", statusCounts.rejected());
        model.addAttribute("archivedClassCount", statusCounts.archived());
        model.addAttribute("pendingJoinRequests", quickJoinRequests.forOwnedClasses(
                page.getContent().stream().map(ClassRow::id).toList(), user.getId()));
        model.addAttribute("semesterOptions", java.util.stream.Stream.concat(
                classesService.availableSemesters().stream(), semesterService.registeredCodes().stream())
                .distinct().sorted((a,b) -> AcademicSemester.parse(b).compareTo(AcademicSemester.parse(a))).toList());
        model.addAttribute("subjectOptions", subjectRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("selectedSemester", semester == null ? "" : semester.toUpperCase());
        model.addAttribute("selectedSubjectCode", subjectCode == null ? "" : subjectCode);
        model.addAttribute("classQuery", query == null ? "" : query);
        return VIEW_CLASS_MANAGE;
    }

    private static String normalizeTab(String tab) {
        if (TAB_PENDING.equalsIgnoreCase(tab)) return TAB_PENDING;
        if (TAB_REJECTED.equalsIgnoreCase(tab)) return TAB_REJECTED;
        if (TAB_ARCHIVED.equalsIgnoreCase(tab)) return TAB_ARCHIVED;
        return TAB_CURRENT;
    }

    private static List<String> statusesForTab(String tab) {
        return switch (tab) {
            case TAB_PENDING -> List.of(ClassEntity.STATUS_PENDING);
            case TAB_REJECTED -> List.of(ClassEntity.STATUS_REJECTED);
            case TAB_ARCHIVED -> List.of(ClassEntity.STATUS_ARCHIVED);
            default -> List.of(ClassEntity.STATUS_ACTIVE);
        };
    }

    /** Approves a pending student without forcing the owner into the Members tab. */
    public record SemesterGroup(String code, String name, List<ClassRow> rows,
                                int students, int lessons, int assignments, int materials) {}

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
        addCurrentSemester(model, semesterService.current());
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
            addCurrentSemester(model, semesterService.current());
            return VIEW_CLASS_FORM;
        }
        try {
            classesService.create(form, user.getId());
        } catch (IllegalArgumentException exception) {
            result.rejectValue("subjectId", "subject.invalid", exception.getMessage());
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            model.addAttribute(ATTR_FORM_ACTION, URL_CLASSES_LIST);
            addSubjectOptions(model);
            addCurrentSemester(model, semesterService.current());
            return VIEW_CLASS_FORM;
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_CREATED);
        return "redirect:" + URL_CLASSES_LIST + "?tab=" + TAB_CURRENT;
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
        addCurrentSemester(model, AcademicSemester.parse(entity.getSemester()));
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
            addCurrentSemester(model, AcademicSemester.parse(classesService
                    .getOwnerManaged(id, user.getId(), user.getRole()).getSemester()));
            return VIEW_CLASS_FORM;
        }
        classesService.update(id, form, user.getId(), user.getRole());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CLASS_UPDATED);
        return "redirect:" + URL_CLASSES_LIST;
    }

    /** Rejects obsolete review submissions without changing historical classes. */
    @PostMapping("/classes/{id}/resubmit")
    public String resubmitForReview(@PathVariable Long id,
                                    @AuthenticationPrincipal KshUserDetails user,
                                    RedirectAttributes ra) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE, "Quy trình duyệt lớp đã ngừng sử dụng");
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

    private static void addCurrentSemester(Model model, AcademicSemester semester) {
        model.addAttribute("currentSemesterCode", semester.code());
        model.addAttribute("currentSemesterLabel", semester.displayName());
    }
}
