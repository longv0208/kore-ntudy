package com.ksh.features.leader.controller;

import com.ksh.features.leader.dto.LeaderDtos.AssignView;
import com.ksh.features.leader.dto.LeaderDtos.DashboardView;
import com.ksh.features.leader.dto.LeaderDtos.ReportView;
import com.ksh.features.leader.service.LeaderDashboardService;
import com.ksh.features.leader.service.LeaderLecturerAssignmentService;
import com.ksh.features.leader.service.LeaderReportService;
import com.ksh.security.Roles;
import com.ksh.security.KshUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ksh.common.IConstant.*;

/**
 * LEADER product shell: dashboard, lecturer assignment, and subject report.
 */
@Controller
@RequestMapping(BASE_LEADER)
@PreAuthorize("hasRole('" + Roles.LEADER + "')")
public class LeaderController {

    private final LeaderDashboardService dashboardService;
    private final LeaderLecturerAssignmentService assignmentService;
    private final LeaderReportService reportService;

    public LeaderController(LeaderDashboardService dashboardService,
                          LeaderLecturerAssignmentService assignmentService,
                          LeaderReportService reportService) {
        this.dashboardService = dashboardService;
        this.assignmentService = assignmentService;
        this.reportService = reportService;
    }

    @GetMapping("/approvals")
    public String approvals(@AuthenticationPrincipal KshUserDetails user, Model model) {
        return "redirect:/leader";
    }

    @PostMapping("/approvals/{classId}/approve")
    public String approveClass(@PathVariable Long classId,
                               @AuthenticationPrincipal KshUserDetails user,
                               RedirectAttributes ra) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE, "Quy trình duyệt lớp đã ngừng sử dụng");
    }

    @PostMapping("/approvals/{classId}/reject")
    public String rejectClass(@PathVariable Long classId,
                              @RequestParam(required = false) String note,
                              @AuthenticationPrincipal KshUserDetails user,
                              RedirectAttributes ra) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE, "Quy trình duyệt lớp đã ngừng sử dụng");
    }

    @GetMapping({"", "/"})
    public String dashboard(@AuthenticationPrincipal KshUserDetails user, Model model) {
        DashboardView view = dashboardService.load(user.getId());
        model.addAttribute(ATTR_LEADER_SUBJECT, view.subject());
        model.addAttribute(ATTR_LEADER_KPIS, view.kpis());
        model.addAttribute(ATTR_LEADER_RECENT, view.recentClasses());
        model.addAttribute(ATTR_LEADER_EMPTY, view.emptySubject());
        model.addAttribute(ATTR_ACTIVE_TAB, "dashboard");
        return VIEW_LEADER_DASHBOARD;
    }

    @GetMapping("/assign")
    public String assign(@AuthenticationPrincipal KshUserDetails user, Model model) {
        AssignView view = assignmentService.load(user.getId());
        model.addAttribute(ATTR_LEADER_SUBJECT, view.subject());
        model.addAttribute(ATTR_LEADER_CLASS_ROWS, view.classRows());
        model.addAttribute(ATTR_LEADER_LECTURERS, view.lecturers());
        model.addAttribute(ATTR_LEADER_EMPTY, view.emptySubject());
        model.addAttribute(ATTR_ACTIVE_TAB, "assign");
        return VIEW_LEADER_ASSIGN;
    }

    @PostMapping("/assign/{classId}")
    public String reassign(@PathVariable Long classId,
                           @RequestParam(name = "lecturerIds", required = false)
                           java.util.List<Long> lecturerIds,
                           @RequestParam(name = "lecturerId", required = false)
                           Long legacyLecturerId,
                           @AuthenticationPrincipal KshUserDetails user,
                           RedirectAttributes ra) {
        try {
            java.util.List<Long> selected = lecturerIds;
            if ((selected == null || selected.isEmpty()) && legacyLecturerId != null) {
                selected = java.util.List.of(legacyLecturerId);
            }
            String className = assignmentService.updateCoLecturers(
                    user.getId(), classId, selected);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    "Đã cập nhật nhóm đồng giảng cho lớp " + className);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        // AccessDeniedException / EntityNotFoundException bubble to global handler (403/404).
        return "redirect:" + URL_LEADER_ASSIGN;
    }

    @GetMapping("/report")
    public String report(@AuthenticationPrincipal KshUserDetails user, Model model) {
        ReportView view = reportService.load(user.getId());
        model.addAttribute(ATTR_LEADER_SUBJECT, view.subject());
        model.addAttribute(ATTR_LEADER_REPORT_ROWS, view.rows());
        model.addAttribute(ATTR_LEADER_EMPTY, view.emptySubject());
        model.addAttribute(ATTR_ACTIVE_TAB, "report");
        return VIEW_LEADER_REPORT;
    }

    // The subject question bank management screen is served by
    // LeaderQuestionBankController at /leader/question-bank; no handler here.

}
