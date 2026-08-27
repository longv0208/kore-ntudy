package com.ksh.features.classes;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.entities.ClassActivity;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.ClassCoLecturer;
import com.ksh.features.classes.repository.ClassActivityRepository;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import com.ksh.features.classes.repository.ClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2 integration test cho Lecturer Classes CRUD.
 *
 * <p>Phu kich ban tu {@code specs/lecturer-classes/spec.md}: list role-scope,
 * create + validation, edit + authz, soft-delete + audit.
 *
 * <p>Seed users tu V5__seed_test_users.sql:
 * lecturer@ksh.edu.vn, leader@ksh.edu.vn, admin@ksh.edu.vn, student@ksh.edu.vn.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint2ClassesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassActivityRepository activityRepository;
    @Autowired private ClassCoLecturerRepository coLecturerRepository;
    @Autowired private UserRepository userRepository;
    @PersistenceContext private EntityManager em;

    private User lecturer;
    private User otherLecturer;
    private User leader;
    private User admin;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        leader = userRepository.findByEmailIgnoreCase("leader@ksh.edu.vn").orElseThrow();
        admin = userRepository.findByEmailIgnoreCase("admin@ksh.edu.vn").orElseThrow();
        // We don't have a 2nd LECTURER seeded — simulate "other" via leader id only when needed.
        otherLecturer = leader;
    }

    // ───────────────────── List ─────────────────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void list_lecturer_sees_only_own_classes() throws Exception {
        ClassEntity own = saveClass("Lect-Own", lecturer.getId(), "OWN01");
        ClassEntity other = saveClass("Leader-Own", leader.getId(), "HDA01");

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lect-Own")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Leader-Own"))));
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void list_leader_sees_same_department_classes() throws Exception {
        saveClass("By-Lect", lecturer.getId(), "BYL01");
        saveClass("By-Leader", leader.getId(), "BYH01");

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("By-Lect")))
                .andExpect(content().string(containsString("By-Leader")));
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void list_admin_sees_all() throws Exception {
        saveClass("Admin-See-1", lecturer.getId(), "ADM01");
        saveClass("Admin-See-2", leader.getId(), "ADM02");

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Admin-See-1")))
                .andExpect(content().string(containsString("Admin-See-2")));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void list_tabs_separate_operational_and_archived_lifecycle_states() throws Exception {
        ClassEntity pending = saveClass("Tab-Pending-Unique", lecturer.getId(), "TPEND");
        ClassEntity rejected = saveClass("Tab-Rejected-Unique", lecturer.getId(), "TREJT");
        rejected.reject(leader.getId(), "Cần bổ sung mô tả", java.time.LocalDateTime.now());
        classRepository.saveAndFlush(rejected);
        ClassEntity active = saveClass("Tab-Active-Unique", lecturer.getId(), "TACTV");
        active.approve(leader.getId(), java.time.LocalDateTime.now());
        classRepository.saveAndFlush(active);
        ClassEntity archived = saveClass("Tab-Archived-Unique", lecturer.getId(), "TARCH");
        archived.approve(leader.getId(), java.time.LocalDateTime.now());
        archived.archive();
        classRepository.saveAndFlush(archived);

        mockMvc.perform(get("/lecturer/classes").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(pending.getName())))
                .andExpect(content().string(containsString(rejected.getName())))
                .andExpect(content().string(containsString(active.getName())))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(archived.getName()))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Hiện xếp hạng"))))
                .andExpect(content().string(containsString("tab=archived")));

        mockMvc.perform(get("/lecturer/classes")
                        .param("tab", "archived")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(archived.getName())))
                .andExpect(content().string(containsString("Đã lưu trữ")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(pending.getName()))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(rejected.getName()))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(active.getName()))));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void list_pagination_is_centered_component_and_preserves_selected_tab() throws Exception {
        saveClass("Pagination-First-Unique", lecturer.getId(), "PGN01");
        saveClass("Pagination-Second-Unique", lecturer.getId(), "PGN02");
        saveClass("Pagination-Third-Unique", lecturer.getId(), "PGN03");

        mockMvc.perform(get("/lecturer/classes")
                        .param("tab", "current")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"classes-pagination\"")))
                .andExpect(content().string(containsString("Trang 2 trên ")))
                .andExpect(content().string(containsString("tab=current&amp;page=0&amp;size=1")))
                .andExpect(content().string(containsString("tab=current&amp;page=2&amp;size=1")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void list_student_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void list_empty_state_when_no_classes() throws Exception {
        // Defensive: purge any leaked (committed) classes for this lecturer so the
        // empty-state assertion is not flaky against manual-smoke leftovers.
        classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId()).forEach(c -> {
            c.softDelete();
            classRepository.saveAndFlush(c);
        });
        em.flush();
        em.clear();

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có lớp học nào")));
    }

    // ───────────────────── Create ─────────────────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_happy_path_persists_and_logs_activity() throws Exception {
        long before = classRepository.count();
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Java cơ bản")
                        .param("description", "Khoá nhập môn")
                        .param("subjectId", String.valueOf(lecturer.getSubjectId()))
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-12-31")
                        .param("maxStudents", "50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes"));

        assertThat(classRepository.count()).isEqualTo(before + 1);
        assertThat(activityRepository.count()).isEqualTo(activityBefore + 1);

        ClassEntity saved = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId())
                .stream().filter(c -> "Java cơ bản".equals(c.getName())).findFirst().orElseThrow();
        assertThat(saved.getSubjectId()).isEqualTo(lecturer.getSubjectId());
        assertThat(saved.getStatus()).isEqualTo(ClassEntity.STATUS_PENDING);
        assertThat(saved.getStartDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 1));

    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_with_blank_name_rerenders_with_inline_error() throws Exception {
        long before = classRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "")
                        .param("description", "preserved-input-marker"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tên lớp")))
                // input preservation: description value still rendered in textarea
                .andExpect(content().string(containsString("preserved-input-marker")));

        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_with_end_before_start_rerenders_with_date_error() throws Exception {
        long before = classRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Test")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày kết thúc phải sau ngày bắt đầu")));

        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_with_end_equal_start_rerenders_with_date_error() throws Exception {
        long before = classRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Test")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày kết thúc phải sau ngày bắt đầu")));

        assertThat(classRepository.count()).isEqualTo(before);
    }

    // ───────────────────── Edit ─────────────────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void edit_by_owner_updates_and_logs_activity() throws Exception {
        ClassEntity entity = saveClass("Old", lecturer.getId(), "OLDED");
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "New")
                        .param("description", "Updated")
                        .param("startDate", "2026-08-01")
                        .param("subjectId", String.valueOf(entity.getSubjectId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes"));

        ClassEntity updated = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(updated.getStartDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("New");

        List<ClassActivity> all = activityRepository.findAll();
        assertThat(activityRepository.count()).isEqualTo(activityBefore + 1);
        ClassActivity latest = all.get(all.size() - 1);
        assertThat(latest.getType()).isEqualTo(ClassActivity.TYPE_UPDATED);
        assertThat(latest.getMetadata()).contains("Old").contains("New");
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void edit_by_non_owner_lecturer_returns_403() throws Exception {
        ClassEntity entity = saveClass("Owned by LEADER", leader.getId(), "HDOWN");
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Hijacked")
                        .param("subjectId", String.valueOf(entity.getSubjectId())))
                .andExpect(status().isForbidden());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Owned by LEADER");
        assertThat(activityRepository.count()).isEqualTo(activityBefore);
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void edit_by_leader_is_forbidden_because_assignment_must_not_transfer_ownership() throws Exception {
        ClassEntity entity = saveClass("Lect class", lecturer.getId(), "LCEDT");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Leader edited")
                        .param("subjectId", String.valueOf(entity.getSubjectId())))
                .andExpect(status().isForbidden());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Lect class");
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void edit_by_admin_succeeds_for_any_class() throws Exception {
        ClassEntity entity = saveClass("Lect class admin", lecturer.getId(), "ADMED");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Admin edited")
                        .param("subjectId", String.valueOf(entity.getSubjectId())))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Admin edited");
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void edit_nonexistent_returns_404() throws Exception {
        mockMvc.perform(get("/lecturer/classes/9999999/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void edit_soft_deleted_returns_404() throws Exception {
        ClassEntity entity = saveClass("Gone", lecturer.getId(), "GONE1");
        entity.softDelete();
        classRepository.saveAndFlush(entity);
        // Clear L1 cache so @SQLRestriction is applied on the next read
        em.flush();
        em.clear();

        mockMvc.perform(get("/lecturer/classes/" + entity.getId() + "/edit"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────── Soft-delete ─────────────────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void delete_by_owner_marks_deleted_and_omits_from_list() throws Exception {
        ClassEntity entity = saveClass("ToRemove", lecturer.getId(), "REMOV");
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes"));

        // Soft-deleted row excluded from list view
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ToRemove"))));

        assertThat(activityRepository.count()).isEqualTo(activityBefore + 1);
        List<ClassActivity> all = activityRepository.findAll();
        assertThat(all.get(all.size() - 1).getType()).isEqualTo(ClassActivity.TYPE_DELETED);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void delete_by_non_owner_lecturer_returns_403() throws Exception {
        ClassEntity entity = saveClass("Owned leader", leader.getId(), "HDDEL");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().isForbidden());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isFalse();
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void delete_by_admin_succeeds_on_any_class() throws Exception {
        ClassEntity entity = saveClass("Admin will delete", lecturer.getId(), "ADMDL");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isTrue();
    }

    // ───────────────────── Authz: STUDENT denied on all write endpoints ─────────

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void create_form_student_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/classes/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void post_create_student_forbidden() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Hack"))
                .andExpect(status().isForbidden());
    }

    // ───────────────────── Validation: missing edge cases ─────────────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_with_too_short_name_rerenders_with_error() throws Exception {
        long before = classRepository.count();
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "ab"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tên lớp 3")));
        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_with_max_students_zero_rerenders_with_error() throws Exception {
        long before = classRepository.count();
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Valid name")
                        .param("maxStudents", "0"))
                .andExpect(status().isOk());
        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void create_with_max_students_omitted_defaults_to_100() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Default max test")
                        .param("subjectId", String.valueOf(lecturer.getSubjectId())))
                .andExpect(status().is3xxRedirection());

        ClassEntity saved = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId())
                .stream().filter(c -> "Default max test".equals(c.getName())).findFirst().orElseThrow();
        assertThat(saved.getMaxStudents()).isEqualTo(100);
    }

    // ───────────────────── LEADER creates → lecturer_id = LEADER ──────────────

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void create_by_leader_assigns_leader_as_lecturer() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Created by leader")
                        .param("subjectId", String.valueOf(leader.getSubjectId())))
                .andExpect(status().is3xxRedirection());

        ClassEntity saved = classRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> "Created by leader".equals(c.getName())).findFirst().orElseThrow();
        assertThat(saved.getLecturerId()).isEqualTo(leader.getId());
    }

    // ───────────────────── Edit: subject immutable + delete-twice 404 ────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void edit_does_not_change_subject() throws Exception {
        ClassEntity entity = saveClass("CodeStays", lecturer.getId(), "CDST1");
        Long originalSubjectId = entity.getSubjectId();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Renamed")
                        .param("subjectId", String.valueOf(entity.getSubjectId())))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getSubjectId()).isEqualTo(originalSubjectId);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void delete_already_deleted_returns_404() throws Exception {
        ClassEntity entity = saveClass("DelTwice", lecturer.getId(), "DELT2");
        entity.softDelete();
        classRepository.saveAndFlush(entity);
        em.flush();
        em.clear();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ───────────────────── Class detail tabs ─────────────────────────────

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void detail_root_redirects_to_lessons() throws Exception {
        ClassEntity c = saveClass("DetailRoot", lecturer.getId(), "DTRT1");
        mockMvc.perform(get("/lecturer/classes/" + c.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes/" + c.getId() + "/lessons"));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void detail_members_renders_empty_state() throws Exception {
        ClassEntity c = saveClass("DetailMem", lecturer.getId(), "DTMM1");
        coLecturerRepository.saveAndFlush(new ClassCoLecturer(c.getId(), leader.getId(), leader.getId()));
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/members"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thành viên lớp học")))
                .andExpect(content().string(containsString("GV chủ lớp")))
                .andExpect(content().string(containsString("Giảng viên đồng giảng")))
                .andExpect(content().string(containsString(leader.getEmail())))
                .andExpect(content().string(containsString("Chưa có học sinh nào")));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void detail_settings_renders_form_prefilled() throws Exception {
        ClassEntity c = saveClass("DetailSet", lecturer.getId(), "DTSS1");
        // Sprint 2.4 detail-page redesign: page title = class name (not the
        // literal "Cài đặt lớp học"). The page sub-heading "Thông tin lớp"
        // identifies the info card. The class name is reused in the title.
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thông tin lớp")))
                .andExpect(content().string(containsString("DetailSet")));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void detail_placeholder_tab_renders_label() throws Exception {
        ClassEntity c = saveClass("DetailPh", lecturer.getId(), "DTPH1");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/assignments"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bài tập")));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void retired_board_url_redirects_to_lessons() throws Exception {
        ClassEntity c = saveClass("OwnedByLeader", leader.getId(), "OWNHD");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/board"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes/" + c.getId() + "/lessons"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void detail_student_forbidden() throws Exception {
        ClassEntity c = saveClass("StudCheck", lecturer.getId(), "STUDC");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/members"))
                .andExpect(status().isForbidden());
    }

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        userRepository.findById(lecturerId)
                .map(User::getSubjectId)
                .ifPresent(e::setSubjectId);
        e.setCode(code);
        try {
            return classRepository.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            // Fall back: regenerate code (defensive, in case a prior leaked row exists)
            e.setCode(code + "x");
            return classRepository.saveAndFlush(e);
        }
    }
}
