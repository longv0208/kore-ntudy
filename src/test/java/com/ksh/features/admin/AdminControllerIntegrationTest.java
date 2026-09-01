package com.ksh.features.admin;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test cho man hinh quan tri he thong (Admin).
 * Cover: auth guards + dashboard render + placeholder tabs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ───────────────────── Auth guards ─────────────────────────────

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void admin_root_redirects_to_dashboard() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    @Test
    void admin_anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void admin_student_forbidden() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void admin_lecturer_forbidden() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void admin_leader_forbidden() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isForbidden());
    }

    // ───────────────────── Dashboard render ────────────────────────

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void admin_dashboard_renders_with_stats() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bảng điều khiển hệ thống")))
                .andExpect(content().string(containsString("Tài khoản hoạt động")))
                .andExpect(content().string(containsString("Lớp học")))
                .andExpect(content().string(containsString("Phân bố vai trò")))
                .andExpect(content().string(containsString("Lớp học mới tạo")));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void admin_sidebar_places_semesters_after_dashboard_and_removes_redundant_classes_tab()
            throws Exception {
        String html = mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var document = Jsoup.parse(html);
        assertThat(document.select(".side-nav > li > a").eachText())
                .startsWith("Dashboard", "Học kỳ", "Tài khoản");
        assertThat(document.select("a[href$='/admin/semesters'] svg.ico")).hasSize(1);
        assertThat(document.select("a[href$='/admin/classes']")).isEmpty();
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void redundant_admin_classes_placeholder_is_not_exposed() throws Exception {
        mockMvc.perform(get("/admin/classes"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────── Placeholder tabs ────────────────────────

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void admin_users_placeholder_renders() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tài khoản")));
    }

    // ───────────────────── Settings index ──────────────────────────

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void admin_settings_index_renders() throws Exception {
        mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cài đặt hệ thống")))
                .andExpect(content().string(containsString("Email")))
                // Spec requires Email entry to LINK to /admin/settings/email,
                // not just appear as text.
                .andExpect(content().string(containsString("/admin/settings/email")));
    }
}
