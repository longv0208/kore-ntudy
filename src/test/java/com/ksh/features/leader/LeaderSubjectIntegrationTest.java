package com.ksh.features.leader;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for LEADER shell, dashboard, assignment, and report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeaderSubjectIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassCoLecturerRepository coLecturerRepository;

    private Subject cntt;
    private User leader;
    private User lecturer;

    @BeforeEach
    void setUp() {
        if (!subjectRepository.existsByCode("KT")) {
            subjectRepository.saveAndFlush(new Subject("Kinh tế", "KT", "Test fixture", true));
        }
        leader = userRepository.findByEmailIgnoreCase("leader@ksh.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        // Isolate the governed portfolio from unrelated seeded subjects.
        subjectRepository.findAll().stream()
                .filter(subject -> leader.getId().equals(subject.getLeaderUserId()))
                .forEach(subject -> {
                    subject.assignLeader(null);
                    subjectRepository.save(subject);
                });
        cntt = subjectRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseGet(() -> subjectRepository.saveAndFlush(
                        new Subject("Công nghệ thông tin", "CNTT", "Test fixture", true)));

        // Ensure LEADER resolution via leader_user_id.
        cntt.applyEdit(cntt.getName(), cntt.getCode(), cntt.getDescription(), true);
        cntt.assignLeader(leader.getId());
        subjectRepository.save(cntt);
        leader.promoteToLeader(cntt.getId());
        userRepository.save(leader);

        lecturer.setSubjectId(cntt.getId());
        userRepository.save(lecturer);
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void dashboard_ok_for_leader() throws Exception {
        mockMvc.perform(get("/leader"))
                .andExpect(status().isOk())
                .andExpect(view().name("leader/dashboard"))
                .andExpect(content().string(containsString("Dashboard môn học")));
    }

    @Test
    @WithUserDetails(value = "student@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void dashboard_403_for_student() throws Exception {
        mockMvc.perform(get("/leader"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void dashboard_lists_only_subject_classes() throws Exception {
        ClassEntity inSubject = new ClassEntity(
                "Lớp CNTT Leader", leader.getId(), leader.getId(),
                "desc", null, null, 50);
        inSubject.setCode("HCN01");
        inSubject.setSubjectId(cntt.getId());
        classRepository.save(inSubject);

        Subject other = subjectRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();
        ClassEntity outSubject = new ClassEntity(
                "Lớp KT Outside", lecturer.getId(), lecturer.getId(),
                "desc", null, null, 50);
        outSubject.setCode("HKT01");
        outSubject.setSubjectId(other.getId());
        classRepository.save(outSubject);

        mockMvc.perform(get("/leader"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lớp CNTT Leader")))
                .andExpect(content().string(not(containsString("Lớp KT Outside"))));
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void assign_page_lists_subject_classes() throws Exception {
        ClassEntity inSubject = new ClassEntity(
                "Lớp Assign", leader.getId(), leader.getId(),
                "desc", null, null, 50);
        inSubject.setCode("HAS01");
        inSubject.setSubjectId(cntt.getId());
        classRepository.save(inSubject);

        mockMvc.perform(get("/leader/assign"))
                .andExpect(status().isOk())
                .andExpect(view().name("leader/assign"))
                .andExpect(content().string(containsString("Lớp Assign")));
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void assign_page_and_submit_allow_an_active_lecturer_from_another_subject() throws Exception {
        Subject other = subjectRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();
        lecturer.setSubjectId(other.getId());
        userRepository.saveAndFlush(lecturer);

        ClassEntity inSubject = new ClassEntity(
                "Lớp cần đồng giảng", leader.getId(), leader.getId(),
                "desc", null, null, 50);
        inSubject.setCode("HXL01");
        inSubject.setSubjectId(cntt.getId());
        ClassEntity saved = classRepository.saveAndFlush(inSubject);

        mockMvc.perform(get("/leader/assign"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(lecturer.getEmail())));

        mockMvc.perform(post("/leader/assign/" + saved.getId()).with(csrf())
                        .param("lecturerId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(coLecturerRepository.existsByClassIdAndLecturerId(
                saved.getId(), lecturer.getId())).isTrue();
        assertThat(classRepository.findById(saved.getId()).orElseThrow().getLecturerId())
                .isEqualTo(leader.getId());
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void add_co_lecturer_same_subject_preserves_owner() throws Exception {
        ClassEntity inSubject = new ClassEntity(
                "Lớp Reassign", leader.getId(), leader.getId(),
                "desc", null, null, 50);
        inSubject.setCode("HRS01");
        inSubject.setSubjectId(cntt.getId());
        ClassEntity saved = classRepository.save(inSubject);

        mockMvc.perform(post("/leader/assign/" + saved.getId()).with(csrf())
                        .param("lecturerId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        ClassEntity updated = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getLecturerId()).isEqualTo(leader.getId());
        assertThat(updated.getCreatedBy()).isEqualTo(leader.getId());
        assertThat(updated.getSubjectId()).isEqualTo(cntt.getId());
        assertThat(coLecturerRepository.existsByClassIdAndLecturerId(
                saved.getId(), lecturer.getId())).isTrue();
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void add_co_lecturer_cross_subject_class_denied() throws Exception {
        Subject other = subjectRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();
        ClassEntity out = new ClassEntity(
                "Lớp Foreign", lecturer.getId(), lecturer.getId(),
                "desc", null, null, 50);
        out.setCode("HFR01");
        out.setSubjectId(other.getId());
        ClassEntity saved = classRepository.save(out);

        mockMvc.perform(post("/leader/assign/" + saved.getId()).with(csrf())
                        .param("lecturerId", String.valueOf(lecturer.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void report_ok_and_scoped() throws Exception {
        ClassEntity inSubject = new ClassEntity(
                "Lớp Report", leader.getId(), leader.getId(),
                "desc", null, null, 50);
        inSubject.setCode("HRP01");
        inSubject.setSubjectId(cntt.getId());
        classRepository.save(inSubject);

        mockMvc.perform(get("/leader/report"))
                .andExpect(status().isOk())
                .andExpect(view().name("leader/report"))
                .andExpect(content().string(containsString("Lớp Report")));
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void retired_approvals_page_redirects_to_dashboard() throws Exception {
        ClassEntity pending = new ClassEntity(
                "Lớp chờ duyệt", lecturer.getId(), lecturer.getId(),
                "desc", null, null, 50);
        pending.setCode("HAP01");
        pending.setSubjectId(cntt.getId());
        classRepository.save(pending);

        mockMvc.perform(get("/leader/approvals"))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/leader"));
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void retired_review_actions_cannot_mutate_active_class() throws Exception {
        ClassEntity pending = new ClassEntity(
                "Lớp được duyệt", lecturer.getId(), lecturer.getId(),
                "desc", null, null, 50);
        pending.setCode("HAP02");
        pending.setSubjectId(cntt.getId());
        ClassEntity saved = classRepository.saveAndFlush(pending);

        mockMvc.perform(post("/leader/approvals/" + saved.getId() + "/approve").with(csrf()))
                .andExpect(status().isGone());
        mockMvc.perform(post("/leader/approvals/" + saved.getId() + "/reject").with(csrf())
                        .param("note", "Retired action"))
                .andExpect(status().isGone());

        ClassEntity approved = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ClassEntity.STATUS_ACTIVE);
        assertThat(approved.getApprovedBy()).isNull();
        assertThat(approved.getApprovedAt()).isNull();
    }

    @Test
    @WithUserDetails(value = "leader@ksh.edu.vn", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void empty_state_when_no_subject() throws Exception {
        // Clear leader assignment and subject_id so resolver returns empty.
        for (Subject d : subjectRepository.findAll()) {
            if (leader.getId().equals(d.getLeaderUserId())) {
                d.assignLeader(null);
                subjectRepository.save(d);
            }
        }
        leader.setSubjectId(null);
        userRepository.save(leader);

        mockMvc.perform(get("/leader"))
                .andExpect(status().isOk())
                .andExpect(view().name("leader/dashboard"))
                .andExpect(model().attribute("emptySubject", true))
                .andExpect(model().attribute("leaderSubject", org.hamcrest.Matchers.nullValue()));
    }
}
