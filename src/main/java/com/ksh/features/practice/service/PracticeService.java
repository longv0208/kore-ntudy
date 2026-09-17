package com.ksh.features.practice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ksh.entities.PracticeQuestion;
import com.ksh.entities.PracticeQuestionGroupVersion;
import com.ksh.entities.PracticeQuestionVersion;
import com.ksh.entities.PracticeAttemptEvaluationJob;
import com.ksh.entities.PracticeSet;
import com.ksh.entities.PracticeSetVersion;
import com.ksh.entities.PracticeQuestionGroup;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.repository.PracticeQuestionGroupRepository;
import com.ksh.features.practice.dto.PracticeDtos;
import com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionGroupRow;
import com.ksh.features.practice.dto.PracticeDtos.ExampleBox;
import com.ksh.features.practice.ai.writing.WritingEvaluationClient;
import com.ksh.features.practice.ai.writing.WritingEvaluationResult;
import com.ksh.features.practice.ai.writing.WritingEvaluationNormalizer;
import com.ksh.features.practice.ai.writing.WritingAssessmentPolicyBundle;
import com.ksh.features.practice.ai.writing.WritingEvidenceLedgerVerifier;
import com.ksh.features.practice.ai.writing.WritingFeedbackContractParser;
import com.ksh.features.practice.ai.writing.WritingScoreAnchorPolicy;
import com.ksh.features.practice.ai.writing.WritingScoringPolicy;
import com.ksh.features.practice.ai.writing.WritingTaskRequirementPolicy;
import com.ksh.features.practice.ai.speaking.SpeakingEvaluationResult;
import com.ksh.features.practice.ai.speaking.SpeakingEvaluationApplicationService;
import com.ksh.features.practice.ai.speaking.SpeakingEvaluationStatus;
import com.ksh.features.practice.ai.speaking.SpeakingFeedbackContractParser;
import com.ksh.features.practice.ai.speaking.SpeakingScorePolicy;
import com.ksh.features.practice.assessment.AnswerSpec;
import com.ksh.features.practice.assessment.AssessmentContractCodec;
import com.ksh.features.practice.assessment.AssessmentScoreResult;
import com.ksh.features.practice.assessment.AssessmentScoreStatus;
import com.ksh.features.practice.assessment.AssessmentScoringEngine;
import com.ksh.features.practice.assessment.CanonicalQuestionType;
import com.ksh.features.practice.assessment.LearnerAnswer;
import com.ksh.features.practice.assessment.QuestionContent;
import com.ksh.features.practice.assessment.WritingBlankContract;
import com.ksh.features.practice.assessment.WritingBlankContractVerifier;
import com.ksh.features.practice.assessment.SpeakingPromptDelivery;
import com.ksh.features.practice.assessment.SpeakingPromptDeliveryPresenter;
import com.ksh.features.practice.assessment.PracticeSectionDelivery;
import com.ksh.features.practice.assessment.QuestionTypeResolver;
import com.ksh.features.practice.assessment.ScoringPolicyCode;
import com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionOptionRow;
import com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionRow;
import com.ksh.features.practice.dto.PracticeDtos.PracticeSetRow;
import com.ksh.features.practice.dto.PracticeDtos.PracticeSetView;
import com.ksh.features.practice.dto.PracticeDtos.PracticeTestRow;
import com.ksh.features.practice.repository.PracticeQuestionRepository;
import com.ksh.features.practice.repository.PracticeQuestionVersionRepository;
import com.ksh.features.practice.repository.PracticeSetRepository;
import com.ksh.features.practice.repository.PracticeSectionRepository;
import com.ksh.features.practice.repository.PracticeAttemptRepository;
import com.ksh.features.practice.repository.PracticeAttemptEvaluationJobRepository;
import com.ksh.features.practice.repository.PracticeTestRepository;
import com.ksh.entities.PracticeAttempt;
import com.ksh.entities.PracticeTest;
import com.ksh.entities.PracticeSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);
    private static final String SPEAKING_AI_CONTRACT = "speaking_ai_v1";
    private static final String SPEAKING_CONTRACT_FIELD = "_contract";
    private static final String SPEAKING_FEEDBACK_BY_QUESTION_FIELD =
            "speaking_feedback_by_question";
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)");
    private static final Pattern MATERIAL_CONTENT_REFERENCE_PATTERN =
            Pattern.compile("^/practice/materials/[1-9][0-9]*/content$");
    private static final Pattern TOPIK35_IMAGE_REFERENCE_PATTERN =
            Pattern.compile("^/images/practice/topik35/[0-9a-f]{64}\\.png$");
    private static final Pattern TOPIK35_AUDIO_REFERENCE_PATTERN =
            Pattern.compile("^/audio/practice/topik35/[0-9a-f]{64}\\.mp3$");
    private static final Pattern EXPLICIT_TYPED_QUESTION_CONTENT_PATTERN =
            Pattern.compile(
                    "\"schemaVersion\"\\s*:\\s*\"question-content-v[23]\"");
    private static final String BUILT_IN_LISTENING_CHECK_AUDIO_REFERENCE =
            "/audio/practice/listening-speaker-check.wav";
    private static final PracticeAttemptStatePolicy ATTEMPT_STATE =
            PracticeAttemptStatePolicy.INSTANCE;
    private static final Duration EVALUATION_JOB_WINDOW =
            Duration.ofMinutes(30);

    private final PracticeSetRepository setRepository;
    private final PracticeQuestionRepository questionRepository;
    private final PracticeQuestionVersionRepository questionVersionRepository;
    private final PracticeQuestionGroupRepository groupRepository;
    private final PracticeSectionRepository sectionRepository;
    private final PracticeAttemptRepository attemptRepository;
    private final PracticeAttemptEvaluationJobRepository
            attemptEvaluationJobRepository;
    private final PracticeTestRepository testRepository;
    private final WritingEvaluationClient evaluationClient;
    private final WritingFeedbackContractParser writingFeedbackParser;
    private final SpeakingFeedbackContractParser speakingFeedbackParser;
    private SpeakingEvaluationApplicationService speakingEvaluationApplicationService;
    private PracticeSpeakingMediaService speakingMediaService;
    private PracticePublishedVersionService publishedVersionService;
    private final ObjectMapper objectMapper;
    private final QuestionTypeResolver questionTypeResolver;
    private final AssessmentContractCodec assessmentContractCodec;
    private final AssessmentScoringEngine assessmentScoringEngine;
    private final PracticeAttemptAnswerCodec attemptAnswerCodec;
    private final SpeakingPromptDeliveryPresenter
            speakingPromptDeliveryPresenter =
            new SpeakingPromptDeliveryPresenter();
    private final TransactionTemplate readTransactionTemplate;
    private final TransactionTemplate nonTransactionalTemplate;
    private final TransactionTemplate writeTransactionTemplate;

    @Autowired
    public PracticeService(PracticeSetRepository setRepository,
                           PracticeQuestionRepository questionRepository,
                           PracticeQuestionVersionRepository questionVersionRepository,
                           PracticeQuestionGroupRepository groupRepository,
                           PracticeSectionRepository sectionRepository,
                           PracticeAttemptRepository attemptRepository,
                           PracticeAttemptEvaluationJobRepository
                                   attemptEvaluationJobRepository,
                           PracticeTestRepository testRepository,
                           WritingEvaluationClient evaluationClient,
                           WritingFeedbackContractParser writingFeedbackParser,
                           SpeakingFeedbackContractParser speakingFeedbackParser,
                           PracticePublishedVersionService publishedVersionService,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.setRepository = setRepository;
        this.questionRepository = questionRepository;
        this.questionVersionRepository = questionVersionRepository;
        this.groupRepository = groupRepository;
        this.sectionRepository = sectionRepository;
        this.attemptRepository = attemptRepository;
        this.attemptEvaluationJobRepository =
                attemptEvaluationJobRepository;
        this.testRepository = testRepository;
        this.evaluationClient = evaluationClient;
        this.writingFeedbackParser = writingFeedbackParser;
        this.speakingFeedbackParser = speakingFeedbackParser;
        this.publishedVersionService = publishedVersionService;
        this.objectMapper = objectMapper;
        this.questionTypeResolver = new QuestionTypeResolver();
        this.assessmentContractCodec = new AssessmentContractCodec(objectMapper, questionTypeResolver);
        this.assessmentScoringEngine = new AssessmentScoringEngine();
        this.attemptAnswerCodec =
                new PracticeAttemptAnswerCodec(objectMapper);
        this.readTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readTransactionTemplate.setReadOnly(true);
        this.readTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.nonTransactionalTemplate = new TransactionTemplate(transactionManager);
        this.nonTransactionalTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.writeTransactionTemplate = new TransactionTemplate(transactionManager);
        this.writeTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Autowired(required = false)
    void setSpeakingEvaluationApplicationService(SpeakingEvaluationApplicationService speakingEvaluationApplicationService) {
        this.speakingEvaluationApplicationService = speakingEvaluationApplicationService;
    }

    @Autowired(required = false)
    void setSpeakingMediaService(PracticeSpeakingMediaService speakingMediaService) {
        this.speakingMediaService = speakingMediaService;
    }

    void setPublishedVersionServiceForTests(PracticePublishedVersionService publishedVersionService) {
        this.publishedVersionService = publishedVersionService;
    }

    PracticeService(PracticeSetRepository setRepository,
                    PracticeQuestionRepository questionRepository,
                    PracticeQuestionVersionRepository questionVersionRepository,
                    PracticeQuestionGroupRepository groupRepository,
                    PracticeSectionRepository sectionRepository,
                    PracticeAttemptRepository attemptRepository,
                    PracticeTestRepository testRepository,
                    WritingEvaluationClient evaluationClient,
                    ObjectMapper objectMapper) {
        this.setRepository = setRepository;
        this.questionRepository = questionRepository;
        this.questionVersionRepository = questionVersionRepository;
        this.groupRepository = groupRepository;
        this.sectionRepository = sectionRepository;
        this.attemptRepository = attemptRepository;
        this.attemptEvaluationJobRepository = null;
        this.testRepository = testRepository;
        this.evaluationClient = evaluationClient;
        this.writingFeedbackParser = new WritingFeedbackContractParser(objectMapper);
        this.speakingFeedbackParser = new SpeakingFeedbackContractParser(objectMapper, new com.ksh.features.practice.ai.speaking.SpeakingEvaluationNormalizer());
        this.publishedVersionService = null;
        this.objectMapper = objectMapper;
        this.questionTypeResolver = new QuestionTypeResolver();
        this.assessmentContractCodec = new AssessmentContractCodec(objectMapper, questionTypeResolver);
        this.assessmentScoringEngine = new AssessmentScoringEngine();
        this.attemptAnswerCodec =
                new PracticeAttemptAnswerCodec(objectMapper);
        this.readTransactionTemplate = null;
        this.nonTransactionalTemplate = null;
        this.writeTransactionTemplate = null;
    }

    @Transactional(readOnly = true)
    public PracticeSetView getPractice(Long setId) {
        PracticeSet set = loadPublished(setId);
        List<PracticeQuestionGroup> dbGroups = groupRepository.findBySetIdOrderByDisplayOrderAsc(setId);
        List<PracticeQuestion> dbQuestions = questionRepository.findBySetIdOrderByDisplayOrderAsc(setId);
        List<PracticeTestRow> tests = testRepository.findBySetIdOrderByDisplayOrderAsc(setId)
                .stream()
                .map(PracticeService::toTestRow)
                .toList();

        List<PracticeQuestionGroupRow> groups;
        if (dbGroups.isEmpty()) {
            groups = fallbackGrouping(dbQuestions);
        } else {
            groups = dbGroups.stream().map(g -> {
                List<PracticeQuestionRow> qRows = dbQuestions.stream()
                        .filter(q -> g.getId().equals(q.getGroupId()))
                        .map(this::toQuestionRow)
                        .toList();
                return toGroupRow(g, qRows);
            }).toList();
        }

        return new PracticeSetView(toSetRow(set), groups, List.of(), tests);
    }

    @Transactional(readOnly = true)
    public PracticeSetView getPracticeSummary(Long setId) {
        PracticeSet set = loadPublished(setId);
        List<PracticeTestRow> tests = testRepository.findBySetIdOrderByDisplayOrderAsc(setId)
                .stream()
                .map(PracticeService::toTestRow)
                .toList();
        return new PracticeSetView(toSetRow(set), List.of(), List.of(), tests);
    }

    public Long reEvaluate(Long attemptId, Long userId) {
        PracticeAttempt gatedAttempt = executeRead(() ->
                requireReEvaluationAttempt(
                        attemptId,
                        userId,
                        PracticeAttemptStatePolicy.ReEvaluationAction.FULL_ATTEMPT));
        WritingGradingSnapshot snapshot = executeRead(() ->
                loadWritingReEvaluationSnapshot(gatedAttempt));
        if (snapshot != null) {
            WritingGradingResult result = executeNonTransactional(() -> gradeWritingSnapshot(snapshot, true));
            return executeWrite(() -> persistWritingReEvaluationResult(snapshot, result));
        }
        return executeWrite(() -> reEvaluateInTransaction(attemptId, userId));
    }

    public Long reEvaluateQuestion(Long attemptId, Long questionId, Long userId) {
        PracticeAttempt gatedAttempt = executeRead(() ->
                requireReEvaluationAttempt(
                        attemptId,
                        userId,
                        PracticeAttemptStatePolicy.ReEvaluationAction
                                .SINGLE_WRITING_QUESTION));
        WritingQuestionReEvaluationSnapshot snapshot = executeRead(
                () -> loadWritingQuestionReEvaluationSnapshot(
                        gatedAttempt, questionId));
        WritingGradingResult result = executeNonTransactional(() -> gradeWritingQuestionSnapshot(snapshot));
        return executeWrite(() -> persistWritingQuestionReEvaluationResult(snapshot, result));
    }

    @Transactional
    public ReEvaluationRequestResult requestReEvaluation(
            Long attemptId,
            Long questionId,
            Long userId) {
        if (attemptEvaluationJobRepository == null) {
            throw new IllegalStateException(
                    "Durable evaluation jobs are unavailable.");
        }
        PracticeAttempt attempt = requireReEvaluationAttemptForUpdate(
                attemptId,
                userId,
                questionId == null
                        ? PracticeAttemptStatePolicy.ReEvaluationAction
                                .FULL_ATTEMPT
                        : PracticeAttemptStatePolicy.ReEvaluationAction
                                .SINGLE_WRITING_QUESTION);
        if (!"WRITING".equals(attempt.getSkill())) {
            throw new PracticeAttemptStatePolicy
                    .PracticeReEvaluationNotAllowedException(
                    PracticeAttemptStatePolicy.ReEvaluationRejection
                            .UNSUPPORTED_ACTION,
                    "Chỉ hỗ trợ xếp lịch chấm lại bất đồng bộ cho bài Writing.");
        }
        if (questionId != null) {
            loadWritingQuestionReEvaluationSnapshot(
                    attempt, questionId);
        } else {
            loadWritingReEvaluationSnapshot(attempt);
        }

        Long scheduledQuestionId = questionId;
        if (questionId != null
                && (!PracticeAttempt.ANALYSIS_SUCCEEDED.equals(
                        attempt.getAnalysisStatus())
                || attempt.getAiFeedbackJson() == null
                || attempt.getAiFeedbackJson().isBlank())) {
            scheduledQuestionId = null;
        }

        LocalDateTime now = LocalDateTime.now();
        String operation = scheduledQuestionId == null
                ? PracticeAttemptEvaluationJob
                        .OPERATION_FULL_REEVALUATE
                : PracticeAttemptEvaluationJob
                        .OPERATION_QUESTION_REEVALUATE;
        String evaluationContractIdentity =
                evaluationContractIdentity(attempt.getSkill());
        String fingerprint = evaluationInputFingerprint(
                attempt,
                normalizeJsonForCompare(attempt.getAnswersJson()),
                reEvaluationIdentityMaterial(
                        attempt, scheduledQuestionId));
        PracticeAttemptEvaluationJob job =
                attemptEvaluationJobRepository
                        .findByAttemptId(attemptId)
                        .orElse(null);
        if (job != null && activeEvaluationJob(job)) {
            return new ReEvaluationRequestResult(
                    "ALREADY_QUEUED",
                    "Yêu cầu chấm lại đang được xử lý.");
        }
        if (job != null) {
            job = attemptEvaluationJobRepository
                    .findByAttemptIdForUpdate(attemptId)
                    .orElse(null);
            if (job != null && activeEvaluationJob(job)) {
                return new ReEvaluationRequestResult(
                        "ALREADY_QUEUED",
                        "Yêu cầu chấm lại đang được xử lý.");
            }
        }
        if (job == null) {
            int inserted = attemptEvaluationJobRepository
                    .insertIfAbsent(
                            attemptId,
                            operation,
                            scheduledQuestionId,
                            fingerprint,
                            evaluationContractIdentity,
                            PracticeAttemptEvaluationJob.STATUS_QUEUED,
                            3,
                            now,
                            now.plus(EVALUATION_JOB_WINDOW),
                            userId,
                            null);
            if (inserted != 1) {
                PracticeAttemptEvaluationJob concurrent =
                        attemptEvaluationJobRepository
                                .findByAttemptIdForUpdate(attemptId)
                                .orElseThrow(this::conflict);
                if (activeEvaluationJob(concurrent)) {
                    return new ReEvaluationRequestResult(
                            "ALREADY_QUEUED",
                            "Yêu cầu chấm lại đang được xử lý.");
                }
                throw conflict();
            }
        } else {
            if (activeEvaluationJob(job)) {
                return new ReEvaluationRequestResult(
                        "ALREADY_QUEUED",
                        "Yêu cầu chấm lại đang được xử lý.");
            }
            if (job.manualRetryLimitReached()) {
                return new ReEvaluationRequestResult(
                        "RETRY_LIMIT_REACHED",
                        "Đã đạt giới hạn hai lần yêu cầu chấm lại cho lượt làm bài này.");
            }
            if (job.getLastRetryRequestedAt() != null
                    && job.getLastRetryRequestedAt()
                            .plusMinutes(1).isAfter(now)) {
                return new ReEvaluationRequestResult(
                        "RATE_LIMITED",
                        "Vui lòng đợi một phút trước khi yêu cầu chấm lại.");
            }
            job.requestManualRetry(
                    operation,
                    scheduledQuestionId,
                    fingerprint,
                    evaluationContractIdentity,
                    userId,
                    now,
                    now.plus(EVALUATION_JOB_WINDOW));
            attemptEvaluationJobRepository.save(job);
        }
        attempt.markAnalysisQueued(now);
        attemptRepository.save(attempt);
        return new ReEvaluationRequestResult(
                "QUEUED",
                "Đã xếp lịch chấm lại. Kết quả hiện tại được giữ nguyên cho đến khi có đánh giá mới.");
    }

    private static boolean activeEvaluationJob(
            PracticeAttemptEvaluationJob job) {
        return PracticeAttemptEvaluationJob.STATUS_QUEUED.equals(
                job.getJobStatus())
                || PracticeAttemptEvaluationJob.STATUS_PROCESSING.equals(
                        job.getJobStatus())
                || PracticeAttemptEvaluationJob.STATUS_RETRY_WAIT.equals(
                        job.getJobStatus());
    }

    public record ReEvaluationRequestResult(
            String status,
            String message
    ) {
    }

    private PracticeAttempt requireReEvaluationAttempt(
            Long attemptId,
            Long userId,
            PracticeAttemptStatePolicy.ReEvaluationAction action
    ) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kết quả không tồn tại"));
        return validateReEvaluationAttempt(attempt, action);
    }

    private PracticeAttempt requireReEvaluationAttemptForUpdate(
            Long attemptId,
            Long userId,
            PracticeAttemptStatePolicy.ReEvaluationAction action
    ) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserIdForUpdate(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kết quả không tồn tại"));
        return validateReEvaluationAttempt(attempt, action);
    }

    private PracticeAttempt validateReEvaluationAttempt(
            PracticeAttempt attempt,
            PracticeAttemptStatePolicy.ReEvaluationAction action
    ) {
        ATTEMPT_STATE.reEvaluationEligibility(attempt, action)
                .requireEligible();
        ATTEMPT_STATE.requireCoherentReEvaluationIdentity(
                publishedVersionService != null
                        && publishedVersionService
                        .hasCoherentAttemptIdentity(attempt));
        loadPublished(attempt.getSetId());
        return attempt;
    }

    private Long reEvaluateInTransaction(Long attemptId, Long userId) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Kết quả không tồn tại"));

        PracticeSection section = sectionRepository.findById(attempt.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));

        if (!attempt.getSetId().equals(section.getSetId()) ||
            !attempt.getTestId().equals(section.getTestId()) ||
            !attempt.getSkill().equals(section.getSkill())) {
            throw new IllegalArgumentException("Section metadata mismatch with attempt");
        }

        loadPublished(attempt.getSetId());

        List<QuestionSnapshot> sectionQuestions = loadQuestionSnapshots(attempt, section.getId());

        Map<String, String> submittedAnswers = readAnswers(attempt.getAnswersJson());

        if ("WRITING".equals(attempt.getSkill())) {
            throw new IllegalStateException("Writing attempt must use snapshot grading path.");
        }

        BigDecimal earnedPoints = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (QuestionSnapshot q : sectionQuestions) {
            total = total.add(q.points());
            String answer = submittedAnswers.getOrDefault(String.valueOf(q.questionId()), "").trim();

            Optional<AssessmentScoreResult> objectiveScore = scoreObjective(q, answer);
            if (objectiveScore.isPresent()) {
                earnedPoints = earnedPoints.add(objectiveScore.get().earnedPoints());
            } else if (PracticeQuestion.TYPE_ESSAY.equals(q.questionType())) {
                throw new IllegalStateException("Essay attempt must use snapshot grading path.");
            } else if (PracticeQuestion.TYPE_SPEAKING.equals(q.questionType())) {
                throw new IllegalStateException(
                        "Speaking re-evaluation requires immutable audio evidence.");
            } else {
                throw new IllegalStateException("Unsupported question type for question ID "
                        + q.questionId() + ": " + q.questionType());
            }
        }
        attempt.markSubmitted(
                earnedPoints,
                total,
                writeJson(submittedAnswers));

        attemptRepository.save(attempt);
        log.info(
                "[PracticeService] Re-evaluated PracticeAttempt id={} score={} / {}",
                attempt.getId(),
                earnedPoints,
                total);
        return attempt.getId();
    }

    private boolean isSpeakingAiEnvelope(JsonNode rootNode) {
        return rootNode != null
                && rootNode.isObject()
                && SPEAKING_AI_CONTRACT.equals(
                rootNode.path(SPEAKING_CONTRACT_FIELD).asText(null));
    }

    public String speakingAiFeedbackEnvelope(Map<Long, SpeakingEvaluationResult> feedbackByQuestionId) {
        com.fasterxml.jackson.databind.node.ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put(SPEAKING_CONTRACT_FIELD, SPEAKING_AI_CONTRACT);
        com.fasterxml.jackson.databind.node.ObjectNode byQuestion =
                envelope.putObject(SPEAKING_FEEDBACK_BY_QUESTION_FIELD);
        if (feedbackByQuestionId != null) {
            feedbackByQuestionId.forEach((questionId, feedback) -> {
                if (questionId != null && feedback != null) {
                    byQuestion.set(String.valueOf(questionId), objectMapper.valueToTree(feedback));
                }
            });
        }
        return writeJson(envelope);
    }

    public static final Comparator<PracticeQuestion> QUESTION_ORDER =
            Comparator.comparing(PracticeQuestion::getDisplayOrder, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(PracticeQuestion::getQuestionNo, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(PracticeQuestion::getId, Comparator.nullsLast(Long::compareTo));

    private void rejectDiscardedAttempt(PracticeAttempt attempt) {
        if (!ATTEMPT_STATE.isActive(attempt)) {
            throw new EntityNotFoundException("Lượt làm bài không tồn tại");
        }
    }

    private PracticeSet loadPublished(Long setId) {
        PracticeSet set = setRepository.findById(setId)
                .orElseThrow(() -> new EntityNotFoundException("Bộ luyện tập không tồn tại"));
        if (!PracticeSet.STATUS_PUBLISHED.equals(set.getStatus())) {
            throw new EntityNotFoundException("Bộ luyện tập không tồn tại");
        }
        return set;
    }

    private static PracticeSetRow toSetRow(PracticeSet set) {
        return new PracticeSetRow(
                set.getId(),
                set.getTitle(),
                set.getDescription(),
                set.getSkill(),
                PracticeDtos.getSkillLabel(set.getSkill()),
                set.getMetadataJson(),
                set.getCreationMethod()
        );
    }

    private static PracticeSetRow toSetRow(PracticeSetVersion set) {
        return new PracticeSetRow(
                set.getSetId(),
                set.getTitle(),
                set.getDescription(),
                set.getSkill(),
                PracticeDtos.getSkillLabel(set.getSkill()),
                set.getMetadataJson(),
                set.getCreationMethod()
        );
    }

    private static PracticeTestRow toTestRow(PracticeTest test) {
        return new PracticeTestRow(
                test.getId(),
                test.getSetId(),
                test.getTitle(),
                test.getDescription(),
                test.getDisplayOrder(),
                test.getEstimatedMinutes()
        );
    }

    private PracticeQuestionRow toQuestionRow(PracticeQuestion question) {
        QuestionContent content = questionContentForDisplay(
                question.getQuestionContentJson(), question.getOptionsJson(), question.getQuestionType());
        String imageReference = firstNonBlank(
                safeInternalMaterialReference(content == null ? null : content.imageReference()),
                firstMarkdownImageReference(question.getPrompt()));
        String audioReference = safeInternalMaterialReference(
                content == null ? null : content.audioReference());
        return new PracticeQuestionRow(
                question.getId(),
                question.getQuestionNo(),
                question.getQuestionType(),
                stripMarkdownImages(question.getPrompt()),
                readOptions(question.getOptionsJson()),
                question.getAnswerKey(),
                question.getExplanation(),
                groupLabel(question.getQuestionNo()),
                blankToNull(imageReference),
                blankToNull(audioReference),
                optionRows(content, readOptions(question.getOptionsJson())),
                blankRows(content),
                content == null ? null : content.writingResponse(),
                content == null || content.languageTag() == null
                        ? "ko"
                        : content.languageTag()
        );
    }

    private PracticeQuestionGroupRow toGroupRow(PracticeQuestionGroup g, List<PracticeQuestionRow> questions) {
        ExampleBox exampleBox = null;
        if (g.getExampleJson() != null && !g.getExampleJson().isBlank()) {
            try {
                exampleBox = objectMapper.readValue(g.getExampleJson(), ExampleBox.class);
            } catch (Exception e) {
                // ignore
            }
        }
        return new PracticeQuestionGroupRow(
                g.getId(),
                g.getSectionId(),
                g.getGroupLabel(),
                g.getQuestionFrom(),
                g.getQuestionTo(),
                stripMarkdownImages(g.getInstruction()),
                g.getStimulusType(),
                g.getPassageText(),
                g.getTranscriptText(),
                groupImageReference(g.getImageUrl(), g.getInstruction()),
                g.getStimulusProvenanceJson(),
                blankToNull(safeObjectiveGroupAudioReference(g.getAudioUrl())),
                exampleBox,
                questions,
                g.getStimulusLanguageTag(),
                g.getInstructionLanguageTag()
        );
    }

    private Optional<PracticeVersionSnapshot> versionSnapshot(PracticeAttempt attempt) {
        if (publishedVersionService == null) {
            return Optional.empty();
        }
        return publishedVersionService.snapshot(
                attempt.getPublishedVersionId(),
                attempt.getSetVersionId(),
                attempt.getTestVersionId(),
                attempt.getSectionVersionId());
    }

    private List<PracticeQuestionGroupRow> groupRowsForAttempt(
            PracticeAttempt attempt,
            PracticeVersionSnapshot version
    ) {
        List<PracticeQuestionVersion> questions = version.questions();
        List<PracticeQuestionGroupRow> groups = new ArrayList<>();
        for (PracticeQuestionGroupVersion group : version.groups()) {
            ExampleBox exampleBox = null;
            if (group.getExampleJson() != null && !group.getExampleJson().isBlank()) {
                try {
                    exampleBox = objectMapper.readValue(group.getExampleJson(), ExampleBox.class);
                } catch (Exception ignored) {
                    // Keep versioned rendering resilient for legacy imported example JSON.
                }
            }
            List<PracticeQuestionRow> questionRows = questions.stream()
                    .filter(q -> Objects.equals(group.getId(), q.getGroupVersionId()))
                    .map(this::toQuestionRow)
                    .toList();
            groups.add(new PracticeQuestionGroupRow(
                    group.getGroupId(),
                    version.sectionVersion().getSectionId(),
                    group.getGroupLabel(),
                    group.getQuestionFrom(),
                    group.getQuestionTo(),
                    stripMarkdownImages(group.getInstruction()),
                    group.getStimulusType(),
                    group.getPassageText(),
                    group.getTranscriptText(),
                    groupImageReference(group.getImageUrl(), group.getInstruction()),
                    group.getStimulusProvenanceJson(),
                    blankToNull(safeObjectiveGroupAudioReference(group.getAudioUrl())),
                    exampleBox,
                    questionRows,
                    group.getStimulusLanguageTag(),
                    group.getInstructionLanguageTag()
            ));
        }
        List<PracticeQuestionRow> orphanQuestionRows = questions.stream()
                .filter(q -> q.getGroupVersionId() == null)
                .map(this::toQuestionRow)
                .toList();
        if (!orphanQuestionRows.isEmpty()) {
            int from = orphanQuestionRows.stream().mapToInt(PracticeQuestionRow::questionNo).min().orElse(1);
            int to = orphanQuestionRows.stream().mapToInt(PracticeQuestionRow::questionNo).max().orElse(from);
            groups.add(new PracticeQuestionGroupRow(
                    null,
                    version.sectionVersion().getSectionId(),
                    "Phần thi",
                    from,
                    to,
                    null,
                    null,
                    null,
                    orphanQuestionRows
            ));
        }
        return groups;
    }

    @Transactional(readOnly = true)
    public AttemptPlayerView getAttemptPlayerView(Long attemptId, Long userId) {
        PracticeAttempt attempt =
                requireCanonicalAttemptDelivery(attemptId, userId);
        if ("SPEAKING".equals(attempt.getSkill())) {
            throw new IllegalArgumentException("Speaking attempts must use the dedicated speaking player.");
        }

        PracticeVersionSnapshot version =
                requireCanonicalAttemptDeliverySnapshot(attempt);
        PracticeSetView view = new PracticeSetView(
                toSetRow(version.setVersion()),
                redactPlayerGroups(groupRowsForAttempt(attempt, version)));
        Map<Long, WritingBlankContract.QuestionResponse>
                writingAuthorities = writingAuthorities(view);
        PracticeAttemptAnswerCodec.DecodedAnswers decoded =
                attemptAnswerCodec.read(
                        attempt.getAnswersJson(), writingAuthorities);
        return new AttemptPlayerView(
                view,
                attemptSectionDelivery(version),
                decoded.textAnswers(),
                playerWritingBlankAnswers(
                        writingAuthorities,
                        decoded.writingBlankAnswers()),
                decoded.legacyEssayShape(),
                attempt.getLockVersion(),
                attempt.getDeadlineAt());
    }

    @Transactional(readOnly = true)
    public AttemptSectionDelivery getAttemptSectionDelivery(Long attemptId, Long userId) {
        PracticeAttempt attempt =
                requireCanonicalAttemptDelivery(attemptId, userId);
        return attemptSectionDelivery(
                requireCanonicalAttemptDeliverySnapshot(attempt));
    }

    @Transactional(readOnly = true)
    public PracticeAttempt requireCanonicalAttemptDelivery(
            Long attemptId,
            Long userId
    ) {
        PracticeAttempt attempt = getPracticeAttempt(attemptId, userId);
        ATTEMPT_STATE.requireCanonicalResumeStructure(attempt);
        boolean coherent = publishedVersionService != null
                && publishedVersionService.hasCoherentAttemptIdentity(attempt);
        ATTEMPT_STATE.requireCoherentResumeIdentity(coherent);
        return attempt;
    }

    private PracticeVersionSnapshot requireCanonicalAttemptDeliverySnapshot(
            PracticeAttempt attempt
    ) {
        PracticeVersionSnapshot snapshot = versionSnapshot(attempt)
                .orElseThrow(() ->
                        new PracticeAttemptStatePolicy
                                .PracticeAttemptResumeNotAllowedException(
                                PracticeAttemptStatePolicy.ResumeRejection
                                        .INCONSISTENT_VERSION_IDENTITY,
                                "Không tìm thấy nội dung bất biến của lượt "
                                        + "làm bài. Vui lòng quay lại bài kiểm "
                                        + "tra và bắt đầu lượt mới."));
        if (!attemptMatchesSnapshot(attempt, snapshot)) {
            ATTEMPT_STATE.requireCoherentResumeIdentity(false);
        }
        return snapshot;
    }

    private static boolean attemptMatchesSnapshot(
            PracticeAttempt attempt,
            PracticeVersionSnapshot version
    ) {
        return Objects.equals(
                        attempt.getSetId(),
                        version.setVersion().getSetId())
                && Objects.equals(
                        attempt.getTestId(),
                        version.testVersion().getTestId())
                && Objects.equals(
                        attempt.getSectionId(),
                        version.sectionVersion().getSectionId())
                && version.sectionVersion().getSkill() != null
                && attempt.getSkill() != null
                && version.sectionVersion().getSkill()
                        .equalsIgnoreCase(attempt.getSkill());
    }

    private AttemptSectionDelivery attemptSectionDelivery(PracticeVersionSnapshot version) {
        PracticeSectionDelivery.ListeningDelivery listening =
                parseListeningDelivery(version.sectionVersion().getDeliveryJson());
        return new AttemptSectionDelivery(
                version.sectionVersion().getSectionId(),
                version.sectionVersion().getTitle(),
                version.sectionVersion().getSkill(),
                version.sectionVersion().getDurationMinutes(),
                listening == null ? null
                        : safeObjectiveGroupAudioReference(
                                listening.programAudioReference()),
                listening != null && listening.startOnce(),
                listening != null && listening.continuousPlayback(),
                listening == null || listening.seekAllowed(),
                listening == null || listening.replayAllowed());
    }

    public record AttemptPlayerView(
            PracticeSetView view,
            AttemptSectionDelivery delivery,
            Map<String, String> savedAnswers,
            Map<String, Map<String, String>>
                    savedWritingBlankAnswers,
            boolean legacyWritingAnswerDocument,
            Long lockVersion,
            LocalDateTime deadlineAt
    ) {
    }

    private static Map<String, Map<String, String>>
            playerWritingBlankAnswers(
            Map<Long, WritingBlankContract.QuestionResponse> authorities,
            Map<String, WritingBlankContract.LearnerResponse> answers) {
        Map<String, Map<String, String>> result =
                new LinkedHashMap<>();
        answers.forEach((questionId, response) -> {
            Long id;
            try {
                id = Long.valueOf(questionId);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Structured Writing answer question ID is invalid",
                        exception);
            }
            WritingBlankContract.QuestionResponse authority =
                    authorities.get(id);
            if (authority == null) {
                throw new IllegalArgumentException(
                        "Structured Writing answer lost question authority");
            }
            result.put(
                    questionId,
                    WritingBlankContractVerifier.orderedAnswers(
                            authority, response));
        });
        return Map.copyOf(result);
    }

    private static Map<Long, WritingBlankContract.QuestionResponse>
            writingAuthorities(PracticeSetView view) {
        Map<Long, WritingBlankContract.QuestionResponse> result =
                new LinkedHashMap<>();
        if (view == null || view.groups() == null) {
            return Map.of();
        }
        for (PracticeQuestionGroupRow group : view.groups()) {
            for (PracticeQuestionRow question : group.questions()) {
                if (question.writingResponse() != null) {
                    WritingBlankContractVerifier.verifyQuestion(
                            question.writingResponse());
                    if (result.putIfAbsent(
                            question.id(),
                            question.writingResponse()) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate structured Writing question ID");
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    public record AttemptSectionDelivery(
            Long sectionId,
            String title,
            String skill,
            Integer durationMinutes,
            String programAudioReference,
            boolean startOnce,
            boolean continuousPlayback,
            boolean seekAllowed,
            boolean replayAllowed
    ) {
    }

    private PracticeSectionDelivery.ListeningDelivery parseListeningDelivery(
            String deliveryJson) {
        if (deliveryJson == null || deliveryJson.isBlank()) {
            return null;
        }
        try {
            PracticeSectionDelivery delivery = objectMapper.readValue(
                    deliveryJson, PracticeSectionDelivery.class);
            if (!PracticeSectionDelivery.SCHEMA_VERSION.equals(
                    delivery.schemaVersion())) {
                return null;
            }
            return delivery.listeningDelivery();
        } catch (Exception exception) {
            log.warn("[PracticeService] Invalid Listening delivery reason={}",
                    exception.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public ListeningPreflightDelivery getListeningPreflightDelivery(
            Long setId, Long testId, Long sectionId) {
        PracticeSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new EntityNotFoundException("Phần Listening không tồn tại."));
        if (!Objects.equals(setId, section.getSetId())
                || !Objects.equals(testId, section.getTestId())
                || !"LISTENING".equals(section.getSkill())) {
            throw new IllegalArgumentException("Phần thi không thuộc Listening đã chọn.");
        }
        String reference = listeningCheckAudioReference(
                section.getDeliveryJson(),
                groupRepository.findBySectionIdOrderByDisplayOrderAsc(sectionId).stream()
                        .map(PracticeQuestionGroup::getAudioUrl)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse(null));
        return new ListeningPreflightDelivery(setId, testId, sectionId, section.getTitle(), reference);
    }

    @Transactional(readOnly = true)
    public ListeningPreflightDelivery getAttemptListeningPreflightDelivery(Long attemptId, Long userId) {
        PracticeAttempt attempt =
                requireCanonicalAttemptDelivery(attemptId, userId);
        if (!"LISTENING".equals(attempt.getSkill())) {
            throw new IllegalArgumentException("Lượt làm bài không thuộc kỹ năng Listening.");
        }
        PracticeVersionSnapshot snapshot =
                requireCanonicalAttemptDeliverySnapshot(attempt);
        String reference = listeningCheckAudioReference(
                snapshot.sectionVersion().getDeliveryJson(),
                snapshot.groups().stream()
                        .map(PracticeQuestionGroupVersion::getAudioUrl)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse(null));
        return new ListeningPreflightDelivery(
                attempt.getSetId(),
                attempt.getTestId(),
                attempt.getSectionId(),
                snapshot.sectionVersion().getTitle(),
                reference);
    }

    private String listeningCheckAudioReference(
            String deliveryJson, String fallbackReference) {
        String canonicalReference = null;
        if (deliveryJson != null && !deliveryJson.isBlank()) {
            try {
                PracticeSectionDelivery delivery = objectMapper.readValue(
                        deliveryJson, PracticeSectionDelivery.class);
                if (PracticeSectionDelivery.SCHEMA_VERSION.equals(delivery.schemaVersion())
                        && delivery.listeningDelivery() != null) {
                    canonicalReference = delivery.listeningDelivery().checkAudioReference();
                }
            } catch (Exception exception) {
                log.warn("[PracticeService] Invalid Listening section delivery reason={}",
                        exception.getMessage());
            }
        }
        String canonicalSafeReference = safeListeningCheckAudioReference(canonicalReference);
        String safeFallbackReference =
                safeListeningCheckAudioReference(fallbackReference);
        String reference = firstNonBlank(
                canonicalSafeReference, safeFallbackReference);
        if (isBlank(reference)) {
            throw new IllegalStateException(
                    "Phần Listening chưa có audio thử loa bất biến hợp lệ.");
        }
        return reference;
    }

    public record ListeningPreflightDelivery(
            Long setId,
            Long testId,
            Long sectionId,
            String sectionTitle,
            String checkAudioReference
    ) {
    }

    @Transactional(readOnly = true)
    public SpeakingPlayerDelivery getSpeakingPlayerDelivery(Long attemptId, Long userId) {
        PracticeAttempt attempt =
                requireCanonicalAttemptDelivery(attemptId, userId);
        if (!"SPEAKING".equals(attempt.getSkill())) {
            throw new IllegalArgumentException("Lượt làm bài không thuộc kỹ năng Speaking.");
        }

        PracticeVersionSnapshot version =
                requireCanonicalAttemptDeliverySnapshot(attempt);

        Map<Long, PracticeQuestionGroupVersion> groupsById = version.groups().stream().collect(Collectors.toMap(
                PracticeQuestionGroupVersion::getId,
                group -> group,
                (left, right) -> left,
                LinkedHashMap::new));

        List<SpeakingPlayerQuestion> questions = version.questions().stream()
                .sorted(Comparator.comparing(
                                PracticeQuestionVersion::getDisplayOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(
                                PracticeQuestionVersion::getQuestionNo,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(
                                PracticeQuestionVersion::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .map(question -> toSpeakingPlayerQuestion(question, groupsById))
                .toList();
        if (questions.isEmpty()) {
            throw new IllegalStateException("Phần Speaking chưa có câu hỏi hợp lệ.");
        }

        return new SpeakingPlayerDelivery(
                attempt.getId(),
                attempt.getSetId(),
                attempt.getTestId(),
                attempt.getSectionId(),
                version.setVersion().getTitle(),
                version.testVersion().getTitle(),
                version.sectionVersion().getTitle(),
                questions);
    }

    private SpeakingPlayerQuestion toSpeakingPlayerQuestion(
            PracticeQuestionVersion question,
            Map<Long, PracticeQuestionGroupVersion> groupsById) {
        if (!PracticeQuestion.TYPE_SPEAKING.equals(question.getQuestionType())) {
            throw new IllegalStateException(
                    "Speaking delivery contains a non-SPEAKING question: " + question.getQuestionId());
        }
        PracticeQuestionGroupVersion group = groupsById.get(question.getGroupVersionId());
        QuestionContent content = legacyCompatibleSpeakingContent(question);
        QuestionContent.SpeakingDelivery delivery = content.speakingDelivery();
        String promptAudioReference =
                safeInternalMaterialReference(
                        delivery == null ? null
                                : delivery.promptAudioReference());
        String legacyAudioFallback = firstNonBlank(
                safeInternalMaterialReference(content.audioReference()),
                safeInternalMaterialReference(
                        group == null ? null : group.getAudioUrl()));
        QuestionContent learnerSafeContent = new QuestionContent(
                content.schemaVersion(),
                content.options(),
                content.blanks(),
                safeInternalMaterialReference(content.imageReference()),
                safeInternalMaterialReference(content.audioReference()),
                delivery == null ? null : new QuestionContent.SpeakingDelivery(
                        delivery.inputType(),
                        delivery.deliveryMode(),
                        blankToNull(promptAudioReference),
                        delivery.audioOrigin(),
                        delivery.promptPlayLimit(),
                        delivery.preparationSeconds(),
                        delivery.responseSeconds()),
                content.writingResponse(),
                content.languageTag());
        String immutablePrompt = stripMarkdownImages(question.getPrompt());
        SpeakingPromptDelivery promptDelivery;
        try {
            promptDelivery = speakingPromptDeliveryPresenter.present(
                    learnerSafeContent, immutablePrompt, legacyAudioFallback);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Speaking question has invalid immutable delivery: "
                            + question.getQuestionId(),
                    exception);
        }
        String imageReference = firstNonBlank(
                safeInternalMaterialReference(content.imageReference()),
                firstNonBlank(
                        firstMarkdownImageReference(question.getPrompt()),
                        group == null ? null : groupImageReference(
                                group.getImageUrl(), group.getInstruction())));
        return new SpeakingPlayerQuestion(
                question.getQuestionId(),
                question.getQuestionNo(),
                group == null || group.getGroupLabel() == null || group.getGroupLabel().isBlank()
                        ? "Phần nói" : group.getGroupLabel(),
                question.getPoints(),
                blankToNull(imageReference),
                content.languageTag(),
                promptDelivery);
    }

    private QuestionContent legacyCompatibleSpeakingContent(PracticeQuestionVersion question) {
        String json = question.getQuestionContentJson();
        if (json != null && !json.isBlank()) {
            try {
                return assessmentContractCodec.readQuestionContent(json, CanonicalQuestionType.SPEAKING);
            } catch (IllegalArgumentException exception) {
                if (explicitTypedQuestionContent(json)) {
                    String schemaVersion = explicitQuestionContentSchema(json);
                    throw new IllegalStateException(
                            "Speaking question has invalid immutable "
                                    + schemaVersion + ": "
                                    + question.getQuestionId(),
                            exception);
                }
                log.warn("[PracticeService] Invalid canonical Speaking content questionId={} versionId={} reason={}",
                        question.getQuestionId(), question.getId(), exception.getMessage());
            }
        }
        return assessmentContractCodec.adaptLegacyContent(
                question.getOptionsJson(), PracticeQuestion.TYPE_SPEAKING);
    }

    private boolean explicitTypedQuestionContent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null
                    && node.isObject()
                    && QuestionContent.supportsTypedSpeakingDelivery(
                    node.path("schemaVersion").asText());
        } catch (Exception ignored) {
            /*
             * Syntax damage must not turn an explicitly declared typed row into
             * historical v1 fallback. This narrow declaration check is used
             * only after JSON parsing failed.
             */
            return EXPLICIT_TYPED_QUESTION_CONTENT_PATTERN.matcher(json).find();
        }
    }

    private String explicitQuestionContentSchema(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String schemaVersion = node == null
                    ? ""
                    : node.path("schemaVersion").asText("");
            return QuestionContent.supportsTypedSpeakingDelivery(schemaVersion)
                    ? schemaVersion
                    : "typed question content";
        } catch (Exception ignored) {
            java.util.regex.Matcher matcher =
                    EXPLICIT_TYPED_QUESTION_CONTENT_PATTERN.matcher(json);
            return matcher.find()
                    ? matcher.group().replaceAll(
                            ".*\"(question-content-v[23])\".*", "$1")
                    : "typed question content";
        }
    }

    private static String firstMarkdownImageReference(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(value);
        return matcher.find() ? safeLegacyMarkdownImageReference(matcher.group(1).trim()) : "";
    }

    private static String safeLegacyMarkdownImageReference(String reference) {
        if (reference == null || reference.isBlank()
                || reference.contains("\n")
                || reference.contains("\r")) {
            return "";
        }
        return safeInternalMaterialReference(reference);
    }

    private static String safeInternalMaterialReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        String normalized = reference.trim();
        return MATERIAL_CONTENT_REFERENCE_PATTERN.matcher(normalized).matches()
                || TOPIK35_IMAGE_REFERENCE_PATTERN.matcher(normalized).matches()
                || TOPIK35_AUDIO_REFERENCE_PATTERN.matcher(normalized).matches()
                ? normalized : "";
    }

    private static String safeListeningCheckAudioReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        String normalized = reference.trim();
        if (BUILT_IN_LISTENING_CHECK_AUDIO_REFERENCE.equals(normalized)) {
            return normalized;
        }
        return safeInternalMaterialReference(normalized);
    }

    private static String safeObjectiveGroupAudioReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        String normalized = reference.trim();
        if (BUILT_IN_LISTENING_CHECK_AUDIO_REFERENCE.equals(normalized)) {
            return normalized;
        }
        return safeInternalMaterialReference(normalized);
    }

    private static String stripMarkdownImages(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return MARKDOWN_IMAGE_PATTERN.matcher(value).replaceAll("").trim();
    }

    private static String groupImageReference(String imageReference, String instruction) {
        return blankToNull(firstNonBlank(
                safeInternalMaterialReference(imageReference),
                firstMarkdownImageReference(instruction)));
    }

    public record SpeakingPlayerDelivery(
            Long attemptId,
            Long setId,
            Long testId,
            Long sectionId,
            String setTitle,
            String testTitle,
            String sectionTitle,
            List<SpeakingPlayerQuestion> questions
    ) {
        public SpeakingPlayerDelivery {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    public record SpeakingPlayerQuestion(
            Long questionId,
            Integer questionNo,
            String groupLabel,
            BigDecimal points,
            String imageReference,
            String languageTag,
            SpeakingPromptDelivery delivery
    ) {
        public SpeakingPlayerQuestion {
            languageTag = "ko".equals(languageTag) || "vi".equals(languageTag)
                    ? languageTag
                    : "ko";
        }

        @JsonIgnore
        public String prompt() {
            return delivery == null ? null : delivery.promptText();
        }

        @JsonIgnore
        public String promptAudioReference() {
            return delivery == null ? null : delivery.promptAudioReference();
        }

        @JsonIgnore
        public Integer promptPlayLimit() {
            return delivery == null ? null : delivery.promptPlayLimit();
        }

        @JsonIgnore
        public Integer preparationSeconds() {
            return delivery == null ? null : delivery.preparationSeconds();
        }

        @JsonIgnore
        public Integer responseSeconds() {
            return delivery == null ? null : delivery.responseSeconds();
        }
    }

    private PracticeQuestionRow toQuestionRow(PracticeQuestionVersion question) {
        QuestionContent content = questionContentForDisplay(
                question.getQuestionContentJson(), question.getOptionsJson(), question.getQuestionType());
        String imageReference = firstNonBlank(
                safeInternalMaterialReference(content == null ? null : content.imageReference()),
                firstMarkdownImageReference(question.getPrompt()));
        String audioReference = safeInternalMaterialReference(
                content == null ? null : content.audioReference());
        return new PracticeQuestionRow(
                question.getQuestionId(),
                question.getQuestionNo(),
                question.getQuestionType(),
                stripMarkdownImages(question.getPrompt()),
                readOptions(question.getOptionsJson()),
                question.getAnswerKey(),
                question.getExplanation(),
                groupLabel(question.getQuestionNo()),
                blankToNull(imageReference),
                blankToNull(audioReference),
                optionRows(content, readOptions(question.getOptionsJson())),
                blankRows(content),
                content == null ? null : content.writingResponse(),
                content == null || content.languageTag() == null
                        ? "ko"
                        : content.languageTag()
        );
    }

    private List<PracticeQuestionGroupRow> fallbackGrouping(List<PracticeQuestion> dbQuestions) {
        Map<String, List<PracticeQuestionRow>> grouped = new LinkedHashMap<>();
        for (PracticeQuestion q : dbQuestions) {
            String label = groupLabel(q.getQuestionNo());
            grouped.computeIfAbsent(label, k -> new ArrayList<>()).add(toQuestionRow(q));
        }

        List<PracticeQuestionGroupRow> groupRows = new ArrayList<>();
        for (Map.Entry<String, List<PracticeQuestionRow>> entry : grouped.entrySet()) {
            String label = entry.getKey();
            List<PracticeQuestionRow> questions = entry.getValue();
            int from = questions.stream().mapToInt(PracticeQuestionRow::questionNo).min().orElse(1);
            int to = questions.stream().mapToInt(PracticeQuestionRow::questionNo).max().orElse(1);
            groupRows.add(new PracticeQuestionGroupRow(
                    null,
                    null,
                    label,
                    from,
                    to,
                    "Nhóm câu " + label,
                    null,
                    null,
                    questions
            ));
        }
        return groupRows;
    }

    private List<String> readOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private QuestionContent questionContentForDisplay(String questionContentJson,
                                                      String optionsJson,
                                                      String questionType) {
        try {
            CanonicalQuestionType type = questionTypeResolver.resolve(questionType);
            return isBlank(questionContentJson)
                    ? assessmentContractCodec.adaptLegacyContent(optionsJson, questionType)
                    : assessmentContractCodec.readQuestionContent(questionContentJson, type);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Map<Long, WritingBlankContract.QuestionResponse>
            writingAuthorities(List<QuestionSnapshot> questions) {
        Map<Long, WritingBlankContract.QuestionResponse> result =
                new LinkedHashMap<>();
        for (QuestionSnapshot question : questions) {
            if (question.questionContentJson() == null
                    || question.questionContentJson().isBlank()) {
                continue;
            }
            CanonicalQuestionType type =
                    questionTypeResolver.resolve(
                            question.questionType());
            QuestionContent content =
                    assessmentContractCodec.readQuestionContent(
                            question.questionContentJson(), type);
            if (content.writingResponse() == null) {
                continue;
            }
            WritingBlankContractVerifier.verifyQuestion(
                    content.writingResponse());
            if (question.writingTaskType()
                    != content.writingResponse().taskType()) {
                throw new IllegalArgumentException(
                        "Structured Writing task identity does not match immutable question");
            }
            if (result.putIfAbsent(
                    question.questionId(),
                    content.writingResponse()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate structured Writing question ID");
            }
        }
        return Map.copyOf(result);
    }

    private String questionImageReference(QuestionSnapshot question) {
        if (question == null) {
            return null;
        }
        QuestionContent content = questionContentForDisplay(
                question.questionContentJson(), question.optionsJson(), question.questionType());
        String canonicalReference = content == null ? null : content.imageReference();
        return blankToNull(firstNonBlank(
                safeInternalMaterialReference(canonicalReference),
                firstMarkdownImageReference(question.prompt())));
    }

    private static List<PracticeQuestionOptionRow> optionRows(QuestionContent content, List<String> legacyOptions) {
        if (content == null || content.options().isEmpty()) {
            if (legacyOptions == null || legacyOptions.isEmpty()) {
                return List.of();
            }
            return java.util.stream.IntStream.range(0, legacyOptions.size())
                    .mapToObj(index -> new PracticeQuestionOptionRow(
                            "opt_" + (index + 1), legacyOptions.get(index), null))
                    .toList();
        }
        return content.options().stream()
                .map(option -> new PracticeQuestionOptionRow(
                        option.id(),
                        option.text(),
                        blankToNull(safeInternalMaterialReference(option.imageReference()))))
                .toList();
    }

    private static List<com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionBlankRow> blankRows(
            QuestionContent content
    ) {
        if (content == null || content.blanks().isEmpty()) {
            return List.of();
        }
        return content.blanks().stream()
                .map(blank -> new com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionBlankRow(
                        blank.id(), blank.prompt()))
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private BigDecimal extractAiScore(
            String aiFeedback,
            WritingTaskType taskType) {
        JsonNode node;
        try {
            node = objectMapper.readTree(aiFeedback);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Writing evaluator returned malformed JSON.",
                    ex);
        }
        String expectedTaskType =
                taskType == null ? "GENERAL" : taskType.name();
        BigDecimal expectedMaximum = BigDecimal.valueOf(
                WritingScoringPolicy.rubricFor(
                        expectedTaskType).totalMaxScore());
        WritingFeedbackContractParser.EntryResult parsed =
                writingFeedbackParser.parseGeneratedEntry(node);
        if (parsed.status()
                != WritingFeedbackContractParser.Status.VALID_CURRENT
                || !isCurrentWritingEnvelope(
                        parsed.value(),
                        expectedTaskType,
                        expectedMaximum)
                || !hasExactCurrentWritingEnvelopeShape(
                        node,
                        parsed.value())) {
            throw new IllegalStateException(
                    "Writing evaluator output does not match the current score contract.");
        }
        WritingEvaluationResult value = parsed.value();
        return value.scoreAvailableFlag()
                ? WritingScoringPolicy.percentage(
                        value.rawScore(),
                        value.rawScoreMax())
                : null;
    }

    private static String groupLabel(Integer questionNo) {
        if (questionNo == null) {
            return "Câu";
        }
        int q = questionNo;
        if (q == 51 || q == 52) return "51-52";
        if (q == 53) return "53";
        if (q == 54) return "54";
        if (q <= 2) return "1-2";
        if (q <= 4) return "3-4";
        if (q <= 8) return "5-8";
        if (q <= 12) return "9-12";
        if (q <= 15) return "13-15";
        if (q <= 18) return "16-18";
        if (q <= 20) return "19-20";
        if (q <= 22) return "21-22";
        if (q <= 38) return "23-38";
        if (q <= 50) return "39-50";
        return String.valueOf(q);
    }

    private static boolean isAutoScoredByKey(String questionType) {
        return questionType != null && switch (questionType) {
            case PracticeQuestion.TYPE_SINGLE_CHOICE,
                    PracticeQuestion.TYPE_TRUE_FALSE_NOT_GIVEN,
                    PracticeQuestion.TYPE_FILL_BLANK -> true;
            default -> false;
        };
    }

    private Map<String, String> readAnswers(String answersJson) {
        if (answersJson == null || answersJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(
                    answersJson, new TypeReference<>() { });
            Map<String, String> normalized = new LinkedHashMap<>();
            if (parsed != null) {
                parsed.forEach((key, value) -> {
                    if (key != null) {
                        normalized.put(key, value == null ? "" : value);
                    }
                });
            }
            return normalized;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static boolean answersMatch(String answer, String answerKey) {
        String left = normalizeKey(answer);
        String right = normalizeKey(answerKey);
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.equals(right);
    }

    private Optional<AssessmentScoreResult> scoreObjective(QuestionSnapshot question, String rawAnswer) {
        Optional<CanonicalQuestionType> resolvedType = questionTypeResolver.resolveOptional(
                question.questionType());
        if (resolvedType.isEmpty()) {
            if (isAutoScoredByKey(question.questionType())) {
                return Optional.of(legacyBinaryScore(question, rawAnswer));
            }
            throw new IllegalStateException("Unsupported practice question type for question ID "
                    + question.questionId() + ": " + question.questionType());
        }

        CanonicalQuestionType type = resolvedType.get();
        if (!type.isObjective()) {
            return Optional.empty();
        }

        if (isBlank(question.answerSpecJson())) {
            if (!isBlank(question.questionContentJson())) {
                throw new IllegalStateException("Question content has no answer spec for question ID "
                        + question.questionId());
            }
        }

        boolean legacyContract = isBlank(question.questionContentJson()) && isBlank(question.answerSpecJson());
        try {
            QuestionContent content = isBlank(question.questionContentJson())
                    ? assessmentContractCodec.adaptLegacyContent(question.optionsJson(), question.questionType())
                    : assessmentContractCodec.readQuestionContent(question.questionContentJson(), type);
            AnswerSpec spec = isBlank(question.answerSpecJson())
                    ? assessmentContractCodec.adaptLegacyAnswerSpec(question.questionType(), question.answerKey(), content)
                    : assessmentContractCodec.readAnswerSpec(question.answerSpecJson(), content);
            LearnerAnswer answer = readLearnerAnswer(type, rawAnswer, content);
            return Optional.of(assessmentScoringEngine.score(spec, answer, question.points()));
        } catch (IllegalArgumentException exception) {
            if (legacyContract) {
                return Optional.of(legacyBinaryScore(question, rawAnswer));
            }
            throw new IllegalStateException("Cannot score question ID " + question.questionId()
                    + " with its assessment contract", exception);
        }
    }

    private LearnerAnswer readLearnerAnswer(CanonicalQuestionType type,
                                            String rawAnswer,
                                            QuestionContent content) {
        if (isBlank(rawAnswer)) {
            return new LearnerAnswer(
                    LearnerAnswer.SCHEMA_VERSION,
                    type,
                    List.of(),
                    null,
                    Map.of(),
                    null
            );
        }
        if (rawAnswer.trim().startsWith("{")) {
            LearnerAnswer typed = assessmentContractCodec.readLearnerAnswer(rawAnswer);
            if (typed.questionType() != type) {
                throw new IllegalArgumentException("Learner answer type does not match question type");
            }
            return typed;
        }
        return assessmentContractCodec.adaptLegacyLearnerAnswer(type.name(), rawAnswer, content);
    }

    private AssessmentScoreResult legacyBinaryScore(QuestionSnapshot question, String rawAnswer) {
        boolean correct = answersMatch(rawAnswer, question.answerKey());
        return new AssessmentScoreResult(
                correct ? AssessmentScoreStatus.CORRECT
                        : (isBlank(rawAnswer) ? AssessmentScoreStatus.NOT_ANSWERED : AssessmentScoreStatus.INCORRECT),
                correct ? question.points() : BigDecimal.ZERO,
                question.points(),
                ScoringPolicyCode.ALL_OR_NOTHING,
                correct ? 1 : 0,
                1
        );
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("\\s+", " ")
                .replace("／", "/")
                .replace("，", ",")
                .toUpperCase();
    }






    @Transactional(readOnly = true)
    public PracticeSection getSection(Long sectionId) {
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));
    }

    @Transactional(readOnly = true)
    public PracticeAttempt getPracticeAttempt(Long attemptId, Long userId) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Lượt làm bài không tồn tại"));
        rejectDiscardedAttempt(attempt);
        return attempt;
    }

    @Transactional(readOnly = true)
    public PracticeAttempt getPracticeAttemptForRouting(
            Long attemptId, Long userId) {
        return attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Lượt làm bài không tồn tại"));
    }

    @Transactional(readOnly = true)
    public List<PracticeSection> getSectionsForTest(Long setId, Long testId) {
        return sectionRepository.findBySetIdOrderByDisplayOrderAsc(setId).stream()
                .filter(s -> testId.equals(s.getTestId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PracticeQuestionGroupRow> getQuestionGroupsForSection(Long setId, Long sectionId) {
        PracticeSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Section không tồn tại"));
        Long testId = section.getTestId();

        List<PracticeQuestionGroup> dbGroups = groupRepository.findBySetIdOrderByDisplayOrderAsc(setId);
        List<PracticeQuestion> dbQuestions = questionRepository.findBySetIdOrderByDisplayOrderAsc(setId);
        List<PracticeSection> testSections = sectionRepository.findBySetIdOrderByDisplayOrderAsc(setId).stream()
                .filter(s -> testId.equals(s.getTestId()))
                .toList();

        // Step 1: Strict match
        List<PracticeQuestionGroup> secGroups = dbGroups.stream()
                .filter(g -> sectionId.equals(g.getSectionId()))
                .toList();

        // Step 2: Legacy fallback for single-section test
        if (secGroups.isEmpty() && testSections.size() == 1) {
            secGroups = dbGroups.stream()
                    .filter(g -> g.getSectionId() == null)
                    .toList();
        }

        // Step 3: Multi-section protection
        if (secGroups.isEmpty() && testSections.size() > 1) {
            throw new IllegalStateException("Không thể xác định câu hỏi cho sectionId=" + sectionId 
                    + " vì nhóm câu hỏi rỗng và bài thi có nhiều phần.");
        }

        List<PracticeQuestionGroupRow> groups = new ArrayList<>();
        for (PracticeQuestionGroup g : secGroups) {
            List<PracticeQuestionRow> qRows = dbQuestions.stream()
                    .filter(q -> java.util.Objects.equals(g.getId(), q.getGroupId()))
                    .map(this::toQuestionRow)
                    .toList();
            groups.add(toGroupRow(g, qRows));
        }

        // Dummy group for orphan questions in single-section test
        if (testSections.size() == 1) {
            List<PracticeQuestionRow> orphanQuestions = dbQuestions.stream()
                    .filter(q -> q.getGroupId() == null)
                    .map(this::toQuestionRow)
                    .toList();
            if (!orphanQuestions.isEmpty()) {
                groups.add(new PracticeQuestionGroupRow(
                        null,
                        sectionId,
                        "Phần thi",
                        1,
                        orphanQuestions.size(),
                        null,
                        null,
                        null,
                        orphanQuestions
                ));
            }
        }

        return groups;
    }

    @Transactional(readOnly = true)
    public List<PracticeQuestionGroupRow> getPlayerQuestionGroupsForAttempt(Long attemptId, Long userId) {
        PracticeAttempt attempt =
                requireCanonicalAttemptDelivery(attemptId, userId);
        PracticeVersionSnapshot snapshot =
                requireCanonicalAttemptDeliverySnapshot(attempt);
        return redactPlayerGroups(groupRowsForAttempt(attempt, snapshot));
    }

    private static List<PracticeQuestionGroupRow> redactPlayerGroups(
            List<PracticeQuestionGroupRow> groups) {
        return groups.stream()
                .map(group -> new PracticeQuestionGroupRow(
                        group.id(),
                        group.sectionId(),
                        group.groupLabel(),
                        group.questionFrom(),
                        group.questionTo(),
                        group.instruction(),
                        group.stimulusType(),
                        group.passageText(),
                        null,
                        group.imageUrl(),
                        null,
                        group.audioUrl(),
                        group.exampleBox(),
                        group.questions().stream()
                                .map(question -> new PracticeQuestionRow(
                                        question.id(),
                                        question.questionNo(),
                                        question.questionType(),
                                        question.prompt(),
                                        question.options(),
                                        null,
                                        null,
                                        question.groupLabel(),
                                        question.imageReference(),
                                        question.audioReference(),
                                        question.optionRows(),
                                        question.blankRows(),
                                        question.writingResponse(),
                                        question.languageTag()))
                                .toList(),
                        group.stimulusLanguageTag(),
                        group.instructionLanguageTag()))
                .toList();
    }

    @Transactional
    public Long startAttempt(Long setId, Long testId, Long sectionId, Long userId) {
        PracticeSet set = setRepository.findById(setId)
                .orElseThrow(() -> new EntityNotFoundException("Bộ luyện tập không tồn tại"));
        if (!PracticeSet.STATUS_PUBLISHED.equals(set.getStatus())) {
            throw new EntityNotFoundException("Bộ luyện tập chưa được xuất bản");
        }

        PracticeTest test = testRepository.findById(testId)
                .orElseThrow(() -> new EntityNotFoundException("Bài thi không tồn tại"));
        if (!setId.equals(test.getSetId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Bài thi không thuộc bộ luyện tập này");
        }

        PracticeSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));
        if (!setId.equals(section.getSetId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Section không thuộc bộ luyện tập này");
        }
        if (!testId.equals(section.getTestId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Section không thuộc bài thi này");
        }

        String liveSkill = section.getSkill();
        if (!isSupportedSkill(liveSkill)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Skill không hợp lệ");
        }

        PracticeSet lockedSet = setRepository.findByIdForUpdate(setId)
                .orElseThrow(() -> new EntityNotFoundException("Bộ luyện tập không tồn tại"));
        if (!PracticeSet.STATUS_PUBLISHED.equals(lockedSet.getStatus())) {
            throw new EntityNotFoundException("Bộ luyện tập chưa được xuất bản");
        }
        PracticeTest lockedTest = testRepository.findByIdForShare(testId)
                .orElseThrow(() -> new EntityNotFoundException("Bài thi không tồn tại"));
        if (!setId.equals(lockedTest.getSetId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Bài thi không thuộc bộ luyện tập này");
        }
        PracticeSection lockedSection = sectionRepository.findByIdForShare(sectionId)
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));
        if (!setId.equals(lockedSection.getSetId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Section không thuộc bộ luyện tập này");
        }
        if (!testId.equals(lockedSection.getTestId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Section không thuộc bài thi này");
        }
        if (!liveSkill.equals(lockedSection.getSkill())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Skill không hợp lệ");
        }

        Optional<PracticeAttemptVersionLock> versionLock = Optional.empty();
        String deliverySkill = liveSkill;
        Integer deliveryDurationMinutes = lockedSection.getDurationMinutes();
        if (publishedVersionService != null) {
            versionLock = publishedVersionService.latestLock(setId, testId, sectionId);
            if (versionLock.isEmpty()) {
                throw new EntityNotFoundException("Bộ luyện tập chưa có phiên bản xuất bản hợp lệ");
            }
            PracticeAttemptVersionLock lock = versionLock.get();
            PracticeVersionSnapshot snapshot = publishedVersionService.snapshot(
                            lock.publishedVersionId(), lock.setVersionId(),
                            lock.testVersionId(), lock.sectionVersionId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Bộ luyện tập chưa có phiên bản xuất bản nhất quán"));
            if (!setId.equals(snapshot.setVersion().getSetId())
                    || !testId.equals(snapshot.testVersion().getTestId())
                    || !sectionId.equals(snapshot.sectionVersion().getSectionId())
                    || !isSupportedSkill(snapshot.sectionVersion().getSkill())) {
                throw new IllegalStateException("Immutable attempt delivery does not match the requested section.");
            }
            if (!snapshot.hasCanonicalQuestionOwnership()) {
                throw new IllegalStateException(
                        "Immutable attempt delivery is missing canonical question-group ownership.");
            }
            deliverySkill = snapshot.sectionVersion().getSkill();
            deliveryDurationMinutes =
                    snapshot.sectionVersion().getDurationMinutes();
            if (!deliverySkill.equals(liveSkill)) {
                log.warn("Using immutable skill={} instead of mutable live skill={} for set={}, test={}, section={}",
                        deliverySkill, liveSkill, setId, testId, sectionId);
            }
        }

        Optional<PracticeAttempt> existing = attemptRepository
                .findFirstByUserIdAndTestIdAndSectionIdAndStatusOrderByCreatedAtDesc(
                        userId, testId, sectionId, PracticeAttempt.STATUS_IN_PROGRESS);

        if (existing.isPresent()) {
            PracticeAttempt attempt = existing.get();
            if (attempt.isExpired(LocalDateTime.now())) {
                // Preserve the durable server snapshot. The attempt endpoint
                // owns the skill-specific deadline transition: objective and
                // Writing attempts submit the saved answers, while Speaking
                // is discarded because incomplete audio cannot be scored.
                return attempt.getId();
            }
            if (setId.equals(attempt.getSetId()) &&
                testId.equals(attempt.getTestId()) &&
                sectionId.equals(attempt.getSectionId()) &&
                deliverySkill.equals(attempt.getSkill()) &&
                userId.equals(attempt.getUserId()) &&
                PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
                if (versionLock.isEmpty()
                        || (hasVersionLock(attempt, versionLock.get())
                        && ATTEMPT_STATE.isCanonicalResumable(attempt, true))) {
                    log.info("[PracticeService] Reusing existing IN_PROGRESS PracticeAttempt id={}", attempt.getId());
                    return attempt.getId();
                }
            }
            attempt.discard(LocalDateTime.now());
            attemptRepository.save(attempt);
            log.warn("[PracticeService] Discarded stale IN_PROGRESS PracticeAttempt id={} before restart",
                    attempt.getId());
        }

        PracticeAttempt attempt = new PracticeAttempt(userId, setId, testId, deliverySkill, sectionId);
        versionLock.ifPresent(lock -> attempt.lockPublishedVersion(
                lock.publishedVersionId(), lock.setVersionId(), lock.testVersionId(), lock.sectionVersionId()));
        attempt.configureDeadline(deliveryDurationMinutes, attempt.getStartedAt());
        attempt.setStatus(PracticeAttempt.STATUS_IN_PROGRESS);
        PracticeAttempt saved = attemptRepository.save(attempt);
        log.info("[PracticeService] Created new PracticeAttempt id={} section={}", saved.getId(), sectionId);
        return saved.getId();
    }

    private static boolean isSupportedSkill(String skill) {
        return "READING".equals(skill) || "LISTENING".equals(skill)
                || "WRITING".equals(skill) || "SPEAKING".equals(skill);
    }

    private static boolean hasVersionLock(PracticeAttempt attempt, PracticeAttemptVersionLock lock) {
        return Objects.equals(attempt.getPublishedVersionId(), lock.publishedVersionId())
                && Objects.equals(attempt.getSetVersionId(), lock.setVersionId())
                && Objects.equals(attempt.getTestVersionId(), lock.testVersionId())
                && Objects.equals(attempt.getSectionVersionId(), lock.sectionVersionId());
    }

    /**
     * Compatibility seam for existing non-HTTP callers and focused service
     * tests. Learner delivery must use the expected-version overload, which
     * persists subjective work before returning.
     */
    public Long submitAttempt(Long attemptId, Long userId, Map<String, String> form) {
        return submitAttemptSynchronously(attemptId, userId, form);
    }

    public Long submitAttempt(
            Long attemptId,
            Long userId,
            Map<String, String> form,
            Long expectedLockVersion) {
        if (attemptEvaluationJobRepository == null) {
            return submitAttemptSynchronously(attemptId, userId, form);
        }
        PracticeAttempt guard = executeRead(() ->
                getPracticeAttempt(attemptId, userId));
        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(guard.getStatus())) {
            throw new IllegalStateException(
                    "Lượt làm bài đã được nộp hoặc chấm điểm.");
        }
        boolean deadlineExpired =
                guard.isExpired(LocalDateTime.now());
        if (deadlineExpired && "SPEAKING".equals(guard.getSkill())) {
            throw new PracticeAttemptDeadlineExpiredException(
                    guard.getDeadlineAt());
        }
        if (!deadlineExpired
                && (expectedLockVersion == null
                || !Objects.equals(
                        expectedLockVersion, guard.getLockVersion()))) {
            throw conflict();
        }
        Map<String, String> effectiveForm =
                deadlineExpired ? Map.of() : form;

        WritingGradingSnapshot writingSnapshot = executeRead(() ->
                loadWritingSubmitSnapshot(
                        attemptId, userId, effectiveForm));
        if (writingSnapshot != null) {
            return executeWrite(() -> queueWritingSubmission(
                    writingSnapshot,
                    expectedLockVersion,
                    deadlineExpired));
        }
        SpeakingGradingSnapshot speakingSnapshot =
                executeRead(() ->
                        loadSpeakingSubmitSnapshot(
                                attemptId, userId, effectiveForm));
        if (speakingSnapshot != null) {
            return executeWrite(() -> queueSpeakingSubmission(
                    speakingSnapshot,
                    expectedLockVersion,
                    deadlineExpired));
        }
        return executeWrite(() -> submitAttemptInTransaction(
                attemptId,
                userId,
                effectiveForm,
                expectedLockVersion,
                deadlineExpired));
    }

    private Long submitAttemptSynchronously(
            Long attemptId, Long userId, Map<String, String> form) {
        WritingGradingSnapshot snapshot = executeRead(() -> loadWritingSubmitSnapshot(attemptId, userId, form));
        if (snapshot != null) {
            WritingGradingResult result = executeNonTransactional(() -> gradeWritingSnapshot(snapshot, false));
            return executeWrite(() -> persistWritingSubmitResult(snapshot, result));
        }
        SpeakingGradingSnapshot speakingSnapshot =
                executeRead(() -> loadSpeakingSubmitSnapshot(attemptId, userId, form));
        if (speakingSnapshot != null) {
            SpeakingGradingResult result =
                    executeNonTransactional(() -> gradeSpeakingSnapshot(speakingSnapshot));
            return executeWrite(() -> persistSpeakingGradingResult(speakingSnapshot, result, true));
        }
        return executeWrite(() -> submitAttemptInTransaction(attemptId, userId, form));
    }

    private Long queueWritingSubmission(
            WritingGradingSnapshot snapshot,
            Long expectedLockVersion,
            boolean deadlineExpired) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserIdForUpdate(
                        snapshot.attemptId(), snapshot.userId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy lượt làm bài"));
        LocalDateTime now = LocalDateTime.now();
        boolean expiredAtWrite =
                deadlineExpired || attempt.isExpired(now);
        verifyQueueTarget(
                attempt,
                snapshot.lockVersion(),
                expectedLockVersion,
                expiredAtWrite);
        String answersJson = expiredAtWrite
                ? normalizeJsonForCompare(attempt.getAnswersJson())
                : snapshot.answersToPersistJson();
        WritingGradingSnapshot queuedSnapshot =
                expiredAtWrite
                        ? loadWritingSubmitSnapshot(
                                attempt.getId(),
                                attempt.getUserId(),
                                Map.of())
                        : snapshot;
        BigDecimal total = queuedSnapshot.questions().stream()
                .map(QuestionSnapshot::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String fingerprint = evaluationInputFingerprint(
                attempt, answersJson, null);
        attempt.markSubmittedForAnalysis(total, answersJson, now);
        flushAttempt(attempt);
        insertEvaluationJob(
                attempt,
                PracticeAttemptEvaluationJob.OPERATION_SUBMIT,
                null,
                fingerprint,
                PracticeAttemptEvaluationJob.STATUS_QUEUED,
                null,
                now);
        return attempt.getId();
    }

    private Long queueSpeakingSubmission(
            SpeakingGradingSnapshot snapshot,
            Long expectedLockVersion,
            boolean deadlineExpired) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserIdForUpdate(
                        snapshot.attemptId(), snapshot.userId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy lượt làm bài"));
        LocalDateTime now = LocalDateTime.now();
        if (deadlineExpired || attempt.isExpired(now)) {
            throw new PracticeAttemptDeadlineExpiredException(
                    attempt.getDeadlineAt());
        }
        verifyQueueTarget(
                attempt,
                snapshot.lockVersion(),
                expectedLockVersion,
                false);
        String answersJson = snapshot.answersToPersistJson();
        BigDecimal total = snapshot.questions().stream()
                .map(QuestionSnapshot::points)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String fingerprint = evaluationInputFingerprint(
                attempt, answersJson,
                speakingMediaIdentityMaterial(
                        attempt, snapshot.questions()));
        attempt.markSubmittedForAnalysis(total, answersJson, now);
        boolean providerDisabled =
                speakingEvaluationApplicationService == null
                || !speakingEvaluationApplicationService.enabled();
        if (providerDisabled) {
            attempt.markAnalysisUnavailable(
                    total,
                    answersJson,
                    null,
                    "SPEAKING_AI_DISABLED",
                    false,
                    now);
        }
        flushAttempt(attempt);
        insertEvaluationJob(
                attempt,
                PracticeAttemptEvaluationJob.OPERATION_SUBMIT,
                null,
                fingerprint,
                providerDisabled
                        ? PracticeAttemptEvaluationJob.STATUS_UNAVAILABLE
                        : PracticeAttemptEvaluationJob.STATUS_QUEUED,
                providerDisabled ? "SPEAKING_AI_DISABLED" : null,
                now);
        return attempt.getId();
    }

    private void verifyQueueTarget(
            PracticeAttempt attempt,
            Long snapshotLockVersion,
            Long expectedLockVersion,
            boolean deadlineExpired) {
        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(
                attempt.getStatus())) {
            throw conflict();
        }
        if (!deadlineExpired
                && !Objects.equals(
                        snapshotLockVersion, attempt.getLockVersion())) {
            throw conflict();
        }
        if (!deadlineExpired
                && !Objects.equals(
                        expectedLockVersion, attempt.getLockVersion())) {
            throw conflict();
        }
        if (!deadlineExpired) {
            requireBeforeDeadline(attempt, LocalDateTime.now());
        }
    }

    private void insertEvaluationJob(
            PracticeAttempt attempt,
            String operation,
            Long targetQuestionId,
            String fingerprint,
            String status,
            String errorCode,
            LocalDateTime now) {
        int inserted = attemptEvaluationJobRepository.insertIfAbsent(
                attempt.getId(),
                operation,
                targetQuestionId,
                fingerprint,
                evaluationContractIdentity(attempt.getSkill()),
                status,
                3,
                now,
                now.plus(EVALUATION_JOB_WINDOW),
                attempt.getUserId(),
                errorCode);
        if (inserted != 1) {
            throw conflict();
        }
    }

    private Long submitAttemptInTransaction(Long attemptId, Long userId, Map<String, String> form) {
        return submitAttemptInTransaction(
                attemptId, userId, form, null, false);
    }

    private Long submitAttemptInTransaction(
            Long attemptId,
            Long userId,
            Map<String, String> form,
            Long expectedLockVersion,
            boolean deadlineExpired) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserIdForUpdate(attemptId, userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Không tìm thấy lượt làm bài"));

        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new IllegalStateException("Lượt làm bài đã được nộp hoặc chấm điểm.");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean expiredAtWrite =
                deadlineExpired || attempt.isExpired(now);
        if (!expiredAtWrite) {
            requireBeforeDeadline(attempt, now);
            if (expectedLockVersion != null
                    && !Objects.equals(
                            expectedLockVersion, attempt.getLockVersion())) {
                throw conflict();
            }
        }

        PracticeSection section = sectionRepository.findById(attempt.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));

        if (!attempt.getSetId().equals(section.getSetId()) ||
            !attempt.getTestId().equals(section.getTestId()) ||
            !attempt.getSkill().equals(section.getSkill())) {
            throw new IllegalArgumentException("Section metadata mismatch with attempt");
        }

        String skill = attempt.getSkill();
        if (skill == null || (!"READING".equals(skill) && !"LISTENING".equals(skill) &&
            !"WRITING".equals(skill) && !"SPEAKING".equals(skill))) {
            throw new IllegalArgumentException("Skill không hợp lệ");
        }

        loadPublished(attempt.getSetId());

        List<QuestionSnapshot> sectionQuestions = loadQuestionSnapshots(attempt, section.getId());

        Map<Long, WritingBlankContract.QuestionResponse> authorities =
                writingAuthorities(sectionQuestions);
        PracticeAttemptAnswerCodec.DecodedAnswers persistedAnswers =
                attemptAnswerCodec.read(attempt.getAnswersJson(), authorities);
        Map<String, String> answers = new LinkedHashMap<>(
                attemptAnswerCodec.compatibilityTextAnswers(
                        persistedAnswers));

        // Process only form fields that belong to sectionQuestions
        PracticeAnswerFormMapper.mergeAllowedQuestionAnswers(
                answers,
                expiredAtWrite ? Map.of() : form,
                sectionQuestions.stream().map(QuestionSnapshot::questionId).toList());

        if ("WRITING".equals(skill)) {
            throw new IllegalStateException("Writing attempt must use snapshot grading path.");
        }

        BigDecimal earnedPoints = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (QuestionSnapshot q : sectionQuestions) {
            total = total.add(q.points());
            String answer = answers.getOrDefault(String.valueOf(q.questionId()), "").trim();

            Optional<AssessmentScoreResult> objectiveScore = scoreObjective(q, answer);
            if (objectiveScore.isPresent()) {
                earnedPoints = earnedPoints.add(objectiveScore.get().earnedPoints());
            } else if (PracticeQuestion.TYPE_ESSAY.equals(q.questionType())) {
                throw new IllegalStateException("Essay attempt must use snapshot grading path.");
            } else if (PracticeQuestion.TYPE_SPEAKING.equals(q.questionType())) {
                throw new IllegalStateException(
                        "Speaking submission requires immutable audio evidence.");
            } else {
                throw new IllegalStateException("Unsupported question type for question ID "
                        + q.questionId() + ": " + q.questionType());
            }
        }
        BigDecimal score = earnedPoints;
        attempt.markSubmitted(score, total, writeJson(answers));

        attemptRepository.save(attempt);
        log.info("[PracticeService] Submitted PracticeAttempt id={} score={} / {}", attempt.getId(), score, total);
        return attempt.getId();
    }

    @Transactional
    public AttemptAnswerSaveResult saveInProgressAnswers(
            Long attemptId,
            Long userId,
            Long expectedLockVersion,
            Map<String, String> form) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserIdForUpdate(attemptId, userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Không tìm thấy lượt làm bài"));

        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new IllegalStateException(
                    "Chỉ có thể lưu nháp cho lượt làm bài chưa hoàn thành.");
        }
        requireCanonicalAttemptDeliverySnapshot(attempt);
        LocalDateTime now = LocalDateTime.now();
        requireBeforeDeadline(attempt, now);
        if (expectedLockVersion == null
                || !Objects.equals(expectedLockVersion, attempt.getLockVersion())) {
            throw conflict();
        }

        List<QuestionSnapshot> questions = loadQuestionSnapshots(
                attempt, attempt.getSectionId());
        Set<Long> allowedQuestionIds = questions.stream()
                .map(QuestionSnapshot::questionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, WritingBlankContract.QuestionResponse> authorities =
                writingAuthorities(questions);
        PracticeAttemptAnswerCodec.DecodedAnswers merged =
                attemptAnswerCodec.mergeForm(
                        attemptAnswerCodec.read(
                                attempt.getAnswersJson(), authorities),
                        form,
                        allowedQuestionIds,
                        authorities);

        attempt.saveAnswers(attemptAnswerCodec.write(merged), now);
        flushAttempt(attempt);
        return new AttemptAnswerSaveResult(
                attempt.getId(),
                attempt.getLockVersion(),
                attempt.getLastSavedAt(),
                attempt.getDeadlineAt(),
                attemptAnswerCodec.compatibilityTextAnswers(merged));
    }

    /**
     * Compatibility entry point for internal callers. Learner HTTP callers
     * must use the explicit expected-version overload.
     */
    @Transactional
    public void saveInProgressAnswers(
            Long attemptId, Long userId, Map<String, String> form) {
        PracticeAttempt attempt = getPracticeAttempt(attemptId, userId);
        saveInProgressAnswers(
                attemptId, userId, attempt.getLockVersion(), form);
    }

    @Transactional(readOnly = true)
    public boolean isDeadlineExpired(Long attemptId, Long userId) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Lượt làm bài không tồn tại"));
        return attempt.isExpired(LocalDateTime.now());
    }

    private void requireBeforeDeadline(
            PracticeAttempt attempt, LocalDateTime now) {
        if (attempt.getDeadlineAt() == null) {
            throw new IllegalStateException(
                    "Lượt làm bài không có thời hạn máy chủ hợp lệ.");
        }
        if (attempt.isExpired(now)) {
            throw new PracticeAttemptDeadlineExpiredException(
                    attempt.getDeadlineAt());
        }
    }

    public record AttemptAnswerSaveResult(
            Long attemptId,
            Long lockVersion,
            LocalDateTime savedAt,
            LocalDateTime deadlineAt,
            Map<String, String> answers
    ) {
    }

    private <T> T executeRead(Supplier<T> action) {
        if (readTransactionTemplate == null) {
            return action.get();
        }
        return readTransactionTemplate.execute(status -> action.get());
    }

    private <T> T executeNonTransactional(Supplier<T> action) {
        if (nonTransactionalTemplate == null) {
            return action.get();
        }
        return nonTransactionalTemplate.execute(status -> action.get());
    }

    private <T> T executeWrite(Supplier<T> action) {
        if (writeTransactionTemplate == null) {
            return action.get();
        }
        return writeTransactionTemplate.execute(status -> action.get());
    }

    private WritingGradingSnapshot loadWritingSubmitSnapshot(Long attemptId, Long userId, Map<String, String> form) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Không tìm thấy lượt làm bài"));

        if (!"WRITING".equals(attempt.getSkill())) {
            return null;
        }
        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new IllegalStateException("Lượt làm bài đã được nộp hoặc chấm điểm.");
        }

        PracticeSection section = sectionRepository.findById(attempt.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));
        validateAttemptSection(attempt, section);
        loadPublished(attempt.getSetId());

        List<QuestionSnapshot> questions = loadQuestionSnapshots(attempt, section.getId());
        validateWritingQuestionPoints(questions);

        Map<Long, WritingBlankContract.QuestionResponse> authorities =
                writingAuthorities(questions);
        PracticeAttemptAnswerCodec.DecodedAnswers merged =
                attemptAnswerCodec.mergeForm(
                        attemptAnswerCodec.read(
                                attempt.getAnswersJson(), authorities),
                        form,
                        questions.stream()
                                .map(QuestionSnapshot::questionId)
                                .collect(Collectors.toCollection(
                                        LinkedHashSet::new)),
                        authorities);
        Map<String, String> answers =
                attemptAnswerCodec.compatibilityTextAnswers(merged);

        return new WritingGradingSnapshot(
                attempt.getId(),
                attempt.getUserId(),
                attempt.getSectionId(),
                attempt.getSkill(),
                PracticeAttempt.STATUS_IN_PROGRESS,
                attempt.getLockVersion(),
                attempt.getAnswersJson(),
                attemptAnswerCodec.write(merged),
                answers,
                questions
        );
    }

    private WritingGradingSnapshot loadWritingReEvaluationSnapshot(
            PracticeAttempt attempt
    ) {
        if (!"WRITING".equals(attempt.getSkill())) {
            return null;
        }

        PracticeSection section = sectionRepository.findById(attempt.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException("Section không tồn tại"));
        validateAttemptSection(attempt, section);
        loadPublished(attempt.getSetId());

        List<QuestionSnapshot> questions = loadQuestionSnapshots(attempt, section.getId());
        validateWritingQuestionPoints(questions);
        PracticeAttemptAnswerCodec.DecodedAnswers decoded =
                attemptAnswerCodec.read(
                        attempt.getAnswersJson(),
                        writingAuthorities(questions));
        Map<String, String> answers =
                attemptAnswerCodec.compatibilityTextAnswers(decoded);

        return new WritingGradingSnapshot(
                attempt.getId(),
                attempt.getUserId(),
                attempt.getSectionId(),
                attempt.getSkill(),
                attempt.getStatus(),
                attempt.getLockVersion(),
                attempt.getAnswersJson(),
                attemptAnswerCodec.write(decoded),
                answers,
                questions
        );
    }

    private WritingQuestionReEvaluationSnapshot loadWritingQuestionReEvaluationSnapshot(
            PracticeAttempt attempt,
            Long questionId
    ) {
        if (!"WRITING".equals(attempt.getSkill())) {
            throw new IllegalArgumentException(
                    "Chỉ có thể chấm lại từng câu cho bài Writing.");
        }

        PracticeSection section = sectionRepository.findById(attempt.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Phần thi không tồn tại."));
        validateAttemptSection(attempt, section);
        loadPublished(attempt.getSetId());

        List<QuestionSnapshot> questions = loadQuestionSnapshots(attempt, section.getId());
        validateWritingQuestionPoints(questions);
        QuestionSnapshot target = questions.stream()
                .filter(q -> q.questionId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Câu hỏi không thuộc bài làm này."));
        if (!PracticeQuestion.TYPE_ESSAY.equals(target.questionType())) {
            throw new IllegalArgumentException(
                    "Chỉ có thể chấm lại câu Writing ESSAY.");
        }

        return new WritingQuestionReEvaluationSnapshot(
                attempt.getId(),
                attempt.getUserId(),
                attempt.getSectionId(),
                attempt.getSkill(),
                attempt.getStatus(),
                attempt.getLockVersion(),
                attempt.getAnswersJson(),
                attempt.getAiFeedbackJson(),
                attemptAnswerCodec.compatibilityTextAnswers(
                        attemptAnswerCodec.read(
                                attempt.getAnswersJson(),
                                writingAuthorities(questions))),
                questions,
                target
        );
    }

    private SpeakingGradingSnapshot loadSpeakingSubmitSnapshot(
            Long attemptId,
            Long userId,
            Map<String, String> form
    ) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Không tìm thấy lượt làm bài"));
        if (!"SPEAKING".equals(attempt.getSkill())) {
            return null;
        }
        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new IllegalStateException("Lượt làm bài đã được nộp hoặc chấm điểm.");
        }

        PracticeVersionSnapshot version = versionSnapshot(attempt)
                .orElseThrow(() -> new IllegalStateException(
                        "Speaking attempt is missing an immutable delivery version."));
        if (!attempt.getSetId().equals(version.setVersion().getSetId())
                || !attempt.getTestId().equals(version.testVersion().getTestId())
                || !attempt.getSectionId().equals(version.sectionVersion().getSectionId())
                || !"SPEAKING".equals(version.sectionVersion().getSkill())) {
            throw new IllegalStateException("Speaking attempt delivery version is inconsistent.");
        }
        loadPublished(attempt.getSetId());
        List<QuestionSnapshot> questions = loadQuestionSnapshots(attempt, attempt.getSectionId());
        if (questions.isEmpty()
                || questions.stream().anyMatch(question ->
                        !PracticeQuestion.TYPE_SPEAKING.equals(question.questionType()))) {
            throw new IllegalStateException("Speaking section may only contain canonical SPEAKING questions.");
        }
        validateWritingQuestionPoints(questions);
        if (speakingMediaService == null) {
            throw new IllegalStateException("Speaking media validation is unavailable.");
        }
        List<Long> questionIds = questions.stream().map(QuestionSnapshot::questionId).toList();
        speakingMediaService.requireReadyMediaForOwner(userId, attemptId, questionIds);

        Map<String, String> answers = new LinkedHashMap<>();
        for (Long questionId : questionIds) {
            answers.put(String.valueOf(questionId), "AUDIO_SUBMITTED");
        }

        return new SpeakingGradingSnapshot(
                attempt.getId(),
                attempt.getUserId(),
                attempt.getSetId(),
                attempt.getTestId(),
                attempt.getSectionId(),
                attempt.getSkill(),
                PracticeAttempt.STATUS_IN_PROGRESS,
                attempt.getLockVersion(),
                attempt.getAnswersJson(),
                attempt.getAiFeedbackJson(),
                attempt.getScore(),
                attempt.getTotalPoints(),
                writeJson(answers),
                answers,
                questions);
    }

    private SpeakingGradingSnapshot loadSpeakingReEvaluationSnapshot(Long attemptId, Long userId) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Không tìm thấy lượt làm bài"));
        if (!"SPEAKING".equals(attempt.getSkill())) {
            return null;
        }
        if (attempt.getPublishedVersionId() == null
                || attempt.getSetVersionId() == null
                || attempt.getTestVersionId() == null
                || attempt.getSectionVersionId() == null) {
            return null;
        }

        PracticeVersionSnapshot version = versionSnapshot(attempt)
                .orElseThrow(() -> new IllegalStateException(
                        "Speaking attempt is missing an immutable delivery version."));
        if (!attempt.getSetId().equals(version.setVersion().getSetId())
                || !attempt.getTestId().equals(version.testVersion().getTestId())
                || !attempt.getSectionId().equals(version.sectionVersion().getSectionId())
                || !"SPEAKING".equals(version.sectionVersion().getSkill())) {
            throw new IllegalStateException("Speaking attempt delivery version is inconsistent.");
        }
        loadPublished(attempt.getSetId());
        List<QuestionSnapshot> questions = loadQuestionSnapshots(attempt, attempt.getSectionId());
        if (questions.isEmpty()
                || questions.stream().anyMatch(question ->
                        !PracticeQuestion.TYPE_SPEAKING.equals(question.questionType()))) {
            throw new IllegalStateException("Speaking section may only contain canonical SPEAKING questions.");
        }
        validateWritingQuestionPoints(questions);
        Map<String, String> answers = readAnswers(attempt.getAnswersJson());

        return new SpeakingGradingSnapshot(
                attempt.getId(),
                attempt.getUserId(),
                attempt.getSetId(),
                attempt.getTestId(),
                attempt.getSectionId(),
                attempt.getSkill(),
                attempt.getStatus(),
                attempt.getLockVersion(),
                attempt.getAnswersJson(),
                attempt.getAiFeedbackJson(),
                attempt.getScore(),
                attempt.getTotalPoints(),
                writeJson(answers),
                answers,
                questions);
    }

    private List<QuestionSnapshot> loadQuestionSnapshots(PracticeAttempt attempt, Long sectionId) {
        Optional<PracticeVersionSnapshot> snapshot = versionSnapshot(attempt);
        if (snapshot.isPresent()) {
            return questionSnapshots(snapshot.get());
        }
        Long setId = attempt.getSetId();
        List<PracticeQuestionGroupRow> groupRows = getQuestionGroupsForSection(setId, sectionId);
        List<Long> sectionQuestionIds = groupRows.stream()
                .flatMap(g -> g.questions().stream())
                .map(com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionRow::id)
                .toList();

        List<PracticeQuestion> allQuestions = questionRepository.findBySetIdOrderByDisplayOrderAsc(setId);
        return allQuestions.stream()
                .filter(q -> sectionQuestionIds.contains(q.getId()))
                .sorted(QUESTION_ORDER)
                .map(this::toQuestionSnapshot)
                .toList();
    }

    private List<QuestionSnapshot> questionSnapshots(
            PracticeVersionSnapshot snapshot
    ) {
        return snapshot.questions().stream()
                .sorted(Comparator.comparing(
                                PracticeQuestionVersion::getDisplayOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(
                                PracticeQuestionVersion::getQuestionNo,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(
                                PracticeQuestionVersion::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .map(q -> new QuestionSnapshot(
                        q.getQuestionId(),
                        q.getId(),
                        q.getQuestionNo(),
                        q.getDisplayOrder(),
                        q.getPrompt(),
                        q.getQuestionType(),
                        q.getQuestionContentJson(),
                        q.getOptionsJson(),
                        q.getAnswerKey(),
                        q.getAnswerSpecJson(),
                        q.getExplanation(),
                        q.getPoints(),
                        q.getWritingTaskType()))
                .toList();
    }

    private QuestionSnapshot toQuestionSnapshot(PracticeQuestion question) {
        return new QuestionSnapshot(
                question.getId(),
                null,
                question.getQuestionNo(),
                question.getDisplayOrder(),
                question.getPrompt(),
                question.getQuestionType(),
                question.getQuestionContentJson(),
                question.getOptionsJson(),
                question.getAnswerKey(),
                question.getAnswerSpecJson(),
                question.getExplanation(),
                question.getPoints(),
                question.getWritingTaskType()
        );
    }

    private QuestionSnapshot toQuestionSnapshot(PracticeQuestionVersion question) {
        return new QuestionSnapshot(
                question.getQuestionId(),
                question.getId(),
                question.getQuestionNo(),
                question.getDisplayOrder(),
                question.getPrompt(),
                question.getQuestionType(),
                question.getQuestionContentJson(),
                question.getOptionsJson(),
                question.getAnswerKey(),
                question.getAnswerSpecJson(),
                question.getExplanation(),
                question.getPoints(),
                question.getWritingTaskType()
        );
    }

    private void validateAttemptSection(PracticeAttempt attempt, PracticeSection section) {
        if (!attempt.getSetId().equals(section.getSetId()) ||
            !attempt.getTestId().equals(section.getTestId()) ||
            !attempt.getSkill().equals(section.getSkill())) {
            throw new IllegalArgumentException("Section metadata mismatch with attempt");
        }
    }

    private void validateKnownSkill(String skill) {
        if (skill == null || (!"READING".equals(skill) && !"LISTENING".equals(skill) &&
            !"WRITING".equals(skill) && !"SPEAKING".equals(skill))) {
            throw new IllegalArgumentException("Skill không hợp lệ");
        }
    }

    private void validateWritingQuestionPoints(List<QuestionSnapshot> questions) {
        for (QuestionSnapshot q : questions) {
            if (q.points() == null || q.points().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Invalid configured points for question ID: " + q.questionId());
            }
        }
    }

    private SpeakingGradingResult gradeSpeakingSnapshot(SpeakingGradingSnapshot snapshot) {
        if (speakingEvaluationApplicationService == null || !speakingEvaluationApplicationService.enabled()) {
            BigDecimal total = snapshot.questions().stream()
                    .map(QuestionSnapshot::points)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new SpeakingGradingResult(
                    null, total, snapshot.answersToPersistJson(), null,
                    false);
        }

        BigDecimal earnedPoints = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        boolean allSpeakingScoreBearing = true;
        Map<Long, SpeakingEvaluationResult> feedbackByQuestion = new LinkedHashMap<>();
        Map<Long, SpeakingEvaluationResult> storedByQuestion = storedSpeakingResults(snapshot.expectedAiFeedbackJson());

        for (QuestionSnapshot q : snapshot.questions()) {
            requireEvaluationThreadActive();
            total = total.add(q.points());
            if (PracticeQuestion.TYPE_SPEAKING.equals(q.questionType())) {
                SpeakingEvaluationApplicationService.Evaluation evaluation =
                        speakingEvaluationApplicationService.evaluateQuestion(
                                new SpeakingEvaluationApplicationService.EvaluationInput(
                                        snapshot.userId(),
                                        snapshot.attemptId(),
                                        q.questionId(),
                                        q.questionVersionId(),
                                        speakingQuestionContentSchemaVersion(q),
                                        q.prompt(),
                                        null,
                                        q.answerKey(),
                                        questionImageReference(q),
                                        "",
                                        storedByQuestion.get(q.questionId())));
                SpeakingEvaluationResult result = evaluation.result();
                if (result == null) {
                    allSpeakingScoreBearing = false;
                    continue;
                }
                feedbackByQuestion.put(q.questionId(), result);
                BigDecimal earnedQuestionPoints = SpeakingScorePolicy.earnedQuestionPoints(q.points(), result);
                if (earnedQuestionPoints == null) {
                    allSpeakingScoreBearing = false;
                    continue;
                }
                earnedPoints = earnedPoints.add(earnedQuestionPoints);
            } else {
                throw new IllegalStateException("Unsupported SPEAKING question type for question ID "
                        + q.questionId() + ": " + q.questionType());
            }
        }
        String feedbackJson = feedbackByQuestion.isEmpty() ? null : speakingAiFeedbackEnvelope(feedbackByQuestion);
        BigDecimal score = allSpeakingScoreBearing && !feedbackByQuestion.isEmpty()
                ? toWritingAttemptPercentage(earnedPoints, total)
                : null;
        return new SpeakingGradingResult(
                score,
                total,
                snapshot.answersToPersistJson(),
                feedbackJson,
                feedbackByQuestion.size() == snapshot.questions().size());
    }

    private String speakingQuestionContentSchemaVersion(
            QuestionSnapshot question) {
        if (question == null || isBlank(question.questionContentJson())) {
            return QuestionContent.SCHEMA_VERSION_V1;
        }
        return assessmentContractCodec.readQuestionContent(
                        question.questionContentJson(),
                        CanonicalQuestionType.SPEAKING)
                .schemaVersion();
    }

    private Map<Long, SpeakingEvaluationResult> storedSpeakingResults(String aiFeedbackJson) {
        JsonNode root = null;
        if (aiFeedbackJson != null && !aiFeedbackJson.isBlank()) {
            try {
                root = objectMapper.readTree(aiFeedbackJson);
            } catch (Exception ex) {
                return Map.of();
            }
        }
        if (!isSpeakingAiEnvelope(root)) {
            return Map.of();
        }
        JsonNode byQuestion = root.path(SPEAKING_FEEDBACK_BY_QUESTION_FIELD);
        if (!byQuestion.isObject()) {
            return Map.of();
        }
        Map<Long, SpeakingEvaluationResult> results = new LinkedHashMap<>();
        byQuestion.fields().forEachRemaining(entry -> {
            try {
                Long questionId = Long.valueOf(entry.getKey());
                if (entry.getValue() != null && entry.getValue().isObject()) {
                    results.put(questionId, speakingFeedbackParser.read(entry.getValue()));
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed stored question keys during reuse; fresh evaluation can replace them.
            }
        });
        return results;
    }

    private WritingGradingResult gradeWritingSnapshot(WritingGradingSnapshot snapshot, boolean isReEvaluate) {
        BigDecimal attemptTotalPoints = BigDecimal.ZERO;
        BigDecimal attemptEarnedPoints = BigDecimal.ZERO;
        com.fasterxml.jackson.databind.node.ObjectNode feedbackMap = objectMapper.createObjectNode();
        boolean allEvaluationsScoreBearing = true;

        for (QuestionSnapshot q : snapshot.questions()) {
            requireEvaluationThreadActive();
            BigDecimal configuredPoints = q.points();
            attemptTotalPoints = attemptTotalPoints.add(configuredPoints);
            String answer = snapshot.answers().getOrDefault(String.valueOf(q.questionId()), "");

            Optional<AssessmentScoreResult> objectiveScore = scoreObjective(q, answer);
            if (objectiveScore.isPresent()) {
                attemptEarnedPoints = attemptEarnedPoints.add(objectiveScore.get().earnedPoints());
            } else if (PracticeQuestion.TYPE_ESSAY.equals(q.questionType())) {
                if (manualOrExperimentalWritingUnscored(q)) {
                    com.fasterxml.jackson.databind.node.ObjectNode node =
                            manualWritingUnscored(q);
                    readGeneratedWritingScore(node, q);
                    allEvaluationsScoreBearing = false;
                    feedbackMap.set(String.valueOf(q.questionId()), node);
                    continue;
                }
                String singleFeedback = evaluateWriting(snapshot.userId(), q.prompt(), answer,
                        isReEvaluate, q.writingTaskType(), questionImageReference(q));
                com.fasterxml.jackson.databind.node.ObjectNode node = readWritingFeedbackObject(q.questionId(), singleFeedback);

                WritingEvaluationResult evaluation =
                        readGeneratedWritingScore(node, q);
                if (!evaluation.scoreAvailableFlag()) {
                    allEvaluationsScoreBearing = false;
                    feedbackMap.set(String.valueOf(q.questionId()), node);
                    continue;
                }
                BigDecimal rawScore = evaluation.rawScore();
                BigDecimal rawScoreMax = evaluation.rawScoreMax();
                rawScore = clamp(rawScore, BigDecimal.ZERO, rawScoreMax);

                BigDecimal earnedQuestionPoints = rawScore.multiply(configuredPoints)
                        .divide(rawScoreMax, java.math.MathContext.DECIMAL128);
                attemptEarnedPoints = attemptEarnedPoints.add(earnedQuestionPoints);
                feedbackMap.set(String.valueOf(q.questionId()), node);
            } else {
                throw new IllegalStateException("Unsupported WRITING question type for question ID " + q.questionId()
                        + ": " + q.questionType());
            }
        }

        BigDecimal attemptScore = allEvaluationsScoreBearing
                ? toWritingAttemptPercentage(attemptEarnedPoints, attemptTotalPoints)
                : null;

        String feedbackJson;
        try {
            feedbackJson = objectMapper.writeValueAsString(feedbackMap);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize writing feedback map", e);
        }

        return new WritingGradingResult(
                attemptScore,
                attemptTotalPoints,
                snapshot.answersToPersistJson(),
                feedbackJson,
                null);
    }

    private WritingGradingResult gradeWritingQuestionSnapshot(WritingQuestionReEvaluationSnapshot snapshot) {
        requireEvaluationThreadActive();
        com.fasterxml.jackson.databind.node.ObjectNode feedbackMap = buildValidatedFeedbackMapBeforeTargetEvaluation(snapshot);

        if (manualOrExperimentalWritingUnscored(snapshot.targetQuestion())) {
            com.fasterxml.jackson.databind.node.ObjectNode targetNode =
                    manualWritingUnscored(snapshot.targetQuestion());
            readGeneratedWritingScore(targetNode, snapshot.targetQuestion());
            feedbackMap.set(String.valueOf(
                    snapshot.targetQuestion().questionId()), targetNode);
            return new WritingGradingResult(
                    null,
                    null,
                    snapshot.expectedAnswersJson(),
                    feedbackMap.toString(),
                    null);
        }

        String targetAnswer = snapshot.answers().getOrDefault(String.valueOf(snapshot.targetQuestion().questionId()), "");
        String targetFeedback = evaluateWriting(
                snapshot.userId(),
                snapshot.targetQuestion().prompt(),
                targetAnswer,
                true,
                snapshot.targetQuestion().writingTaskType(),
                questionImageReference(snapshot.targetQuestion()));
        com.fasterxml.jackson.databind.node.ObjectNode targetNode =
                readWritingFeedbackObject(snapshot.targetQuestion().questionId(), targetFeedback);
        WritingEvaluationResult targetScore =
                readStoredWritingScore(targetNode, snapshot.targetQuestion());
        if (!targetScore.scoreAvailableFlag()) {
            return new WritingGradingResult(
                    null,
                    null,
                    snapshot.expectedAnswersJson(),
                    snapshot.expectedAiFeedbackJson(),
                    targetFeedback);
        }
        feedbackMap.set(String.valueOf(snapshot.targetQuestion().questionId()), targetNode);

        WritingScoreAggregate aggregate = aggregateWritingScore(snapshot.questions(), snapshot.answers(), feedbackMap);
        String feedbackJson;
        try {
            feedbackJson = objectMapper.writeValueAsString(feedbackMap);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize writing feedback map", e);
        }
        return new WritingGradingResult(
                aggregate.score(),
                aggregate.totalPoints(),
                snapshot.expectedAnswersJson(),
                feedbackJson,
                null);
    }

    private String evaluateWriting(Long userId,
                                   String prompt,
                                   String answer,
                                   boolean reEvaluate,
                                   WritingTaskType taskType,
                                   String imageReference) {
        if (imageReference == null || imageReference.isBlank()) {
            return evaluationClient.evaluate(userId, prompt, answer, reEvaluate, taskType);
        }
        return evaluationClient.evaluate(
                userId, prompt, answer, reEvaluate, taskType, imageReference);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode buildValidatedFeedbackMapBeforeTargetEvaluation(
            WritingQuestionReEvaluationSnapshot snapshot
    ) {
        List<QuestionSnapshot> essayQuestions = snapshot.questions().stream()
                .filter(q -> PracticeQuestion.TYPE_ESSAY.equals(q.questionType()))
                .toList();
        JsonNode root = readExistingWritingFeedbackRoot(snapshot.expectedAiFeedbackJson());
        com.fasterxml.jackson.databind.node.ObjectNode feedbackMap = objectMapper.createObjectNode();

        if (!root.isObject()) {
            throw unsupportedPerQuestionFeedback();
        }

        com.fasterxml.jackson.databind.node.ObjectNode rootObject = (com.fasterxml.jackson.databind.node.ObjectNode) root;
        feedbackMap = rootObject.deepCopy();
        for (QuestionSnapshot q : essayQuestions) {
            if (q.questionId().equals(snapshot.targetQuestion().questionId())) {
                continue;
            }
            JsonNode entry = feedbackMap.get(String.valueOf(q.questionId()));
            if (entry == null || entry.isNull() || !entry.isObject()) {
                throw unsupportedPerQuestionFeedback();
            }
            readStoredWritingScore(entry, q);
        }
        return feedbackMap;
    }

    private JsonNode readExistingWritingFeedbackRoot(String feedbackJson) {
        if (feedbackJson == null || feedbackJson.isBlank()) {
            throw unsupportedPerQuestionFeedback();
        }
        try {
            JsonNode root = objectMapper.readTree(feedbackJson);
            if (root == null || !root.isObject()) {
                throw unsupportedPerQuestionFeedback();
            }
            return root;
        } catch (PracticeAttemptConflictException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unsupportedPerQuestionFeedback();
        }
    }

    private WritingScoreAggregate aggregateWritingScore(
            List<QuestionSnapshot> questions,
            Map<String, String> answers,
            com.fasterxml.jackson.databind.node.ObjectNode feedbackMap
    ) {
        BigDecimal attemptTotalPoints = BigDecimal.ZERO;
        BigDecimal attemptEarnedPoints = BigDecimal.ZERO;

        for (QuestionSnapshot q : questions) {
            BigDecimal configuredPoints = q.points();
            attemptTotalPoints = attemptTotalPoints.add(configuredPoints);
            String answer = answers.getOrDefault(String.valueOf(q.questionId()), "").trim();

            Optional<AssessmentScoreResult> objectiveScore = scoreObjective(q, answer);
            if (objectiveScore.isPresent()) {
                attemptEarnedPoints = attemptEarnedPoints.add(objectiveScore.get().earnedPoints());
            } else if (PracticeQuestion.TYPE_ESSAY.equals(q.questionType())) {
                JsonNode node = feedbackMap.get(String.valueOf(q.questionId()));
                if (node == null || node.isNull() || !node.isObject()) {
                    throw unsupportedPerQuestionFeedback();
                }
                WritingEvaluationResult storedScore =
                        readStoredWritingScore(node, q);
                if (!storedScore.scoreAvailableFlag()) {
                    return new WritingScoreAggregate(null, attemptTotalPoints);
                }
                BigDecimal rawScore = clamp(storedScore.rawScore(), BigDecimal.ZERO, storedScore.rawScoreMax());
                BigDecimal earnedQuestionPoints = rawScore.multiply(configuredPoints)
                        .divide(storedScore.rawScoreMax(), java.math.MathContext.DECIMAL128);
                attemptEarnedPoints = attemptEarnedPoints.add(earnedQuestionPoints);
            } else {
                throw new IllegalStateException("Unsupported WRITING question type for question ID " + q.questionId()
                        + ": " + q.questionType());
            }
        }

        return new WritingScoreAggregate(toWritingAttemptPercentage(attemptEarnedPoints, attemptTotalPoints), attemptTotalPoints);
    }

    private BigDecimal toWritingAttemptPercentage(BigDecimal earnedPoints, BigDecimal totalPoints) {
        BigDecimal attemptScore = BigDecimal.ZERO;
        if (totalPoints.compareTo(BigDecimal.ZERO) > 0) {
            attemptScore = earnedPoints.multiply(BigDecimal.valueOf(100))
                    .divide(totalPoints, 2, RoundingMode.HALF_UP);
        }
        attemptScore = clamp(attemptScore, BigDecimal.ZERO, BigDecimal.valueOf(100));
        if (attemptScore.compareTo(BigDecimal.ZERO) == 0) {
            attemptScore = BigDecimal.ZERO;
        }
        return attemptScore;
    }

    private WritingEvaluationResult readStoredWritingScore(
            JsonNode node,
            QuestionSnapshot question) {
        WritingFeedbackContractParser.EntryResult parsed = writingFeedbackParser.parseStoredEntry(node);
        if (parsed.status() != WritingFeedbackContractParser.Status.VALID_CURRENT
                || !isCurrentWritingEnvelope(parsed.value(), question)
                || !hasExactCurrentWritingEnvelopeShape(
                        node, parsed.value())) {
            throw unsupportedPerQuestionFeedback();
        }
        return parsed.value();
    }

    private WritingEvaluationResult readGeneratedWritingScore(
            JsonNode node,
            QuestionSnapshot question) {
        WritingFeedbackContractParser.EntryResult parsed = writingFeedbackParser.parseGeneratedEntry(node);
        if (parsed.status() != WritingFeedbackContractParser.Status.VALID_CURRENT
                || !isCurrentWritingEnvelope(parsed.value(), question)
                || !hasExactCurrentWritingEnvelopeShape(
                        node, parsed.value())) {
            throw new IllegalStateException(
                    "AI feedback does not match the immutable Writing task contract for question ID: "
                            + question.questionId());
        }
        return parsed.value();
    }

    private boolean isCurrentWritingEnvelope(
            WritingEvaluationResult value,
            QuestionSnapshot question) {
        if (question == null
                || question.writingTaskType() == null
                || question.points() == null
                || question.points().signum() <= 0) {
            return false;
        }
        String expectedTaskType = question.writingTaskType().name();
        if (manualOrExperimentalWritingUnscored(question)) {
            return value != null
                    && !value.scoreAvailableFlag()
                    && "EVALUATION_UNAVAILABLE".equals(
                    value.evaluationStatus())
                    && "SYSTEM".equals(value.evaluationSource())
                    && "MANUAL_OR_EXPERIMENTAL_UNSCORED".equals(
                    value.evaluationReason())
                    && !value.evaluationRetryable()
                    && "KSH_WRITING_EVALUATOR_STATUS".equals(value.engine())
                    && WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID.equals(
                    value.policyBundleId());
        }
        // An unavailable provider result is intentionally non-scoring.  It must
        // still be bound to the immutable task type and policy bundle, but it
        // cannot be rejected merely because the question's score ceiling is
        // irrelevant until a score is actually returned.
        if (value != null && !value.scoreAvailableFlag()) {
            return isCurrentWritingEnvelope(
                    value, expectedTaskType, BigDecimal.ZERO);
        }
        BigDecimal expectedMaximum = BigDecimal.valueOf(
                WritingScoringPolicy.rubricFor(
                        expectedTaskType).totalMaxScore());
        if (question.points().compareTo(expectedMaximum) != 0) {
            return false;
        }
        return isCurrentWritingEnvelope(
                value, expectedTaskType, expectedMaximum);
    }

    private boolean manualOrExperimentalWritingUnscored(
            QuestionSnapshot question) {
        if (question == null
                || isBlank(question.answerSpecJson())) {
            return false;
        }
        if (isBlank(question.questionContentJson())) {
            throw new IllegalStateException(
                    "Writing evaluation policy requires typed question content.");
        }
        CanonicalQuestionType type = questionTypeResolver.resolve(
                question.questionType());
        QuestionContent content = assessmentContractCodec.readQuestionContent(
                question.questionContentJson(), type);
        AnswerSpec spec = assessmentContractCodec.readAnswerSpec(
                question.answerSpecJson(), content);
        return spec.effectiveEvaluationMode()
                == AnswerSpec.EvaluationMode
                .MANUAL_OR_EXPERIMENTAL_UNSCORED;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode
    manualWritingUnscored(QuestionSnapshot question) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                objectMapper.createObjectNode();
        node.put("task_type", question.writingTaskType().name());
        node.put("engine", "KSH_WRITING_EVALUATOR_STATUS");
        node.put("policy_bundle_id",
                WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID);
        node.put("evaluation_status", "EVALUATION_UNAVAILABLE");
        node.put("evaluation_source", "SYSTEM");
        node.put("evaluation_reason",
                "MANUAL_OR_EXPERIMENTAL_UNSCORED");
        node.put("evaluation_retryable", false);
        node.put("score_available", false);
        node.set("result_completeness", objectMapper.valueToTree(
                com.ksh.features.practice.ai.contract
                        .PracticeAiResultCompleteness.unavailable(
                                "MANUAL_OR_EXPERIMENTAL_UNSCORED", 0)
                        .toMap()));
        return node;
    }

    private static boolean isCurrentWritingEnvelope(
            WritingEvaluationResult value,
            String expectedTaskType,
            BigDecimal expectedMaximum) {
        if (value == null
                || expectedTaskType == null
                || expectedMaximum == null
                || !WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID.equals(
                        value.policyBundleId())
                || !expectedTaskType.equals(value.taskType())) {
            return false;
        }
        if (!value.scoreAvailableFlag()) {
            boolean unavailable =
                    "EVALUATION_UNAVAILABLE".equals(
                            value.evaluationStatus())
                    && ("PROVIDER".equals(value.evaluationSource())
                    || "SYSTEM".equals(value.evaluationSource()));
            boolean contractFailed =
                    "EVALUATION_CONTRACT_FAILED".equals(
                            value.evaluationStatus())
                    && "PROVIDER".equals(value.evaluationSource());
            return "KSH_WRITING_EVALUATOR_STATUS".equals(value.engine())
                    && (unavailable || contractFailed);
        }
        if (!WritingEvaluationNormalizer.EVALUATION_ENGINE.equals(
                value.engine())
                || !WritingScoringPolicy.SCORING_CONTRACT.equals(
                        value.scoringContract())
                || !WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID.equals(
                        value.policyBundleId())) {
            return false;
        }
        if (value.rawScore() == null
                || value.rawScoreMax() == null
                || value.rawScore().signum() < 0
                || value.rawScore().compareTo(value.rawScoreMax()) > 0
                || value.rawScoreMax().compareTo(expectedMaximum) != 0) {
            return false;
        }
        return WritingAssessmentPolicyBundle
                .hasExactCurrentScoreProvenance(value);
    }

    private static boolean hasExactCurrentWritingEnvelopeShape(
            JsonNode node,
            WritingEvaluationResult value) {
        if (node == null
                || !node.isObject()
                || value == null
                || !node.path("task_type").isTextual()
                || !node.path("engine").isTextual()
                || !node.path("policy_bundle_id").isTextual()
                || !node.path("evaluation_status").isTextual()
                || !node.path("evaluation_source").isTextual()
                || !node.path("evaluation_reason").isTextual()
                || !node.path("evaluation_retryable").isBoolean()
                || !node.path("score_available").isBoolean()) {
            return false;
        }
        if (value.scoreAvailableFlag()) {
            return node.path("score_available").asBoolean()
                    && node.path("raw_score").isNumber()
                    && node.path("raw_score_max").isNumber()
                    && node.path("scoring_contract").isTextual()
                    && WritingEvidenceLedgerVerifier.CONTRACT_VERSION.equals(
                            node.path("ledger_contract_version").asText())
                    && WritingScoreAnchorPolicy.VERSION.equals(
                            node.path("score_anchor_version").asText())
                    && WritingTaskRequirementPolicy.VERSION.equals(
                            node.path("task_requirement_version").asText())
                    && WritingEvidenceLedgerVerifier.SOURCE_NORMALIZATION
                            .equals(node.path("source_normalization").asText())
                    && node.path("source_hash").isTextual()
                    && node.path("source_hash").asText().length() == 64
                    && node.path("rubric_scores").isArray()
                    && node.path("task_coverage").isArray()
                    && node.path("evidence_ledger").isArray()
                    && node.path("strengths").isArray()
                    && node.path("needs_improvement").isArray();
        }
        return !node.path("score_available").asBoolean()
                && !node.has("raw_score")
                && !node.has("raw_score_max");
    }

    private static boolean isSelfConsistentCurrentWritingEnvelope(
            JsonNode node,
            WritingEvaluationResult value) {
        if (value == null || value.taskType() == null) {
            return false;
        }
        try {
            WritingTaskType taskType =
                    WritingTaskType.valueOf(value.taskType());
            BigDecimal expectedMaximum = BigDecimal.valueOf(
                    WritingScoringPolicy.rubricFor(
                            taskType.name()).totalMaxScore());
            return isCurrentWritingEnvelope(
                    value, taskType.name(), expectedMaximum)
                    && hasExactCurrentWritingEnvelopeShape(
                            node, value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Long persistSpeakingGradingResult(
            SpeakingGradingSnapshot snapshot,
            SpeakingGradingResult result,
            boolean submit
    ) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(snapshot.attemptId(), snapshot.userId())
                .orElseThrow(() -> new EntityNotFoundException("Bai lam da thay doi trong luc cham. Vui long tai lai va thu lai."));
        verifySpeakingSnapshotIdentity(attempt, snapshot);
        if (submit && !PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw conflict();
        }
        if (!submit && !Objects.equals(snapshot.expectedStatus(), attempt.getStatus())) {
            throw conflict();
        }
        if (!Objects.equals(normalizeJsonForCompare(snapshot.expectedExistingAnswersJson()), normalizeJsonForCompare(attempt.getAnswersJson()))) {
            throw conflict();
        }
        if (!submit && !Objects.equals(snapshot.expectedAiFeedbackJson(), attempt.getAiFeedbackJson())) {
            throw conflict();
        }
        verifySpeakingSnapshotVersion(attempt, snapshot);
        if (result.aiFeedbackJson() == null) {
            attempt.markSubmitted(result.score(), result.totalPoints(), result.answersJson());
        } else {
            attempt.markGraded(result.score(), result.totalPoints(), result.answersJson(), result.aiFeedbackJson());
        }
        flushAttempt(attempt);
        log.info("[PracticeService] {} PracticeAttempt id={} score={} / {}",
                submit ? "Submitted" : "Re-evaluated", attempt.getId(), result.score(), result.totalPoints());
        return attempt.getId();
    }

    private void verifySpeakingSnapshotIdentity(PracticeAttempt attempt, SpeakingGradingSnapshot snapshot) {
        if (!Objects.equals(attempt.getSetId(), snapshot.setId())
                || !Objects.equals(attempt.getTestId(), snapshot.testId())
                || !Objects.equals(attempt.getSectionId(), snapshot.sectionId())
                || !Objects.equals(attempt.getSkill(), snapshot.skill())) {
            throw conflict();
        }
    }

    private void verifySpeakingSnapshotVersion(PracticeAttempt attempt, SpeakingGradingSnapshot snapshot) {
        if (snapshot.lockVersion() != null && !Objects.equals(attempt.getLockVersion(), snapshot.lockVersion())) {
            throw conflict();
        }
    }

    private Long persistWritingSubmitResult(WritingGradingSnapshot snapshot, WritingGradingResult result) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(snapshot.attemptId(), snapshot.userId())
                .orElseThrow(() -> new EntityNotFoundException("Bài làm đã thay đổi trong lúc chấm. Vui lòng tải lại và thử lại."));
        if (!PracticeAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw conflict();
        }
        if (!Objects.equals(normalizeJsonForCompare(snapshot.expectedExistingAnswersJson()), normalizeJsonForCompare(attempt.getAnswersJson()))) {
            throw conflict();
        }
        verifySnapshotVersion(attempt, snapshot);
        if (result.score() == null) {
            attempt.markSubmitted(null, result.totalPoints(), result.answersJson());
            attempt.setAiFeedbackJson(result.feedbackJson());
        } else {
            attempt.markGraded(result.score(), result.totalPoints(), result.answersJson(), result.feedbackJson());
        }
        flushAttempt(attempt);
        log.info("[PracticeService] Submitted WRITING PracticeAttempt id={} score={} / {}", attempt.getId(), attempt.getScore(), attempt.getTotalPoints());
        return attempt.getId();
    }

    private Long persistWritingReEvaluationResult(WritingGradingSnapshot snapshot, WritingGradingResult result) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(snapshot.attemptId(), snapshot.userId())
                .orElseThrow(() -> new EntityNotFoundException("Bài làm đã thay đổi trong lúc chấm. Vui lòng tải lại và thử lại."));
        if (!Objects.equals(snapshot.expectedStatus(), attempt.getStatus())) {
            throw conflict();
        }
        if (!Objects.equals(normalizeJsonForCompare(snapshot.expectedExistingAnswersJson()), normalizeJsonForCompare(attempt.getAnswersJson()))) {
            throw conflict();
        }
        verifySnapshotVersion(attempt, snapshot);
        if (result.score() == null) {
            return attempt.getId();
        }
        attempt.markGraded(result.score(), result.totalPoints(), result.answersJson(), result.feedbackJson());
        flushAttempt(attempt);
        log.info("[PracticeService] Re-evaluated WRITING PracticeAttempt id={} score={} / {}", attempt.getId(), attempt.getScore(), attempt.getTotalPoints());
        return attempt.getId();
    }

    private Long persistWritingQuestionReEvaluationResult(
            WritingQuestionReEvaluationSnapshot snapshot,
            WritingGradingResult result
    ) {
        PracticeAttempt attempt = attemptRepository.findByIdAndUserId(snapshot.attemptId(), snapshot.userId())
                .orElseThrow(() -> new EntityNotFoundException("Bai lam da thay doi trong luc cham. Vui long tai lai va thu lai."));
        if (!Objects.equals(snapshot.expectedStatus(), attempt.getStatus())) {
            throw conflict();
        }
        if (!Objects.equals(snapshot.expectedAnswersJson(), attempt.getAnswersJson())) {
            throw conflict();
        }
        if (!Objects.equals(snapshot.expectedAiFeedbackJson(), attempt.getAiFeedbackJson())) {
            throw conflict();
        }
        verifyQuestionSnapshotVersion(attempt, snapshot);
        if (result.score() == null) {
            return attempt.getId();
        }
        attempt.markGraded(result.score(), result.totalPoints(), result.answersJson(), result.feedbackJson());
        flushAttempt(attempt);
        log.info("[PracticeService] Re-evaluated WRITING question PracticeAttempt id={} questionId={} score={} / {}",
                attempt.getId(), snapshot.targetQuestion().questionId(), attempt.getScore(), attempt.getTotalPoints());
        return attempt.getId();
    }

    private void verifySnapshotVersion(PracticeAttempt attempt, WritingGradingSnapshot snapshot) {
        if (!Objects.equals(attempt.getLockVersion(), snapshot.lockVersion())) {
            throw conflict();
        }
    }

    private void verifyQuestionSnapshotVersion(PracticeAttempt attempt, WritingQuestionReEvaluationSnapshot snapshot) {
        if (!Objects.equals(attempt.getLockVersion(), snapshot.lockVersion())) {
            throw conflict();
        }
    }

    private void flushAttempt(PracticeAttempt attempt) {
        try {
            attemptRepository.saveAndFlush(attempt);
        } catch (OptimisticLockingFailureException ex) {
            throw conflict();
        }
    }

    private PracticeAttemptConflictException conflict() {
        return new PracticeAttemptConflictException("Bài làm đã thay đổi trong lúc chấm. Vui lòng tải lại và thử lại.");
    }

    private PracticeAttemptConflictException unsupportedPerQuestionFeedback() {
        return new PracticeAttemptConflictException("Du lieu phan hoi cu khong ho tro cham lai tung cau. Vui long cham lai toan bai.");
    }

    private String normalizeJsonForCompare(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    public PracticeAttemptEvaluationOutcome evaluateClaimedAttempt(
            PracticeAttemptEvaluationJobTransactions.ClaimedEvaluationJob
                    claim) {
        EvaluationJobWork work = executeRead(() ->
                loadEvaluationJobWork(claim));
        return executeNonTransactional(() ->
                evaluateJobWork(work));
    }

    private EvaluationJobWork loadEvaluationJobWork(
            PracticeAttemptEvaluationJobTransactions.ClaimedEvaluationJob
                    claim) {
        PracticeAttempt attempt = attemptRepository
                .findByIdAndUserId(claim.attemptId(), claim.userId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Evaluation attempt no longer exists."));
        String evaluationContractIdentity =
                evaluationContractIdentity(attempt.getSkill());
        if (!Objects.equals(
                claim.evaluationContractIdentity(),
                evaluationContractIdentity)) {
            throw new PracticeEvaluationContractChangedException(
                    "Evaluation contract changed after the job was queued.");
        }
        String mediaIdentity = "SPEAKING".equals(attempt.getSkill())
                ? speakingMediaIdentityMaterial(
                        attempt,
                        loadQuestionSnapshots(
                                attempt, attempt.getSectionId()))
                : null;
        String operationIdentity;
        if (PracticeAttemptEvaluationJob.OPERATION_FULL_REEVALUATE
                .equals(claim.operation())
                || PracticeAttemptEvaluationJob
                        .OPERATION_QUESTION_REEVALUATE
                        .equals(claim.operation())) {
            operationIdentity = reEvaluationIdentityMaterial(
                    attempt, claim.targetQuestionId());
        } else {
            operationIdentity = mediaIdentity;
        }
        String fingerprint = evaluationInputFingerprint(
                attempt,
                normalizeJsonForCompare(attempt.getAnswersJson()),
                operationIdentity);
        if (!fingerprint.equals(claim.inputFingerprint())) {
            throw new IllegalStateException(
                    "Evaluation input changed after the job was queued.");
        }

        if ("WRITING".equals(attempt.getSkill())) {
            if (PracticeAttemptEvaluationJob.OPERATION_QUESTION_REEVALUATE
                    .equals(claim.operation())) {
                return new EvaluationJobWork(
                        fingerprint,
                        attempt.getSkill(),
                        claim.operation(),
                        null,
                        loadWritingQuestionReEvaluationSnapshot(
                                attempt, claim.targetQuestionId()),
                        null);
            }
            return new EvaluationJobWork(
                    fingerprint,
                    attempt.getSkill(),
                    claim.operation(),
                    loadWritingReEvaluationSnapshot(attempt),
                    null,
                    null);
        }
        if ("SPEAKING".equals(attempt.getSkill())
                && PracticeAttemptEvaluationJob.OPERATION_SUBMIT.equals(
                        claim.operation())) {
            return new EvaluationJobWork(
                    fingerprint,
                    attempt.getSkill(),
                    claim.operation(),
                    null,
                    null,
                    loadSpeakingReEvaluationSnapshot(
                            attempt.getId(), attempt.getUserId()));
        }
        throw new IllegalStateException(
                "Evaluation job does not match a supported subjective skill.");
    }

    private PracticeAttemptEvaluationOutcome evaluateJobWork(
            EvaluationJobWork work) {
        if (work.writingQuestionSnapshot() != null) {
            WritingGradingResult result = gradeWritingQuestionSnapshot(
                    work.writingQuestionSnapshot());
            return writingEvaluationOutcome(
                    work.inputFingerprint(), result);
        }
        if (work.writingSnapshot() != null) {
            WritingGradingResult result = gradeWritingSnapshot(
                    work.writingSnapshot(),
                    !PracticeAttemptEvaluationJob.OPERATION_SUBMIT.equals(
                            work.operation()));
            return writingEvaluationOutcome(
                    work.inputFingerprint(), result);
        }
        if (work.speakingSnapshot() != null) {
            SpeakingGradingResult result =
                    gradeSpeakingSnapshot(work.speakingSnapshot());
            return speakingEvaluationOutcome(
                    work.inputFingerprint(), result);
        }
        throw new IllegalStateException(
                "Evaluation job has no executable snapshot.");
    }

    private PracticeAttemptEvaluationOutcome writingEvaluationOutcome(
            String fingerprint,
            WritingGradingResult result) {
        if (result.score() != null) {
            return new PracticeAttemptEvaluationOutcome(
                    PracticeAttemptEvaluationOutcome.SUCCEEDED,
                    fingerprint,
                    result.score(),
                    result.totalPoints(),
                    result.answersJson(),
                    result.feedbackJson(),
                    "KSH_WRITING_ASYNC",
                    null,
                    false);
        }
        EvaluationFailureMetadata failure =
                writingFailureMetadata(
                        result.failureMetadataJson() == null
                                ? result.feedbackJson()
                                : result.failureMetadataJson());
        return new PracticeAttemptEvaluationOutcome(
                failure.terminalStatus(),
                fingerprint,
                null,
                result.totalPoints(),
                result.answersJson(),
                result.feedbackJson(),
                "KSH_WRITING_ASYNC",
                failure.errorCode(),
                failure.retryable());
    }

    private PracticeAttemptEvaluationOutcome speakingEvaluationOutcome(
            String fingerprint,
            SpeakingGradingResult result) {
        Map<Long, SpeakingEvaluationResult> feedback =
                storedSpeakingResults(result.aiFeedbackJson());
        SpeakingOutcomeClassification classification =
                classifySpeakingEvaluation(
                        feedback,
                        result.completeQuestionFeedback());
        if (classification.succeeded()) {
            return new PracticeAttemptEvaluationOutcome(
                    PracticeAttemptEvaluationOutcome.SUCCEEDED,
                    fingerprint,
                    result.score(),
                    result.totalPoints(),
                    result.answersJson(),
                    result.aiFeedbackJson(),
                    "KSH_SPEAKING_ASYNC",
                    null,
                    false);
        }
        return new PracticeAttemptEvaluationOutcome(
                classification.terminalStatus(),
                fingerprint,
                null,
                result.totalPoints(),
                result.answersJson(),
                result.aiFeedbackJson(),
                "KSH_SPEAKING_ASYNC",
                classification.errorCode(),
                classification.retryable());
    }

    static SpeakingOutcomeClassification classifySpeakingEvaluation(
            Map<Long, SpeakingEvaluationResult> feedback,
            boolean completeQuestionFeedback) {
        Map<Long, SpeakingEvaluationResult> safeFeedback =
                feedback == null ? Map.of() : feedback;
        boolean succeeded = completeQuestionFeedback
                && !safeFeedback.isEmpty()
                && safeFeedback.values().stream()
                        .allMatch(PracticeService
                                ::successfulSpeakingEvaluation);
        if (succeeded) {
            return new SpeakingOutcomeClassification(
                    true,
                    PracticeAttemptEvaluationOutcome.SUCCEEDED,
                    null,
                    false);
        }
        List<SpeakingEvaluationResult> failures =
                safeFeedback.values().stream()
                        .filter(value ->
                                !successfulSpeakingEvaluation(value))
                        .toList();
        boolean unavailableOnly = !failures.isEmpty()
                && failures.stream()
                        .allMatch(PracticeService
                                ::explicitSpeakingUnavailable);
        String terminalStatus = unavailableOnly
                ? PracticeAttemptEvaluationOutcome.UNAVAILABLE
                : PracticeAttemptEvaluationOutcome.FAILED;
        SpeakingEvaluationResult failure = failures.stream()
                .filter(value -> value != null
                        && value.errorCategory() != null
                        && !value.errorCategory().isBlank()
                        && (PracticeAttemptEvaluationOutcome
                                        .UNAVAILABLE
                                        .equals(terminalStatus)
                                == explicitSpeakingUnavailable(
                                        value)))
                .findFirst()
                .orElse(null);
        return new SpeakingOutcomeClassification(
                false,
                terminalStatus,
                failure == null
                        ? (PracticeAttemptEvaluationOutcome.FAILED
                                .equals(terminalStatus)
                                ? "SPEAKING_EVALUATION_FAILED"
                                : "SPEAKING_EVALUATION_UNAVAILABLE")
                        : failure.errorCategory(),
                failures.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(
                                SpeakingEvaluationResult::retryable));
    }

    private static boolean explicitSpeakingUnavailable(
            SpeakingEvaluationResult value) {
        return value != null
                && (value.evaluationStatus()
                        == SpeakingEvaluationStatus
                                .TRANSCRIPTION_UNAVAILABLE
                || value.evaluationStatus()
                        == SpeakingEvaluationStatus
                                .EVALUATION_UNAVAILABLE
                || value.evaluationStatus()
                        == SpeakingEvaluationStatus
                                .AUDIO_UNAVAILABLE);
    }

    private static boolean successfulSpeakingEvaluation(
            SpeakingEvaluationResult value) {
        if (value == null
                || value.evaluationStatus() == null
                || !value.currentEvidenceContract()) {
            return false;
        }
        if (value.evaluationStatus()
                == com.ksh.features.practice.ai.speaking
                        .SpeakingEvaluationStatus
                        .TRANSCRIPTION_LOW_CONFIDENCE) {
            return true;
        }
        return value.evaluationStatus()
                == com.ksh.features.practice.ai.speaking
                        .SpeakingEvaluationStatus.EVALUATED
                && value.profileAvailable();
    }

    EvaluationFailureMetadata writingFailureMetadata(
            String feedbackJson) {
        boolean retryable = false;
        boolean explicitUnavailable = false;
        boolean failed = false;
        String unavailableError = null;
        String failedError = null;
        try {
            JsonNode root = objectMapper.readTree(feedbackJson);
            if (root != null && root.isObject()) {
                Iterable<JsonNode> feedbackEntries =
                        root.has("evaluation_reason")
                                ? List.of(root)
                                : iterable(root.elements());
                for (JsonNode value : feedbackEntries) {
                    if (value == null || !value.isObject()) continue;
                    WritingFeedbackContractParser.EntryResult parsed =
                            writingFeedbackParser.parseGeneratedEntry(value);
                    WritingEvaluationResult evaluation = parsed.value();
                    boolean currentEnvelope =
                            parsed.status()
                                    == WritingFeedbackContractParser.Status.VALID_CURRENT
                            && isSelfConsistentCurrentWritingEnvelope(
                                    value,
                                    evaluation);
                    if (currentEnvelope
                            && evaluation.scoreAvailableFlag()) {
                        continue;
                    }
                    if (!currentEnvelope) {
                        failed = true;
                        if (failedError == null) {
                            failedError =
                                    "WRITING_EVALUATION_CONTRACT_FAILED";
                        }
                        continue;
                    }
                    String reason = value.path(
                            "evaluation_reason").asText("");
                    String status = value.path(
                                    "evaluation_status")
                            .asText("")
                            .trim()
                            .toUpperCase(java.util.Locale.ROOT);
                    if (status.contains("UNAVAILABLE")
                            || status.contains("NOT_SCORABLE")) {
                        explicitUnavailable = true;
                        if (unavailableError == null) {
                            unavailableError = reason.isBlank()
                                    ? "WRITING_EVALUATION_UNAVAILABLE"
                                    : reason;
                        }
                    } else {
                        failed = true;
                        if (failedError == null) {
                            failedError = reason.isBlank()
                                    ? "WRITING_EVALUATION_FAILED"
                                    : reason;
                        }
                    }
                    retryable = retryable
                            || value.path(
                                    "evaluation_retryable")
                                    .asBoolean(false);
                }
            } else {
                failed = true;
                failedError =
                        "WRITING_EVALUATION_CONTRACT_FAILED";
            }
        } catch (Exception ignored) {
            failed = true;
            failedError =
                    "WRITING_EVALUATION_CONTRACT_FAILED";
        }
        String terminalStatus =
                failed || !explicitUnavailable
                        ? PracticeAttemptEvaluationOutcome.FAILED
                        : PracticeAttemptEvaluationOutcome.UNAVAILABLE;
        return new EvaluationFailureMetadata(
                terminalStatus,
                PracticeAttemptEvaluationOutcome.FAILED.equals(
                        terminalStatus)
                        ? (failedError == null
                                ? "WRITING_EVALUATION_FAILED"
                                : failedError)
                        : (unavailableError == null
                                ? "WRITING_EVALUATION_UNAVAILABLE"
                                : unavailableError),
                retryable);
    }

    private static <T> Iterable<T> iterable(
            java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    private String speakingMediaIdentityMaterial(
            PracticeAttempt attempt,
            List<QuestionSnapshot> questions) {
        if (speakingMediaService == null) {
            throw new IllegalStateException(
                    "Speaking media identity service is unavailable.");
        }
        List<Long> questionIds = questions.stream()
                .map(QuestionSnapshot::questionId)
                .sorted()
                .toList();
        Map<Long, SpeakingMediaIdentity> identities =
                PracticeAttempt.STATUS_IN_PROGRESS.equals(
                        attempt.getStatus())
                        ? speakingMediaService
                                .requireReadyMediaForOwner(
                                        attempt.getUserId(),
                                        attempt.getId(),
                                        questionIds)
                        : speakingMediaService
                                .requireReadyMediaForTerminalEvaluation(
                                        attempt.getUserId(),
                                        attempt.getId(),
                                        questionIds);
        return questionIds.stream()
                .map(id -> {
                    SpeakingMediaIdentity media = identities.get(id);
                    if (media == null) {
                        throw new IllegalStateException(
                                "Speaking media identity is incomplete.");
                    }
                    return id + ":" + media.mediaId()
                            + ":" + media.lockVersion()
                            + ":" + media.contentHash()
                            + ":" + media.byteSize();
                })
                .collect(Collectors.joining("|"));
    }

    private String evaluationInputFingerprint(
            PracticeAttempt attempt,
            String answersJson,
            String extraIdentity) {
        String material = String.join(
                "|",
                String.valueOf(attempt.getId()),
                String.valueOf(attempt.getPublishedVersionId()),
                String.valueOf(attempt.getSetVersionId()),
                String.valueOf(attempt.getTestVersionId()),
                String.valueOf(attempt.getSectionVersionId()),
                String.valueOf(attempt.getSkill()),
                evaluationContractIdentity(attempt.getSkill()),
                canonicalAnswerFingerprintMaterial(answersJson),
                extraIdentity == null ? "" : extraIdentity);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.", exception);
        }
    }

    private String evaluationContractIdentity(String skill) {
        String identity;
        if ("WRITING".equals(skill)) {
            identity = evaluationClient.evaluationContractIdentity();
        } else if ("SPEAKING".equals(skill)
                && speakingEvaluationApplicationService != null) {
            identity = speakingEvaluationApplicationService
                    .evaluationContractIdentity();
        } else {
            throw new IllegalStateException(
                    "No subjective evaluation contract exists for skill "
                            + skill + ".");
        }
        if (identity == null
                || identity.isBlank()
                || identity.length()
                > PracticeAttemptEvaluationJob
                        .MAX_EVALUATION_CONTRACT_IDENTITY_LENGTH) {
            throw new IllegalStateException(
                    "Subjective evaluation contract identity is invalid.");
        }
        return identity;
    }

    private static void requireEvaluationThreadActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException(
                    "Subjective evaluation exceeded its execution window.");
        }
    }

    private String reEvaluationIdentityMaterial(
            PracticeAttempt attempt, Long questionId) {
        return String.join(
                "|",
                questionId == null
                        ? "full"
                        : "question:" + questionId,
                canonicalJsonFingerprintMaterial(
                        attempt.getAiFeedbackJson()),
                String.valueOf(attempt.getScore()),
                String.valueOf(attempt.getTotalPoints()));
    }

    private String canonicalJsonFingerprintMaterial(
            String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            return value;
        }
    }

    private String canonicalAnswerFingerprintMaterial(
            String answersJson) {
        Map<String, String> answers = new java.util.TreeMap<>(
                readAnswers(answersJson));
        return writeJson(answers);
    }

    private record EvaluationJobWork(
            String inputFingerprint,
            String skill,
            String operation,
            WritingGradingSnapshot writingSnapshot,
            WritingQuestionReEvaluationSnapshot
                    writingQuestionSnapshot,
            SpeakingGradingSnapshot speakingSnapshot
    ) {
    }

    record EvaluationFailureMetadata(
            String terminalStatus,
            String errorCode,
            boolean retryable
    ) {
    }

    record SpeakingOutcomeClassification(
            boolean succeeded,
            String terminalStatus,
            String errorCode,
            boolean retryable
    ) {
    }

    private record WritingGradingSnapshot(
            Long attemptId,
            Long userId,
            Long sectionId,
            String skill,
            String expectedStatus,
            Long lockVersion,
            String expectedExistingAnswersJson,
            String answersToPersistJson,
            Map<String, String> answers,
            List<QuestionSnapshot> questions
    ) {
    }

    private record WritingQuestionReEvaluationSnapshot(
            Long attemptId,
            Long userId,
            Long sectionId,
            String skill,
            String expectedStatus,
            Long lockVersion,
            String expectedAnswersJson,
            String expectedAiFeedbackJson,
            Map<String, String> answers,
            List<QuestionSnapshot> questions,
            QuestionSnapshot targetQuestion
    ) {
    }

    private record SpeakingGradingSnapshot(
            Long attemptId,
            Long userId,
            Long setId,
            Long testId,
            Long sectionId,
            String skill,
            String expectedStatus,
            Long lockVersion,
            String expectedExistingAnswersJson,
            String expectedAiFeedbackJson,
            BigDecimal expectedScore,
            BigDecimal expectedTotalPoints,
            String answersToPersistJson,
            Map<String, String> answers,
            List<QuestionSnapshot> questions
    ) {
    }

    private record QuestionSnapshot(
            Long questionId,
            Long questionVersionId,
            Integer questionNo,
            Integer displayOrder,
            String prompt,
            String questionType,
            String questionContentJson,
            String optionsJson,
            String answerKey,
            String answerSpecJson,
            String teacherExplanation,
            BigDecimal points,
            WritingTaskType writingTaskType
    ) {
    }

    private record WritingScoreAggregate(
            BigDecimal score,
            BigDecimal totalPoints
    ) {
    }

    private record WritingGradingResult(
            BigDecimal score,
            BigDecimal totalPoints,
            String answersJson,
            String feedbackJson,
            String failureMetadataJson
    ) {
    }

    private record SpeakingGradingResult(
            BigDecimal score,
            BigDecimal totalPoints,
            String answersJson,
            String aiFeedbackJson,
            boolean completeQuestionFeedback
    ) {
    }

    private com.fasterxml.jackson.databind.node.ObjectNode readWritingFeedbackObject(Long questionId, String feedbackJson) {
        try {
            JsonNode node = objectMapper.readTree(feedbackJson);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("AI feedback root must be an object for question ID: " + questionId);
            }
            return (com.fasterxml.jackson.databind.node.ObjectNode) node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid AI feedback JSON for question ID: " + questionId, e);
        }
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        if (value.compareTo(max) > 0) {
            return max;
        }
        return value;
    }

    private static String exceptionCategory(Exception ex) {
        return ex == null ? "unknown" : ex.getClass().getSimpleName();
    }
}
