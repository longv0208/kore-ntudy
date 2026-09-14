package com.ksh.features.tests.service;

import com.ksh.common.HtmlSanitizer;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.tests.dto.TestDtos.ClassTestsView;
import com.ksh.features.tests.dto.TestDtos.ExamListRow;
import com.ksh.features.tests.dto.TestDtos.StudentExamList;
import com.ksh.features.tests.dto.TestDtos.StudentTestDetail;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.entity.TestAttempt;
import com.ksh.features.tests.repository.TestAttemptRepository;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.features.tests.support.TestAccessQueries;
import com.ksh.features.tests.support.TestAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ksh.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;

/** Builds the student exam list (accessible exams + attempt summary), paginated. */
@Service
public class TestCatalogService {

    private final TestAccessQueries accessQueries;
    private final TestAttemptRepository attemptRepository;
    private final ClassRepository classRepository;
    private final TestRepository testRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final TestAccessResolver accessResolver;
    private final SubjectRepository subjectRepository;
    private final TestAttemptService attemptLifecycle;

    public TestCatalogService(TestAccessQueries accessQueries,
                              TestAttemptRepository attemptRepository,
                              ClassRepository classRepository,
                              TestRepository testRepository,
                              EnrollmentRepository enrollmentRepository,
                              UserRepository userRepository,
                              TestAccessResolver accessResolver,
                              SubjectRepository subjectRepository,
                              TestAttemptService attemptLifecycle) {
        this.accessQueries = accessQueries;
        this.attemptRepository = attemptRepository;
        this.classRepository = classRepository;
        this.testRepository = testRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
        this.accessResolver = accessResolver;
        this.subjectRepository = subjectRepository;
        this.attemptLifecycle = attemptLifecycle;
    }

    /**
     * One SSR page of the student's accessible exams, newest-first, each carrying
     * the student's last attempt status and best score percentage. Only the exams
     * paginate (manual {@link PageImpl}); the pager navigates by {@code ?page=N}.
     */
    @Transactional(readOnly = true)
    public StudentExamList listForStudent(Long userId, int page) {
        List<Test> exams = accessQueries.accessibleExams(userId);
        Map<Long, String> classNames = resolveClassNames(exams);
        Map<Long, List<TestAttempt>> attemptsByTest = attemptsByTest(userId, exams);

        List<ExamListRow> rows = new ArrayList<>();
        for (Test t : exams) {
            List<TestAttempt> attempts = attemptsByTest.getOrDefault(t.getId(), List.of());
            rows.add(toRow(t, classNames.get(t.getClassId()), attempts));
        }

        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_EXAM_PAGE_SIZE);
        int from = Math.min(safePage * DEFAULT_EXAM_PAGE_SIZE, rows.size());
        int to = Math.min(from + DEFAULT_EXAM_PAGE_SIZE, rows.size());
        Page<ExamListRow> examsPage = new PageImpl<>(rows.subList(from, to), pageable, rows.size());
        return new StudentExamList(examsPage);
    }

    /**
     * One SSR page of a single class's PUBLISHED exams, title-filtered, for the
     * class-scoped student tests page ({@code GET /my/classes/{classId}/tests}).
     * Gates on ACTIVE enrollment first (same 404-no-leak policy as lessons), then
     * returns class metadata for the shared sidebar plus the paginated rows.
     */
    @Transactional(readOnly = true)
    public ClassTestsView listClassTests(Long classId, Long userId, String query, int page) {
        // Gate 1 — enrollment must be ACTIVE (else 404, no existence leak).
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp hoặc bạn không có quyền truy cập"));
        // Gate 2 — class must be live (soft-deletes filtered by @SQLRestriction).
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp hoặc bạn không có quyền truy cập"));

        String normalized = query == null ? "" : query.trim();
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_EXAM_PAGE_SIZE);
        Page<Test> examPage = testRepository
                .findByClassIdAndStatusAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                        classId, Test.STATUS_PUBLISHED, normalized, pageable);

        Map<Long, List<TestAttempt>> attemptsByTest = attemptsByTest(userId, examPage.getContent());
        Page<ExamListRow> rows = examPage.map(t -> toRow(t, clazz.getName(),
                attemptsByTest.getOrDefault(t.getId(), List.of())));

        String lecturerName = userRepository.findById(clazz.getLecturerId())
                .map(User::getFullName).orElse(null);
        String subjectCode = clazz.getSubjectId() == null ? "—"
                : subjectRepository.findById(clazz.getSubjectId())
                        .map(com.ksh.entities.Subject::getCode).orElse("—");
        return new ClassTestsView(clazz.getId(), clazz.getName(), subjectCode,
                lecturerName, normalized, rows);
    }

    /**
     * Loads the class-exam landing page without creating an attempt. The
     * student starts the timer only after explicitly submitting the start form.
     */
    @Transactional
    public StudentTestDetail detailForStudent(Long testId, Long userId) {
        Test test = accessResolver.requireViewable(testId, userId);
        List<TestAttempt> attempts =
                attemptRepository.findByTestIdAndUserIdOrderByStartedAtDesc(testId, userId);
        TestAttempt open = attempts.stream().filter(TestAttempt::isInProgress).findFirst().orElse(null);
        TestAttempt completed = attempts.stream().filter(a -> !a.isInProgress()).findFirst().orElse(null);
        boolean singleAttemptCompleted = !test.isPractice() && completed != null;
        if (!singleAttemptCompleted && open != null) {
            TestAttempt refreshed = attemptLifecycle.lockAndFinalizeExpiredAttempt(
                    test, open.getId(), userId);
            if (!refreshed.isInProgress()) {
                completed = refreshed;
                open = null;
                singleAttemptCompleted = !test.isPractice();
            } else {
                open = refreshed;
            }
        }
        LocalDateTime now = LocalDateTime.now();

        String availability;
        boolean canStart = false;
        boolean canResume = false;
        if (singleAttemptCompleted) {
            // Old deployments may already contain a completed attempt plus a
            // second open attempt. The one-attempt rule wins: never expose a
            // resume action for that legacy duplicate.
            availability = "COMPLETED";
        } else if (open != null) {
            availability = "IN_PROGRESS";
            canResume = true;
        } else if (test.getStartAt() != null && now.isBefore(test.getStartAt())) {
            availability = "UPCOMING";
        } else if (test.getEndAt() != null && !now.isBefore(test.getEndAt())) {
            availability = "CLOSED";
        } else if (Test.TIME_MODE_FIXED_WINDOW.equals(test.getTimeMode())
                && test.getEndAt() == null) {
            availability = "INVALID_CONFIG";
        } else if (test.isIndividualTimer()
                && (test.getDurationMinutes() == null || test.getDurationMinutes() <= 0)) {
            availability = "INVALID_CONFIG";
        } else {
            availability = "AVAILABLE";
            canStart = true;
        }

        TestAttempt shownAttempt = singleAttemptCompleted ? completed : open;
        String className = test.getClassId() == null ? null
                : classRepository.findById(test.getClassId()).map(ClassEntity::getName).orElse(null);
        return new StudentTestDetail(
                test.getId(), test.getTitle(), sanitizeOptional(test.getDescription()),
                test.getClassId(), className, test.getType(), test.getTimeMode(),
                test.getDurationMinutes(), test.getStartAt(), test.getEndAt(),
                test.getTotalQuestions() == null ? 0 : test.getTotalQuestions(),
                availability,
                shownAttempt == null ? null : shownAttempt.getId(),
                shownAttempt == null ? null : shownAttempt.getStatus(),
                singleAttemptCompleted ? scorePercent(completed) : null,
                canStart, canResume, singleAttemptCompleted);
    }

    private static String sanitizeOptional(String value) {
        if (value == null) return null;
        String sanitized = HtmlSanitizer.sanitize(value).trim();
        return sanitized.isEmpty() ? null : sanitized;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ExamListRow toRow(Test t, String className, List<TestAttempt> attempts) {
        String lastStatus = attempts.isEmpty() ? null : attempts.get(0).getStatus();
        Integer bestPercent = bestScorePercent(attempts);
        Long lastCompletedAttemptId = attempts.stream()
                .filter(attempt -> !attempt.isInProgress())
                .map(TestAttempt::getId)
                .findFirst()
                .orElse(null);
        return new ExamListRow(t.getId(), t.getTitle(), t.getType(), t.getStatus(), className,
                t.isPractice(), t.getDurationMinutes(), t.getTimeMode(),
                t.getStartAt(), t.getEndAt(),
                t.getTotalQuestions() == null ? 0 : t.getTotalQuestions(),
                lastStatus, bestPercent, lastCompletedAttemptId);
    }

    /** Best score percentage across submitted attempts, or null if none submitted. */
    private Integer bestScorePercent(List<TestAttempt> attempts) {
        Integer best = null;
        for (TestAttempt a : attempts) {
            if (a.isInProgress() || a.getScore() == null || a.getTotalPoints() == null
                    || a.getTotalPoints().signum() <= 0) {
                continue;
            }
            int pct = a.getScore().multiply(BigDecimal.valueOf(100))
                    .divide(a.getTotalPoints(), 0, RoundingMode.HALF_UP).intValue();
            if (best == null || pct > best) best = pct;
        }
        return best;
    }

    private Integer scorePercent(TestAttempt attempt) {
        if (attempt.getScore() == null || attempt.getTotalPoints() == null
                || attempt.getTotalPoints().signum() <= 0) {
            return null;
        }
        return attempt.getScore().multiply(BigDecimal.valueOf(100))
                .divide(attempt.getTotalPoints(), 0, RoundingMode.HALF_UP).intValue();
    }

    private Map<Long, List<TestAttempt>> attemptsByTest(Long userId, List<Test> exams) {
        Map<Long, List<TestAttempt>> map = new HashMap<>();
        if (exams.isEmpty()) return map;
        List<Long> testIds = exams.stream().map(Test::getId).toList();
        for (TestAttempt a : attemptRepository.findByUserIdAndTestIdIn(userId, testIds)) {
            map.computeIfAbsent(a.getTestId(), k -> new ArrayList<>()).add(a);
        }
        // Newest-first so index 0 is the last attempt.
        map.values().forEach(list -> list.sort((x, y) -> y.getStartedAt().compareTo(x.getStartedAt())));
        return map;
    }

    private Map<Long, String> resolveClassNames(List<Test> exams) {
        Map<Long, String> names = new HashMap<>();
        List<Long> classIds = exams.stream().map(Test::getClassId)
                .filter(id -> id != null).distinct().toList();
        if (classIds.isEmpty()) return names;
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }
}
