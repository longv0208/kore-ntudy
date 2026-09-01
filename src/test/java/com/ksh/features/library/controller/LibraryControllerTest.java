package com.ksh.features.library.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** MockMvc contracts for Library-owned authoring and material inventory. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LibraryControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void anonymous_library_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/library"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void student_is_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/library"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymous_personal_asset_picker_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/library/assets/api"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void student_cannot_browse_lecturer_personal_assets() throws Exception {
        mockMvc.perform(get("/lecturer/library/assets/api"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_personal_asset_picker_is_owner_scoped_json() throws Exception {
        mockMvc.perform(get("/lecturer/library/assets/api")
                        .param("kind", "DOCUMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(12));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_personal_asset_inventory_renders_format_icons_and_right_detail_panel()
            throws Exception {
        mockMvc.perform(get("/lecturer/library/assets"))
                .andExpect(status().isOk())
                .andExpect(view().name("library/assets"))
                .andExpect(content().string(containsString("personal-library-hero.png")))
                .andExpect(content().string(containsString("data-library-detail-panel")))
                .andExpect(content().string(containsString("Đang sử dụng")))
                .andExpect(content().string(containsString("Gần đây")));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_library_page_uses_subject_lesson_flow_without_loose_attach_ui()
            throws Exception {
        mockMvc.perform(get("/lecturer/library/templates"))
                .andExpect(status().isOk())
                .andExpect(view().name("library/index"))
                .andExpect(content().string(not(containsString(">Tạo bài học<"))))
                .andExpect(content().string(containsString("mã môn")))
                .andExpect(content().string(not(containsString("libraryAttachWizard"))))
                .andExpect(content().string(not(containsString("Thêm vào lớp"))))
                .andExpect(content().string(not(containsString("Gắn vào lớp"))));
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void root_redirects_to_canonical_lessons_and_form_owns_uploads() throws Exception {
        mockMvc.perform(get("/lecturer/library"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/library/list"));

        mockMvc.perform(get("/lecturer/library/templates/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("library/lesson-form"))
                .andExpect(content().string(containsString("multipart/form-data")))
                .andExpect(content().string(containsString("Trình soạn thảo nội dung")))
                .andExpect(content().string(containsString("Hoặc link video YouTube/Vimeo")))
                .andExpect(content().string(containsString("Kéo thả file vào đây")));
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void library_selector_keeps_wide_hero_and_filters_without_semester_contract() throws Exception {
        mockMvc.perform(get("/lecturer/library/list"))
                .andExpect(status().isOk())
                .andExpect(view().name("library/list-library"))
                .andExpect(content().string(containsString("library-list-hero")))
                .andExpect(content().string(containsString("library-list-illustration")))
                .andExpect(content().string(containsString("library-filter-bar")))
                .andExpect(content().string(not(containsString("data-library-semester-filter"))))
                .andExpect(content().string(containsString("data-library-status-filter")))
                .andExpect(content().string(containsString("data-library-subject-filter")))
                .andExpect(content().string(containsString("data-lucide-icon=\"graduation-cap\"")))
                .andExpect(content().string(containsString("Xóa bộ lọc")));
    }
}
