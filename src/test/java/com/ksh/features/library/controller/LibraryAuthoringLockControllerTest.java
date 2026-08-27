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
                .andExpect(content().string(containsString("Khóa biên soạn")));

        mockMvc.perform(post("/lecturer/library/templates/subjects/{subjectId}/lock",
                        subject.getId())
                        .with(csrf())
                        .param("locked", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/library/templates?subjectId=" + subject.getId()));

        mockMvc.perform(get("/lecturer/library/templates")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mở khóa biên soạn")))
                .andExpect(content().string(containsString("chế độ chỉ đọc")))
                .andExpect(content().string(not(containsString(">Tạo bài học<"))));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturer_sees_read_only_state_without_lock_or_edit_controls() throws Exception {
        subject.setLibraryLocked(true);
        departmentRepository.saveAndFlush(subject);

        mockMvc.perform(get("/lecturer/library/templates")
                        .param("subjectId", subject.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Trưởng bộ môn đã khóa phiên bản hiện tại")))
                .andExpect(content().string(not(containsString("Mở khóa biên soạn"))))
                .andExpect(content().string(not(containsString("Khóa biên soạn"))))
                .andExpect(content().string(not(containsString("data-inline-edit"))));
    }
}
