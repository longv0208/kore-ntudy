package com.ksh.features.classes.controller;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercise actual Thymeleaf fragments and repository queries, not just source-text contracts. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClassWorkspaceRenderIntegrationTest {
    @Autowired private MockMvc mvc;

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void admin_workspace_renders_metrics_semester_groups_and_filter_form() throws Exception {
        assertWorkspace("/lecturer/classes");
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_workspace_renders_with_scoped_counts() throws Exception {
        assertWorkspace("/lecturer/classes");
        assertWorkspace("/lecturer/classes?tab=pending");
        assertWorkspace("/lecturer/classes?tab=rejected");
        assertWorkspace("/lecturer/classes?tab=archived");
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_workspace_exposes_four_real_lifecycle_tabs() throws Exception {
        String html = mvc.perform(get("/lecturer/classes")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(html);
        assertThat(document.select(".cw-view-tabs a")).extracting(element -> element.ownText().trim())
                .containsExactly("Lớp của bạn", "Chờ phê duyệt", "Bị từ chối", "Đã lưu trữ");
        assertThat(document.select(".cw-view-tabs a[href*='tab=current']")).hasSize(1);
        assertThat(document.select(".cw-view-tabs a[href*='tab=pending']")).hasSize(1);
        assertThat(document.select(".cw-view-tabs a[href*='tab=rejected']")).hasSize(1);
        assertThat(document.select(".cw-view-tabs a[href*='tab=archived']")).hasSize(1);
    }

    @Test
    @WithUserDetails("kor_leader@ksh.edu.vn")
    void multi_subject_leader_workspace_renders_and_searches_teacher_names() throws Exception {
        assertWorkspace("/lecturer/classes");
        assertWorkspace("/lecturer/classes?q=Nguy%E1%BB%85n");
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void student_workspace_renders_membership_and_catalog_views() throws Exception {
        assertWorkspace("/my/classes");
        assertWorkspace("/my/classes?tab=archived");
        assertWorkspace("/my/classes?tab=open");
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void semester_admin_renders_lifecycle_controls() throws Exception {
        String html = mvc.perform(get("/admin/semesters")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(html);
        assertThat(document.select("form[action$='/transition']")).hasSize(1);
        assertThat(document.select("form[action$='/schedule']")).hasSize(1);
        assertThat(document.select("input[name=code], input[name=start]")).isEmpty();
    }

    private void assertWorkspace(String path) throws Exception {
        String html = mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(html);
        assertThat(document.select(".cw-controls")).hasSize(1);
        assertThat(document.select(".cw-semester-tabs")).hasSize(1);
        assertThat(document.select("form[role=search] input[name=q]")).hasSize(1);
        assertThat(document.select("#viewToggle, a[href$='/trash'], button[data-action=toggle-grid]")).isEmpty();
        assertThat(document.select("link[href$='/css/class-workspace.css']")).hasSize(1);
    }
}
