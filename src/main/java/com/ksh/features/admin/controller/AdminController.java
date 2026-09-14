package com.ksh.features.admin.controller;

import com.ksh.features.admin.dto.AdminDashboardDtos.DashboardStats;
import com.ksh.features.admin.dto.AdminDashboardDtos.RecentClass;
import com.ksh.features.admin.dto.AdminDashboardDtos.UserRoleCount;
import com.ksh.features.admin.service.AdminDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static com.ksh.common.IConstant.*;

/**
 * MVC controller for the system administration panel.
 * Access is restricted to the {@code ADMIN} role only — may be relaxed to include
 * {@code LEADER} in a future sprint once per-subject dashboards are available.
 *
 * <p>URL pattern: {@code /admin/{tab}}:
 * <ul>
 *   <li>{@code /dashboard} — platform statistics, role breakdown chart, and recent
 *       classes (Sprint 2 wireframe).</li>
 *   <li>{@code /settings}  — settings index page (links to Email, General, etc.).</li>
 *   <li>{@code /subjects} — handled by
 *       {@link com.ksh.features.admin.subjects.controller.AdminSubjectsController}.</li>
 * </ul>
 *
 * <p>The {@code /admin/settings/email} sub-tab is handled by
 * {@link com.ksh.features.admin.settings.controller.EmailSettingsController}.
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasAuthority('PERM_dashboard.system')")
public class AdminController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String REDIRECT_DASHBOARD = "redirect:/admin/dashboard";

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_DASHBOARD   = "admin/dashboard";
    private static final String VIEW_SETTINGS    = "admin/settings";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_STATS           = "stats";
    private static final String ATTR_ROLES_BREAKDOWN = "rolesBreakdown";
    private static final String ATTR_RECENT_CLASSES  = "recentClasses";

    /** Recent-classes panel size on the dashboard. */
    private static final int RECENT_CLASSES_LIMIT = 5;

    private final AdminDashboardService dashboardService;

    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** Redirects /admin to the default dashboard tab. */
    @GetMapping
    public String root() {
        return REDIRECT_DASHBOARD;
    }

    /** Renders the admin dashboard with platform stats and recent classes. */
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        DashboardStats stats = dashboardService.stats();
        List<UserRoleCount> rolesBreakdown = dashboardService.usersByRole();
        List<RecentClass> recentClasses = dashboardService.recentClasses(RECENT_CLASSES_LIMIT);

        model.addAttribute(ATTR_STATS, stats);
        model.addAttribute(ATTR_ROLES_BREAKDOWN, rolesBreakdown);
        model.addAttribute(ATTR_RECENT_CLASSES, recentClasses);
        populateSidebar(model, TAB_DASHBOARD);
        return VIEW_DASHBOARD;
    }

    /** Renders the settings index page (links to Email, OAuth, General sub-tabs). */
    @GetMapping("/settings")
    public String settingsIndex(Model model) {
        populateSidebar(model, TAB_SETTINGS);
        return VIEW_SETTINGS;
    }

    private void populateSidebar(Model model, String activeTab) {
        model.addAttribute(ATTR_ACTIVE_TAB, activeTab);
    }
}
