package com.ksh.features.questionbank.service;

import com.ksh.entities.Department;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.questionbank.dto.QuestionBankItemForm;
import com.ksh.features.questionbank.dto.QuestionBankViews.ChapterOption;
import com.ksh.features.questionbank.dto.QuestionBankViews.ItemRow;
import com.ksh.features.questionbank.dto.QuestionBankViews.SubjectOption;
import com.ksh.features.questionbank.dto.QuestionBankViews.SubjectCatalogRow;
import com.ksh.features.questionbank.dto.QuestionBankViews.WorkspaceView;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionBankItemServiceTest {

    private static final long ACTOR_ID = 31L;
    private static final long SUBJECT_ID = 12L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DepartmentRepository subjectRepository = mock(DepartmentRepository.class);
    private final QuestionBankAccessPolicy accessPolicy = mock(QuestionBankAccessPolicy.class);
    private final QuestionBankItemRepository itemRepository = mock(QuestionBankItemRepository.class);
    private final QuestionBankOptionRepository optionRepository = mock(QuestionBankOptionRepository.class);
    private final LessonTemplateRepository lessonRepository = mock(LessonTemplateRepository.class);
    private final QuestionBankItemService service = new QuestionBankItemService(
            userRepository, subjectRepository, accessPolicy, itemRepository, optionRepository, lessonRepository);

    private User lecturer;
    private Department subject;

    @BeforeEach
    void setUp() {
        lecturer = mock(User.class);
        when(lecturer.getId()).thenReturn(ACTOR_ID);
        when(lecturer.getFullName()).thenReturn("Giảng viên Kim");
        when(lecturer.getRole()).thenReturn(Role.LECTURER);
        when(lecturer.getSubjectId()).thenReturn(SUBJECT_ID);
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(lecturer));

        subject = subject("Korean Intermediate", "KOR311", SUBJECT_ID, true);
        when(subjectRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(subject));
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject));
        when(accessPolicy.canAccessSubject(lecturer, SUBJECT_ID)).thenReturn(true);
    }

    @Test
    void list_filters_status_contributor_and_normalized_query() {
        QuestionBankItem matching = item(101L, QuestionBankItem.STATUS_REVIEW, ACTOR_ID, 201L,
                "<p>Alpha <strong>question</strong></p>");
        QuestionBankItem otherStatus = item(102L, QuestionBankItem.STATUS_DRAFT, ACTOR_ID, 201L,
                "<p>Alpha draft</p>");
        QuestionBankItem otherContributor = item(103L, QuestionBankItem.STATUS_REVIEW, 88L, 201L,
                "<p>Alpha foreign</p>");
        when(itemRepository.findBySubjectIdOrderByUpdatedAtDescIdDesc(SUBJECT_ID))
                .thenReturn(List.of(matching, otherStatus, otherContributor));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(lecturer));
        when(lessonRepository.findAllById(List.of(201L))).thenReturn(List.of());

        List<ItemRow> rows = service.list(ACTOR_ID, Role.LECTURER, SUBJECT_ID,
                QuestionBankItem.STATUS_REVIEW, ACTOR_ID, "  ALPHA  ");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(101L);
            assertThat(row.contentPreview()).isEqualTo("Alpha question");
            assertThat(row.contributorId()).isEqualTo(ACTOR_ID);
            assertThat(row.contributorName()).isEqualTo("Giảng viên Kim");
            assertThat(row.editable()).isTrue();
            assertThat(row.reviewable()).isFalse();
        });
        verify(userRepository, times(1)).findAllById(anyCollection());
    }

    @Test
    void workspace_groups_only_approved_and_review_items_by_lesson() {
        QuestionBankItem approved = item(101L, QuestionBankItem.STATUS_APPROVED, ACTOR_ID, 201L, "<p>A</p>");
        QuestionBankItem review = item(102L, QuestionBankItem.STATUS_REVIEW, ACTOR_ID, 202L, "<p>B</p>");
        QuestionBankItem draft = item(103L, QuestionBankItem.STATUS_DRAFT, ACTOR_ID, 201L, "<p>C</p>");
        LessonTemplate firstLesson = lesson(201L, 1, 1, "Chapter one", "Lesson one");
        LessonTemplate secondLesson = lesson(202L, 2, 1, "Chapter two", "Lesson two");
        when(itemRepository.findBySubjectIdOrderByUpdatedAtDescIdDesc(SUBJECT_ID))
                .thenReturn(List.of(draft, review, approved));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(lessonRepository.findAllById(List.of(201L, 202L))).thenReturn(List.of(firstLesson, secondLesson));

        WorkspaceView workspace = service.workspace(ACTOR_ID, Role.LECTURER, SUBJECT_ID, "");

        assertThat(workspace.approvedCount()).isEqualTo(1);
        assertThat(workspace.pendingCount()).isEqualTo(1);
        assertThat(workspace.approvedGroups()).singleElement()
                .satisfies(group -> assertThat(group.lessonTitle()).isEqualTo("Lesson one"));
        assertThat(workspace.pendingGroups()).singleElement()
                .satisfies(group -> assertThat(group.lessonTitle()).isEqualTo("Lesson two"));
    }

    @Test
    void require_visible_item_rejects_inactive_or_out_of_scope_subjects() {
        QuestionBankItem item = item(101L, QuestionBankItem.STATUS_DRAFT, ACTOR_ID, 201L, "<p>A</p>");
        when(itemRepository.findById(101L)).thenReturn(Optional.of(item));
        when(accessPolicy.canAccessSubject(lecturer, SUBJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.requireVisibleItem(101L, lecturer))
                .isInstanceOf(QuestionBankValidationException.class);

        when(accessPolicy.canAccessSubject(lecturer, SUBJECT_ID)).thenReturn(true);
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject("Hidden", "HID", SUBJECT_ID, false)));

        assertThatThrownBy(() -> service.requireVisibleItem(101L, lecturer))
                .isInstanceOf(QuestionBankValidationException.class);
    }

    @Test
    void require_visible_item_rejects_missing_item() {
        when(itemRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireVisibleItem(999L, lecturer))
                .isInstanceOf(QuestionBankValidationException.class);
    }

    @Test
    void review_and_archive_flags_require_curator_scope_and_matching_state() {
        QuestionBankItem review = item(101L, QuestionBankItem.STATUS_REVIEW, ACTOR_ID, 201L, "<p>A</p>");
        QuestionBankItem archived = item(102L, QuestionBankItem.STATUS_ARCHIVED, ACTOR_ID, 201L, "<p>B</p>");
        when(accessPolicy.canCurateSubject(lecturer, SUBJECT_ID)).thenReturn(true);

        assertThat(service.canReview(lecturer, review)).isTrue();
        assertThat(service.canArchive(lecturer, review)).isTrue();
        assertThat(service.canUnarchive(lecturer, review)).isFalse();
        assertThat(service.canArchive(lecturer, archived)).isFalse();
        assertThat(service.canUnarchive(lecturer, archived)).isTrue();

        when(accessPolicy.canCurateSubject(lecturer, SUBJECT_ID)).thenReturn(false);
        assertThat(service.canReview(lecturer, review)).isFalse();
        assertThat(service.canArchive(lecturer, review)).isFalse();
    }

    @Test
    void subject_options_are_catalog_wide_for_lecturers_and_sorted_by_code() {
        Department later = subject("Korean advanced", "KOR401", 13L, true);
        Department first = subject("Korean foundation", "KOR101", 11L, true);
        when(subjectRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(later, first));

        List<SubjectOption> options = service.subjectOptions(ACTOR_ID, Role.LECTURER);

        assertThat(options).extracting(SubjectOption::code).containsExactly("KOR101", "KOR401");
    }

    @Test
    void subject_catalog_aggregates_content_and_question_counts_and_filters_by_name() {
        Department other = subject("Japanese foundation", "JPN101", 13L, true);
        when(subjectRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(other, subject));
        LessonTemplateRepository.SubjectContentCount content =
                mock(LessonTemplateRepository.SubjectContentCount.class);
        when(content.getSubjectId()).thenReturn(SUBJECT_ID);
        when(content.getChapterCount()).thenReturn(3L);
        when(content.getLessonCount()).thenReturn(8L);
        QuestionBankItemRepository.SubjectQuestionCount questions =
                mock(QuestionBankItemRepository.SubjectQuestionCount.class);
        when(questions.getSubjectId()).thenReturn(SUBJECT_ID);
        when(questions.getQuestionCount()).thenReturn(47L);
        when(lessonRepository.summarizeSubjects(List.of(13L, SUBJECT_ID)))
                .thenReturn(List.of(content));
        when(itemRepository.summarizeSubjects(List.of(13L, SUBJECT_ID)))
                .thenReturn(List.of(questions));

        List<SubjectCatalogRow> rows = service.subjectCatalog(
                ACTOR_ID, Role.LECTURER, "intermediate");

        assertThat(rows).containsExactly(new SubjectCatalogRow(
                SUBJECT_ID, "KOR311", "Korean Intermediate", null, 3, 8, 47, null));
    }

    @Test
    void page_clamps_requested_size_and_maps_only_the_database_page() {
        QuestionBankItem item = item(501L, QuestionBankItem.STATUS_APPROVED,
                ACTOR_ID, 201L, "<p>Page-only question</p>");
        when(itemRepository.findPage(SUBJECT_ID, QuestionBankItem.STATUS_APPROVED, "page",
                PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 100), 1001));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(lecturer));
        LessonTemplate linkedLesson = lesson(201L, 1, 1, "Foundation", "Greeting");
        when(lessonRepository.findAllById(List.of(201L))).thenReturn(List.of(linkedLesson));

        var result = service.page(ACTOR_ID, Role.LECTURER, SUBJECT_ID,
                "approved", " PAGE ", -3, 500);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1001);
        assertThat(result.getContent().get(0).contentPreview()).isEqualTo("Page-only question");
    }

    @Test
    void page_falls_back_to_saved_hierarchy_when_library_lesson_is_unavailable() {
        QuestionBankItem item = item(502L, QuestionBankItem.STATUS_APPROVED,
                ACTOR_ID, 201L, "<p>Historical question</p>");
        item.bindLesson(201L, 3, "Chapter snapshot", 4, "Lesson snapshot");
        PageRequest request = PageRequest.of(0, 25);
        when(itemRepository.findPage(SUBJECT_ID, null, null, request))
                .thenReturn(new PageImpl<>(List.of(item), request, 1));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(lecturer));
        when(lessonRepository.findAllById(List.of(201L))).thenReturn(List.of());

        ItemRow row = service.page(ACTOR_ID, Role.LECTURER, SUBJECT_ID,
                null, null, 0, 25).getContent().get(0);

        assertThat(row.chapterTitle()).isEqualTo("Chapter snapshot");
        assertThat(row.lessonTitle()).isEqualTo("Lesson snapshot");
        assertThat(row.chapterOrder()).isEqualTo(3);
        assertThat(row.lessonOrder()).isEqualTo(4);
        assertThat(row.librarySourceAvailable()).isFalse();
    }

    @Test
    void page_treats_all_status_as_no_filter_for_legacy_urls() {
        PageRequest request = PageRequest.of(1, 25);
        when(itemRepository.findPage(SUBJECT_ID, null, null, request))
                .thenReturn(new PageImpl<>(List.of(), request, 0));

        service.page(ACTOR_ID, Role.LECTURER, SUBJECT_ID, " ALL ", " ", 1, 25);

        verify(itemRepository).findPage(SUBJECT_ID, null, null, request);
    }

    @Test
    void workspace_summary_counts_statuses_without_loading_all_items() {
        when(itemRepository.countForWorkspace(SUBJECT_ID,
                QuestionBankItem.STATUS_APPROVED, "alpha")).thenReturn(37L);
        when(itemRepository.countForWorkspace(SUBJECT_ID,
                QuestionBankItem.STATUS_REVIEW, "alpha")).thenReturn(4L);

        WorkspaceView result = service.workspaceSummary(
                ACTOR_ID, Role.LECTURER, SUBJECT_ID, " ALPHA ");

        assertThat(result.approvedCount()).isEqualTo(37);
        assertThat(result.pendingCount()).isEqualTo(4);
        assertThat(result.approvedGroups()).isEmpty();
        assertThat(result.pendingGroups()).isEmpty();
        verify(itemRepository, never()).findBySubjectIdOrderByUpdatedAtDescIdDesc(SUBJECT_ID);
    }

    @Test
    void chapter_options_keep_first_lesson_of_each_chapter_in_library_order() {
        LessonTemplate chapterOneFirst = lesson(201L, 1, 1, "Chapter one", "One");
        LessonTemplate chapterOneSecond = lesson(202L, 1, 2, "Chapter one", "Two");
        LessonTemplate chapterTwo = lesson(203L, 2, 1, "Chapter two", "Three");
        when(lessonRepository.findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(SUBJECT_ID))
                .thenReturn(List.of(chapterOneFirst, chapterOneSecond, chapterTwo));

        List<ChapterOption> options = service.chapterOptions(ACTOR_ID, Role.LECTURER, SUBJECT_ID);

        assertThat(options).containsExactly(
                new ChapterOption(201L, 1, "Chapter one"),
                new ChapterOption(203L, 2, "Chapter two"));
    }

    @Test
    void new_form_uses_the_requested_active_subject() {
        QuestionBankItemForm form = service.newForm(ACTOR_ID, Role.LECTURER, SUBJECT_ID);

        assertThat(form.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(form.getOptions()).hasSize(4);
        assertThat(form.getWorkflowAction()).isEqualTo(QuestionBankItem.STATUS_DRAFT);
    }

    @Test
    void save_creates_review_item_and_persists_ordered_options() {
        LessonTemplate lesson = lesson(201L, 1, 1, "Chapter one", "Lesson one");
        when(lessonRepository.findByIdAndSubjectId(201L, SUBJECT_ID)).thenReturn(Optional.of(lesson));
        when(itemRepository.save(any(QuestionBankItem.class))).thenAnswer(invocation -> {
            QuestionBankItem saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 701L);
            return saved;
        });
        QuestionBankItemForm form = validForm();
        form.setWorkflowAction(QuestionBankItem.STATUS_REVIEW);

        Long id = service.save(ACTOR_ID, Role.LECTURER, form);

        assertThat(id).isEqualTo(701L);
        ArgumentCaptor<QuestionBankItem> itemCaptor = ArgumentCaptor.forClass(QuestionBankItem.class);
        verify(itemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_REVIEW);
        assertThat(itemCaptor.getValue().getQuestionType()).isEqualTo(QuestionBankItem.TYPE_MCQ);
        assertThat(itemCaptor.getValue().getChapterTitleSnapshot()).isEqualTo("Chapter one");
        assertThat(itemCaptor.getValue().getLessonTitleSnapshot()).isEqualTo("Lesson one");
        verify(optionRepository).deleteByItemIdIn(List.of(701L));
        verify(optionRepository, times(2)).save(any());
    }

    @Test
    void save_rejects_mcq_without_exactly_one_correct_option_before_writing() {
        LessonTemplate lesson = lesson(201L, 1, 1, "Chapter one", "Lesson one");
        when(lessonRepository.findByIdAndSubjectId(201L, SUBJECT_ID)).thenReturn(Optional.of(lesson));
        QuestionBankItemForm form = validForm();
        form.getOptions().forEach(option -> option.setCorrect(true));

        assertThatThrownBy(() -> service.save(ACTOR_ID, Role.LECTURER, form))
                .isInstanceOf(QuestionBankValidationException.class);

        verify(itemRepository, never()).save(any());
        verify(optionRepository, never()).save(any());
    }

    @Test
    void load_form_blocks_edits_to_approved_item_created_by_lecturer() {
        QuestionBankItem approved = item(101L, QuestionBankItem.STATUS_APPROVED, ACTOR_ID, 201L, "<p>A</p>");
        when(itemRepository.findById(101L)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.loadForm(ACTOR_ID, Role.LECTURER, 101L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void actor_role_mismatch_is_forbidden_before_subject_or_item_access() {
        assertThatThrownBy(() -> service.subjectOptions(ACTOR_ID, Role.ADMIN))
                .isInstanceOf(AccessDeniedException.class);

        verify(subjectRepository, never()).findByActiveTrueOrderByNameAsc();
    }

    private QuestionBankItemForm validForm() {
        QuestionBankItemForm form = QuestionBankItemForm.empty();
        form.setSubjectId(SUBJECT_ID);
        form.setLessonTemplateId(201L);
        form.setQuestionType(QuestionBankItem.TYPE_MCQ);
        form.setContent("<p>Which answer is correct?</p>");
        form.setOptions(List.of(option("A", true), option("B", false)));
        return form;
    }

    private static QuestionBankItemForm.OptionField option(String content, boolean correct) {
        QuestionBankItemForm.OptionField option = new QuestionBankItemForm.OptionField();
        option.setContent(content);
        option.setCorrect(correct);
        return option;
    }

    private static QuestionBankItem item(long id, String status, long contributorId,
                                         long lessonId, String content) {
        QuestionBankItem item = new QuestionBankItem(SUBJECT_ID, lessonId, contributorId,
                QuestionBankItem.TYPE_MCQ, status, content, null);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static Department subject(String name, String code, long id, boolean active) {
        Department subject = new Department(name, code, null, active);
        ReflectionTestUtils.setField(subject, "id", id);
        return subject;
    }

    private static LessonTemplate lesson(long id, int chapterOrder, int displayOrder,
                                         String chapterTitle, String title) {
        LessonTemplate lesson = new LessonTemplate(ACTOR_ID, SUBJECT_ID, chapterOrder, chapterTitle,
                displayOrder, title, Lesson.CONTENT_TYPE_RICHTEXT);
        ReflectionTestUtils.setField(lesson, "id", id);
        return lesson;
    }
}
