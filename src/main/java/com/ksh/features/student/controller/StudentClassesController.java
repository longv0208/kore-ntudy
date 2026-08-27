package com.ksh.features.student.controller;

import com.ksh.security.KshUserDetails;
import com.ksh.security.Roles;
import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.service.JoinClassService;
import com.ksh.features.student.dto.StudentClassesDtos.EnrolledClassRow;
import com.ksh.features.student.service.StudentClassesService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
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

import java.util.List;

import static com.ksh.common.IConstant.*;

/**
 * Student-facing controller for the {@code /my/classes} surface.
 *
 * <p>This is a learner-only surface. Elevated roles must use their own class
 * management routes instead of entering the student enrollment flow.
 */
@Controller
@RequestMapping("/my")
@PreAuthorize(Roles.PREAUTH_STUDENT)
public class StudentClassesController {

    private static final String VIEW_MY_CLASSES = "student/my-classes";
    private static final String REDIRECT_MY_CLASSES = "redirect:/my/classes";
    private static final String ATTR_ROWS = "rows";
    private static final String MSG_LEFT_CLASS = "Đã rời lớp ";
    private static final String MSG_CANNOT_LEAVE_DONE = "Không thể rời lớp đã hoàn thành";

    private final StudentClassesService studentClassesService;
    private final JoinClassService joinClassService;

    public StudentClassesController(StudentClassesService studentClassesService,
                                    JoinClassService joinClassService) {
        this.studentClassesService = studentClassesService;
        this.joinClassService = joinClassService;
    }

    /** Lists ACTIVE enrollments and PENDING join requests. */
    @GetMapping("/classes")
    public String list(@AuthenticationPrincipal KshUserDetails user,
                       @RequestParam(name = "q", required = false) String query,
                       @RequestParam(name = "tab", defaultValue = "mine") String tab,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       Model model) {
        List<EnrolledClassRow> rows = studentClassesService.listEnrolledClasses(user.getId());
        List<EnrolledClassRow> pending = studentClassesService.listPendingClasses(user.getId());
        String activeTab = "open".equalsIgnoreCase(tab) ? "open" : "mine";
        model.addAttribute(ATTR_ROWS, rows);
        model.addAttribute(ATTR_PENDING_ROWS, pending);
        var catalogPage = "open".equals(activeTab)
                ? studentClassesService.listActiveCatalog(user.getId(), query, page, 25)
                : org.springframework.data.domain.Page.empty();
        model.addAttribute("catalogPage", catalogPage);
        model.addAttribute("catalogRows", catalogPage.getContent());
        model.addAttribute("catalogQuery", query == null ? "" : query);
        model.addAttribute("classesTab", activeTab);
        return VIEW_MY_CLASSES;
    }

    @PostMapping("/classes/{id}/leave")
    public String leave(@PathVariable Long id,
                        @AuthenticationPrincipal KshUserDetails user,
                        RedirectAttributes ra) {
        try {
            ClassEntity clazz = joinClassService.leave(id, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LEFT_CLASS + clazz.getName());
            return REDIRECT_MY_CLASSES;
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_CANNOT_LEAVE_DONE);
            return REDIRECT_MY_CLASSES;
        }
    }

}
