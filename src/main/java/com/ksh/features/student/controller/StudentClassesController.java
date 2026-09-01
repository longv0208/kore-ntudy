package com.ksh.features.student.controller;

import com.ksh.security.KshUserDetails;
import com.ksh.security.Roles;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.features.classes.semester.AcademicSemester;
import com.ksh.features.student.dto.StudentClassesDtos.CatalogClassRow;
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
                       @RequestParam(name = "semester", defaultValue = "") String semester,
                       @RequestParam(name = "subjectCode", defaultValue = "") String subjectCode,
                       Model model) {
        List<EnrolledClassRow> workspaceRows = studentClassesService.listWorkspaceClasses(
                user.getId(), query, semester, subjectCode);
        String activeTab = "open".equalsIgnoreCase(tab) ? "open"
                : "archived".equalsIgnoreCase(tab) ? "archived" : "mine";
        List<EnrolledClassRow> displayedRows = workspaceRows.stream()
                .filter(row -> row.archived() == "archived".equals(activeTab)).toList();
        List<EnrolledClassRow> rows = displayedRows.stream()
                .filter(row -> Enrollment.STATUS_ACTIVE.equals(row.status())
                        || Enrollment.STATUS_COMPLETED.equals(row.status())).toList();
        List<EnrolledClassRow> pending = displayedRows.stream()
                .filter(row -> Enrollment.STATUS_PENDING.equals(row.status())).toList();
        model.addAttribute(ATTR_ROWS, rows);
        model.addAttribute(ATTR_PENDING_ROWS, pending);
        org.springframework.data.domain.Page<CatalogClassRow> catalogPage = "open".equals(activeTab)
                ? studentClassesService.listActiveCatalog(
                        user.getId(), query, semester, subjectCode, page, 25)
                : org.springframework.data.domain.Page.empty();
        model.addAttribute("catalogPage", catalogPage);
        model.addAttribute("catalogRows", catalogPage.getContent());
        model.addAttribute("semesterGroups", groupRows(displayedRows, EnrolledClassRow::semester));
        model.addAttribute("catalogSemesterGroups", groupRows(catalogPage.getContent(), CatalogClassRow::semester));
        model.addAttribute("classOverview", "open".equals(activeTab)
                ? studentClassesService.catalogOverview(query, semester, subjectCode)
                : studentClassesService.workspaceOverview(workspaceRows));
        model.addAttribute("currentClassCount", workspaceRows.stream().filter(row -> !row.archived()).count());
        model.addAttribute("archivedClassCount", workspaceRows.stream().filter(EnrolledClassRow::archived).count());
        model.addAttribute("catalogQuery", query == null ? "" : query);
        model.addAttribute("classesTab", activeTab);
        model.addAttribute("semesterOptions", studentClassesService.semesterOptions());
        model.addAttribute("subjectOptions", studentClassesService.subjectOptions());
        model.addAttribute("selectedSemester", semester == null ? "" : semester.toUpperCase());
        model.addAttribute("selectedSubjectCode", subjectCode == null ? "" : subjectCode);
        return VIEW_MY_CLASSES;
    }

    public record SemesterGroup<T>(String code, String name, List<T> rows) {}

    private static <T> List<SemesterGroup<T>> groupRows(List<T> rows,
                                                       java.util.function.Function<T, String> semester) {
        java.util.Map<String, List<T>> groups = new java.util.TreeMap<>((a, b) ->
                AcademicSemester.parse(b).compareTo(AcademicSemester.parse(a)));
        rows.forEach(row -> groups.computeIfAbsent(semester.apply(row),
                ignored -> new java.util.ArrayList<>()).add(row));
        return groups.entrySet().stream().map(entry -> new SemesterGroup<>(entry.getKey(),
                AcademicSemester.parse(entry.getKey()).displayName(), entry.getValue())).toList();
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
