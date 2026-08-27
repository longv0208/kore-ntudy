package com.ksh.features.classes.controller;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.controller.support.ClassDetailModelSupport;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.service.ClassMembersService;
import com.ksh.features.classes.service.ClassMaterialsService;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.classes.service.JoinClassService;
import com.ksh.features.tests.service.LecturerExamService;
import com.ksh.security.Roles;
import com.ksh.security.KshUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ksh.common.IConstant.*;
import static com.ksh.features.classes.controller.support.ClassDetailModelSupport.classUrl;
import static com.ksh.features.classes.controller.support.ClassDetailModelSupport.labelFor;

/**
 * Controller for the class detail screens (sidebar tabs introduced in Sprint 2 phase 2).
 * Split out of {@link ClassesController} during the file-size refactor so each controller
 * stays focused on a single responsibility.
 *
 * <p>Exposed endpoints:
 * <ul>
 *   <li>{@code GET  /lecturer/classes/{id}/board}    — legacy redirect to lessons</li>
 *   <li>{@code GET  /lecturer/classes/{id}/members}  — member list tab</li>
 *   <li>{@code GET  /lecturer/classes/{id}/settings} — class settings</li>
 *   <li>{@code GET  /lecturer/classes/{id}/schedule|roles|groups|...} — placeholder tabs</li>
 * </ul>
 *
 * <p>Authorization: class-level {@code @PreAuthorize} blocks STUDENT and anonymous
 * users. Ownership checks are enforced at the service layer using the
 * {@code (userId, role)} pair from {@link KshUserDetails}.
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassDetailController {

    private final ClassesService classesService;
    private final ClassMembersService classMembersService;
    private final ClassDetailModelSupport detailSupport;
    private final LecturerExamService examService;
    private final JoinClassService joinClassService;
    private final ClassMaterialsService classMaterialsService;

    public ClassDetailController(ClassesService classesService,
                                 ClassMembersService classMembersService,
                                 ClassDetailModelSupport detailSupport,
                                 LecturerExamService examService,
                                 JoinClassService joinClassService,
                                 ClassMaterialsService classMaterialsService) {
        this.classesService = classesService;
        this.classMembersService = classMembersService;
        this.detailSupport = detailSupport;
        this.examService = examService;
        this.joinClassService = joinClassService;
        this.classMaterialsService = classMaterialsService;
    }

    /** Retired board URL kept as a compatibility redirect. */
    @GetMapping("/classes/{id}/board")
    public String detailBoard(@PathVariable Long id) {
        return "redirect:" + classUrl(id) + "/lessons";
    }

    /** Renders the class members tab with ACTIVE members and PENDING requests. */
    @GetMapping("/classes/{id}/members")
    public String detailMembers(@PathVariable Long id,
                                @AuthenticationPrincipal KshUserDetails user,
                                Model model) {
        ClassMembersService.ClassMembersView view =
                classMembersService.listForClass(id, user.getId(), user.getRole());
        detailSupport.populateDetail(model, view.clazz(), TAB_MEMBERS,
                user.getId(), user.getRole());
        model.addAttribute(ATTR_MEMBERS, view.members());
        model.addAttribute(ATTR_MEMBER_TOTAL, view.total());
        model.addAttribute(ATTR_PENDING_MEMBERS, view.pendingMembers());
        model.addAttribute(ATTR_PENDING_TOTAL, view.pendingTotal());
        return VIEW_CLASS_DETAIL_MEMBERS;
    }

    /** Owner approves a PENDING join request. */
    @PostMapping("/classes/{id}/members/{userId}/approve")
    public String approveMember(@PathVariable Long id,
                                @PathVariable Long userId,
                                @AuthenticationPrincipal KshUserDetails user,
                                RedirectAttributes ra) {
        try {
            joinClassService.approve(id, userId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOIN_APPROVED);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_JOIN_APPROVE_FAILED + ex.getMessage());
        }
        return "redirect:" + classUrl(id) + "/" + TAB_MEMBERS;
    }

    /** Owner rejects a PENDING join request. */
    @PostMapping("/classes/{id}/members/{userId}/reject")
    public String rejectMember(@PathVariable Long id,
                               @PathVariable Long userId,
                               @AuthenticationPrincipal KshUserDetails user,
                               RedirectAttributes ra) {
        try {
            joinClassService.reject(id, userId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOIN_REJECTED);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_JOIN_REJECT_FAILED + ex.getMessage());
        }
        return "redirect:" + classUrl(id) + "/" + TAB_MEMBERS;
    }

    /** Renders the class exams tab — the exams belonging to this class. */
    @GetMapping("/classes/{id}/tests")
    public String detailTests(@PathVariable Long id,
                              @RequestParam(name = "page", defaultValue = "0") int page,
                              @AuthenticationPrincipal KshUserDetails user,
                              Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        detailSupport.populateDetail(model, clazz, TAB_TESTS, user.getId(), user.getRole());
        model.addAttribute(ATTR_EXAMS_PAGE,
                examService.listForClass(id, user.getId(), user.getRole(), page));
        return VIEW_CLASS_DETAIL_TESTS;
    }

    /**
     * Renders a placeholder view for class detail tabs not yet implemented (Sprint 3–5).
     * Handles: {@code /schedule}, {@code /roles}, {@code /groups}, {@code /assignments},
     * {@code /scores}.
     *
     * <p>Note: {@code /lessons} and {@code /assignments} are intentionally NOT mapped here —
     * they are owned by {@code SectionsController} and {@code LecturerAssignmentController}
     * respectively. Adding both mappings would raise
     * {@code IllegalStateException: Ambiguous mapping} at startup.
     */
    @GetMapping({"/classes/{id}/schedule", "/classes/{id}/roles",
                "/classes/{id}/groups"})
    public String detailPlaceholder(@PathVariable Long id,
                                    @AuthenticationPrincipal KshUserDetails user,
                                    jakarta.servlet.http.HttpServletRequest request,
                                    Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        // Extract the last URL segment as the active tab key (e.g. "schedule").
        String path = request.getRequestURI();
        String tab = path.substring(path.lastIndexOf('/') + 1);
        detailSupport.populateDetail(model, clazz, tab, user.getId(), user.getRole());
        model.addAttribute(ATTR_PLACEHOLDER_TAB, tab);
        model.addAttribute(ATTR_PLACEHOLDER_LABEL, labelFor(tab));
        return VIEW_CLASS_DETAIL_PLACEHOLDER;
    }

    /** Standalone class Materials tab; these files never become lesson content. */
    @GetMapping("/classes/{id}/materials")
    public String detailMaterials(@PathVariable Long id,
                                  @AuthenticationPrincipal KshUserDetails user,
                                  Model model) {
        ClassEntity clazz = classesService.getViewable(id, user.getId(), user.getRole());
        detailSupport.populateDetail(model, clazz, TAB_MATERIALS,
                user.getId(), user.getRole());
        model.addAttribute("classMaterials",
                classMaterialsService.listForTeaching(id, user.getId(), user.getRole()));
        return "classes/detail-materials";
    }

    /** Removes the class reference while retaining the personal-library file. */
    @PostMapping("/classes/{id}/materials/{materialId}/remove")
    public String removeMaterial(@PathVariable Long id,
                                 @PathVariable Long materialId,
                                 @AuthenticationPrincipal KshUserDetails user,
                                 RedirectAttributes ra) {
        try {
            classMaterialsService.remove(id, materialId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, "Đã gỡ tài liệu khỏi lớp");
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        }
        return "redirect:" + classUrl(id) + "/materials";
    }

    /**
     * Renders the class settings tab, reusing the edit-class form.
     * Only the immutable class owner (or ADMIN) may access this endpoint.
     *
     * <p>Invite settings were retired; this route only renders class info.
     */
    @GetMapping("/classes/{id}/settings")
    public String detailSettings(@PathVariable Long id,
                                 @RequestParam(defaultValue = SUBTAB_INFO) String tab,
                                 @AuthenticationPrincipal KshUserDetails user,
                                 Model model) {
        ClassEntity entity = classesService.getOwnerManaged(id, user.getId(), user.getRole());
        // Preserve flashed form values from a prior failed POST.
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, ClassForm.fromEntity(entity));
        }
        detailSupport.populateDetail(model, entity, TAB_SETTINGS, user.getId(), user.getRole());

        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, SUBTAB_INFO);

        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_FORM_ACTION, classUrl(id));
        model.addAttribute(ATTR_CLASS_ID, id);
        return VIEW_CLASS_DETAIL_SETTINGS;
    }
}
