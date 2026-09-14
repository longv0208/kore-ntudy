package com.ksh.features.questionbank.service;

import com.ksh.entities.Subject;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.questionbank.dto.QuestionBankImportDtos.ConfirmResult;
import com.ksh.features.questionbank.dto.QuestionBankImportDtos.PreviewRow;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.entity.QuestionBankOption;
import com.ksh.features.questionbank.imports.QuestionBankImportParser;
import com.ksh.features.questionbank.imports.QuestionBankImportParser.ParsedFile;
import com.ksh.features.questionbank.imports.QuestionBankImportParser.RawRow;
import com.ksh.features.questionbank.imports.QuestionBankImportSession;
import com.ksh.features.questionbank.imports.QuestionBankImportSession.ImportedItem;
import com.ksh.features.questionbank.imports.QuestionBankImportSession.ImportedOption;
import com.ksh.features.questionbank.imports.QuestionBankImportSessionStore;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Two-step preview and confirm flow for subject-scoped Excel imports. */
@Service
public class QuestionBankImportService {

    private static final String MSG_FORBIDDEN = "Bạn không có quyền import câu hỏi của mã môn này";
    private static final String MSG_SESSION_EXPIRED =
            "Phiên import đã hết hạn hoặc không tồn tại. Vui lòng tải file lên lại";
    private static final String MSG_BLOCKING_ERRORS =
            "Bản xem trước còn lỗi chặn nên chưa thể xác nhận import";
    private static final String MSG_EMPTY_SUBJECT =
            "Chưa có mã môn đang hoạt động để import câu hỏi";
    private static final int MAX_PREVIEW_LENGTH = 80;

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;
    private final LessonTemplateRepository lessonRepository;
    private final QuestionBankImportParser importParser;
    private final QuestionBankImportSessionStore sessionStore;

    public QuestionBankImportService(UserRepository userRepository,
                                     SubjectRepository subjectRepository,
                                     QuestionBankAccessPolicy accessPolicy,
                                     QuestionBankItemRepository itemRepository,
                                     QuestionBankOptionRepository optionRepository,
                                     LessonTemplateRepository lessonRepository,
                                     QuestionBankImportParser importParser,
                                     QuestionBankImportSessionStore sessionStore) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
        this.lessonRepository = lessonRepository;
        this.importParser = importParser;
        this.sessionStore = sessionStore;
    }

    /**
     * Returns the actor's active subject code for a workbook sample.
     * The same access check is reused by preview and confirm.
     */
    @Transactional(readOnly = true)
    public String subjectCodeForTemplate(Long userId, Role role) {
        return subjectCodeForTemplate(userId, role, null);
    }

    @Transactional(readOnly = true)
    public String subjectCodeForTemplate(Long userId, Role role, Long subjectId) {
        User actor = requireActor(userId, role);
        return requireSubject(actor, subjectId).getCode();
    }

    @Transactional(readOnly = true)
    public QuestionBankImportSession previewUpload(Long userId, Role role, MultipartFile file) {
        return previewUpload(userId, role, null, file);
    }

    @Transactional(readOnly = true)
    public QuestionBankImportSession previewUpload(Long userId, Role role, Long subjectId,
                                                   MultipartFile file) {
        User actor = requireActor(userId, role);
        Subject subject = requireSubject(actor, subjectId);
        Long firstLessonId = lessonRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId())
                .stream().findFirst()
                .map(com.ksh.entities.LessonTemplate::getId)
                .orElseThrow(() -> new QuestionBankValidationException(
                        "Mã môn chưa có chương/bài trong Kho học liệu"));
        return previewUpload(userId, role, subjectId, firstLessonId, file);
    }

    @Transactional(readOnly = true)
    public QuestionBankImportSession previewUpload(Long userId, Role role, Long subjectId,
                                                   Long lessonTemplateId, MultipartFile file) {
        User actor = requireActor(userId, role);
        Subject subject = requireSubject(actor, subjectId);
        Long lessonId = lessonRepository.findByIdAndSubjectId(lessonTemplateId, subject.getId())
                .map(com.ksh.entities.LessonTemplate::getId)
                .orElseThrow(() -> new QuestionBankValidationException(
                        "Hãy chọn một chương/bài hợp lệ trong Kho học liệu trước khi import"));
        String workflowStatus = importedWorkflowStatus(actor);
        ParsedFile parsed = importParser.parse(file);

        List<ImportedItem> acceptedItems = new ArrayList<>();
        List<PreviewRow> previewRows = new ArrayList<>();
        for (RawRow raw : parsed.rows()) {
            ValidationResult result = validateRow(raw, subject.getCode());
            previewRows.add(result.previewRow());
            if (!result.blocking()) {
                acceptedItems.add(result.item());
            }
        }

        QuestionBankImportSession session = new QuestionBankImportSession(
                UUID.randomUUID(),
                actor.getId(),
                subject.getId(),
                lessonId,
                Instant.now(),
                parsed.fileName(),
                workflowStatus,
                acceptedItems,
                previewRows);
        sessionStore.save(session);
        return session;
    }

    @Transactional
    public ConfirmResult confirm(Long userId, Role role, UUID sessionId) {
        User actor = requireActor(userId, role);
        QuestionBankImportSession session = sessionStore.claim(sessionId, actor.getId())
                .orElseThrow(() -> new QuestionBankValidationException(MSG_SESSION_EXPIRED));
        Subject subject;
        try {
            subject = requireSubject(actor, session.getSubjectId());
        } catch (QuestionBankValidationException | AccessDeniedException ex) {
            sessionStore.restore(session);
            throw ex;
        }
        if (!session.toPreview().confirmable()) {
            sessionStore.restore(session);
            throw new QuestionBankValidationException(MSG_BLOCKING_ERRORS);
        }
        restoreSessionOnRollback(session);

        LessonTemplate lesson = lessonRepository
                .findByIdAndSubjectId(session.getLessonTemplateId(), subject.getId())
                .orElseThrow(() -> new QuestionBankValidationException(
                        "Bài học trong Kho học liệu đã thay đổi hoặc bị xóa; vui lòng xem trước lại file import"));

        List<Long> itemIds = new ArrayList<>();
        for (ImportedItem importedItem : session.getItems()) {
            QuestionBankItem item = new QuestionBankItem(
                    subject.getId(),
                    session.getLessonTemplateId(),
                    actor.getId(),
                    importedItem.questionType(),
                    session.getWorkflowStatus(),
                    importedItem.contentHtml(),
                    importedItem.explanationHtml());
            item.bindLesson(lesson.getId(), lesson.getChapterOrder(), lesson.getChapterTitle(),
                    lesson.getDisplayOrder(), lesson.getTitle());
            item = itemRepository.save(item);
            int order = 1;
            for (ImportedOption option : importedItem.options()) {
                optionRepository.save(new QuestionBankOption(
                        item.getId(), option.contentHtml(), option.correct(), order++));
            }
            itemIds.add(item.getId());
        }
        return new ConfirmResult(itemIds.size(), session.toPreview().totalRows(), session.getWorkflowStatus(), itemIds);
    }

    private void restoreSessionOnRollback(QuestionBankImportSession session) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    sessionStore.restore(session);
                }
            }
        });
    }

    private ValidationResult validateRow(RawRow raw, String expectedSubjectCode) {
        List<String> messages = new ArrayList<>();
        if (raw.subjectCode() == null
                || !expectedSubjectCode.equalsIgnoreCase(raw.subjectCode().trim())) {
            messages.add("Mã môn phải là " + expectedSubjectCode);
        }

        String questionType = normalizeQuestionType(raw.questionType());
        if (questionType == null) {
            messages.add("Loại câu hỏi chỉ chấp nhận MCQ hoặc MR");
        }

        String contentHtml = plainTextToHtml(raw.content());
        if (contentHtml == null) {
            messages.add("Nội dung câu hỏi không được để trống");
        }

        String explanationHtml = plainTextToHtml(raw.explanation());
        if (explanationHtml != null && explanationHtml.length() > 5000) {
            messages.add("Giải thích tối đa 5000 ký tự");
        }

        List<ImportedOption> importedOptions = new ArrayList<>();
        Map<String, Integer> optionIndexByLabel = new LinkedHashMap<>();
        int visualIndex = 0;
        for (String value : raw.optionValues()) {
            String content = plainTextToHtml(value);
            String label = String.valueOf((char) ('A' + visualIndex));
            if (content != null) {
                optionIndexByLabel.put(label, importedOptions.size());
                importedOptions.add(new ImportedOption(content, false, importedOptions.size() + 1));
            }
            visualIndex++;
        }
        if (importedOptions.size() < 2) {
            messages.add("Cần ít nhất hai đáp án không rỗng");
        }

        Set<String> correctLabels = parseCorrectAnswers(raw.correctAnswers());
        if (correctLabels.isEmpty()) {
            messages.add("Phải khai báo ít nhất một đáp án đúng");
        }
        int correctCount = 0;
        for (String label : correctLabels) {
            Integer index = optionIndexByLabel.get(label);
            if (index == null) {
                messages.add("Đáp án đúng chứa lựa chọn không tồn tại: " + label);
                continue;
            }
            ImportedOption old = importedOptions.get(index);
            importedOptions.set(index, new ImportedOption(old.contentHtml(), true, old.sortOrder()));
            correctCount++;
        }
        if (QuestionBankItem.TYPE_MCQ.equals(questionType) && correctCount != 1) {
            messages.add("Câu hỏi MCQ phải có đúng một đáp án đúng");
        }
        if (QuestionBankItem.TYPE_MR.equals(questionType) && correctCount == 0) {
            messages.add("Câu hỏi MR phải có ít nhất một đáp án đúng");
        }

        boolean blocking = !messages.isEmpty();
        String message = blocking ? String.join("; ", messages) : "Sẵn sàng import";
        PreviewRow previewRow = new PreviewRow(
                raw.rowNumber(),
                raw.subjectCode(),
                questionType == null ? blankToDash(raw.questionType()) : questionType,
                preview(raw.content()),
                blocking ? "ERROR" : "READY",
                message,
                importedOptions.size(),
                correctCount,
                blocking);
        ImportedItem item = blocking ? null : new ImportedItem(
                questionType,
                contentHtml,
                explanationHtml,
                importedOptions);
        return new ValidationResult(previewRow, item, blocking);
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        if (actor.getRole() != role) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        if (role != Role.LECTURER && role != Role.LEADER && role != Role.ADMIN) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return actor;
    }

    private Subject requireSubject(User actor) {
        return requireSubject(actor, null);
    }

    private Subject requireSubject(User actor, Long requestedSubjectId) {
        Long subjectId = requestedSubjectId != null
                ? requestedSubjectId : accessPolicy.resolveSubjectId(actor);
        if (subjectId == null && (actor.getRole() == Role.LECTURER
                || actor.getRole() == Role.ADMIN)) {
            return subjectRepository.findByActiveTrueOrderByNameAsc().stream()
                    .findFirst()
                    .orElseThrow(() -> new QuestionBankValidationException(MSG_EMPTY_SUBJECT));
        }
        if (subjectId == null || !accessPolicy.canAccessSubject(actor, subjectId)) {
            throw new QuestionBankValidationException(MSG_EMPTY_SUBJECT);
        }
        return subjectRepository.findById(subjectId)
                .filter(Subject::isActive)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_EMPTY_SUBJECT));
    }

    private static String importedWorkflowStatus(User actor) {
        return actor.getRole() == Role.LECTURER
                ? QuestionBankItem.STATUS_REVIEW
                : QuestionBankItem.STATUS_APPROVED;
    }

    private static String normalizeQuestionType(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (QuestionBankItem.TYPE_MCQ.equals(normalized) || QuestionBankItem.TYPE_MR.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private static Set<String> parseCorrectAnswers(String raw) {
        Set<String> labels = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return labels;
        }
        for (String token : raw.toUpperCase(Locale.ROOT).split("[,;/\\s]+")) {
            if (!token.isBlank()) {
                labels.add(token.trim());
            }
        }
        return labels;
    }

    private static String plainTextToHtml(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = HtmlUtils.htmlEscape(value.trim());
        return "<p>" + escaped.replace("\n", "<br>") + "</p>";
    }

    private static String preview(String value) {
        String trimmed = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return trimmed.length() > MAX_PREVIEW_LENGTH ? trimmed.substring(0, MAX_PREVIEW_LENGTH - 3) + "..." : trimmed;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record ValidationResult(PreviewRow previewRow,
                                    ImportedItem item,
                                    boolean blocking) {
    }
}
