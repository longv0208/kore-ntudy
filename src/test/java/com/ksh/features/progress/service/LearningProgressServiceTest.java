package com.ksh.features.progress.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.LearningProgress;
import com.ksh.entities.Lesson;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.entities.UserFactory;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.progress.repository.LearningProgressRepository;
import com.ksh.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ksh.features.student.dto.StudentLessonsDtos.LessonEngagementTab;
import com.ksh.features.student.dto.StudentLessonsDtos.LessonEngagementView;
import com.ksh.features.student.service.StudentLessonsService;
import com.ksh.security.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link LearningProgressService} (KSH-4.5). Also
 * exercises {@link StudentLessonsService} aggregates so DRAFT-exclusion is
 * covered end-to-end against MySQL.
 */
@SpringBootTest
@Transactional
class LearningProgressServiceTest {

    @Autowired private LearningProgressService progressService;
    @Autowired private LearningProgressRepository progressRepository;
    @Autowired private StudentLessonsService studentLessonsService;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section;
    private short orderSeq;

    @BeforeEach
    void setUp() {
        orderSeq = 0;
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        student = ensureUser("student-progress@ksh.edu.vn", "Student Progress");
        clazz = saveClass("Progress class", "PRGCLS");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
    }

    @Test
    void first_open_creates_in_progress() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();

        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());

        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
        assertThat(p.getStartedAt()).isNotNull();
        assertThat(p.getCompletedAt()).isNull();
    }

    @Test
    void reopen_is_idempotent() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();

        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());
        LearningProgress first = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());

        // Still IN_PROGRESS, same started_at, exactly one row for the pair.
        LearningProgress again = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
    }

    @Test
    void legacy_toggle_cannot_bypass_incomplete_checklist() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();
        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());

        boolean now = progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        assertThat(now).isFalse();
        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
        assertThat(p.getCompletedAt()).isNull();
        assertThat(p.getProgressPercent()).isEqualByComparingTo("0");
    }

    @Test
    void legacy_toggle_is_idempotent_and_does_not_alternate_state() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();
        progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        boolean now = progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        assertThat(now).isFalse();
        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
        assertThat(p.getCompletedAt()).isNull();
        assertThat(p.getProgressPercent()).isEqualByComparingTo("0");
    }

    @Test
    void legacy_toggle_without_prior_open_creates_in_progress_only() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();

        boolean now = progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        assertThat(now).isFalse();
        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
        assertThat(p.getStartedAt()).isNotNull();
    }

    @Test
    void checkpoint_exposes_applicable_and_not_applicable_items_without_fake_completion() {
        Lesson lesson = persistLesson("Bài có nội dung", true);
        enrollActive();

        LessonEngagementView view = progressService.checkpointEngagement(
                clazz.getId(), lesson.getId(), student.getId(),
                LessonEngagementTab.CONTENT, true);

        assertThat(view.content().applicable()).isTrue();
        assertThat(view.content().requiredSeconds()).isEqualTo(60);
        assertThat(view.content().complete()).isFalse();
        assertThat(view.video().applicable()).isFalse();
        assertThat(view.video().satisfied()).isTrue();
        assertThat(view.attachments().applicable()).isFalse();
        assertThat(view.attachments().satisfied()).isTrue();
        assertThat(view.eligible()).isFalse();
        assertThat(view.overallCompleted()).isFalse();
    }

    @Test
    void completed_checklist_unlocks_then_explicit_action_marks_lesson_complete() {
        Lesson lesson = persistLesson("Bài đủ checklist", true);
        enrollActive();
        LearningProgress evidence = new LearningProgress(student.getId(), lesson.getId());
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        evidence.checkpointEngagement(LearningProgress.TAB_CONTENT, true,
                true, false, false, now);
        for (int i = 0; i < 4; i++) {
            now = now.plusSeconds(15);
            evidence.checkpointEngagement(LearningProgress.TAB_CONTENT, true,
                    true, false, false, now);
        }
        evidence.checkpointEngagement(LearningProgress.TAB_CONTENT, false,
                true, false, false, now);
        progressRepository.saveAndFlush(evidence);
        entityManager.flush();
        entityManager.clear();

        LessonEngagementView unlocked = progressService.getEngagement(
                clazz.getId(), lesson.getId(), student.getId());
        assertThat(unlocked.eligible()).isTrue();
        assertThat(unlocked.overallCompleted()).isFalse();

        assertThat(progressService.toggleCompletion(
                clazz.getId(), lesson.getId(), student.getId())).isTrue();
        LearningProgress completed = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(completed.isCompleted()).isTrue();
        assertThat(completed.getProgressPercent()).isEqualByComparingTo("100");
    }

    @Test
    void non_enrolled_open_denied() {
        Lesson lesson = persistLesson("Bài 1", true);

        assertThatThrownBy(() -> progressService.recordOpened(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId())).isEmpty();
    }

    @Test
    void draft_lesson_toggle_denied() {
        Lesson draft = persistLesson("Bài nháp", false);
        enrollActive();

        assertThatThrownBy(() -> progressService.toggleCompletion(
                clazz.getId(), draft.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(progressRepository
                .findByUserIdAndLessonId(student.getId(), draft.getId())).isEmpty();
    }

    @Test
    void aggregates_exclude_draft_lessons() {
        Lesson pub1 = persistLesson("Bài 1", true);
        persistLesson("Bài 2", true);
        persistLesson("Bài nháp", false); // DRAFT must not count
        enrollActive();
        LearningProgress completed = new LearningProgress(student.getId(), pub1.getId());
        completed.markCompleted();
        progressRepository.saveAndFlush(completed);
        entityManager.flush();
        entityManager.clear();

        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), student.getId(), Role.STUDENT);

        assertThat(view.publishedTotal()).isEqualTo(2);
        assertThat(view.completedTotal()).isEqualTo(1);
        assertThat(view.percent()).isEqualTo(50);
        assertThat(view.sections().get(0).completedCount()).isEqualTo(1);
        assertThat(view.sections().get(0).publishedCount()).isEqualTo(2);
    }

    @Test
    void empty_class_reports_zero_percent() {
        enrollActive();

        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), student.getId(), Role.STUDENT);

        assertThat(view.publishedTotal()).isZero();
        assertThat(view.percent()).isZero();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Lesson persistLesson(String title, boolean published) {
        Lesson l = new Lesson(section.getId(), title, orderSeq++, lecturer.getId());
        l.updateContent("<p>Body</p>");
        if (published) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private Enrollment enrollActive() {
        return enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.REQUEST, null));
    }

    private ClassEntity saveClass(String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);

        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, Role.STUDENT, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
