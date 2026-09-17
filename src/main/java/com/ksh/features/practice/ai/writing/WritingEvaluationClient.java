package com.ksh.features.practice.ai.writing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.ai.OpenAiProperties;
import com.ksh.features.practice.ai.media.AiImageEvidence;
import com.ksh.features.practice.ai.controlplane.PracticeAiPurpose;
import com.ksh.features.practice.ai.media.AiQuestionImageResolver;
import com.ksh.features.practice.ai.metrics.PracticeAiMetrics;
import com.ksh.features.practice.ai.transport.PracticeAiAuthoritySnapshot;
import com.ksh.features.practice.ai.transport.PracticeAiCapability;
import com.ksh.features.practice.ai.transport.PracticeAiContractException;
import com.ksh.features.practice.ai.transport.PracticeModelCapabilityProfile;
import com.ksh.features.practice.ai.transport.PracticeStructuredGenerationPort;
import com.ksh.features.practice.ai.transport.PracticeStructuredGenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WritingEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(WritingEvaluationClient.class);

    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final WritingEvaluationNormalizer normalizer;
    private final WritingRuleEngine ruleEngine;
    private final WritingTaskResolver taskResolver;
    private final WritingEvaluationCacheService cacheService;
    private final PracticeAiMetrics metrics;
    private final AiQuestionImageResolver imageResolver;
    private final PracticeStructuredGenerationPort structuredGeneration;

    @Autowired
    public WritingEvaluationClient(OpenAiProperties properties,
            ObjectMapper objectMapper,
            WritingEvaluationNormalizer normalizer,
            WritingRuleEngine ruleEngine,
            WritingTaskResolver taskResolver,
            WritingEvaluationCacheService cacheService,
            AiQuestionImageResolver imageResolver,
            PracticeAiMetrics metrics,
            PracticeStructuredGenerationPort structuredGeneration) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
        this.ruleEngine = ruleEngine;
        this.taskResolver = taskResolver;
        this.cacheService = cacheService;
        this.imageResolver = imageResolver;
        this.metrics = metrics == null ? PracticeAiMetrics.noop() : metrics;
        this.structuredGeneration = structuredGeneration;
    }

    WritingEvaluationClient(OpenAiProperties properties,
            ObjectMapper objectMapper,
            WritingEvaluationNormalizer normalizer,
            WritingRuleEngine ruleEngine,
            WritingEvaluationCacheService cacheService,
            PracticeStructuredGenerationPort structuredGeneration) {
        this(
                properties,
                objectMapper,
                normalizer,
                ruleEngine,
                new WritingTaskResolver(),
                cacheService,
                null,
                PracticeAiMetrics.noop(),
                structuredGeneration);
    }

    public String evaluate(String prompt, String learnerAnswer) {
        return evaluate(null, prompt, learnerAnswer, false);
    }

    public String evaluationContractIdentity() {
        return String.join(
                "|",
                "ksh-writing-evaluation-v3",
                transportIdentity(),
                evaluatorModel(),
                properties.connectTimeout().toString(),
                properties.readTimeout().toString(),
                "retry-authority=purpose-binding-0..3-plus-at-most-one-full-replacement-within-budget",
                WritingPromptRules.PROMPT_VERSION,
                WritingPromptRules.RUBRIC_VERSION,
                cacheSchemaVersion(),
                WritingAssessmentPolicyBundle.identity());
    }

    public String evaluate(String prompt, String learnerAnswer, boolean isReEvaluation) {
        return evaluate(null, prompt, learnerAnswer, isReEvaluation);
    }

    public String evaluate(Long userId, String prompt, String learnerAnswer, boolean isReEvaluation) {
        return evaluate(userId, prompt, learnerAnswer, isReEvaluation, null);
    }

    public String evaluate(Long userId, String prompt, String learnerAnswer, boolean isReEvaluation,
                           WritingTaskType explicitTaskType) {
        return evaluate(userId, prompt, learnerAnswer, isReEvaluation, explicitTaskType, null);
    }

    public String evaluate(Long userId, String prompt, String learnerAnswer, boolean isReEvaluation,
                           WritingTaskType explicitTaskType, String imageReference) {
        AiImageEvidence imageEvidence = imageResolver == null
                ? null
                : imageResolver.resolve(imageReference, userId).orElse(null);
        String cachePrompt = cachePrompt(prompt, imageEvidence);
        String resolvedTaskType = taskResolver.resolve(explicitTaskType, prompt);
        WritingRuleEngine.RuleAnalysis ruleAnalysis = ruleEngine.analyze(prompt, learnerAnswer, resolvedTaskType);
        log.info("KSH writing evaluation started: model={}, taskType={}, charCount={}, violations={}, reEvaluation={}",
                evaluatorModel(), ruleAnalysis.taskType(), ruleAnalysis.characterCount(),
                ruleAnalysis.ruleViolations().size(), isReEvaluation);

        // 1. Deterministic spam short-circuit — task-aware
        if (isDefinitelyInvalid(learnerAnswer, ruleAnalysis)) {
            log.info("KSH writing evaluation deterministic invalid-response short-circuit: taskType={}", ruleAnalysis.taskType());
            return normalizer.spamResponse(ruleAnalysis.taskType(), learnerAnswer);
        }

        // 2. Cache lookup (skip for re-evaluation)
        if (!isReEvaluation) {
            try {
                var cached = cacheService.get(userId, cachePrompt, learnerAnswer,
                        ruleAnalysis.taskType(), evaluatorModel(),
                        WritingPromptRules.PROMPT_VERSION, WritingPromptRules.RUBRIC_VERSION,
                        cacheSchemaVersion());
                if (cached.isPresent()) {
                    long parseStart = PracticeAiMetrics.startNanos();
                    try {
                        String rehydrated = normalizer.rehydrateCachedResult(
                                cached.get(), learnerAnswer, ruleAnalysis.taskType());
                        log.info("KSH writing evaluation cache hit: taskType={}, charCount={}",
                                ruleAnalysis.taskType(), ruleAnalysis.characterCount());
                        return rehydrated;
                    } catch (Exception ex) {
                        metrics.recordCacheOperation(
                                PracticeAiMetrics.CacheType.WRITING,
                                PracticeAiMetrics.CacheOperation.PARSE,
                                PracticeAiMetrics.CacheOutcome.MALFORMED,
                                PracticeAiMetrics.elapsedSince(parseStart));
                        log.warn("KSH writing evaluation cache entry ignored because payload is malformed: taskType={}",
                                ruleAnalysis.taskType());
                        deleteCacheEntry(userId, cachePrompt, learnerAnswer, ruleAnalysis);
                    }
                }
            } catch (Exception ex) {
                log.warn("KSH writing evaluation cache read failed; treating as miss: operation=cache-read taskType={} exception={}",
                        ruleAnalysis.taskType(), exceptionCategory(ex));
            }
        }

        // 3. Fail closed when provider credentials are unavailable
        if (!providerAvailable()) {
            long providerStart = PracticeAiMetrics.startNanos();
            String unavailable = normalizer.providerUnavailable(
                    "MISSING_API_KEY",
                    ruleAnalysis.taskType(),
                    learnerAnswer,
                    false);
            recordWritingProvider(PracticeAiMetrics.ProviderOutcome.FAILURE, providerStart);
            return unavailable;
        }

        // 4. Single unified provider call
        JsonNode response;
        long providerStart = PracticeAiMetrics.startNanos();
        try {
            String systemPrompt = WritingPromptRules.buildUnifiedPrompt(
                    ruleAnalysis.taskType(), isReEvaluation);
            Map<String, Object> userPayload = userPayloadObject(
                    prompt, learnerAnswer, ruleAnalysis, isReEvaluation, imageEvidence);

            response = callPass(
                    "unified", systemPrompt, userPayload, imageEvidence,
                    unifiedResponseFormat(ruleAnalysis.taskType()));
            log.info("KSH writing evaluation unified call complete: taskType={}",
                    ruleAnalysis.taskType());
        } catch (ProviderContractException ex) {
            log.warn("Writing AI evaluation contract failed: operation=provider-contract model={} taskType={} reason={} exception={}",
                    evaluatorModel(), ruleAnalysis.taskType(), ex.reason(), exceptionCategory(ex));
            String failure = normalizer.contractFailure(ex.reason(), ruleAnalysis.taskType(), learnerAnswer);
            recordWritingProvider(PracticeAiMetrics.ProviderOutcome.FAILURE, providerStart);
            return failure;
        } catch (EvaluationInterruptedException ex) {
            // Worker interruption is lifecycle control, not a provider
            // availability outcome. Let the durable job remain reclaimable.
            throw ex;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            log.warn("Writing AI evaluation failed: operation=provider-call status={} model={} taskType={} retryable={} exception={}",
                    status, evaluatorModel(), ruleAnalysis.taskType(), isRetryable(status), exceptionCategory(ex));
            String unavailable = normalizer.providerUnavailable(
                    "PROVIDER_HTTP_ERROR",
                    ruleAnalysis.taskType(),
                    learnerAnswer,
                    isRetryable(status));
            recordWritingProvider(PracticeAiMetrics.ProviderOutcome.FAILURE, providerStart);
            return unavailable;
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            log.warn("Writing AI evaluation failed: operation=provider-call model={} taskType={} category=transport exception={}",
                    evaluatorModel(), ruleAnalysis.taskType(), exceptionCategory(ex));
            String unavailable = normalizer.providerUnavailable(
                    "PROVIDER_TRANSPORT_ERROR",
                    ruleAnalysis.taskType(),
                    learnerAnswer,
                    true);
            recordWritingProvider(PracticeAiMetrics.ProviderOutcome.FAILURE, providerStart);
            return unavailable;
        } catch (Exception ex) {
            log.warn("Writing AI evaluation failed: operation=provider-call model={} taskType={} category=unexpected exception={}",
                    evaluatorModel(), ruleAnalysis.taskType(), exceptionCategory(ex));
            String unavailable = normalizer.providerUnavailable(
                    "PROVIDER_UNEXPECTED_ERROR",
                    ruleAnalysis.taskType(),
                    learnerAnswer,
                    false);
            recordWritingProvider(PracticeAiMetrics.ProviderOutcome.FAILURE, providerStart);
            return unavailable;
        }

        // 5. Normalize — normalizer is sole source of score/raw_score/raw_score_max
        String responseJson;
        try {
            responseJson = objectMapper.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            recordWritingProvider(PracticeAiMetrics.ProviderOutcome.FAILURE, providerStart);
            throw new IllegalStateException("Internal error serializing JSON response", e);
        }

        String normalized = normalizer.normalize(
                responseJson,
                ruleAnalysis.taskType(),
                learnerAnswer,
                ruleAnalysis);
        recordWritingProvider(normalizer.isCacheableAiResult(normalized)
                ? PracticeAiMetrics.ProviderOutcome.SUCCESS
                : PracticeAiMetrics.ProviderOutcome.FALLBACK, providerStart);

        // 6. Cache result (both submit and re-evaluate overwrite cache)
        if (normalizer.isCacheableAiResult(normalized)) {
            try {
                cacheService.put(userId, cachePrompt, learnerAnswer,
                        ruleAnalysis.taskType(), evaluatorModel(),
                        WritingPromptRules.PROMPT_VERSION, WritingPromptRules.RUBRIC_VERSION,
                        cacheSchemaVersion(),
                        normalizer.sanitizeForCache(normalized));
            } catch (Exception ex) {
                log.warn("KSH writing evaluation cache write failed; returning provider result: operation=cache-write taskType={} exception={}",
                        ruleAnalysis.taskType(), exceptionCategory(ex));
            }
        }

        return normalized;
    }

    private void deleteCacheEntry(Long userId, String prompt, String learnerAnswer,
                                  WritingRuleEngine.RuleAnalysis ruleAnalysis) {
        try {
            cacheService.delete(userId, prompt, learnerAnswer,
                    ruleAnalysis.taskType(), evaluatorModel(),
                    WritingPromptRules.PROMPT_VERSION, WritingPromptRules.RUBRIC_VERSION,
                        cacheSchemaVersion());
        } catch (Exception ex) {
            log.warn("KSH writing evaluation malformed cache cleanup failed: operation=cache-delete taskType={} exception={}",
                    ruleAnalysis.taskType(), exceptionCategory(ex));
        }
    }

    private void recordWritingProvider(PracticeAiMetrics.ProviderOutcome outcome, long startNanos) {
        metrics.recordProviderOperation(
                PracticeAiMetrics.ProviderFeature.WRITING,
                outcome,
                PracticeAiMetrics.elapsedSince(startNanos));
    }

    private static String exceptionCategory(Exception ex) {
        return ex == null ? "unknown" : ex.getClass().getSimpleName();
    }

    private static String cacheSchemaVersion() {
        return WritingPromptRules.EVALUATION_SCHEMA_VERSION + ":" + WritingPromptRules.EVALUATION_CONTRACT_VERSION;
    }

    // ---- Spam detection — task-aware ----

    /**
     * Deterministic check for clearly invalid answers.
     * Task-aware: Q51/Q52 allows short answers, so length alone does not
     * disqualify.
     * Only short-circuits when answer is empty/whitespace-only, or contains no
     * Hangul at all.
     */
    static boolean isDefinitelyInvalid(String answer, WritingRuleEngine.RuleAnalysis ruleAnalysis) {
        if (answer == null || answer.trim().isEmpty()) {
            return true;
        }
        String trimmed = Normalizer.normalize(
                answer.trim(), Normalizer.Form.NFC);
        // No Hangul characters at all — definitely not a Korean writing answer
        return trimmed.codePoints()
                .noneMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
    }

    // ---- Provider call ----

    private JsonNode callPass(String passName,
            String systemPrompt,
            Map<String, Object> userPayload,
            AiImageEvidence imageEvidence,
            Map<String, Object> responseFormat) throws Exception {
        try {
            Map<String, Object> jsonSchema = nestedMap(
                    responseFormat,
                    "json_schema");
            Map<String, Object> schema = nestedMap(
                    jsonSchema,
                    "schema");
            List<PracticeStructuredGenerationRequest.ImageEvidence> images =
                    imageEvidence == null
                            ? List.of()
                            : List.of(new PracticeStructuredGenerationRequest.ImageEvidence(
                                    "QUESTION_IMAGE",
                                    imageEvidence.sha256(),
                                    imageEvidence.dataUrl(),
                                    "high"));
            PracticeStructuredGenerationRequest request =
                    new PracticeStructuredGenerationRequest(
                            PracticeAiPurpose.PRACTICE_WRITING_EVALUATION,
                            "writing-evaluation-" + passName,
                            PracticeAiCapability.STRICT_STRUCTURED_TEXT_VISION,
                            new PracticeAiAuthoritySnapshot(
                                    cacheSchemaVersion(),
                                    WritingPromptRules.PROMPT_VERSION,
                                    "TASK_NATIVE_WRITING",
                                    WritingPromptRules.RUBRIC_VERSION,
                                    WritingAssessmentPolicyBundle.identity()),
                            PracticeModelCapabilityProfile.openAiAssessmentV1(),
                            systemPrompt,
                            "",
                            userPayload,
                            String.valueOf(jsonSchema.get("name")),
                            schema,
                            images,
                            4096,
                            "");
            return structuredGeneration.generate(request).output();
        } catch (PracticeAiContractException exception) {
            if ("EVALUATION_INTERRUPTED".equals(exception.category())) {
                throw new EvaluationInterruptedException(exception);
            }
            throw new ProviderContractException(
                    exception.category(),
                    exception);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static final class EvaluationInterruptedException
            extends RuntimeException {
        private EvaluationInterruptedException(Throwable cause) {
            super("Writing evaluation was interrupted.", cause);
        }
    }

    // ---- Payload ----

    private Map<String, Object> userPayloadObject(String prompt,
            String learnerAnswer,
            WritingRuleEngine.RuleAnalysis ruleAnalysis,
            boolean isReEvaluation,
            AiImageEvidence imageEvidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skill_type", "WRITING");
        payload.put("platform", "KSH Korean Study Hub");
        payload.put("level", "TOPIK");
        payload.put("policy_bundle_id", WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID);
        payload.put("policy_bundle_components", WritingAssessmentPolicyBundle.components());
        payload.put("prompt", prompt);
        String learnerAnswerNfc = Normalizer.normalize(
                learnerAnswer == null ? "" : learnerAnswer,
                Normalizer.Form.NFC);
        payload.put("learner_answer", learnerAnswerNfc);
        payload.put("learner_answer_source", Map.of(
                "sourceRole", WritingEvidenceLedgerVerifier.SOURCE_ROLE,
                "normalization",
                WritingEvidenceLedgerVerifier.SOURCE_NORMALIZATION,
                "sourceHash",
                WritingEvidenceLedgerVerifier.sha256(learnerAnswerNfc),
                "offsetUnit", "UTF16_CODE_UNIT"));
        payload.put("task_type", ruleAnalysis.taskType());
        payload.put("character_count", ruleAnalysis.characterCount());
        payload.put("char_count_warning", ruleAnalysis.charCountWarning());
        payload.put("rule_violations", ruleAnalysis.ruleViolations());
        payload.put("audio_evidence_available", false);
        payload.put("question_image", imageEvidence == null
                ? Map.of("available", false)
                : Map.of(
                        "available", true,
                        "asset_id", imageEvidence.assetId(),
                        "mime_type", imageEvidence.mimeType(),
                        "sha256", imageEvidence.sha256(),
                        "size_bytes", imageEvidence.sizeBytes()));
        payload.put("is_re_evaluation", isReEvaluation);
        payload.put("audit_mode", isReEvaluation);
        payload.put("allowed_rubric", Map.of(
                "scoring_criteria", scoringCriteria(ruleAnalysis.taskType()),
                "finding_criteria", allowedRubric(ruleAnalysis.taskType())));
        payload.put("task_requirements",
                taskRequirements(ruleAnalysis.taskType()));
        payload.put("output_contract", Map.of(
                "ledgerContractVersion",
                WritingEvidenceLedgerVerifier.CONTRACT_VERSION,
                "scoreAnchorVersion", WritingScoreAnchorPolicy.VERSION,
                "taskRequirementVersion",
                WritingTaskRequirementPolicy.VERSION));
        return payload;
    }

    private boolean providerAvailable() {
        return structuredGeneration.identity(
                PracticeAiPurpose.PRACTICE_WRITING_EVALUATION).available();
    }

    private String evaluatorModel() {
        return structuredGeneration.identity(
                PracticeAiPurpose.PRACTICE_WRITING_EVALUATION).model();
    }

    private String transportIdentity() {
        PracticeStructuredGenerationPort.ProviderIdentity identity =
                structuredGeneration.identity(
                        PracticeAiPurpose.PRACTICE_WRITING_EVALUATION);
        return identity.provider()
                + ":"
                + identity.providerProfileCode()
                + ":binding-revision="
                + identity.bindingRevision()
                + ":profile-revision="
                + identity.providerProfileRevision()
                + ":"
                + identity.capabilityProfile().profileVersion();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(
            Map<String, Object> source,
            String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new ProviderContractException(
                    "INVALID_INTERNAL_RESPONSE_SCHEMA");
        }
        return (Map<String, Object>) map;
    }

    // ---- Rubric info ----

    static List<Map<String, Object>> allowedRubric(String taskType) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WritingRubricCriterion criterion : WritingRubricCriterion.activeForTask(taskType)) {
            if (!WritingDiagnosticContract.ledgerEligible(criterion)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("criterionId", criterion.id());
            row.put("vietnameseLabel", criterion.vietnameseLabel());
            row.put("koreanLabel", criterion.koreanLabel());
            row.put("polarity", criterion.polarity().name());
            row.put("category", WritingDiagnosticContract.categoryCode(criterion));
            row.put("allowedSubtypes",
                    WritingDiagnosticContract.allowedSubtypes(criterion));
            row.put("exactScoringCriterionId",
                    WritingDiagnosticContract.expectedParentCriterionId(
                            criterion, taskType));
            row.put("allowedScoringCriterionIds",
                    WritingDiagnosticContract.allowedParentCriterionIds(
                            criterion, taskType));
            row.put("evidenceScopes",
                    criterion.evidenceScopes().stream().map(Enum::name).sorted().toList());
            row.put("rule", criterion.rule());
            rows.add(java.util.Collections.unmodifiableMap(row));
        }
        return rows;
    }

    static List<Map<String, Object>> scoringCriteria(String taskType) {
        return WritingPromptRules.scoringCriteriaForTask(taskType).stream()
                .map(row -> {
                    WritingScoringCriterion criterion =
                            new WritingScoringCriterion(
                                    row.criterionId(),
                                    row.displayName(),
                                    row.maxScore(),
                                    row.order());
                    return Map.<String, Object>of(
                            "criterionId", row.criterionId(),
                            "displayName", row.displayName(),
                            "maxScore", row.maxScore(),
                            "order", row.order(),
                            "scoreAnchors",
                            WritingScoreAnchorPolicy.anchors(criterion).stream()
                                    .map(anchor -> Map.of(
                                            "score", anchor.score(),
                                            "labelVi", anchor.labelVi(),
                                            "descriptionVi",
                                            anchor.descriptionVi()))
                                    .toList());
                })
                .toList();
    }

    static List<Map<String, Object>> taskRequirements(String taskType) {
        return WritingTaskRequirementPolicy.requirementsFor(taskType).stream()
                .map(row -> {
                    Map<String, Object> requirement = new LinkedHashMap<>();
                    requirement.put("requirementId", row.requirementId());
                    requirement.put("labelVi", row.labelVi());
                    requirement.put("scoringCriterionId",
                            row.scoringCriterionId());
                    requirement.put("required", row.required());
                    requirement.put("evidenceRequired",
                            row.evidenceRequired());
                    return java.util.Collections.unmodifiableMap(requirement);
                })
                .toList();
    }

    // ---- Response format / schema ----

    private Map<String, Object> unifiedResponseFormat(String taskType) {
        return responseFormat(
                "ksh_writing_unified",
                unifiedSchema(taskType));
    }

    private Map<String, Object> unifiedSchema(String taskType) {
        Map<String, Object> schema = baseObject(list(
                "schemaVersion", "promptVersion", "scoreAnchorVersion",
                "taskRequirementVersion",
                "rubricScores", "taskCoverage", "evidenceLedger",
                "findings", "upgradedAnswer"));
        schema.put("properties", prop(
                "schemaVersion", enumSchema(
                        WritingPromptRules.EVALUATION_SCHEMA_VERSION),
                "promptVersion", enumSchema(
                        WritingPromptRules.PROMPT_VERSION),
                "scoreAnchorVersion", enumSchema(
                        WritingScoreAnchorPolicy.VERSION),
                "taskRequirementVersion", enumSchema(
                        WritingTaskRequirementPolicy.VERSION),
                "rubricScores", arrayOf(rubricJudgmentSchema()),
                "taskCoverage", arrayOf(taskCoverageSchema()),
                "evidenceLedger", arrayOf(evidenceSchema()),
                "findings", arrayOf(findingSchema(taskType)),
                "upgradedAnswer", upgradedAnswerSchema()));
        return schema;
    }

    private Map<String, Object> rubricJudgmentSchema() {
        return objectSchema(
                list("criterionId", "score", "maxScore", "evidenceIds",
                        "findingIds", "requirementIds"),
                prop("criterionId", typed("string"),
                        "score", integerSchema(0),
                        "maxScore", integerSchema(0),
                        "evidenceIds", stringArray(),
                        "findingIds", stringArray(),
                        "requirementIds", stringArray()));
    }

    private Map<String, Object> taskCoverageSchema() {
        return objectSchema(
                list("requirementId", "status", "evidenceIds"),
                prop("requirementId", typed("string"),
                        "status", enumSchema(
                                "MET", "PARTIAL", "NOT_MET",
                                "NOT_APPLICABLE"),
                        "evidenceIds", stringArray()));
    }

    private Map<String, Object> evidenceSchema() {
        return objectSchema(
                list("evidenceId", "sourceRole", "exactText",
                        "startOffset", "endOffset", "occurrenceIndex",
                        "occurrenceCount", "normalization", "sourceHash"),
                prop("evidenceId", typed("string"),
                        "sourceRole", enumSchema(
                                WritingEvidenceLedgerVerifier.SOURCE_ROLE),
                        "exactText", typed("string"),
                        "startOffset", integerSchema(0),
                        "endOffset", integerSchema(1),
                        "occurrenceIndex", integerSchema(1),
                        "occurrenceCount", integerSchema(1),
                        "normalization", enumSchema(
                                WritingEvidenceLedgerVerifier
                                        .SOURCE_NORMALIZATION),
                        "sourceHash", typed("string")));
    }

    private Map<String, Object> findingSchema(String taskType) {
        List<WritingRubricCriterion> criteria =
                WritingRubricCriterion.activeForTask(taskType).stream()
                        .filter(WritingDiagnosticContract::ledgerEligible)
                        .toList();
        List<Map<String, Object>> shapes = new ArrayList<>();
        for (String category : criteria.stream()
                .map(WritingDiagnosticContract::categoryCode)
                .distinct()
                .toList()) {
            List<WritingRubricCriterion> categoryCriteria = criteria.stream()
                    .filter(criterion -> category.equals(
                            WritingDiagnosticContract
                                    .categoryCode(criterion)))
                    .toList();
            List<WritingRubricCriterion> strengths = categoryCriteria.stream()
                    .filter(criterion -> criterion.polarity()
                            == WritingRubricCriterion.Polarity.STRENGTH)
                    .toList();
            addFindingShape(
                    shapes,
                    strengths.stream().filter(criterion ->
                            criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.TEXT_SPAN)
                                    && !criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.WHOLE_ANSWER))
                            .toList(),
                    taskType, "STRENGTH", new String[] {"KEEP"},
                    1, 1, enumSchema(""));
            addFindingShape(
                    shapes,
                    strengths.stream().filter(criterion ->
                            !criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.TEXT_SPAN)
                                    && criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.WHOLE_ANSWER))
                            .toList(),
                    taskType, "STRENGTH", new String[] {"KEEP"},
                    0, 0, enumSchema(""));
            addFindingShape(
                    shapes,
                    strengths.stream().filter(criterion ->
                            criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.TEXT_SPAN)
                                    && criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.WHOLE_ANSWER))
                            .toList(),
                    taskType, "STRENGTH", new String[] {"KEEP"},
                    0, 1, enumSchema(""));

            List<WritingRubricCriterion> improvements =
                    categoryCriteria.stream()
                            .filter(criterion -> criterion.polarity()
                                    == WritingRubricCriterion.Polarity
                                    .NEEDS_IMPROVEMENT)
                            .toList();
            addFindingShape(
                    shapes,
                    improvements.stream().filter(criterion ->
                            criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.WHOLE_ANSWER))
                            .toList(),
                    taskType, "IMPROVEMENT", new String[] {"MISSING"},
                    0, 0, enumSchema(""));
            List<WritingRubricCriterion> textImprovements =
                    improvements.stream().filter(criterion ->
                            criterion.supports(
                                    WritingRubricCriterion.EvidenceScope.TEXT_SPAN))
                            .toList();
            addFindingShape(
                    shapes, textImprovements, taskType,
                    "IMPROVEMENT", new String[] {"REPLACE"},
                    1, 1, nonBlankString());
            addFindingShape(
                    shapes, textImprovements, taskType,
                    "IMPROVEMENT", new String[] {"REDUNDANT"},
                    1, 1, enumSchema(""));
        }
        return Map.of("anyOf", List.copyOf(shapes));
    }

    private void addFindingShape(
            List<Map<String, Object>> shapes,
            List<WritingRubricCriterion> criteria,
            String taskType,
            String polarity,
            String[] operations,
            int minimumEvidence,
            int maximumEvidence,
            Map<String, Object> replacementSchema) {
        if (criteria.isEmpty()) {
            return;
        }
        String[] criterionIds = criteria.stream()
                .map(WritingRubricCriterion::id)
                .toArray(String[]::new);
        String[] subtypes = criteria.stream()
                .flatMap(criterion -> WritingDiagnosticContract
                        .allowedSubtypes(criterion).stream())
                .distinct()
                .toArray(String[]::new);
        String[] parentIds = criteria.stream()
                .flatMap(criterion -> WritingDiagnosticContract
                        .allowedParentCriterionIds(
                                criterion, taskType).stream())
                .distinct()
                .toArray(String[]::new);
        shapes.add(findingShape(
                criterionIds,
                subtypes,
                parentIds,
                WritingDiagnosticContract.categoryCode(criteria.get(0)),
                polarity,
                operations,
                minimumEvidence,
                maximumEvidence,
                replacementSchema));
    }

    private Map<String, Object> findingShape(
            String[] criterionIds,
            String[] subtypes,
            String[] parentIds,
            String category,
            String polarity,
            String[] operations,
            int minimumEvidence,
            int maximumEvidence,
            Map<String, Object> replacementSchema) {
        return objectSchema(
                list("findingId", "polarity", "operation",
                        "criterionId", "subtype", "scoringCriterionId",
                        "errorCategory", "evidenceIds", "requirementIds",
                        "explanationVi", "replacementKo",
                        "impact", "frequency", "confidence", "observability"),
                prop("findingId", typed("string"),
                        "polarity", enumSchema(polarity),
                        "operation", enumSchema(operations),
                        "criterionId", enumSchema(criterionIds),
                        "subtype", enumSchema(subtypes),
                        "scoringCriterionId", parentIds.length == 0
                                ? typed("null")
                                : enumSchema(parentIds),
                        "errorCategory", enumSchema(category),
                        "evidenceIds", boundedStringArray(
                                minimumEvidence, maximumEvidence),
                        "requirementIds", stringArray(),
                        "explanationVi", nonBlankString(),
                        "replacementKo", replacementSchema,
                        "impact", enumSchema(
                                "MINOR", "MODERATE", "MAJOR", "BLOCKING"),
                        "frequency", integerSchema(1),
                        "confidence", boundedNumberSchema(0.0, 1.0),
                        "observability", enumSchema(
                                "DIRECT", "INFERRED_BOUNDED")));
    }

    private Map<String, Object> upgradedAnswerSchema() {
        return objectSchema(
                list("content", "rewrites"),
                prop("content", typed("string"),
                        "rewrites", arrayOf(objectSchema(
                                list("findingIds", "evidenceId",
                                        "replacementKo", "reasonVi"),
                                prop("findingIds", stringArray(),
                                        "evidenceId", typed("string"),
                                        "replacementKo", typed("string"),
                                        "reasonVi", typed("string"))))));
    }

    // ---- Schema helpers ----

    private static Map<String, Object> responseFormat(String name, Map<String, Object> schema) {
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", name);
        jsonSchema.put("strict", Boolean.TRUE);
        jsonSchema.put("schema", schema);
        responseFormat.put("json_schema", jsonSchema);
        return responseFormat;
    }

    private static String extractOutputText(JsonNode root, String raw) {
        JsonNode choice = root.path("choices").path(0);
        if (choice.path("message").hasNonNull("content")) {
            return choice.path("message").path("content").asText();
        }
        if (root.hasNonNull("output_text")) {
            return root.path("output_text").asText();
        }
        JsonNode output = root.path("output");
        if (output.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode contentItem : content) {
                        if (contentItem.has("text")) {
                            builder.append(contentItem.path("text").asText());
                        }
                    }
                }
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }
        return raw;
    }

    private static List<Map<String, Object>> multimodalContent(
            String payload,
            AiImageEvidence imageEvidence
    ) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", payload));
        if (imageEvidence != null) {
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", imageEvidence.dataUrl(), "detail", "high")));
        }
        return content;
    }

    private static String cachePrompt(String prompt, AiImageEvidence imageEvidence) {
        if (imageEvidence == null) {
            return prompt;
        }
        return (prompt == null ? "" : prompt)
                + "\n[KSH_QUESTION_IMAGE_SHA256:" + imageEvidence.sha256() + "]";
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> typed(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        return node;
    }

    private static Map<String, Object> enumSchema(String... values) {
        Map<String, Object> node = typed("string");
        node.put("enum", List.of(values));
        return node;
    }

    private static Map<String, Object> nullableString() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", List.of("string", "null"));
        return node;
    }

    private static Map<String, Object> nonBlankString() {
        Map<String, Object> node = typed("string");
        node.put("minLength", 1);
        return node;
    }

    private static Map<String, Object> integerSchema(int minimum) {
        Map<String, Object> node = typed("integer");
        node.put("minimum", minimum);
        return node;
    }

    private static Map<String, Object> boundedNumberSchema(double minimum, double maximum) {
        Map<String, Object> node = typed("number");
        node.put("minimum", minimum);
        node.put("maximum", maximum);
        return node;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> itemSchema) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "array");
        node.put("items", itemSchema);
        return node;
    }

    private static Map<String, Object> stringArray() {
        return arrayOf(typed("string"));
    }

    private static Map<String, Object> boundedStringArray(
            int minimum,
            int maximum) {
        Map<String, Object> node = stringArray();
        node.put("minItems", minimum);
        node.put("maxItems", maximum);
        return node;
    }

    private static Map<String, Object> baseObject(List<String> required) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        node.put("additionalProperties", Boolean.FALSE);
        node.put("required", required);
        return node;
    }

    private static Map<String, Object> objectSchema(List<String> required, Map<String, Object> propertiesMap) {
        Map<String, Object> node = baseObject(required);
        node.put("properties", propertiesMap);
        return node;
    }

    private static Map<String, Object> prop(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static List<String> list(String... values) {
        List<String> list = new ArrayList<>();
        for (String value : values) {
            list.add(value);
        }
        return list;
    }

    private static final class ProviderContractException extends RuntimeException {
        private final String reason;

        ProviderContractException(String reason) {
            super(reason);
            this.reason = reason;
        }

        ProviderContractException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
