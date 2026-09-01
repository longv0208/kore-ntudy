package com.ksh.features.library.controller;

import com.ksh.entities.Department;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Render and endpoint contracts for the Subject Leader authoring lock. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LibraryAuthoringLockControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private com.ksh.features.library.repository.LessonTemplateRepository templateRepository;

    private Department subject;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        subject = departmentRepository.findById(lecturer.getSubjectId()).orElseThrow();
        subject.setLibraryLocked(false);
        departmentRepository.saveAndFlush(subject);
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void assigned_leader_sees_lock_button_and_can_lock_subject() throws Exception {
        mockMvc.perform(get("/lecturer/library/templates")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khóa thêm tài nguyên GV")));

        mockMvc.perform(post("/lecturer/library/templates/subjects/{subjectId}/lock",
                        subject.getId())
                        .with(csrf())
                        .param("locked", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/library/templates?subjectId=" + subject.getId()));

        mockMvc.perform(get("/lecturer/library/templates")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cho phép GV thêm tài nguyên")))
                .andExpect(content().string(containsString(">Tạo bài học<")))
                .andExpect(content().string(containsString("data-inline-edit")))
                .andExpect(content().string(not(containsString("structure-lock"))));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_sees_read_only_state_without_lock_or_edit_controls() throws Exception {
        subject.setLibraryLocked(true);
        departmentRepository.saveAndFlush(subject);

        mockMvc.perform(get("/lecturer/library/templates")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("library-lock-btn"))))
                .andExpect(content().string(not(containsString(">Tạo bài học<"))))
                .andExpect(content().string(not(containsString("data-inline-edit"))));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void unlocked_lecturer_gets_only_resource_form_not_syllabus_editor() throws Exception {
        var lesson = templateRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId()).get(0);
        mockMvc.perform(get("/lecturer/library/templates/{id}/edit", lesson.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-resource-only=\"true\"")))
                .andExpect(content().string(containsString("Thêm tài nguyên")))
                .andExpect(content().string(not(containsString("id=\"libraryFormTabContent\""))))
                .andExpect(content().string(not(containsString("id=\"libraryFormTabVideo\""))));
        mockMvc.perform(get("/lecturer/library/templates/new")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("kor_leader@ksh.edu.vn")
    void kor_leader_sees_authoring_and_drag_controls_even_when_locked() throws Exception {
        subject.setLibraryLocked(true);
        departmentRepository.saveAndFlush(subject);
        mockMvc.perform(get("/lecturer/library/templates")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cho phép GV thêm tài nguyên")))
                .andExpect(content().string(containsString("data-inline-edit")))
                .andExpect(content().string(containsString("library-lesson-drag-handle")))
                .andExpect(content().string(containsString("library-drag-handle")))
                .andExpect(content().string(containsString(">Tạo bài học<")));
    }
}
