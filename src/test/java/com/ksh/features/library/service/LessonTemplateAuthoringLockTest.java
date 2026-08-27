package com.ksh.features.library.service;

import com.ksh.entities.Department;
import com.ksh.entities.Lesson;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.security.Role;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration contracts for the Subject Leader curriculum authoring lock. */
@SpringBootTest
@Transactional
class LessonTemplateAuthoringLockTest {

    @Autowired private LessonTemplateService templateService;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User lecturer;
    private User leader;
    private Department subject;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        subject = departmentRepository.findById(lecturer.getSubjectId()).orElseThrow();
        leader = userRepository.findById(subject.getLeaderUserId()).orElseThrow();
        assertThat(leader.getRole()).isEqualTo(Role.LEADER);
        subject.setLibraryLocked(false);
        departmentRepository.saveAndFlush(subject);
    }

    @Test
    void assigned_leader_can_lock_and_unlock_while_lecturer_authoring_is_blocked() {
        var template = templateService.saveForm(
                lecturer.getId(), Role.LECTURER, uniqueLessonForm());

        assertThatThrownBy(() -> templateService.setSubjectLibraryLocked(
                lecturer.getId(), Role.LECTURER, subject.getId(), true))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("trưởng bộ môn");

        assertThat(templateService.setSubjectLibraryLocked(
                leader.getId(), Role.LEADER, subject.getId(), true)).isTrue();
        departmentRepository.flush();
        entityManager.clear();

        var lecturerView = templateService.list(lecturer.getId(), Role.LECTURER,
                subject.getId(), template.title(), 0, 20);
        assertThat(lecturerView.libraryLocked()).isTrue();
        assertThat(lecturerView.canManageLibraryLock()).isFalse();
        assertThat(lecturerView.page().getContent())
                .allSatisfy(row -> assertThat(row.canManage()).isFalse());

        var leaderView = templateService.list(leader.getId(), Role.LEADER,
                subject.getId(), template.title(), 0, 20);
        assertThat(leaderView.libraryLocked()).isTrue();
        assertThat(leaderView.canManageLibraryLock()).isTrue();

        assertThatThrownBy(() -> templateService.loadForm(
                lecturer.getId(), Role.LECTURER, template.id(), subject.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("đã được trưởng bộ môn khóa");
        assertThatThrownBy(() -> templateService.renameLesson(
                lecturer.getId(), Role.LECTURER, template.id(), "Tên không được lưu"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(templateService.setSubjectLibraryLocked(
                leader.getId(), Role.LEADER, subject.getId(), false)).isFalse();
        templateService.renameLesson(
                lecturer.getId(), Role.LECTURER, template.id(), "Tên sau khi mở khóa");
        assertThat(templateService.loadForm(
                lecturer.getId(), Role.LECTURER, template.id(), subject.getId()).getTitle())
                .isEqualTo("Tên sau khi mở khóa");
    }

    private LessonTemplateForm uniqueLessonForm() {
        LessonTemplateForm form = new LessonTemplateForm();
        form.setSubjectId(subject.getId());
        form.setChapterNumber(999);
        form.setChapterTitle("Kiểm thử khóa biên soạn");
        form.setTitle("Bài kiểm thử khóa " + System.nanoTime());
        form.setContentType(Lesson.CONTENT_TYPE_RICHTEXT);
        form.setContentRichtext("<p>Nội dung kiểm thử khóa.</p>");
        return form;
    }
}
