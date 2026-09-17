package com.ksh.features.assignments;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.entities.UserFactory;
import com.ksh.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ksh.features.assignments.dto.AssignmentDtos.AssignmentRow;
import com.ksh.features.assignments.dto.AssignmentDtos.GradeForm;
import com.ksh.features.assignments.dto.AssignmentDtos.StudentAssignmentDetail;
import com.ksh.features.assignments.dto.AssignmentDtos.StudentAssignmentRow;
import com.ksh.features.assignments.dto.AssignmentDtos.SubmissionRow;
import com.ksh.features.assignments.dto.AssignmentDtos.SubmitForm;
import com.ksh.features.assignments.entity.AssignmentStatus;
import com.ksh.features.assignments.entity.AssignmentSubmission;
import com.ksh.features.assignments.service.LecturerAssignmentService;
import com.ksh.features.assignments.service.StudentAssignmentService;
import com.ksh.features.assignments.repository.AssignmentSubmissionRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for lecturer + student assignment services.
 *
 * <p>Every test creates a fresh class + enrolled student via real repositories
 * so state never bleeds across tests (all wrapped in @Transactional).
 */
@SpringBootTest
@Transactional
class AssignmentServiceTest {

    @Autowired private LecturerAssignmentService lecturerAssignmentService;
    @Autowired private StudentAssignmentService studentAssignmentService;
    @Autowired private AssignmentSubmissionRepository submissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private User student;
    private ClassEntity clazz;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        student  = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();
        clazz    = saveClass("Assignment IT class", lecturer.getId(), "ASGNIT" + System.nanoTime() % 10000);
        // Enroll the student so assignment operations succeed.
        enrollmentRepository.saveAndFlush(
                Enrollment.createFor(student, clazz.getId(), Enrollment.JoinedVia.REQUEST, null));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Test
    void create_returns_row_with_draft_status() {
        AssignmentForm form = new AssignmentForm(
                null, "Bài tập 1", "Mô tả",
                BigDecimal.valueOf(100), null, false);

        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);

        List<AssignmentRow> rows = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(AssignmentStatus.DRAFT);
        assertThat(rows.get(0).title()).isEqualTo("Bài tập 1");
    }

    @Test
    void create_fails_for_non_owner_lecturer() {
        User other = ensureUser("other-lect@ksh.edu.vn", "Other Lect", Role.LECTURER);
        AssignmentForm form = new AssignmentForm(null, "X", "Y", BigDecimal.TEN, null, false);

        assertThatThrownBy(() ->
                lecturerAssignmentService.create(clazz.getId(), form, other.getId(), Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Publish lifecycle ─────────────────────────────────────────────────

    @Test
    void publish_transitions_draft_to_published() {
        AssignmentForm form = new AssignmentForm(
                null, "Bài tập pub", "Mô tả",
                BigDecimal.valueOf(100), null, false);
        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();

        lecturerAssignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        AssignmentRow row = lecturerAssignmentService
                .listForLecturer(clazz.getId(), lecturer.getId(), Role.LECTURER).get(0);
        assertThat(row.status()).isEqualTo(AssignmentStatus.PUBLISHED);
    }

    @Test
    void close_transitions_published_to_closed() {
        Long aid = createAndPublish("Bài tập close");

        lecturerAssignmentService.close(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        AssignmentRow row = lecturerAssignmentService
                .listForLecturer(clazz.getId(), lecturer.getId(), Role.LECTURER).get(0);
        assertThat(row.status()).isEqualTo(AssignmentStatus.CLOSED);
    }

    @Test
    void published_assignment_cannot_be_edited_or_loaded_into_edit_form() {
        Long aid = createAndPublish("Immutable published assignment");
        AssignmentForm replacement = new AssignmentForm(
                aid, "Tampered title", "Tampered description",
                BigDecimal.ONE, null, false);

        assertThatThrownBy(() -> lecturerAssignmentService.update(
                clazz.getId(), aid, replacement, lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lecturerAssignmentService.getFormForEdit(
                clazz.getId(), aid, lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalStateException.class);

        AssignmentRow row = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0);
        assertThat(row.title()).isEqualTo("Immutable published assignment");
    }

    @Test
    void close_on_draft_throws_illegal_state() {
        AssignmentForm form = new AssignmentForm(null, "X", "Y", BigDecimal.TEN, null, false);
        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();

        assertThatThrownBy(() ->
                lecturerAssignmentService.close(clazz.getId(), aid, lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Student submit ────────────────────────────────────────────────────

    @Test
    void submit_creates_submitted_status() {
        Long aid = createAndPublish("Bài tập submit");

        studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("  Nội dung bài làm  "), student.getId());

        AssignmentSubmission persisted = submissionRepository
                .findByAssignmentIdAndUserId(aid, student.getId()).orElseThrow();
        assertThat(persisted.getAssignmentId()).isEqualTo(aid);
        assertThat(persisted.getUserId()).isEqualTo(student.getId());
        assertThat(persisted.getContent()).isEqualTo("Nội dung bài làm");
        assertThat(persisted.getStatus()).isEqualTo(AssignmentStatus.SUB_SUBMITTED);
        assertThat(persisted.isLate()).isFalse();
        assertThat(persisted.getSubmittedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();

        List<StudentAssignmentRow> rows = studentAssignmentService
                .listPublishedForStudent(clazz.getId(), student.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).submissionStatus()).isEqualTo(AssignmentStatus.SUB_SUBMITTED);
    }

    @Test
    void submit_rejects_assignment_from_another_class_without_persisting() {
        ClassEntity otherClass = saveClass("Other assignment class", lecturer.getId(),
                "ASGNOTHER" + System.nanoTime() % 10000);
        Long aid = createAndPublishInClass(otherClass, "Wrong class assignment");

        assertThatThrownBy(() -> studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("Không được nhận"), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(submissionRepository.countByAssignmentId(aid)).isZero();
    }

    @Test
    void submit_requires_active_enrollment_without_persisting() {
        User unenrolled = ensureUser("assignment-outsider@ksh.edu.vn", "Assignment Outsider", Role.STUDENT);
        Long aid = createAndPublish("Enrollment required");

        assertThatThrownBy(() -> studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("Không được nhận"), unenrolled.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(submissionRepository.countByAssignmentId(aid)).isZero();
    }

    @Test
    void submit_rejects_draft_without_persisting() {
        AssignmentForm form = new AssignmentForm(
                null, "Draft submit guard", "Mô tả", BigDecimal.valueOf(100), null, false);
        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();

        assertThatThrownBy(() -> studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("Không được nhận"), student.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(submissionRepository.countByAssignmentId(aid)).isZero();
    }

    @Test
    void submit_rejects_closed_assignment_without_persisting() {
        Long aid = createAndPublish("Closed submit guard");
        lecturerAssignmentService.close(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() -> studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("Không được nhận"), student.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(submissionRepository.countByAssignmentId(aid)).isZero();
    }

    @Test
    void submit_after_graded_throws_illegal_state() {
        Long aid = createAndPublish("Bài tập grade guard");
        studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("Bài làm"), student.getId());

        List<SubmissionRow> subs = lecturerAssignmentService
                .listSubmissions(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);
        Long sid = subs.get(0).submissionId();
        lecturerAssignmentService.grade(clazz.getId(), aid, sid,
                new GradeForm(BigDecimal.valueOf(90), "Tốt"),
                lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() ->
                studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("Nộp lại"), student.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void second_submit_is_rejected_even_before_grading_and_original_content_is_preserved() {
        Long aid = createAndPublish("One shot assignment");
        studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("First and final"), student.getId());

        assertThatThrownBy(() -> studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("Forbidden replacement"), student.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chỉ được nộp một lần");
        assertThat(submissionRepository.countByAssignmentId(aid)).isEqualTo(1);

        StudentAssignmentDetail detail =
                studentAssignmentService.getForStudent(clazz.getId(), aid, student.getId());
        assertThat(detail.submissionContent()).isEqualTo("First and final");
    }

    @Test
    void blank_submission_is_rejected_without_consuming_the_one_attempt() {
        Long aid = createAndPublish("Blank submission guard");

        assertThatThrownBy(() -> studentAssignmentService.submit(
                clazz.getId(), aid, new SubmitForm("   "), student.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không được để trống");

        StudentAssignmentDetail detail =
                studentAssignmentService.getForStudent(clazz.getId(), aid, student.getId());
        assertThat(detail.submissionId()).isNull();
    }

    @Test
    void submit_late_when_due_date_passed_and_late_allowed() {
        AssignmentForm form = new AssignmentForm(
                null, "Late allowed",  "Mô tả",
                BigDecimal.valueOf(100),
                LocalDateTime.now().minusDays(1), // due date in the past
                true);
        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();
        lecturerAssignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("Nộp muộn"), student.getId());

        AssignmentSubmission persisted = submissionRepository
                .findByAssignmentIdAndUserId(aid, student.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AssignmentStatus.SUB_SUBMITTED);
        assertThat(persisted.isLate()).isTrue();

        StudentAssignmentDetail detail =
                studentAssignmentService.getForStudent(clazz.getId(), aid, student.getId());
        assertThat(detail.isLate()).isTrue();
    }

    @Test
    void submit_late_when_due_passed_and_late_not_allowed_throws() {
        AssignmentForm form = new AssignmentForm(
                null, "No late", "Mô tả",
                BigDecimal.valueOf(100),
                LocalDateTime.now().minusDays(1),
                false); // late NOT allowed
        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();
        lecturerAssignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() ->
                studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("Nộp muộn"), student.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(submissionRepository.countByAssignmentId(aid)).isZero();
    }

    // ── Grade ─────────────────────────────────────────────────────────────

    @Test
    void grade_sets_submission_to_graded_with_score() {
        Long aid = createAndPublish("Bài tập grading");
        studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("Bài làm"), student.getId());
        Long sid = lecturerAssignmentService
                .listSubmissions(clazz.getId(), aid, lecturer.getId(), Role.LECTURER)
                .get(0).submissionId();

        lecturerAssignmentService.grade(clazz.getId(), aid, sid,
                new GradeForm(BigDecimal.valueOf(85), "Khá tốt"),
                lecturer.getId(), Role.LECTURER);

        StudentAssignmentDetail detail =
                studentAssignmentService.getForStudent(clazz.getId(), aid, student.getId());
        assertThat(detail.submissionStatus()).isEqualTo(AssignmentStatus.SUB_GRADED);
        assertThat(detail.score()).isEqualByComparingTo(BigDecimal.valueOf(85));
        assertThat(detail.feedback()).isEqualTo("Khá tốt");
    }

    @Test
    void grade_score_above_max_throws_illegal_argument() {
        Long aid = createAndPublish("Score cap test");
        studentAssignmentService.submit(clazz.getId(), aid, new SubmitForm("Bài làm"), student.getId());
        Long sid = lecturerAssignmentService
                .listSubmissions(clazz.getId(), aid, lecturer.getId(), Role.LECTURER)
                .get(0).submissionId();

        // max_score defaults to 100; submitting 101 must fail.
        assertThatThrownBy(() ->
                lecturerAssignmentService.grade(clazz.getId(), aid, sid,
                        new GradeForm(BigDecimal.valueOf(101), null),
                        lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Student visibility ────────────────────────────────────────────────

    @Test
    void draft_assignment_not_visible_to_student() {
        AssignmentForm form = new AssignmentForm(null, "Draft only", "X", BigDecimal.TEN, null, false);
        lecturerAssignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);

        List<StudentAssignmentRow> rows =
                studentAssignmentService.listPublishedForStudent(clazz.getId(), student.getId());
        assertThat(rows).isEmpty();
    }

    @Test
    void unenrolled_student_cannot_see_assignment() {
        User stranger = ensureUser("stranger@ksh.edu.vn", "Stranger", Role.STUDENT);
        Long aid = createAndPublish("Enrolled-only");

        assertThatThrownBy(() ->
                studentAssignmentService.getForStudent(clazz.getId(), aid, stranger.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Long createAndPublish(String title) {
        return createAndPublishInClass(clazz, title);
    }

    private Long createAndPublishInClass(ClassEntity targetClass, String title) {
        AssignmentForm form = new AssignmentForm(
                null, title, "Mô tả", BigDecimal.valueOf(100), null, false);
        lecturerAssignmentService.create(targetClass.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService
                .listForLecturer(targetClass.getId(), lecturer.getId(), Role.LECTURER)
                .stream().filter(r -> title.equals(r.title()))
                .findFirst().orElseThrow().id();
        lecturerAssignmentService.publish(
                targetClass.getId(), aid, lecturer.getId(), Role.LECTURER);
        return aid;
    }

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity c = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        c.setCode(code);
        return classRepository.saveAndFlush(c);
    }

    private User ensureUser(String email, String fullName, Role role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(
                    email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    fullName,
                    role,
                    true,
                    null,
                    null);
            return userRepository.saveAndFlush(u);
        });
    }
}
