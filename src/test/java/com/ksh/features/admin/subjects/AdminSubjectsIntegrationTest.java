package com.ksh.features.admin.subjects;

import com.ksh.entities.Subject;
import com.ksh.entities.SubjectActivity;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectActivityRepository;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@code /admin/subjects}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminSubjectsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private SubjectActivityRepository activityRepository;
    @Autowired private UserRepository userRepository;

    @org.junit.jupiter.api.BeforeEach
    void createIsolatedSubjectFixtures() {
        for (String code : java.util.List.of("KT", "CK", "DDT")) {
            if (!subjectRepository.existsByCode(code)) {
                subjectRepository.saveAndFlush(new Subject("Test " + code, code, "Test fixture", true));
            }
        }
        // These catalog entries are test data, not assumptions about production seeds.
        if (!subjectRepository.existsByCode("CNTT")) {
            subjectRepository.saveAndFlush(new Subject("Công nghệ thông tin", "CNTT", "Test fixture", true));
        }
        if (!subjectRepository.existsByCode("NN")) {
            subjectRepository.saveAndFlush(new Subject("Ngoại ngữ", "NN", "Test fixture", true));
        }
        for (Subject subject : subjectRepository.findAll()) {
            if (subject.getLeaderUserId() != null) {
                subject.assignLeader(null);
                subjectRepository.save(subject);
            }
        }
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void list_renders_seeded_subjects() throws Exception {
        mockMvc.perform(get("/admin/subjects"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/subjects"))
                .andExpect(model().attribute("activeTab", "subjects"))
                .andExpect(content().string(containsString("Công nghệ thông tin")));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void list_name_links_to_edit() throws Exception {
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(get("/admin/subjects"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("subject-name-link")))
                .andExpect(content().string(containsString(
                        "/admin/subjects/" + subject.getId() + "/edit")));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void edit_form_shows_info_and_history_tabs() throws Exception {
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(get("/admin/subjects/" + subject.getId() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/subjects-form"))
                .andExpect(model().attribute("activeDetailTab", "info"))
                .andExpect(content().string(containsString("Thông tin chung")))
                .andExpect(content().string(containsString("Lịch sử cập nhật")))
                .andExpect(content().string(containsString("id=\"tabPanel\"")))
                .andExpect(content().string(containsString("/js/detail-tabs.js")))
                .andExpect(content().string(containsString("subject-status-toggle")));

        mockMvc.perform(get("/admin/subjects/" + subject.getId() + "/edit")
                        .param("tab", "history"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "history"))
                .andExpect(model().attributeExists("activitiesPage"))
                .andExpect(content().string(containsString("Lịch sử cập nhật môn học")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void list_returns_403_for_non_admin() throws Exception {
        mockMvc.perform(get("/admin/subjects"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void create_persists_unique_subject() throws Exception {
        mockMvc.perform(post("/admin/subjects").with(csrf())
                        .param("name", "Khoa học máy tính")
                        .param("code", "khmt")
                        .param("description", "Test")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/subjects"))
                .andExpect(flash().attributeExists("flashSuccess"));

        Subject saved = subjectRepository.findAll().stream()
                .filter(d -> "KHMT".equals(d.getCode()))
                .findFirst().orElseThrow();
        assertThat(saved.getName()).isEqualTo("Khoa học máy tính");
        assertThat(saved.isActive()).isTrue();

        assertThat(activityRepository.findAll()).anyMatch(a ->
                saved.getId().equals(a.getSubjectId())
                        && SubjectActivity.TYPE_CREATED.equals(a.getType()));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void create_duplicate_code_rejected() throws Exception {
        mockMvc.perform(post("/admin/subjects").with(csrf())
                        .param("name", "Duplicate CNTT")
                        .param("code", "CNTT")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/subjects-form"))
                .andExpect(model().attributeExists("flashError"));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void create_blank_name_field_error() throws Exception {
        mockMvc.perform(post("/admin/subjects").with(csrf())
                        .param("name", "")
                        .param("code", "NEWX")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/subjects-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void assign_lecturer_promotes_to_leader() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/edit").with(csrf())
                        .param("name", subject.getName())
                        .param("code", subject.getCode())
                        .param("description", subject.getDescription() == null ? "" : subject.getDescription())
                        .param("active", "true")
                        .param("leaderUserId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/subjects/*/edit?tab=info"))
                .andExpect(flash().attributeExists("flashSuccess"));

        Subject updated = subjectRepository.findById(subject.getId()).orElseThrow();
        User promoted = userRepository.findById(lecturer.getId()).orElseThrow();
        assertThat(updated.getLeaderUserId()).isEqualTo(lecturer.getId());
        assertThat(promoted.getRole()).isEqualTo(Role.LEADER);
        assertThat(promoted.getSubjectId()).isEqualTo(subject.getId());
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void reject_student_as_leader() throws Exception {
        User student = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "NN".equals(d.getCode()))
                .findFirst().orElseThrow();
        Long previousLeader = subject.getLeaderUserId();

        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/edit").with(csrf())
                        .param("name", subject.getName())
                        .param("code", subject.getCode())
                        .param("active", "true")
                        .param("leaderUserId", String.valueOf(student.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/subjects-form"))
                .andExpect(model().attributeExists("flashError"));

        Subject unchanged = subjectRepository.findById(subject.getId()).orElseThrow();
        assertThat(unchanged.getLeaderUserId()).isEqualTo(previousLeader);
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void replace_leader_demotes_previous() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        User leader = userRepository.findByEmailIgnoreCase("leader@ksh.edu.vn").orElseThrow();
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        // Ensure leader is currently leader of CNTT (seed may already set this).
        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/edit").with(csrf())
                        .param("name", subject.getName())
                        .param("code", subject.getCode())
                        .param("active", "true")
                        .param("leaderUserId", String.valueOf(leader.getId())))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/edit").with(csrf())
                        .param("name", subject.getName())
                        .param("code", subject.getCode())
                        .param("active", "true")
                        .param("leaderUserId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection());

        User previous = userRepository.findById(leader.getId()).orElseThrow();
        User next = userRepository.findById(lecturer.getId()).orElseThrow();
        Subject updated = subjectRepository.findById(subject.getId()).orElseThrow();
        assertThat(updated.getLeaderUserId()).isEqualTo(lecturer.getId());
        assertThat(next.getRole()).isEqualTo(Role.LEADER);
        // Previous leader demoted only if not leader of any other subject.
        if (!subjectRepository.existsByLeaderUserId(previous.getId())) {
            assertThat(previous.getRole()).isEqualTo(Role.LECTURER);
        }
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void unassign_leader_demotes_when_no_other_subject() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "CK".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/edit").with(csrf())
                        .param("name", subject.getName())
                        .param("code", subject.getCode())
                        .param("active", "true")
                        .param("leaderUserId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/edit").with(csrf())
                        .param("name", subject.getName())
                        .param("code", subject.getCode())
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        Subject updated = subjectRepository.findById(subject.getId()).orElseThrow();
        User demoted = userRepository.findById(lecturer.getId()).orElseThrow();
        assertThat(updated.getLeaderUserId()).isNull();
        assertThat(demoted.getRole()).isEqualTo(Role.LECTURER);
        assertThat(demoted.getSubjectId()).isEqualTo(subject.getId());
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void toggle_deactivates_subject() throws Exception {
        Subject subject = subjectRepository.findAll().stream()
                .filter(d -> "DDT".equals(d.getCode()))
                .findFirst().orElseThrow();
        boolean wasActive = subject.isActive();

        mockMvc.perform(post("/admin/subjects/" + subject.getId() + "/toggle").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        Subject updated = subjectRepository.findById(subject.getId()).orElseThrow();
        assertThat(updated.isActive()).isEqualTo(!wasActive);
    }
}
