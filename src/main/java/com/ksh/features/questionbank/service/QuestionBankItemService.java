package com.ksh.features.questionbank.service;

import com.ksh.common.HtmlSanitizer;
import com.ksh.entities.Department;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.questionbank.dto.QuestionBankItemForm;
import com.ksh.features.questionbank.dto.QuestionBankViews.ContributorOption;
import com.ksh.features.questionbank.dto.QuestionBankViews.ChapterOption;
import com.ksh.features.questionbank.dto.QuestionBankViews.ItemDetail;
import com.ksh.features.questionbank.dto.QuestionBankViews.ItemRow;
import com.ksh.features.questionbank.dto.QuestionBankViews.LessonOption;
import com.ksh.features.questionbank.dto.QuestionBankViews.QuestionGroup;
import com.ksh.features.questionbank.dto.QuestionBankViews.WorkspaceView;
import com.ksh.features.questionbank.dto.QuestionBankViews.OptionView;
import com.ksh.features.questionbank.dto.QuestionBankViews.StatusCounts;
import com.ksh.features.questionbank.dto.QuestionBankViews.SubjectReviewView;
import com.ksh.features.questionbank.dto.QuestionBankViews.SubjectOption;
import com.ksh.features.questionbank.dto.QuestionBankViews.SubjectCatalogRow;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.entity.QuestionBankOption;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lecturer authoring and leader review reads for a subject-scoped Question Bank. */
@Service
public class QuestionBankItemService {

    private static final String MSG_EMPTY_SUBJECT =
            "Chưa có mã môn đang hoạt động để soạn câu hỏi";
    private static final String MSG_NOT_FOUND = "Không tìm thấy câu hỏi cộng tác";
    private static final String MSG_FORBIDDEN = "Bạn không có quyền thao tác với câu hỏi cộng tác này";

    private final UserRepository userRepository;
    private final DepartmentRepository subjectRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;
    private final LessonTemplateRepository lessonRepository;

    public QuestionBankItemService(UserRepository userRepository,
                                   DepartmentRepository subjectRepository,
                                   QuestionBankAccessPolicy accessPolicy,
                                   QuestionBankItemRepository itemRepository,
                                   QuestionBankOptionRepository optionRepository,
                                   LessonTemplateRepository lessonRepository) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
        this.lessonRepository = lessonRepository;
    }

    @Transactional(readOnly = true)
    public List<ItemRow> list(Long userId, Role role, String status,
                              Long contributorId, String query) {
        return list(userId, role, null, status, contributorId, query);
    }

    @Transactional(readOnly = true)
    public List<ItemRow> list(Long userId, Role role, Long subjectId, String status,
                              Long contributorId, String query) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        List<QuestionBankItem> items = itemRepository
                .findBySubjectIdOrderByUpdatedAtDescIdDesc(subject.getId());
        Map<Long, String> userNames = userNames(items);
        Map<Long, LessonTemplate> lessons = lessonsById(items);
        String normalizedQuery = normalizeQuery(query);
        return items.stream()
                .filter(item -> matchesStatus(item, status))
                .filter(item -> contributorId == null || contributorId.equals(item.getContributorId()))
                .filter(item -> matchesQuery(item, subject.getCode(), userNames, normalizedQuery))
                .map(item -> toRow(actor, item, subject.getCode(), userNames, lessons))
                .toList();
    }

    /**
     * Bounded workspace read for large subject banks. Paging remains in the
     * database so rendering one screen never materializes the entire bank.
     */
    @Transactional(readOnly = true)
    public Page<ItemRow> page(Long userId, Role role, Long subjectId, String status,
                              String query, int page, int size) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String normalizedStatus = status == null || status.isBlank()
                || "ALL".equalsIgnoreCase(status.trim())
                ? null : status.trim().toUpperCase();
        String normalizedQuery = normalizeQuery(query);
        Page<QuestionBankItem> items = itemRepository.findPage(subject.getId(), normalizedStatus,
                normalizedQuery, PageRequest.of(safePage, safeSize));
        Map<Long, String> names = userNames(items.getContent());
        Map<Long, LessonTemplate> lessons = lessonsById(items.getContent());
        return items.map(item -> toRow(actor, item, subject.getCode(), names, lessons));
    }

    @Transactional(readOnly = true)
    public WorkspaceView workspace(Long userId, Role role, Long subjectId, String query) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        List<QuestionBankItem> items = itemRepository
                .findBySubjectIdOrderByUpdatedAtDescIdDesc(subject.getId());
        Map<Long, String> names = userNames(items);
        Map<Long, LessonTemplate> lessons = lessonsById(items);
        String normalizedQuery = normalizeQuery(query);
        List<ItemRow> approved = items.stream()
                .filter(item -> QuestionBankItem.STATUS_APPROVED.equals(item.getWorkflowStatus()))
                .filter(item -> matchesQuery(item, subject.getCode(), names, normalizedQuery))
                .map(item -> toRow(actor, item, subject.getCode(), names, lessons)).toList();
        List<ItemRow> pending = items.stream()
                .filter(item -> QuestionBankItem.STATUS_REVIEW.equals(item.getWorkflowStatus()))
                .filter(item -> matchesQuery(item, subject.getCode(), names, normalizedQuery))
                .map(item -> toRow(actor, item, subject.getCode(), names, lessons)).toList();
        SubjectOption option = new SubjectOption(subject.getId(), subject.getCode(),
                subject.getName(), subject.getDescription());
        return new WorkspaceView(option, groupRows(approved), groupRows(pending),
                approved.size(), pending.size());
    }

    /**
     * Lightweight workspace header for the paged lecturer screen. Unlike the
     * legacy grouped view, this never materializes every question in a subject.
     */
    @Transactional(readOnly = true)
    public WorkspaceView workspaceSummary(Long userId, Role role, Long subjectId, String query) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        String normalizedQuery = normalizeQuery(query);
        long approved = itemRepository.countForWorkspace(subject.getId(),
                QuestionBankItem.STATUS_APPROVED, normalizedQuery);
        long pending = itemRepository.countForWorkspace(subject.getId(),
                QuestionBankItem.STATUS_REVIEW, normalizedQuery);
        SubjectOption option = new SubjectOption(subject.getId(), subject.getCode(),
                subject.getName(), subject.getDescription());
        return new WorkspaceView(option, List.of(), List.of(), approved, pending);
    }

    private ItemRow toRow(User actor, QuestionBankItem item, String subjectCode,
                          Map<Long, String> userNames, Map<Long, LessonTemplate> lessons) {
        Long lessonTemplateId = item.getLessonTemplateId();
        LessonTemplate lesson = lessonTemplateId != null ? lessons.get(lessonTemplateId) : null;
        int chapterOrder = lesson != null ? lesson.getChapterOrder()
                : snapshotOrder(item.getChapterOrderSnapshot());
        int lessonOrder = lesson != null ? lesson.getDisplayOrder()
                : snapshotOrder(item.getLessonOrderSnapshot());
        String chapterTitle = lesson != null ? lesson.getChapterTitle()
                : snapshotTitle(item.getChapterTitleSnapshot(), "Chưa phân chương");
        String lessonTitle = lesson != null ? lesson.getTitle()
                : snapshotTitle(item.getLessonTitleSnapshot(), "Chưa gắn bài học");
        Long contributorId = item.getContributorId();
        String contributorName = contributorId != null ? userNames.getOrDefault(contributorId, "—") : "—";
        return new ItemRow(
                        item.getId(), preview(item.getContent()), item.getQuestionType(),
                        item.getWorkflowStatus(), subjectCode, lessonTemplateId,
                        chapterOrder, lessonOrder, chapterTitle, lessonTitle,
                        contributorId,
                        contributorName,
                        item.getUpdatedAt(), canEdit(actor, item), canReview(actor, item),
                        lesson != null);
    }

    private static List<QuestionGroup> groupRows(List<ItemRow> rows) {
        List<ItemRow> orderedRows = rows.stream()
                .sorted(Comparator.comparingInt(ItemRow::chapterOrder)
                        .thenComparingInt(ItemRow::lessonOrder)
                        .thenComparing(ItemRow::id))
                .toList();
        Map<String, List<ItemRow>> grouped = new LinkedHashMap<>();
        for (ItemRow row : orderedRows) {
            String key = row.chapterTitle() + "\u0000" + row.lessonTitle()
                    + "\u0000" + (row.lessonTemplateId() == null ? "" : row.lessonTemplateId());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream().map(group -> {
            ItemRow first = group.get(0);
            return new QuestionGroup(first.lessonTemplateId(), first.chapterTitle(),
                    first.lessonTitle(), List.copyOf(group));
        }).toList();
    }

    @Transactional(readOnly = true)
    public SubjectReviewView reviewView(Long userId, Role role, String status,
                                        Long contributorId, String query) {
        return reviewView(userId, role, null, status, contributorId, query);
    }

    @Transactional(readOnly = true)
    public SubjectReviewView reviewView(Long userId, Role role, Long subjectId,
                                        String status, Long contributorId, String query) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        List<QuestionBankItem> all = itemRepository
                .findBySubjectIdOrderByUpdatedAtDescIdDesc(subject.getId());
        Map<Long, String> names = userNames(all);
        String normalizedQuery = normalizeQuery(query);
        List<QuestionBankItem> filtered = all.stream()
                .filter(item -> matchesStatus(item, status))
                .filter(item -> contributorId == null || contributorId.equals(item.getContributorId()))
                .filter(item -> matchesQuery(item, subject.getCode(), names, normalizedQuery))
                .toList();
        Map<Long, List<OptionView>> options = optionsByItemId(filtered);
        List<ItemDetail> details = filtered.stream()
                .map(item -> toDetail(actor, item, subject.getCode(), names, options))
                .toList();
        return new SubjectReviewView(subject.getCode(), subject.getName(), details,
                tallyStatus(all), contributors(all, names));
    }

    @Transactional(readOnly = true)
    public StatusCounts countByStatus(Long userId, Role role) {
        User actor = requireActor(userId, role);
        return tallyStatus(itemRepository.findBySubjectIdOrderByUpdatedAtDescIdDesc(
                requireSubject(actor).getId()));
    }

    @Transactional(readOnly = true)
    public List<ContributorOption> contributorsFor(Long userId, Role role) {
        User actor = requireActor(userId, role);
        List<QuestionBankItem> items = itemRepository.findBySubjectIdOrderByUpdatedAtDescIdDesc(
                requireSubject(actor).getId());
        return contributors(items, userNames(items));
    }

    @Transactional(readOnly = true)
    public QuestionBankItemForm loadForm(Long userId, Role role, Long itemId) {
        User actor = requireActor(userId, role);
        QuestionBankItem item = requireVisibleItem(itemId, actor);
        if (!canEdit(actor, item)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        QuestionBankItemForm form = new QuestionBankItemForm();
        form.setId(item.getId());
        form.setSubjectId(item.getSubjectId());
        form.setLessonTemplateId(item.getLessonTemplateId());
        form.setQuestionType(item.getQuestionType());
        form.setContent(item.getContent());
        form.setExplanation(item.getExplanation());
        List<QuestionBankItemForm.OptionField> fields = new ArrayList<>();
        for (QuestionBankOption option : optionRepository
                .findByItemIdInOrderBySortOrderAscIdAsc(List.of(item.getId()))) {
            QuestionBankItemForm.OptionField field = new QuestionBankItemForm.OptionField();
            field.setContent(option.getContent());
            field.setCorrect(option.isCorrect());
            fields.add(field);
        }
        form.setOptions(fields);
        form.ensureMinOptions(4);
        form.setWorkflowAction(QuestionBankItem.STATUS_DRAFT);
        return form;
    }

    @Transactional(readOnly = true)
    public ItemDetail detail(Long userId, Role role, Long itemId) {
        User actor = requireActor(userId, role);
        QuestionBankItem item = requireVisibleItem(itemId, actor);
        Department subject = requireSubject(actor, item.getSubjectId());
        Map<Long, String> names = loadNames(Stream.of(item.getContributorId(), item.getReviewedBy())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return toDetail(actor, item, subject.getCode(), names, optionsByItemId(List.of(item)));
    }

    @Transactional
    public Long save(Long userId, Role role, QuestionBankItemForm form) {
        User actor = requireActor(userId, role);
        Long subjectId = requireSubject(actor, form.getSubjectId()).getId();
        LessonTemplate lesson = requireLesson(subjectId, form.getLessonTemplateId());
        Long lessonId = lesson.getId();
        List<QuestionBankOption> options = validatedOptions(form);
        String workflowStatus = resolveWorkflowAction(form.getWorkflowAction());
        QuestionBankItem item;
        if (form.getId() == null) {
            item = new QuestionBankItem(subjectId, lessonId, actor.getId(),
                    normalizedQuestionType(form.getQuestionType()), workflowStatus,
                    sanitizeRequired(form.getContent(), "Nội dung câu hỏi không được để trống"),
                    sanitizeOptional(form.getExplanation()));
        } else {
            item = requireVisibleItem(form.getId(), actor);
            if (!subjectId.equals(item.getSubjectId())) {
                throw new QuestionBankValidationException("Không thể chuyển câu hỏi sang mã môn khác");
            }
            if (!canEdit(actor, item)) {
                throw new AccessDeniedException(MSG_FORBIDDEN);
            }
            item.updateAuthoring(lessonId, normalizedQuestionType(form.getQuestionType()),
                    sanitizeRequired(form.getContent(), "Nội dung câu hỏi không được để trống"),
                    sanitizeOptional(form.getExplanation()));
            item.transitionWorkflow(workflowStatus, null, null, null, null);
        }
        item.bindLesson(lesson.getId(), lesson.getChapterOrder(), lesson.getChapterTitle(),
                lesson.getDisplayOrder(), lesson.getTitle());
        QuestionBankItem saved = itemRepository.save(item);
        optionRepository.deleteByItemIdIn(List.of(saved.getId()));
        int order = 1;
        for (QuestionBankOption option : options) {
            optionRepository.save(new QuestionBankOption(
                    saved.getId(), option.getContent(), option.isCorrect(), order++));
        }
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public boolean hasSubject(Long userId, Role role) {
        return !subjectOptions(userId, role).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<SubjectOption> subjectOptions(Long userId, Role role) {
        User actor = requireActor(userId, role);
        return allowedSubjects(actor).stream()
                .map(subject -> new SubjectOption(subject.getId(), subject.getCode(),
                        subject.getName(), subject.getDescription()))
                .toList();
    }

    /**
     * Returns the subject-first Question Bank catalog. Counts are aggregated in
     * two grouped queries, rather than loading every lesson and question row.
     */
    @Transactional(readOnly = true)
    public List<SubjectCatalogRow> subjectCatalog(Long userId, Role role, String query) {
        User actor = requireActor(userId, role);
        List<Department> subjects = allowedSubjects(actor);
        if (subjects.isEmpty()) return List.of();

        List<Long> subjectIds = subjects.stream().map(Department::getId).toList();
        Map<Long, LessonTemplateRepository.SubjectContentCount> contentCounts = lessonRepository
                .summarizeSubjects(subjectIds).stream()
                .collect(Collectors.toMap(
                        LessonTemplateRepository.SubjectContentCount::getSubjectId,
                        count -> count));
        Map<Long, QuestionBankItemRepository.SubjectQuestionCount> questionCounts = itemRepository
                .summarizeSubjects(subjectIds).stream()
                .collect(Collectors.toMap(
                        QuestionBankItemRepository.SubjectQuestionCount::getSubjectId,
                        count -> count));
        String normalizedQuery = normalizeQuery(query);

        return subjects.stream()
                .filter(subject -> matchesSubject(subject, normalizedQuery))
                .map(subject -> {
                    var content = contentCounts.get(subject.getId());
                    var questions = questionCounts.get(subject.getId());
                    return new SubjectCatalogRow(subject.getId(), subject.getCode(), subject.getName(),
                            subject.getDescription(),
                            content == null ? 0 : content.getChapterCount(),
                            content == null ? 0 : content.getLessonCount(),
                            questions == null ? 0 : questions.getQuestionCount());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LessonOption> lessonOptions(Long userId, Role role) {
        User actor = requireActor(userId, role);
        List<LessonOption> result = new ArrayList<>();
        for (Department subject : allowedSubjects(actor)) {
            for (LessonTemplate lesson : lessonRepository
                    .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId())) {
                result.add(new LessonOption(lesson.getId(), subject.getId(), subject.getCode(),
                        lesson.getChapterTitle(), lesson.getTitle()));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ChapterOption> chapterOptions(Long userId, Role role, Long subjectId) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        Map<Integer, ChapterOption> chapters = new LinkedHashMap<>();
        for (LessonTemplate lesson : lessonRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId())) {
            chapters.putIfAbsent(lesson.getChapterOrder(), new ChapterOption(
                    lesson.getId(), lesson.getChapterOrder(), lesson.getChapterTitle()));
        }
        return List.copyOf(chapters.values());
    }

    @Transactional(readOnly = true)
    public QuestionBankItemForm newForm(Long userId, Role role, Long subjectId) {
        User actor = requireActor(userId, role);
        Department subject = requireSubject(actor, subjectId);
        QuestionBankItemForm form = QuestionBankItemForm.empty();
        form.setSubjectId(subject.getId());
        return form;
    }

    QuestionBankItem requireVisibleItem(Long itemId, User actor) {
        QuestionBankItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_NOT_FOUND));
        if (!accessPolicy.canAccessSubject(actor, item.getSubjectId())
                || subjectRepository.findById(item.getSubjectId())
                .filter(Department::isActive).isEmpty()) {
            throw new QuestionBankValidationException(MSG_NOT_FOUND);
        }
        return item;
    }

    boolean canReview(User actor, QuestionBankItem item) {
        return accessPolicy.canCurateSubject(actor, item.getSubjectId())
                && QuestionBankItem.STATUS_REVIEW.equals(item.getWorkflowStatus());
    }

    boolean canArchive(User actor, QuestionBankItem item) {
        return accessPolicy.canCurateSubject(actor, item.getSubjectId())
                && !QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus());
    }

    boolean canUnarchive(User actor, QuestionBankItem item) {
        return accessPolicy.canCurateSubject(actor, item.getSubjectId())
                && QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus());
    }

    private boolean canEdit(User actor, QuestionBankItem item) {
        if (!accessPolicy.canAccessSubject(actor, item.getSubjectId())
                || QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus())) {
            return false;
        }
        if (accessPolicy.canCurateSubject(actor, item.getSubjectId())) {
            return !QuestionBankItem.STATUS_APPROVED.equals(item.getWorkflowStatus());
        }
        return actor.getId().equals(item.getContributorId())
                && !QuestionBankItem.STATUS_APPROVED.equals(item.getWorkflowStatus());
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        if (actor.getRole() != role) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return actor;
    }

    private Department requireSubject(User actor) {
        return requireSubject(actor, null);
    }

    private Department requireSubject(User actor, Long requestedSubjectId) {
        List<Department> allowed = allowedSubjects(actor);
        if (allowed.isEmpty()) {
            throw new QuestionBankValidationException(MSG_EMPTY_SUBJECT);
        }
        Long subjectId = requestedSubjectId != null
                ? requestedSubjectId : accessPolicy.resolveSubjectId(actor);
        if (subjectId == null) {
            return allowed.get(0);
        }
        if (subjectId == null || !accessPolicy.canAccessSubject(actor, subjectId)) {
            throw new QuestionBankValidationException(MSG_EMPTY_SUBJECT);
        }
        return allowed.stream()
                .filter(subject -> subjectId.equals(subject.getId()))
                .findFirst()
                .orElseThrow(() -> new QuestionBankValidationException(MSG_EMPTY_SUBJECT));
    }

    private List<Department> allowedSubjects(User actor) {
        if (actor.getRole() == Role.LEADER) {
            return subjectRepository.findByActiveTrueOrderByNameAsc().stream()
                    .filter(subject -> accessPolicy.canAccessSubject(actor, subject.getId()))
                    .sorted(Comparator.comparing(Department::getCode,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
        if (actor.getRole() != Role.LECTURER && actor.getRole() != Role.ADMIN) {
            return List.of();
        }
        return subjectRepository.findByActiveTrueOrderByNameAsc().stream()
                .sorted(Comparator.comparing(Department::getCode,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private LessonTemplate requireLesson(Long subjectId, Long lessonId) {
        if (lessonId == null) {
            throw new QuestionBankValidationException(
                    "Hãy tạo và chọn một chương/bài trong Kho học liệu trước khi thêm câu hỏi");
        }
        return lessonRepository.findByIdAndSubjectId(lessonId, subjectId)
                .orElseThrow(() -> new QuestionBankValidationException(
                        "Bài học không thuộc mã môn đã chọn"));
    }

    private static int snapshotOrder(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }

    private static String snapshotTitle(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<Long, LessonTemplate> lessonsById(List<QuestionBankItem> items) {
        List<Long> ids = items.stream().map(QuestionBankItem::getLessonTemplateId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return new LinkedHashMap<>();
        Map<Long, LessonTemplate> map = new LinkedHashMap<>();
        for (LessonTemplate lesson : lessonRepository.findAllById(ids)) {
            map.put(lesson.getId(), lesson);
        }
        return map;
    }

    private ItemDetail toDetail(User actor, QuestionBankItem item, String subjectCode,
                                Map<Long, String> names,
                                Map<Long, List<OptionView>> options) {
        Long contributorId = item.getContributorId();
        Long reviewedBy = item.getReviewedBy();
        return new ItemDetail(item.getId(), item.getQuestionType(), item.getWorkflowStatus(),
                HtmlSanitizer.sanitize(item.getContent()), preview(item.getContent()),
                sanitizeOptional(item.getExplanation()),
                item.getReviewNote(), subjectCode,
                contributorId,
                contributorId != null ? names.getOrDefault(contributorId, "—") : "—",
                reviewedBy != null ? names.get(reviewedBy) : null,
                item.getReviewedAt(), item.getApprovedAt(), item.getUpdatedAt(),
                options.getOrDefault(item.getId(), List.of()), canEdit(actor, item),
                canReview(actor, item), canArchive(actor, item), canUnarchive(actor, item));
    }

    private Map<Long, List<OptionView>> optionsByItemId(List<QuestionBankItem> items) {
        if (items.isEmpty()) return Map.of();
        Map<Long, List<OptionView>> result = new LinkedHashMap<>();
        for (QuestionBankOption option : optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(
                items.stream().map(QuestionBankItem::getId).toList())) {
            result.computeIfAbsent(option.getItemId(), ignored -> new ArrayList<>())
                    .add(new OptionView(
                            HtmlSanitizer.sanitize(option.getContent()), option.isCorrect()));
        }
        return result;
    }

    private static StatusCounts tallyStatus(List<QuestionBankItem> items) {
        return new StatusCounts(count(items, QuestionBankItem.STATUS_REVIEW),
                count(items, QuestionBankItem.STATUS_APPROVED),
                count(items, QuestionBankItem.STATUS_REJECTED),
                count(items, QuestionBankItem.STATUS_ARCHIVED), items.size());
    }

    private static long count(List<QuestionBankItem> items, String status) {
        return items.stream().filter(item -> status.equals(item.getWorkflowStatus())).count();
    }

    private List<ContributorOption> contributors(List<QuestionBankItem> items,
                                                 Map<Long, String> names) {
        Map<Long, ContributorOption> result = new LinkedHashMap<>();
        for (QuestionBankItem item : items) {
            result.computeIfAbsent(item.getContributorId(),
                    id -> new ContributorOption(id, names.getOrDefault(id, "—")));
        }
        return new ArrayList<>(result.values());
    }

    private Map<Long, String> userNames(List<QuestionBankItem> items) {
        return loadNames(items.stream()
                .flatMap(item -> Stream.of(item.getContributorId(), item.getReviewedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet()));
    }

    private Map<Long, String> loadNames(Set<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return names;
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private List<QuestionBankOption> validatedOptions(QuestionBankItemForm form) {
        List<QuestionBankOption> options = new ArrayList<>();
        int correct = 0;
        int order = 1;
        for (QuestionBankItemForm.OptionField field : form.getOptions()) {
            String content = sanitizeOptional(field.getContent());
            if (content == null) continue;
            if (field.isCorrect()) correct++;
            options.add(new QuestionBankOption(null, content, field.isCorrect(), order++));
        }
        if (options.size() < 2) throw new QuestionBankValidationException("Mỗi câu hỏi phải có ít nhất hai đáp án");
        if (correct == 0) throw new QuestionBankValidationException("Mỗi câu hỏi phải có ít nhất một đáp án đúng");
        if (QuestionBankItem.TYPE_MCQ.equals(normalizedQuestionType(form.getQuestionType())) && correct != 1) {
            throw new QuestionBankValidationException("Câu hỏi MCQ phải có đúng một đáp án đúng");
        }
        return options;
    }

    private static boolean matchesStatus(QuestionBankItem item, String status) {
        return status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                || status.equalsIgnoreCase(item.getWorkflowStatus());
    }

    private static boolean matchesQuery(QuestionBankItem item, String subjectCode,
                                        Map<Long, String> names, String query) {
        if (query == null) return true;
        return preview(item.getContent()).toLowerCase().contains(query)
                || names.getOrDefault(item.getContributorId(), "").toLowerCase().contains(query)
                || subjectCode.toLowerCase().contains(query);
    }

    private static boolean matchesSubject(Department subject, String query) {
        if (query == null) return true;
        return subject.getCode().toLowerCase().contains(query)
                || subject.getName().toLowerCase().contains(query);
    }

    private static String preview(String html) {
        String plain = html == null ? "" : html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
        return plain.length() > 120 ? plain.substring(0, 117) + "..." : plain;
    }

    private static String normalizeQuery(String query) {
        return query == null || query.isBlank() ? null : query.trim().toLowerCase();
    }

    private static String resolveWorkflowAction(String action) {
        return QuestionBankItem.STATUS_REVIEW.equalsIgnoreCase(action)
                ? QuestionBankItem.STATUS_REVIEW : QuestionBankItem.STATUS_DRAFT;
    }

    private static String normalizedQuestionType(String value) {
        return QuestionBankItem.TYPE_MR.equalsIgnoreCase(value)
                ? QuestionBankItem.TYPE_MR : QuestionBankItem.TYPE_MCQ;
    }

    private static String sanitizeRequired(String value, String message) {
        String sanitized = sanitizeOptional(value);
        if (sanitized == null) throw new QuestionBankValidationException(message);
        return sanitized;
    }

    private static String sanitizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        String sanitized = HtmlSanitizer.sanitize(value.trim()).trim();
        return sanitized.isBlank() ? null : sanitized;
    }
}
