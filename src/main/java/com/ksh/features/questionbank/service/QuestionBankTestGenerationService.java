package com.ksh.features.questionbank.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.TestActivity;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.entity.QuestionBankOption;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ksh.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ksh.features.tests.dto.LecturerTestDtos.TestDistributionTarget;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.features.tests.service.ExamQuestionBankWriter;
import com.ksh.features.tests.service.LecturerExamService;
import com.ksh.features.tests.service.TestActivityWriter;
import com.ksh.features.tests.support.TestAccessResolver;
import com.ksh.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Builds an independent subject Test Bank snapshot from approved questions. */
@Service
public class QuestionBankTestGenerationService {

    public static final String SCOPE_SUBJECT = "SUBJECT";
    public static final String SCOPE_CHAPTER = "CHAPTER";
    public static final String SCOPE_LESSON = "LESSON";

    private final UserRepository userRepository;
    private final DepartmentRepository subjectRepository;
    private final LessonTemplateRepository lessonRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;
    private final TestRepository testRepository;
    private final TestAccessResolver testAccessResolver;
    private final ExamQuestionBankWriter questionWriter;
    private final LecturerExamService examService;
    private final TestActivityWriter activityWriter;

    public QuestionBankTestGenerationService(UserRepository userRepository,
                                             DepartmentRepository subjectRepository,
                                             LessonTemplateRepository lessonRepository,
                                             QuestionBankAccessPolicy accessPolicy,
                                             QuestionBankItemRepository itemRepository,
                                             QuestionBankOptionRepository optionRepository,
                                             TestRepository testRepository,
                                             TestAccessResolver testAccessResolver,
                                             ExamQuestionBankWriter questionWriter,
                                             LecturerExamService examService,
                                             TestActivityWriter activityWriter) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.lessonRepository = lessonRepository;
        this.accessPolicy = accessPolicy;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
        this.testRepository = testRepository;
        this.testAccessResolver = testAccessResolver;
        this.questionWriter = questionWriter;
        this.examService = examService;
        this.activityWriter = activityWriter;
    }

    @Transactional(readOnly = true)
    public List<TestDistributionTarget> eligibleClasses(Long userId, Role role, Long subjectId) {
        User actor = requireActor(userId, role);
        requireSubject(actor, subjectId);
        return testAccessResolver.manageableClasses(userId, role).stream()
                .filter(clazz -> subjectId.equals(clazz.getSubjectId()))
                .filter(clazz -> ClassEntity.STATUS_ACTIVE.equals(clazz.getStatus()))
                .map(clazz -> new TestDistributionTarget(clazz.getId(), clazz.getName()))
                .toList();
    }

    @Transactional
    public GenerationResult generate(Long userId, Role role, Long subjectId,
                                     String title, String scope, Long lessonTemplateId,
                                     Integer questionCount, List<Long> classIds) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        int requested = questionCount == null ? 10 : Math.max(1, Math.min(questionCount, 50));
        String normalizedScope = normalizeScope(scope);
        LessonTemplate selectedLesson = null;
        if (!SCOPE_SUBJECT.equals(normalizedScope)) {
            selectedLesson = lessonRepository.findByIdAndSubjectId(lessonTemplateId, subjectId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Vui lòng chọn bài học thuộc mã môn để random câu hỏi"));
        }

        List<QuestionBankItem> candidates = new ArrayList<>(itemRepository
                .findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                        subjectId, List.of(QuestionBankItem.STATUS_APPROVED)));
        if (SCOPE_LESSON.equals(normalizedScope)) {
            Long selectedLessonId = selectedLesson.getId();
            candidates.removeIf(item -> !selectedLessonId.equals(item.getLessonTemplateId()));
        } else if (SCOPE_CHAPTER.equals(normalizedScope)) {
            String chapter = selectedLesson.getChapterTitle();
            Map<Long, LessonTemplate> lessons = lessonRepository
                    .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subjectId)
                    .stream().collect(java.util.stream.Collectors.toMap(LessonTemplate::getId, row -> row));
            candidates.removeIf(item -> {
                Long lid = item.getLessonTemplateId();
                LessonTemplate lesson = lid != null ? lessons.get(lid) : null;
                return lesson == null || !chapter.equalsIgnoreCase(lesson.getChapterTitle());
            });
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Phạm vi đã chọn chưa có câu hỏi được duyệt");
        }
        Collections.shuffle(candidates);
        List<QuestionBankItem> selected = candidates.subList(0, Math.min(requested, candidates.size()));
        Map<Long, List<QuestionBankOption>> options = optionRepository
                .findByItemIdInOrderBySortOrderAscIdAsc(selected.stream().map(QuestionBankItem::getId).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        QuestionBankOption::getItemId, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));

        Test test = new Test(userId, Test.TYPE_MODULE);
        test.setTitle(normalizedTitle(title, subject.getCode(), normalizedScope, selectedLesson));
        test.setDescription("Đề random từ Bộ chung đã duyệt của " + subject.getCode());
        test.setSubjectId(subjectId);
        test.setClassId(null);
        test.setDurationMinutes(Math.max(10, selected.size() * 2));
        test.setPassingScore(BigDecimal.valueOf(Math.max(1, selected.size() / 2.0)));
        test.setTimeMode(Test.TIME_MODE_INDIVIDUAL);
        test.setShuffleQuestions(true);
        test.setShuffleOptions(true);
        test.setStatus(Test.STATUS_PUBLISHED);
        Test saved = testRepository.saveAndFlush(test);

        List<QuestionForm> snapshots = selected.stream().map(item -> new QuestionForm(
                null, item.getQuestionType(), item.getContent(), item.getExplanation(), BigDecimal.ONE,
                options.getOrDefault(item.getId(), List.of()).stream()
                        .map(option -> new OptionForm(null, option.getContent(), option.isCorrect()))
                        .toList())).toList();
        questionWriter.appendQuestions(saved.getId(), snapshots);
        saved.setTotalQuestions(snapshots.size());
        testRepository.save(saved);
        activityWriter.write(saved.getId(), TestActivity.TYPE_CREATED,
                "Tạo đề random từ ngân hàng câu hỏi " + subject.getCode(), null, userId);
        activityWriter.write(saved.getId(), TestActivity.TYPE_PUBLISHED,
                "Lưu đề hoàn tất vào Kho bài test", null, userId);

        int distributed = 0;
        List<Long> targets = classIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(classIds));
        if (!targets.isEmpty()) {
            distributed = examService.distributePublished(userId, role, saved.getId(), targets)
                    .distributedCount();
        }
        return new GenerationResult(saved.getId(), snapshots.size(), distributed);
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Không có quyền tạo đề"));
        if (actor.getRole() != role) throw new AccessDeniedException("Không có quyền tạo đề");
        return actor;
    }

    private Department requireSubject(User actor, Long subjectId) {
        if (subjectId == null || !accessPolicy.canAccessSubject(actor, subjectId)) {
            throw new AccessDeniedException("Không có quyền truy cập mã môn");
        }
        return subjectRepository.findById(subjectId).filter(Department::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Mã môn không còn hoạt động"));
    }

    private static String normalizeScope(String scope) {
        if (SCOPE_CHAPTER.equalsIgnoreCase(scope)) return SCOPE_CHAPTER;
        if (SCOPE_LESSON.equalsIgnoreCase(scope)) return SCOPE_LESSON;
        return SCOPE_SUBJECT;
    }

    private static String normalizedTitle(String title, String subjectCode, String scope,
                                          LessonTemplate lesson) {
        if (title != null && !title.isBlank()) return title.trim();
        String suffix = SCOPE_SUBJECT.equals(scope) ? "toàn mã môn"
                : SCOPE_CHAPTER.equals(scope) ? lesson.getChapterTitle() : lesson.getTitle();
        return subjectCode + " · Đề random " + suffix;
    }

    public record GenerationResult(Long testId, int questionCount, int distributedCount) {}
}
