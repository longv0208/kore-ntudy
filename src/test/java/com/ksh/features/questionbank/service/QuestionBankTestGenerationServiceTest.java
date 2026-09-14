package com.ksh.features.questionbank.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.TestActivity;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.entity.QuestionBankOption;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ksh.features.tests.dto.LecturerTestDtos.TestDistributionResult;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.features.tests.service.ExamQuestionBankWriter;
import com.ksh.features.tests.service.LecturerExamService;
import com.ksh.features.tests.service.TestActivityWriter;
import com.ksh.features.tests.support.TestAccessResolver;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionBankTestGenerationServiceTest {

    private static final long USER_ID = 7L;
    private static final long SUBJECT_ID = 41L;
    private static final long LESSON_ID = 75L;
    private static final long TEST_ID = 920L;

    private UserRepository userRepository;
    private SubjectRepository subjectRepository;
    private LessonTemplateRepository lessonRepository;
    private QuestionBankAccessPolicy accessPolicy;
    private QuestionBankItemRepository itemRepository;
    private QuestionBankOptionRepository optionRepository;
    private TestRepository testRepository;
    private TestAccessResolver testAccessResolver;
    private ExamQuestionBankWriter questionWriter;
    private LecturerExamService examService;
    private TestActivityWriter activityWriter;
    private QuestionBankTestGenerationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        lessonRepository = mock(LessonTemplateRepository.class);
        accessPolicy = mock(QuestionBankAccessPolicy.class);
        itemRepository = mock(QuestionBankItemRepository.class);
        optionRepository = mock(QuestionBankOptionRepository.class);
        testRepository = mock(TestRepository.class);
        testAccessResolver = mock(TestAccessResolver.class);
        questionWriter = mock(ExamQuestionBankWriter.class);
        examService = mock(LecturerExamService.class);
        activityWriter = mock(TestActivityWriter.class);
        service = new QuestionBankTestGenerationService(userRepository, subjectRepository,
                lessonRepository, accessPolicy, itemRepository, optionRepository,
                testRepository, testAccessResolver, questionWriter, examService, activityWriter);
    }

    @org.junit.jupiter.api.Test
    void eligibleClassesOnlyReturnsActiveClassesInTheSelectedSubject() {
        User actor = lecturer();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(actor));
        when(accessPolicy.canAccessSubject(actor, SUBJECT_ID)).thenReturn(true);
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(activeSubject()));
        when(testAccessResolver.manageableClasses(USER_ID, Role.LECTURER)).thenReturn(List.of(
                classroom(1L, SUBJECT_ID, ClassEntity.STATUS_ACTIVE, "Lớp hợp lệ"),
                classroom(2L, SUBJECT_ID, ClassEntity.STATUS_PENDING, "Lớp chờ duyệt"),
                classroom(3L, 99L, ClassEntity.STATUS_ACTIVE, "Sai mã môn")
        ));

        assertThat(service.eligibleClasses(USER_ID, Role.LECTURER, SUBJECT_ID))
                .extracting(target -> target.id(), target -> target.name())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "Lớp hợp lệ"));
    }

    @org.junit.jupiter.api.Test
    void generateSubjectScopePersistsPublishedSnapshotAndActivities() {
        arrangeActorAndSubject();
        QuestionBankItem first = approvedItem(101L, LESSON_ID, "Câu một");
        QuestionBankItem second = approvedItem(102L, LESSON_ID, "Câu hai");
        when(itemRepository.findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                SUBJECT_ID, List.of(QuestionBankItem.STATUS_APPROVED))).thenReturn(List.of(first, second));
        when(optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(anyList())).thenReturn(List.of(
                new QuestionBankOption(101L, "Đúng", true, 1),
                new QuestionBankOption(101L, "Sai", false, 2),
                new QuestionBankOption(102L, "Khác", true, 1)
        ));
        saveAs(TEST_ID);

        QuestionBankTestGenerationService.GenerationResult result = service.generate(
                USER_ID, Role.LECTURER, SUBJECT_ID, "  Đề giữa kỳ  ",
                QuestionBankTestGenerationService.SCOPE_SUBJECT, null, 20, List.of());

        assertThat(result).isEqualTo(new QuestionBankTestGenerationService.GenerationResult(TEST_ID, 2, 0));
        ArgumentCaptor<Test> saved = ArgumentCaptor.forClass(Test.class);
        verify(testRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Đề giữa kỳ");
        assertThat(saved.getValue()).extracting(Test::getStatus, Test::getType, Test::getClassId,
                        Test::getSubjectId, Test::getDurationMinutes, Test::getTotalQuestions)
                .containsExactly(Test.STATUS_PUBLISHED, Test.TYPE_MODULE, null, SUBJECT_ID, 10, 2);
        ArgumentCaptor<List<QuestionForm>> questions = ArgumentCaptor.forClass(List.class);
        verify(questionWriter).appendQuestions(eq(TEST_ID), questions.capture());
        assertThat(questions.getValue()).hasSize(2);
        assertThat(questions.getValue()).allSatisfy(question -> assertThat(question.points()).isEqualByComparingTo("1"));
        verify(activityWriter).write(eq(TEST_ID), eq(TestActivity.TYPE_CREATED), any(), eq(null), eq(USER_ID));
        verify(activityWriter).write(eq(TEST_ID), eq(TestActivity.TYPE_PUBLISHED), any(), eq(null), eq(USER_ID));
    }

    @org.junit.jupiter.api.Test
    void generateChapterScopeUsesOnlyLessonsFromSelectedChapter() {
        arrangeActorAndSubject();
        LessonTemplate selected = lesson(LESSON_ID, "Chương 2", "Bài 3 · Chọn lọc");
        LessonTemplate sibling = lesson(76L, "Chương 2", "Bài 4 · Cùng chương");
        LessonTemplate other = lesson(77L, "Chương 3", "Bài 5 · Khác chương");
        when(lessonRepository.findByIdAndSubjectId(LESSON_ID, SUBJECT_ID)).thenReturn(Optional.of(selected));
        when(lessonRepository.findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(SUBJECT_ID))
                .thenReturn(List.of(selected, sibling, other));
        when(itemRepository.findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                SUBJECT_ID, List.of(QuestionBankItem.STATUS_APPROVED))).thenReturn(List.of(
                        approvedItem(201L, LESSON_ID, "A"),
                        approvedItem(202L, sibling.getId(), "B"),
                        approvedItem(203L, other.getId(), "C")
        ));
        when(optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(anyList())).thenReturn(List.of());
        saveAs(TEST_ID);

        QuestionBankTestGenerationService.GenerationResult result = service.generate(
                USER_ID, Role.LECTURER, SUBJECT_ID, null,
                QuestionBankTestGenerationService.SCOPE_CHAPTER, LESSON_ID, 50, null);

        assertThat(result.questionCount()).isEqualTo(2);
        ArgumentCaptor<List<QuestionForm>> questions = ArgumentCaptor.forClass(List.class);
        verify(questionWriter).appendQuestions(eq(TEST_ID), questions.capture());
        assertThat(questions.getValue()).extracting(QuestionForm::content).containsExactlyInAnyOrder("A", "B");
        ArgumentCaptor<Test> saved = ArgumentCaptor.forClass(Test.class);
        verify(testRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("KOR · Đề random Chương 2");
    }

    @org.junit.jupiter.api.Test
    void generateLessonScopeKeepsTheSelectedLessonAndDeduplicatesDistributionTargets() {
        arrangeActorAndSubject();
        LessonTemplate selected = lesson(LESSON_ID, "Chương 1", "Bài 1 · Giới thiệu");
        when(lessonRepository.findByIdAndSubjectId(LESSON_ID, SUBJECT_ID)).thenReturn(Optional.of(selected));
        when(itemRepository.findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                SUBJECT_ID, List.of(QuestionBankItem.STATUS_APPROVED))).thenReturn(List.of(
                        approvedItem(301L, LESSON_ID, "Đúng phạm vi"),
                        approvedItem(302L, 99L, "Ngoài phạm vi")
        ));
        when(optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(anyList())).thenReturn(List.of());
        saveAs(TEST_ID);
        when(examService.distributePublished(USER_ID, Role.LECTURER, TEST_ID, List.of(1L, 2L)))
                .thenReturn(new TestDistributionResult(List.of(1001L, 1002L)));

        QuestionBankTestGenerationService.GenerationResult result = service.generate(
                USER_ID, Role.LECTURER, SUBJECT_ID, null,
                QuestionBankTestGenerationService.SCOPE_LESSON, LESSON_ID, 1, List.of(1L, 1L, 2L));

        assertThat(result).isEqualTo(new QuestionBankTestGenerationService.GenerationResult(TEST_ID, 1, 2));
        verify(examService).distributePublished(USER_ID, Role.LECTURER, TEST_ID, List.of(1L, 2L));
        ArgumentCaptor<Test> saved = ArgumentCaptor.forClass(Test.class);
        verify(testRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("KOR · Đề random Bài 1 · Giới thiệu");
    }

    @org.junit.jupiter.api.Test
    void generateRejectsUnapprovedSubjectBeforeAnySnapshotIsWritten() {
        User actor = lecturer();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(actor));
        when(accessPolicy.canAccessSubject(actor, SUBJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.generate(USER_ID, Role.LECTURER, SUBJECT_ID,
                "Đề", "UNRECOGNIZED", null, 10, List.of()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("mã môn");
        verify(testRepository, never()).saveAndFlush(any());
        verify(questionWriter, never()).appendQuestions(any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void generateRequiresAnExistingLessonForLessonAndChapterScope() {
        arrangeActorAndSubject();
        when(lessonRepository.findByIdAndSubjectId(LESSON_ID, SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(USER_ID, Role.LECTURER, SUBJECT_ID,
                null, QuestionBankTestGenerationService.SCOPE_LESSON, LESSON_ID, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bài học thuộc mã môn");
        verify(itemRepository, never()).findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void generateStopsWhenNoApprovedQuestionExistsInTheSelectedScope() {
        arrangeActorAndSubject();
        when(itemRepository.findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                SUBJECT_ID, List.of(QuestionBankItem.STATUS_APPROVED))).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(USER_ID, Role.LECTURER, SUBJECT_ID,
                null, QuestionBankTestGenerationService.SCOPE_SUBJECT, null, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chưa có câu hỏi được duyệt");
        verify(testRepository, never()).saveAndFlush(any());
    }

    @org.junit.jupiter.api.Test
    void generateRejectsAUserWhosePersistedRoleDoesNotMatchTheSessionRole() {
        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.STUDENT);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(actor));

        assertThatThrownBy(() -> service.eligibleClasses(USER_ID, Role.LECTURER, SUBJECT_ID))
                .isInstanceOf(AccessDeniedException.class);
        verify(subjectRepository, never()).findById(any());
    }

    private void arrangeActorAndSubject() {
        User actor = lecturer();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(actor));
        when(accessPolicy.canAccessSubject(actor, SUBJECT_ID)).thenReturn(true);
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(activeSubject()));
    }

    private void saveAs(long id) {
        when(testRepository.saveAndFlush(any(Test.class))).thenAnswer(invocation -> {
            Test test = invocation.getArgument(0);
            ReflectionTestUtils.setField(test, "id", id);
            return test;
        });
        when(testRepository.save(any(Test.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static User lecturer() {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.LECTURER);
        return user;
    }

    private static Subject activeSubject() {
        Subject subject = new Subject("Korean", "KOR", null, true);
        ReflectionTestUtils.setField(subject, "id", SUBJECT_ID);
        return subject;
    }

    private static LessonTemplate lesson(long id, String chapter, String title) {
        LessonTemplate lesson = new LessonTemplate(USER_ID, SUBJECT_ID, chapter, title, "RICHTEXT");
        ReflectionTestUtils.setField(lesson, "id", id);
        return lesson;
    }

    private static QuestionBankItem approvedItem(long id, long lessonId, String content) {
        QuestionBankItem item = new QuestionBankItem(SUBJECT_ID, lessonId, USER_ID,
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_APPROVED, content, "Giải thích");
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static ClassEntity classroom(long id, long subjectId, String status, String name) {
        ClassEntity clazz = new ClassEntity(name, USER_ID, USER_ID, null, null, null, 50);
        ReflectionTestUtils.setField(clazz, "id", id);
        clazz.setSubjectId(subjectId);
        ReflectionTestUtils.setField(clazz, "status", status);
        return clazz;
    }
}
