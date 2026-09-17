package com.ksh.features.practice.ai.writing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ksh.features.practice.ai.contract.PracticeAiResultCompleteness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WritingEvaluationNormalizer {
    private static final Logger log =
            LoggerFactory.getLogger(WritingEvaluationNormalizer.class);


    public static final String EVALUATION_ENGINE =
            "KSH_WRITING_EVALUATOR_V3";

    private final ObjectMapper objectMapper;
    private final WritingEvidenceLedgerVerifier ledgerVerifier;

    public WritingEvaluationNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.ledgerVerifier = new WritingEvidenceLedgerVerifier();
    }

    /**
     * Production normalizer for the unified one-call path.
     * Normalizer is the sole source of score, raw_score, raw_score_max.
     * AI provider does NOT return these fields.
     */
    public String normalize(String aiJson, String taskType, String learnerAnswer,
                            WritingRuleEngine.RuleAnalysis ruleAnalysis) {
        try {
            JsonNode root = objectMapper.readTree(aiJson);
            WritingEvidenceLedgerVerifier.VerifiedEnvelope verified =
                    ledgerVerifier.verify(root, taskType, learnerAnswer);
            String studentText = verified.learnerAnswerNfc();
            List<Map<String, Object>> rubricScores =
                    normalizedRubricScores(verified.rubrics(), taskType);
            List<Map<String, Object>> strengths =
                    normalizedFindings(verified, "STRENGTH");
            List<Map<String, Object>> needs =
                    normalizedFindings(verified, "IMPROVEMENT");
            double score = deriveScoreFromRubrics(rubricScores);
            double rawTopikScore = sumRubricScores(rubricScores);
            double rawTopikMax =
                    WritingScoringPolicy.rubricFor(taskType).totalMaxScore();
            List<Map<String, Object>> annotations =
                    verifiedAnnotations(verified);

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("score", score);
            normalized.put("overall_score", score);
            normalized.put("percentage", score);
            normalized.put("raw_score", rawTopikScore);
            normalized.put("raw_score_max", rawTopikMax);
            normalized.put("scoring_contract",
                    WritingScoringPolicy.SCORING_CONTRACT);
            normalized.put("policy_bundle_id",
                    WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID);
            normalized.put("ledger_contract_version",
                    WritingEvidenceLedgerVerifier.CONTRACT_VERSION);
            normalized.put("score_anchor_version",
                    WritingScoreAnchorPolicy.VERSION);
            normalized.put("task_requirement_version",
                    WritingTaskRequirementPolicy.VERSION);
            normalized.put("source_normalization",
                    WritingEvidenceLedgerVerifier.SOURCE_NORMALIZATION);
            normalized.put("source_hash", verified.sourceHash());
            normalized.put("task_type", taskType);
            String summary = derivedSummary(
                    verified, rawTopikScore, rawTopikMax);
            normalized.put("summary", summary);
            normalized.put("summary_vi", summary);
            normalized.put("rubric_scores", rubricScores);
            normalized.put("task_coverage",
                    normalizedCoverage(verified.coverage()));
            normalized.put("evidence_ledger",
                    normalizedEvidence(verified.evidence()));
            normalized.put("strengths", strengths);
            normalized.put("needs_improvement", needs);
            normalized.put("student_text", studentText);
            normalized.put("student_strengths_annotated", "");
            normalized.put("student_needs_annotated", "");
            normalized.put("annotations", annotations);
            normalized.put("upgraded_answer",
                    verified.upgrade().content());
            normalized.put("upgraded_answer_annotated", "");
            normalized.put("upgraded_annotations", List.of());
            normalized.put("corrected_version",
                    verified.upgrade().content());
            normalized.put("sample_answer", "");
            normalized.put("sentence_rewrites",
                    normalizedRewrites(verified.upgrade().rewrites()));
            normalized.put("engine", EVALUATION_ENGINE);
            putEvaluationMetadata(normalized,
                    "EVALUATED",
                    "PROVIDER",
                    "NONE",
                    false,
                    true);
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            log.warn(
                    "Writing provider envelope rejected: taskType={} reason={} exception={}",
                    taskType,
                    ex.getMessage(),
                    ex.getClass().getSimpleName());
            return contractFailure(
                    ex instanceof com.fasterxml.jackson.core.JsonProcessingException
                            ? "PROVIDER_MALFORMED_JSON"
                            : "PROVIDER_CONTRACT_INVALID",
                    taskType,
                    learnerAnswer);
        }
    }

    private static List<Map<String, Object>> normalizedRubricScores(
            List<WritingEvidenceLedgerVerifier.RubricJudgment> judgments,
            String taskType) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WritingEvidenceLedgerVerifier.RubricJudgment judgment
                : judgments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("criterionId", judgment.criterionId());
            row.put("name", WritingScoringPolicy.rubricFor(taskType)
                    .criteria().stream()
                    .filter(value -> value.criterionId()
                            .equals(judgment.criterionId()))
                    .map(WritingScoringCriterion::displayName)
                    .findFirst()
                    .orElseGet(() -> judgment.criterionId()));
            row.put("score", judgment.score());
            row.put("maxScore", judgment.maxScore());
            row.put("anchorLabelVi", judgment.anchorLabelVi());
            row.put("performanceLevel",
                    WritingScoreAnchorPolicy.requireAnchor(
                            WritingScoringPolicy.rubricFor(taskType)
                                    .criteria().stream()
                                    .filter(value -> value.criterionId()
                                            .equals(judgment.criterionId()))
                                    .findFirst()
                                    .orElseThrow(),
                            judgment.score())
                            .performanceLevel()
                            .name());
            row.put("feedback", judgment.anchorDescriptionVi());
            row.put("evidenceIds", judgment.evidenceIds());
            row.put("findingIds", judgment.findingIds());
            row.put("requirementIds", judgment.requirementIds());
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> normalizedFindings(
            WritingEvidenceLedgerVerifier.VerifiedEnvelope verified,
            String polarity) {
        Map<String, WritingEvidenceLedgerVerifier.Evidence> evidenceById =
                verified.evidence().stream().collect(
                        java.util.stream.Collectors.toMap(
                                WritingEvidenceLedgerVerifier.Evidence::evidenceId,
                                java.util.function.Function.identity()));
        List<Map<String, Object>> rows = new ArrayList<>();
        int ordinal = 0;
        for (WritingEvidenceLedgerVerifier.Finding finding
                : verified.findings()) {
            ordinal++;
            if (!polarity.equals(finding.polarity())) {
                continue;
            }
            WritingEvidenceLedgerVerifier.Evidence evidence =
                    finding.evidenceIds().isEmpty()
                            ? null
                            : evidenceById.get(finding.evidenceIds().get(0));
            WritingRubricCriterion criterion =
                    WritingRubricCriterion.parse(finding.criterionId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", ordinal);
            row.put("findingId", finding.findingId());
            row.put("criterionId", finding.criterionId());
            row.put("subtype", finding.subtype());
            row.put("scoringCriterionId", finding.scoringCriterionId());
            row.put("evidenceScope",
                    evidence == null ? "WHOLE_ANSWER" : "TEXT_SPAN");
            row.put("evidenceId",
                    evidence == null ? null : evidence.evidenceId());
            row.put("evidenceIds", finding.evidenceIds());
            row.put("requirementIds", finding.requirementIds());
            row.put("operation", finding.operation());
            row.put("errorCategory", finding.errorCategory());
            row.put("category", finding.errorCategory());
            row.put("subcategory", finding.subtype());
            row.put("vietnameseLabel",
                    criterion == null
                            ? finding.errorCategory()
                            : criterion.vietnameseLabel());
            row.put("koreanLabel",
                    criterion == null ? "" : criterion.koreanLabel());
            row.put("evidence",
                    evidence == null ? "" : evidence.exactText());
            row.put("startOffset",
                    evidence == null ? null : evidence.startOffset());
            row.put("endOffset",
                    evidence == null ? null : evidence.endOffset());
            row.put("occurrenceIndex",
                    evidence == null ? null : evidence.occurrenceIndex());
            row.put("occurrenceCount",
                    evidence == null ? null : evidence.occurrenceCount());
            row.put("sourceHash", verified.sourceHash());
            row.put("explanationVi", finding.explanationVi());
            row.put("correction", finding.replacementKo());
            row.put("severity", finding.impact());
            row.put("impact", finding.impact());
            row.put("frequency", finding.frequency());
            row.put("confidence", finding.confidence());
            row.put("observability", finding.observability());
            row.put("displayType",
                    evidence == null
                            ? "WHOLE_ANSWER"
                            : inferDisplayType(evidence.exactText()));
            row.put("uiLabel",
                    criterion == null
                            ? finding.errorCategory()
                            : criterion.vietnameseLabel());
            row.put("errorType", finding.errorCategory());
            row.put("whyItIsGood",
                    "STRENGTH".equals(polarity)
                            ? finding.explanationVi()
                            : "");
            row.put("topikTip", "");
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> verifiedAnnotations(
            WritingEvidenceLedgerVerifier.VerifiedEnvelope verified) {
        Map<String, WritingEvidenceLedgerVerifier.Evidence> evidenceById =
                verified.evidence().stream().collect(
                        java.util.stream.Collectors.toMap(
                                WritingEvidenceLedgerVerifier.Evidence::evidenceId,
                                java.util.function.Function.identity()));
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 1;
        for (WritingEvidenceLedgerVerifier.Finding finding
                : verified.findings()) {
            if (finding.evidenceIds().size() != 1) {
                continue;
            }
            WritingEvidenceLedgerVerifier.Evidence evidence =
                    evidenceById.get(finding.evidenceIds().get(0));
            if (evidence == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", finding.findingId());
            row.put("findingId", finding.findingId());
            row.put("evidenceId", evidence.evidenceId());
            row.put("kind",
                    "STRENGTH".equals(finding.polarity())
                            ? "strength"
                            : "need");
            row.put("criterionId", finding.criterionId());
            row.put("category", finding.errorCategory());
            row.put("subcategory", finding.subtype());
            row.put("evidence", evidence.exactText());
            row.put("start", evidence.startOffset());
            row.put("end", evidence.endOffset());
            row.put("startOffset", evidence.startOffset());
            row.put("endOffset", evidence.endOffset());
            row.put("occurrenceIndex", evidence.occurrenceIndex());
            row.put("occurrenceCount", evidence.occurrenceCount());
            row.put("sourceHash", evidence.sourceHash());
            row.put("explanationVi", finding.explanationVi());
            row.put("correction", finding.replacementKo());
            row.put("severity", finding.impact());
            row.put("operation", finding.operation());
            row.put("displayType", inferDisplayType(evidence.exactText()));
            row.put("index", index++);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> normalizedCoverage(
            List<WritingEvidenceLedgerVerifier.Coverage> coverage) {
        return coverage.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requirementId", row.requirementId());
            result.put("status", row.status());
            result.put("evidenceIds", row.evidenceIds());
            return result;
        }).toList();
    }

    private static List<Map<String, Object>> normalizedEvidence(
            List<WritingEvidenceLedgerVerifier.Evidence> evidence) {
        return evidence.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("evidenceId", row.evidenceId());
            result.put("sourceRole",
                    WritingEvidenceLedgerVerifier.SOURCE_ROLE);
            result.put("exactText", row.exactText());
            result.put("startOffset", row.startOffset());
            result.put("endOffset", row.endOffset());
            result.put("occurrenceIndex", row.occurrenceIndex());
            result.put("occurrenceCount", row.occurrenceCount());
            result.put("normalization", row.normalization());
            result.put("sourceHash", row.sourceHash());
            return result;
        }).toList();
    }

    private static List<Map<String, Object>> normalizedRewrites(
            List<WritingEvidenceLedgerVerifier.Rewrite> rewrites) {
        return rewrites.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("findingIds", row.findingIds());
            result.put("evidenceId", row.evidenceId());
            result.put("original", row.original());
            result.put("upgraded", row.replacementKo());
            result.put("reason", row.reasonVi());
            return result;
        }).toList();
    }

    private static String derivedSummary(
            WritingEvidenceLedgerVerifier.VerifiedEnvelope verified,
            double score,
            double maxScore) {
        long strengths = verified.findings().stream()
                .filter(row -> "STRENGTH".equals(row.polarity()))
                .count();
        long improvements = verified.findings().stream()
                .filter(row -> "IMPROVEMENT".equals(row.polarity()))
                .count();
        long met = verified.coverage().stream()
                .filter(row -> "MET".equals(row.status()))
                .count();
        return "Kết quả "
                + compact(score)
                + "/"
                + compact(maxScore)
                + "; "
                + met
                + "/"
                + verified.coverage().size()
                + " yêu cầu đã có bằng chứng; "
                + strengths
                + " điểm mạnh và "
                + improvements
                + " điểm cần cải thiện đã được đối chiếu.";
    }

    private static String compact(double value) {
        return value == Math.rint(value)
                ? Long.toString(Math.round(value))
                : Double.toString(value);
    }

    public boolean isCacheableAiResult(String normalizedJson) {
        try {
            JsonNode root = objectMapper.readTree(normalizedJson);
            return isTrustedProviderEvaluation(
                    root,
                    root == null ? null : root.path("task_type").asText(null),
                    false);
        } catch (Exception ex) {
            return false;
        }
    }

    public String sanitizeForCache(String normalizedJson) {
        try {
            JsonNode root = objectMapper.readTree(normalizedJson);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Writing cache payload must be a JSON object.");
            }
            ObjectNode sanitized = ((ObjectNode) root).deepCopy();
            sanitized.remove("student_text");
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Writing cache payload is not valid JSON.", ex);
        }
    }

    public String rehydrateCachedResult(String cachedJson, String learnerAnswer,
                                        String expectedTaskType) {
        try {
            JsonNode root = objectMapper.readTree(cachedJson);
            if (!isTrustedProviderEvaluation(root, expectedTaskType, true)) {
                throw new IllegalArgumentException(
                        "Writing cache payload does not match the current provider contract.");
            }

            String studentText = Normalizer.normalize(
                    learnerAnswer == null ? "" : learnerAnswer,
                    Normalizer.Form.NFC);
            ObjectNode hydrated = ((ObjectNode) root).deepCopy();
            if (!WritingEvidenceLedgerVerifier.sha256(studentText)
                    .equals(hydrated.path("source_hash").asText())) {
                throw new IllegalArgumentException(
                        "Writing cache payload source identity does not match.");
            }
            hydrated.put("student_text", studentText);
            hydrated.put("evaluation_origin_source", "PROVIDER");
            hydrated.put("evaluation_source", "CACHE");

            return objectMapper.writeValueAsString(hydrated);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Writing cached result is malformed.", ex);
        }
    }

    public String rehydrateCachedResult(String cachedJson, String learnerAnswer) {
        try {
            JsonNode root = objectMapper.readTree(cachedJson);
            return rehydrateCachedResult(
                    cachedJson,
                    learnerAnswer,
                    root == null ? null : root.path("task_type").asText(null));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Writing cached result is malformed.", ex);
        }
    }

    private static boolean isTrustedProviderEvaluation(JsonNode root,
                                                       String expectedTaskType,
                                                       boolean requireSanitizedPayload) {
        if (root == null || !root.isObject()
                || expectedTaskType == null || expectedTaskType.isBlank()
                || !EVALUATION_ENGINE.equals(root.path("engine").asText())
                || !"EVALUATED".equals(root.path("evaluation_status").asText())
                || !"PROVIDER".equals(root.path("evaluation_source").asText())
                || !"NONE".equals(root.path("evaluation_reason").asText())
                || !root.path("score_available").asBoolean(false)
                || !WritingScoringPolicy.SCORING_CONTRACT.equals(
                        root.path("scoring_contract").asText())
                || !WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID.equals(
                        root.path("policy_bundle_id").asText())
                || !WritingEvidenceLedgerVerifier.CONTRACT_VERSION.equals(
                        root.path("ledger_contract_version").asText())
                || !WritingScoreAnchorPolicy.VERSION.equals(
                        root.path("score_anchor_version").asText())
                || !WritingTaskRequirementPolicy.VERSION.equals(
                        root.path("task_requirement_version").asText())
                || !WritingEvidenceLedgerVerifier.SOURCE_NORMALIZATION.equals(
                        root.path("source_normalization").asText())
                || !root.path("source_hash").isTextual()
                || root.path("source_hash").asText().length() != 64
                || !expectedTaskType.equals(root.path("task_type").asText())
                || !root.path("score").isNumber()
                || !root.path("overall_score").isNumber()
                || !root.path("percentage").isNumber()
                || !root.path("raw_score").isNumber()
                || !root.path("raw_score_max").isNumber()
                || (requireSanitizedPayload && root.has("student_text"))) {
            return false;
        }

        WritingScoringRubric expectedRubric = WritingScoringPolicy.rubricFor(expectedTaskType);
        JsonNode rubricScores = root.path("rubric_scores");
        if (!rubricScores.isArray() || rubricScores.size() != expectedRubric.criteria().size()) {
            return false;
        }

        Map<String, WritingScoringCriterion> expectedById = new java.util.HashMap<>();
        for (WritingScoringCriterion criterion : expectedRubric.criteria()) {
            expectedById.put(criterion.criterionId(), criterion);
        }

        java.util.Set<String> seenCriterionIds = new java.util.HashSet<>();
        double rubricScoreSum = 0.0;
        for (JsonNode rubricScore : rubricScores) {
            if (!rubricScore.isObject()) {
                return false;
            }
            String criterionId = rubricScore.path("criterionId").asText();
            WritingScoringCriterion criterion = expectedById.get(criterionId);
            if (criterion == null
                    || !seenCriterionIds.add(criterionId)
                    || !criterion.displayName().equals(rubricScore.path("name").asText())
                    || !rubricScore.path("maxScore").isNumber()
                    || Double.compare(
                            rubricScore.path("maxScore").asDouble(),
                            criterion.maxScore()) != 0
                    || !rubricScore.path("score").isNumber()) {
                return false;
            }
            double criterionScore = rubricScore.path("score").asDouble();
            if (!Double.isFinite(criterionScore)
                    || criterionScore < 0.0
                    || criterionScore > criterion.maxScore()) {
                return false;
            }
            rubricScoreSum += criterionScore;
        }
        if (seenCriterionIds.size() != expectedById.size()) {
            return false;
        }

        double score = root.path("score").asDouble();
        double overallScore = root.path("overall_score").asDouble();
        double percentage = root.path("percentage").asDouble();
        double rawScore = root.path("raw_score").asDouble();
        double rawScoreMax = root.path("raw_score_max").asDouble();
        double expectedRawScoreMax = expectedRubric.totalMaxScore();
        double expectedRawScore = Math.round(rubricScoreSum * 100.0) / 100.0;
        double expectedPercentage = Math.round(
                rubricScoreSum / expectedRawScoreMax * 10_000.0) / 100.0;
        return Double.isFinite(score)
                && Double.isFinite(overallScore)
                && Double.isFinite(percentage)
                && Double.isFinite(rawScore)
                && Double.isFinite(rawScoreMax)
                && Double.compare(rawScoreMax, expectedRawScoreMax) == 0
                && sameScoreValue(rawScore, expectedRawScore)
                && sameScoreValue(score, expectedPercentage)
                && sameScoreValue(overallScore, expectedPercentage)
                && sameScoreValue(percentage, expectedPercentage)
                && hasTrustedFindings(
                        root.path("strengths"),
                        WritingRubricCriterion.Polarity.STRENGTH,
                        expectedTaskType)
                && hasTrustedFindings(
                        root.path("needs_improvement"),
                        WritingRubricCriterion.Polarity.NEEDS_IMPROVEMENT,
                        expectedTaskType)
                && root.path("task_coverage").isArray()
                && root.path("task_coverage").size()
                == WritingTaskRequirementPolicy.requirementsFor(
                        expectedTaskType).size()
                && root.path("evidence_ledger").isArray()
                && root.path("annotations").isArray();
    }

    private static boolean hasTrustedFindings(
            JsonNode findings,
            WritingRubricCriterion.Polarity polarity,
            String taskType
    ) {
        if (!findings.isArray()) {
            return false;
        }
        for (JsonNode finding : findings) {
            WritingRubricCriterion criterion = WritingRubricCriterion.parse(
                    finding.path("criterionId").asText(null));
            WritingRubricCriterion.EvidenceScope scope = parseEvidenceScope(
                    finding.path("evidenceScope").asText(null));
            if (criterion == null
                    || criterion.polarity() != polarity
                    || !criterion.activeForProvider()
                    || !criterion.appliesTo(taskType)
                    || scope == null
                    || !criterion.supports(scope)
                    || scope == WritingRubricCriterion.EvidenceScope.TASK_METADATA
                    || !WritingDiagnosticContract.validProviderMetadata(
                            finding, criterion, taskType, scope)) {
                return false;
            }
            String evidence = finding.path("evidence").asText("");
            if ((scope == WritingRubricCriterion.EvidenceScope.TEXT_SPAN
                    && evidence.isBlank())
                    || (scope == WritingRubricCriterion.EvidenceScope.WHOLE_ANSWER
                    && !evidence.isEmpty())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameScoreValue(double actual, double expected) {
        return Math.abs(actual - expected) < 0.000_000_1;
    }

    /**
     * Deterministic spam/empty response. 0 provider calls.
     */
    public String spamResponse(String taskType, String learnerAnswer) {
        try {
            String effectiveTaskType = taskType == null ? "GENERAL" : taskType;
            double score = 0.0;
            double rawScore = 0.0;
            WritingScoringRubric scoringRubric = WritingScoringPolicy.rubricFor(effectiveTaskType);
            double rawMax = scoringRubric.totalMaxScore();

            List<Map<String, Object>> rubricScores = new ArrayList<>();
            for (WritingScoringCriterion criterion : scoringRubric.criteria()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("criterionId", criterion.criterionId());
                row.put("name", criterion.displayName());
                row.put("score", 0.0);
                row.put("maxScore", criterion.maxScore());
                WritingScoreAnchorPolicy.ScoreAnchor anchor =
                        WritingScoreAnchorPolicy.requireAnchor(
                                criterion, 0);
                row.put("anchorLabelVi", anchor.labelVi());
                row.put("performanceLevel",
                        anchor.performanceLevel().name());
                row.put("feedback", anchor.descriptionVi());
                row.put("evidenceIds", List.of());
                row.put("findingIds", List.of());
                row.put("requirementIds",
                        WritingTaskRequirementPolicy
                                .requirementsFor(effectiveTaskType)
                                .stream()
                                .filter(requirement ->
                                        criterion.criterionId().equals(
                                                requirement
                                                        .scoringCriterionId()))
                                .map(WritingTaskRequirementPolicy
                                        .Requirement::requirementId)
                                .toList());
                rubricScores.add(row);
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("score", score);
            normalized.put("overall_score", score);
            normalized.put("percentage", score);
            normalized.put("raw_score", rawScore);
            normalized.put("raw_score_max", rawMax);
            normalized.put("scoring_contract",
                    WritingScoringPolicy.SCORING_CONTRACT);
            normalized.put("policy_bundle_id",
                    WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID);
            normalized.put("ledger_contract_version",
                    WritingEvidenceLedgerVerifier.CONTRACT_VERSION);
            normalized.put("score_anchor_version",
                    WritingScoreAnchorPolicy.VERSION);
            normalized.put("task_requirement_version",
                    WritingTaskRequirementPolicy.VERSION);
            normalized.put("source_normalization",
                    WritingEvidenceLedgerVerifier.SOURCE_NORMALIZATION);
            normalized.put("source_hash",
                    WritingEvidenceLedgerVerifier.sha256(
                            Normalizer.normalize(
                                    learnerAnswer == null
                                            ? "" : learnerAnswer,
                                    Normalizer.Form.NFC)));
            normalized.put("task_type", effectiveTaskType);
            String invalidSummary = "[INVALID_LEARNER_RESPONSE] Bài làm bỏ trống hoặc chưa có đủ dữ liệu tiếng Hàn để chấm.";
            normalized.put("summary", invalidSummary);
            normalized.put("summary_vi", invalidSummary);
            normalized.put("rubric_scores", rubricScores);
            normalized.put("task_coverage",
                    WritingTaskRequirementPolicy
                            .requirementsFor(effectiveTaskType)
                            .stream()
                            .map(requirement -> Map.of(
                                    "requirementId",
                                    requirement.requirementId(),
                                    "status",
                                    "NOT_APPLICABLE",
                                    "evidenceIds",
                                    List.of()))
                            .toList());
            normalized.put("evidence_ledger", List.of());
            normalized.put("strengths", List.of());
            normalized.put("needs_improvement", List.of());
            normalized.put("student_text", learnerAnswer == null ? "" : learnerAnswer);
            normalized.put("student_strengths_annotated", "");
            normalized.put("student_needs_annotated", "");
            normalized.put("annotations", List.of());
            normalized.put("upgraded_answer", "");
            normalized.put("upgraded_answer_annotated", "");
            normalized.put("upgraded_annotations", List.of());
            normalized.put("corrected_version", "");
            normalized.put("sample_answer", "");
            normalized.put("sentence_rewrites", List.of());
            normalized.put("engine", EVALUATION_ENGINE);
            putEvaluationMetadata(normalized,
                    "INVALID_LEARNER_RESPONSE",
                    "BACKEND_RULE",
                    invalidLearnerReason(learnerAnswer),
                    false,
                    true);
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            return fallback(
                    "[INVALID_LEARNER_RESPONSE] Bài làm không hợp lệ.",
                    taskType);
        }
    }

    public String fallback(String reason) {
        return fallback(reason, "GENERAL");
    }

    public String fallback(String reason, String taskType) {
        return availabilityResult(
                "EVALUATION_UNAVAILABLE",
                "SYSTEM",
                "PROVIDER_UNEXPECTED_ERROR",
                true,
                reason,
                taskType,
                "");
    }

    public String providerUnavailable(String reason,
                                      String taskType,
                                      String learnerAnswer,
                                      boolean retryable) {
        return availabilityResult(
                "EVALUATION_UNAVAILABLE",
                "PROVIDER",
                reason,
                retryable,
                "Chưa có đánh giá AI khả dụng — vui lòng chấm lại.",
                taskType,
                learnerAnswer);
    }

    public String contractFailure(String reason, String taskType, String learnerAnswer) {
        return availabilityResult(
                "EVALUATION_CONTRACT_FAILED",
                "PROVIDER",
                reason,
                true,
                "Phản hồi AI không đúng định dạng chấm điểm — vui lòng chấm lại.",
                taskType,
                learnerAnswer);
    }

    private String availabilityResult(String status,
                                      String source,
                                      String reason,
                                      boolean retryable,
                                      String message,
                                      String taskType,
                                      String learnerAnswer) {
        try {
            String effectiveTaskType = taskType == null ? "GENERAL" : taskType;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("task_type", effectiveTaskType);
            normalized.put("policy_bundle_id",
                    WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID);
            normalized.put("summary", message);
            normalized.put("summary_vi", message);
            normalized.put("rubric_scores", List.of());
            normalized.put("strengths", List.of());
            normalized.put("needs_improvement", List.of());
            normalized.put("student_text", learnerAnswer == null ? "" : learnerAnswer);
            normalized.put("student_strengths_annotated", "");
            normalized.put("student_needs_annotated", "");
            normalized.put("annotations", List.of());
            normalized.put("upgraded_answer", "");
            normalized.put("upgraded_answer_annotated", "");
            normalized.put("upgraded_annotations", List.of());
            normalized.put("corrected_version", "");
            normalized.put("sample_answer", "");
            normalized.put("sentence_rewrites", List.of());
            normalized.put("engine", "KSH_WRITING_EVALUATOR_STATUS");
            putEvaluationMetadata(normalized, status, source, reason, retryable, false);
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            return "{\"policy_bundle_id\":\"KSH_WRITING_POLICY_BUNDLE_V3\",\"evaluation_status\":\"EVALUATION_UNAVAILABLE\",\"evaluation_source\":\"SYSTEM\",\"evaluation_reason\":\"PROVIDER_UNEXPECTED_ERROR\",\"evaluation_retryable\":true,\"score_available\":false,\"result_completeness\":{\"version\":\"practice-ai-result-completeness-v1\",\"status\":\"UNAVAILABLE\",\"reason_code\":\"PROVIDER_UNEXPECTED_ERROR\",\"rejected_item_count\":0},\"summary_vi\":\"Chưa có đánh giá AI khả dụng.\"}";
        }
    }

    // ---- Scoring ----

    /** Derives the percentage from the authoritative task-native maxima. */
    static double deriveScoreFromRubrics(List<Map<String, Object>> rubricScores) {
        if (rubricScores == null || rubricScores.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        double max = 0;
        int count = 0;
        for (Map<String, Object> row : rubricScores) {
            Object scoreObj = row.get("score");
            if (scoreObj instanceof Number n) {
                sum += n.doubleValue();
                Object maxObj = row.get("maxScore");
                if (maxObj instanceof Number m) max += m.doubleValue();
                count++;
            }
        }
        if (count == 0) return 0.0;
        if (max > 0) return Math.round(sum / max * 10000.0) / 100.0;
        return 0.0;
    }

    private static double sumRubricScores(List<Map<String, Object>> rubricScores) {
        double sum = 0.0;
        for (Map<String, Object> row : rubricScores) {
            Object score = row.get("score");
            if (score instanceof Number number) {
                sum += number.doubleValue();
            }
        }
        return Math.round(sum * 100.0) / 100.0;
    }

    private static String inferDisplayType(String evidence) {
        if (evidence == null) return "PHRASE";
        int len = evidence.length();
        if (len <= 8) return "WORD";
        if (evidence.contains(".") || evidence.contains("?") || evidence.contains("!") || evidence.contains("。")) return "SENTENCE";
        if (len <= 30) return "PHRASE";
        return "SENTENCE";
    }

    private static WritingRubricCriterion.EvidenceScope parseEvidenceScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return WritingRubricCriterion.EvidenceScope.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ---- Helpers ----

    private static void putEvaluationMetadata(Map<String, Object> target,
                                              String status,
                                              String source,
                                              String reason,
                                              boolean retryable,
                                              boolean scoreAvailable) {
        target.put("evaluation_status", status);
        target.put("evaluation_source", source);
        target.put("evaluation_reason", reason);
        target.put("evaluation_retryable", retryable);
        target.put("score_available", scoreAvailable);
        target.put(PracticeAiResultCompleteness.FIELD,
                scoreAvailable
                        ? PracticeAiResultCompleteness.complete().toMap()
                        : PracticeAiResultCompleteness.unavailable(
                                reason, 0).toMap());
    }

    private static String invalidLearnerReason(String learnerAnswer) {
        if (learnerAnswer == null || learnerAnswer.trim().isEmpty()) {
            return "BLANK_ANSWER";
        }
        String trimmed = Normalizer.normalize(
                learnerAnswer.trim(), Normalizer.Form.NFC);
        boolean hasHangul = trimmed.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
        if (!hasHangul) {
            return "NO_HANGUL";
        }
        return "INVALID_LEARNER_RESPONSE";
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null ? fallback : value;
    }
}
