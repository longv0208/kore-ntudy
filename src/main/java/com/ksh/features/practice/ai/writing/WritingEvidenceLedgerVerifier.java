package com.ksh.features.practice.ai.writing;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict referential verifier for the current Writing provider envelope.
 *
 * <p>Offsets are provider-supplied UTF-16 indexes and are only verified, never
 * inferred from an evidence string. Invalid, ambiguous, overlapping or
 * contradictory envelopes fail closed before score availability.</p>
 */
public final class WritingEvidenceLedgerVerifier {

    public static final String CONTRACT_VERSION =
            "writing-evidence-ledger-v2";
    public static final String SOURCE_NORMALIZATION = "NFC";
    public static final String SOURCE_ROLE = "LEARNER_ANSWER";

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "promptVersion", "scoreAnchorVersion",
            "taskRequirementVersion",
            "rubricScores",
            "taskCoverage",
            "evidenceLedger",
            "findings",
            "upgradedAnswer");
    private static final Set<String> COVERAGE_FIELDS = Set.of(
            "requirementId", "status", "evidenceIds");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "evidenceId", "sourceRole", "exactText",
            "startOffset", "endOffset", "occurrenceIndex",
            "occurrenceCount", "normalization", "sourceHash");
    private static final Set<String> FINDING_FIELDS = Set.of(
            "findingId", "polarity", "operation", "criterionId",
            "subtype", "scoringCriterionId", "errorCategory",
            "evidenceIds", "requirementIds", "explanationVi",
            "replacementKo", "impact", "frequency",
            "confidence", "observability");
    private static final Set<String> RUBRIC_FIELDS = Set.of(
            "criterionId", "score", "maxScore",
            "evidenceIds", "findingIds", "requirementIds");
    private static final Set<String> UPGRADE_FIELDS =
            Set.of("content", "rewrites");
    private static final Set<String> REWRITE_FIELDS = Set.of(
            "findingIds", "evidenceId", "replacementKo", "reasonVi");

    private static final Set<String> COVERAGE_STATUSES =
            Set.of("MET", "PARTIAL", "NOT_MET", "NOT_APPLICABLE");
    private static final Set<String> POLARITIES =
            Set.of("STRENGTH", "IMPROVEMENT");
    private static final Set<String> OPERATIONS =
            Set.of("KEEP", "MISSING", "REPLACE", "REDUNDANT");

    public VerifiedEnvelope verify(
            JsonNode root,
            String taskType,
            String learnerAnswer) {
        String source = Normalizer.normalize(
                learnerAnswer == null ? "" : learnerAnswer,
                Normalizer.Form.NFC);
        requireExactFields(root, ROOT_FIELDS, "Writing root");
        if (!WritingPromptRules.EVALUATION_SCHEMA_VERSION.equals(
                text(root, "schemaVersion"))
                || !WritingPromptRules.PROMPT_VERSION.equals(
                text(root, "promptVersion"))
                || !WritingScoreAnchorPolicy.VERSION.equals(
                text(root, "scoreAnchorVersion"))
                || !WritingTaskRequirementPolicy.VERSION.equals(
                text(root, "taskRequirementVersion"))) {
            throw invalid("Writing response contract version is stale");
        }
        String sourceHash = sha256(source);

        Map<String, Evidence> evidence = evidence(
                array(root, "evidenceLedger"),
                source,
                sourceHash);
        Map<String, WritingTaskRequirementPolicy.Requirement> requirements =
                new LinkedHashMap<>();
        for (WritingTaskRequirementPolicy.Requirement requirement
                : WritingTaskRequirementPolicy.requirementsFor(taskType)) {
            requirements.put(requirement.requirementId(), requirement);
        }
        List<Coverage> coverage = coverage(
                array(root, "taskCoverage"),
                requirements,
                evidence.keySet(),
                source);
        Map<String, Finding> findings = findings(
                array(root, "findings"),
                taskType,
                evidence,
                requirements.keySet());
        List<RubricJudgment> rubrics = rubrics(
                array(root, "rubricScores"),
                taskType,
                evidence.keySet(),
                findings,
                coverage,
                requirements);
        Upgrade upgrade = upgrade(
                object(root, "upgradedAnswer"),
                evidence,
                findings);
        requireOneToOnePositionedFindings(findings.values());
        return new VerifiedEnvelope(
                source,
                sourceHash,
                List.copyOf(evidence.values()),
                coverage,
                List.copyOf(findings.values()),
                rubrics,
                upgrade);
    }

    private static Map<String, Evidence> evidence(
            JsonNode rows,
            String source,
            String sourceHash) {
        Map<String, Evidence> result = new LinkedHashMap<>();
        List<Range> ranges = new ArrayList<>();
        for (JsonNode node : rows) {
            requireExactFields(node, EVIDENCE_FIELDS, "Writing evidence");
            String evidenceId = identifier(node, "evidenceId");
            String exactText = nonBlankText(node, "exactText");
            int start = integer(node, "startOffset");
            int end = integer(node, "endOffset");
            int occurrenceIndex = positiveInteger(node, "occurrenceIndex");
            int occurrenceCount = positiveInteger(node, "occurrenceCount");
            if (!SOURCE_ROLE.equals(text(node, "sourceRole"))
                    || !SOURCE_NORMALIZATION.equals(text(node, "normalization"))
                    || !sourceHash.equals(text(node, "sourceHash"))
                    || start < 0
                    || end <= start
                    || end > source.length()
                    || !source.startsWith(exactText, start)
                    || end != start + exactText.length()) {
                throw invalid("Writing evidence is not an exact UTF-16 source span");
            }
            List<Integer> occurrences = occurrences(source, exactText);
            if (occurrences.size() != occurrenceCount
                    || occurrenceIndex > occurrences.size()
                    || occurrences.get(occurrenceIndex - 1) != start) {
                throw invalid(
                        "Writing evidence occurrence identity is invalid");
            }
            if (result.putIfAbsent(evidenceId, new Evidence(
                    evidenceId,
                    exactText,
                    start,
                    end,
                    occurrenceIndex,
                    occurrenceCount,
                    SOURCE_NORMALIZATION,
                    sourceHash)) != null) {
                throw invalid("Duplicate Writing evidence ID");
            }
            ranges.add(new Range(start, end, evidenceId));
        }
        ranges.sort(Comparator.comparingInt(Range::start)
                .thenComparingInt(Range::end));
        for (int index = 1; index < ranges.size(); index++) {
            if (ranges.get(index).start() < ranges.get(index - 1).end()) {
                throw invalid(
                        "Overlapping Writing evidence spans are not authoritative");
            }
        }
        return result;
    }

    private static List<Coverage> coverage(
            JsonNode rows,
            Map<String, WritingTaskRequirementPolicy.Requirement> requirements,
            Set<String> evidenceIds,
            String source) {
        Map<String, Coverage> result = new LinkedHashMap<>();
        for (JsonNode node : rows) {
            requireExactFields(node, COVERAGE_FIELDS, "Writing task coverage");
            String requirementId = identifier(node, "requirementId");
            WritingTaskRequirementPolicy.Requirement requirement =
                    requirements.get(requirementId);
            String status = text(node, "status");
            List<String> refs = identifiers(node, "evidenceIds");
            requireKnown(refs, evidenceIds, "coverage evidence");
            if (requirement == null
                    || !COVERAGE_STATUSES.contains(status)
                    || ("MET".equals(status)
                    && requirement.evidenceRequired()
                    && refs.isEmpty())
                    || !deterministicCoverageMatches(
                    requirementId, status, source)
                    || result.putIfAbsent(requirementId, new Coverage(
                    requirementId, status, refs)) != null) {
                throw invalid("Writing task coverage is incomplete");
            }
        }
        if (!result.keySet().equals(requirements.keySet())) {
            throw invalid("Writing task coverage must contain every requirement");
        }
        return List.copyOf(result.values());
    }

    private static boolean deterministicCoverageMatches(
            String requirementId,
            String status,
            String source) {
        int length = source == null ? 0 : source.strip().length();
        return switch (requirementId) {
            case "Q53_LENGTH_200_300" ->
                    ("MET".equals(status)) == (length >= 200 && length <= 300);
            case "Q54_LENGTH_600_700" ->
                    ("MET".equals(status)) == (length >= 600 && length <= 700);
            default -> true;
        };
    }

    private static Map<String, Finding> findings(
            JsonNode rows,
            String taskType,
            Map<String, Evidence> evidence,
            Set<String> requirementIds) {
        Map<String, Finding> result = new LinkedHashMap<>();
        Set<String> findingEvidenceIds = new HashSet<>();
        for (JsonNode node : rows) {
            requireExactFields(node, FINDING_FIELDS, "Writing finding");
            String findingId = identifier(node, "findingId");
            String polarity = text(node, "polarity");
            String operation = text(node, "operation");
            WritingRubricCriterion criterion = WritingRubricCriterion.parse(
                    text(node, "criterionId"));
            String scoringCriterionId = nullableText(
                    node.get("scoringCriterionId"));
            String subtype = nonBlankText(node, "subtype");
            String errorCategory = nonBlankText(node, "errorCategory");
            List<String> evidenceRefs = identifiers(node, "evidenceIds");
            List<String> requirementRefs = identifiers(node, "requirementIds");
            WritingRubricCriterion.EvidenceScope evidenceScope =
                    evidenceRefs.isEmpty()
                            ? WritingRubricCriterion.EvidenceScope.WHOLE_ANSWER
                            : WritingRubricCriterion.EvidenceScope.TEXT_SPAN;
            requireKnown(evidenceRefs, evidence.keySet(), "finding evidence");
            requireKnown(requirementRefs, requirementIds, "finding requirement");
            if (!POLARITIES.contains(polarity)
                    || !OPERATIONS.contains(operation)
                    || criterion == null
                    || !criterion.activeForProvider()
                    || !WritingDiagnosticContract.ledgerEligible(criterion)
                    || !criterion.appliesTo(taskType)) {
                throw invalid("Writing finding criterion is outside the strict registry");
            }
            if (!criterion.supports(evidenceScope)) {
                throw invalid("Writing finding evidence scope is not supported");
            }
            if (!WritingDiagnosticContract.allowedSubtypes(criterion)
                    .contains(subtype)) {
                throw invalid("Writing finding subtype is outside the strict registry");
            }
            if (!WritingDiagnosticContract.categoryCode(criterion)
                    .equals(errorCategory)) {
                throw invalid("Writing finding category does not match its criterion");
            }
            if (!java.util.Objects.equals(
                    WritingDiagnosticContract.expectedParentCriterionId(
                            criterion, taskType, requirementRefs),
                    scoringCriterionId)) {
                throw invalid("Writing finding scoring criterion is inconsistent");
            }
            if (!validOperation(polarity, operation, evidenceRefs)) {
                throw invalid("Writing finding operation is inconsistent");
            }
            if (!validFindingMetadata(node)) {
                throw invalid("Writing finding metadata is invalid");
            }
            if (result.containsKey(findingId)) {
                throw invalid("Duplicate Writing finding ID");
            }
            for (String evidenceRef : evidenceRefs) {
                if (!findingEvidenceIds.add(evidenceRef)) {
                    throw invalid(
                            "One Writing evidence span cannot own multiple findings");
                }
            }
            Finding finding = new Finding(
                    findingId,
                    polarity,
                    operation,
                    criterion.id(),
                    subtype,
                    scoringCriterionId,
                    errorCategory,
                    evidenceRefs,
                    requirementRefs,
                    nonBlankText(node, "explanationVi"),
                    text(node, "replacementKo"),
                    text(node, "impact"),
                    positiveInteger(node, "frequency"),
                    number(node, "confidence"),
                    text(node, "observability"));
            result.put(findingId, finding);
        }
        return result;
    }

    private static boolean validFindingMetadata(JsonNode node) {
        String polarity = text(node, "polarity");
        String operation = text(node, "operation");
        String replacement = text(node, "replacementKo");
        double confidence = number(node, "confidence");
        return Set.of("MINOR", "MODERATE", "MAJOR", "BLOCKING")
                .contains(text(node, "impact"))
                && confidence >= 0.0 && confidence <= 1.0
                && Set.of("DIRECT", "INFERRED_BOUNDED")
                .contains(text(node, "observability"))
                && ("STRENGTH".equals(polarity)
                ? "KEEP".equals(operation) && replacement.isEmpty()
                : switch (operation) {
                    case "MISSING", "REDUNDANT" -> replacement.isEmpty();
                    case "REPLACE" -> !replacement.isBlank();
                    default -> false;
                });
    }

    private static boolean validOperation(
            String polarity,
            String operation,
            List<String> evidenceRefs) {
        if ("STRENGTH".equals(polarity)) {
            return "KEEP".equals(operation) && evidenceRefs.size() <= 1;
        }
        if ("MISSING".equals(operation)) {
            return evidenceRefs.isEmpty();
        }
        return evidenceRefs.size() == 1;
    }

    private static List<RubricJudgment> rubrics(
            JsonNode rows,
            String taskType,
            Set<String> evidenceIds,
            Map<String, Finding> findings,
            List<Coverage> coverage,
            Map<String, WritingTaskRequirementPolicy.Requirement> requirements) {
        WritingScoringRubric rubric = WritingScoringPolicy.rubricFor(taskType);
        Map<String, WritingScoringCriterion> expected = new LinkedHashMap<>();
        for (WritingScoringCriterion criterion : rubric.criteria()) {
            expected.put(criterion.criterionId(), criterion);
        }
        Map<String, RubricJudgment> result = new LinkedHashMap<>();
        for (JsonNode node : rows) {
            requireExactFields(node, RUBRIC_FIELDS, "Writing rubric judgment");
            String criterionId = identifier(node, "criterionId");
            WritingScoringCriterion criterion = expected.get(criterionId);
            int score = integer(node, "score");
            int maxScore = integer(node, "maxScore");
            List<String> evidenceRefs = identifiers(node, "evidenceIds");
            List<String> findingRefs = identifiers(node, "findingIds");
            List<String> requirementRefs = identifiers(
                    node, "requirementIds");
            requireKnown(evidenceRefs, evidenceIds, "rubric evidence");
            requireKnown(findingRefs, findings.keySet(), "rubric finding");
            requireKnown(requirementRefs, requirements.keySet(),
                    "rubric requirement");
            if (criterion == null
                    || maxScore != criterion.maxScore()
                    || result.containsKey(criterionId)) {
                throw invalid("Writing rubric identity is invalid");
            }
            WritingScoreAnchorPolicy.ScoreAnchor anchor =
                    WritingScoreAnchorPolicy.requireAnchor(criterion, score);
            Set<String> ownedFindings = new LinkedHashSet<>();
            for (Finding finding : findings.values()) {
                if (criterionId.equals(finding.scoringCriterionId())) {
                    ownedFindings.add(finding.findingId());
                }
            }
            Set<String> ownedRequirements = new LinkedHashSet<>();
            for (WritingTaskRequirementPolicy.Requirement requirement
                    : requirements.values()) {
                if (criterionId.equals(requirement.scoringCriterionId())) {
                    ownedRequirements.add(requirement.requirementId());
                }
            }
            Set<String> ownedEvidence = new LinkedHashSet<>();
            for (Finding finding : findings.values()) {
                if (ownedFindings.contains(finding.findingId())) {
                    ownedEvidence.addAll(finding.evidenceIds());
                }
            }
            for (Coverage row : coverage) {
                WritingTaskRequirementPolicy.Requirement requirement =
                        requirements.get(row.requirementId());
                if (requirement != null
                        && criterionId.equals(
                                requirement.scoringCriterionId())) {
                    ownedEvidence.addAll(row.evidenceIds());
                }
            }
            if (contradictsAnchor(
                    criterionId,
                    score,
                    maxScore,
                    findings.values(),
                    coverage,
                    requirements)) {
                String anchorType = score == maxScore
                        ? "MAXIMUM"
                        : score == 0 ? "ZERO" : "INTERMEDIATE";
                throw invalid(
                        "Writing rubric anchor is contradicted: "
                                + anchorType);
            }
            result.put(criterionId, new RubricJudgment(
                    criterionId,
                    score,
                    maxScore,
                    anchor.labelVi(),
                    anchor.descriptionVi(),
                    List.copyOf(ownedEvidence),
                    List.copyOf(ownedFindings),
                    List.copyOf(ownedRequirements)));
        }
        if (!result.keySet().equals(expected.keySet())) {
            throw invalid("Writing rubric coverage is incomplete");
        }
        return List.copyOf(result.values());
    }

    private static boolean contradictsMaximum(
            String criterionId,
            java.util.Collection<Finding> findings,
            List<Coverage> coverage,
            Map<String, WritingTaskRequirementPolicy.Requirement> requirements) {
        boolean confirmedImprovement = findings.stream().anyMatch(finding ->
                "IMPROVEMENT".equals(finding.polarity())
                        && criterionId.equals(finding.scoringCriterionId()));
        boolean uncoveredRequirement = coverage.stream().anyMatch(row -> {
            WritingTaskRequirementPolicy.Requirement requirement =
                    requirements.get(row.requirementId());
            return requirement != null
                    && requirement.required()
                    && criterionId.equals(requirement.scoringCriterionId())
                    && !"MET".equals(row.status());
        });
        return confirmedImprovement || uncoveredRequirement;
    }

    private static boolean contradictsAnchor(
            String criterionId,
            int score,
            int maxScore,
            java.util.Collection<Finding> findings,
            List<Coverage> coverage,
            Map<String, WritingTaskRequirementPolicy.Requirement> requirements) {
        boolean hasImprovement = findings.stream().anyMatch(finding ->
                "IMPROVEMENT".equals(finding.polarity())
                        && criterionId.equals(finding.scoringCriterionId()));
        boolean hasUnmetRequirement = coverage.stream().anyMatch(row -> {
            WritingTaskRequirementPolicy.Requirement requirement =
                    requirements.get(row.requirementId());
            return requirement != null
                    && requirement.required()
                    && criterionId.equals(requirement.scoringCriterionId())
                    && !"MET".equals(row.status());
        });
        if (score == maxScore) {
            return contradictsMaximum(
                    criterionId, findings, coverage, requirements);
        }
        if (score == 0) {
            return false;
        }
        return !hasImprovement && !hasUnmetRequirement;
    }

    private static Upgrade upgrade(
            JsonNode node,
            Map<String, Evidence> evidence,
            Map<String, Finding> findings) {
        requireExactFields(node, UPGRADE_FIELDS, "Writing upgrade");
        String content = text(node, "content");
        List<Rewrite> rewrites = new ArrayList<>();
        for (JsonNode rewrite : array(node, "rewrites")) {
            requireExactFields(rewrite, REWRITE_FIELDS, "Writing rewrite");
            String evidenceId = identifier(rewrite, "evidenceId");
            Evidence source = evidence.get(evidenceId);
            List<String> findingIds = identifiers(rewrite, "findingIds");
            requireKnown(findingIds, findings.keySet(), "rewrite finding");
            String replacement = nonBlankText(rewrite, "replacementKo");
            String reason = nonBlankText(rewrite, "reasonVi");
            if (source == null || findingIds.isEmpty()
                    || findingIds.stream().anyMatch(id -> {
                Finding finding = findings.get(id);
                return finding == null
                        || !"IMPROVEMENT".equals(finding.polarity())
                        || !finding.evidenceIds().contains(evidenceId);
            })) {
                throw invalid(
                        "Writing rewrite is not linked to an exact negative finding");
            }
            rewrites.add(new Rewrite(
                    findingIds,
                    evidenceId,
                    source.exactText(),
                    replacement,
                    reason));
        }
        String verifiedContent = rewrites.isEmpty() ? "" : content;
        return new Upgrade(
                verifiedContent,
                List.copyOf(rewrites));
    }

    private static void requireOneToOnePositionedFindings(
            java.util.Collection<Finding> findings) {
        Set<String> evidenceIds = new HashSet<>();
        for (Finding finding : findings) {
            for (String evidenceId : finding.evidenceIds()) {
                if (!evidenceIds.add(evidenceId)) {
                    throw invalid(
                            "Writing finding/span ownership is not one-to-one");
                }
            }
        }
    }

    private static List<Integer> occurrences(
            String source,
            String exactText) {
        List<Integer> offsets = new ArrayList<>();
        int limit = source.length() - exactText.length();
        for (int offset = 0; offset <= limit; offset++) {
            if (source.startsWith(exactText, offset)) {
                offsets.add(offset);
            }
        }
        return offsets;
    }

    public static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requireExactFields(
            JsonNode node,
            Set<String> expected,
            String label) {
        if (node == null || !node.isObject()) {
            throw invalid(label + " must be an object");
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(label + " contains missing or unknown fields");
        }
    }

    private static JsonNode array(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) {
            throw invalid(field + " must be an array");
        }
        return value;
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw invalid(field + " must be an object");
        }
        return value;
    }

    private static String identifier(JsonNode node, String field) {
        String value = nonBlankText(node, field);
        if (!value.matches("[A-Za-z0-9._:-]{1,96}")) {
            throw invalid(field + " is not a stable identifier");
        }
        return value;
    }

    private static List<String> identifiers(
            JsonNode node,
            String field) {
        JsonNode values = array(node, field);
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid(field + " must contain identifiers");
            }
            String id = value.asText();
            if (!id.matches("[A-Za-z0-9._:-]{1,96}")
                    || !unique.add(id)) {
                throw invalid(field + " contains invalid or duplicate IDs");
            }
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static void requireKnown(
            List<String> refs,
            Set<String> authority,
            String label) {
        if (!authority.containsAll(refs)) {
            throw invalid(label + " references a foreign ID");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.asText();
    }

    private static String nonBlankText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value;
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw invalid("Nullable text field is invalid");
        }
        return node.asText();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        return value.intValue();
    }

    private static int positiveInteger(JsonNode node, String field) {
        int value = integer(node, field);
        if (value < 1) {
            throw invalid(field + " must be positive");
        }
        return value;
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()
                || !Double.isFinite(value.asDouble())) {
            throw invalid(field + " must be a finite number");
        }
        return value.asDouble();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record Range(int start, int end, String evidenceId) {
    }

    public record Evidence(
            String evidenceId,
            String exactText,
            int startOffset,
            int endOffset,
            int occurrenceIndex,
            int occurrenceCount,
            String normalization,
            String sourceHash) {
    }

    public record Coverage(
            String requirementId,
            String status,
            List<String> evidenceIds) {
    }

    public record Finding(
            String findingId,
            String polarity,
            String operation,
            String criterionId,
            String subtype,
            String scoringCriterionId,
            String errorCategory,
            List<String> evidenceIds,
            List<String> requirementIds,
            String explanationVi,
            String replacementKo,
            String impact,
            int frequency,
            double confidence,
            String observability) {
    }

    public record RubricJudgment(
            String criterionId,
            int score,
            int maxScore,
            String anchorLabelVi,
            String anchorDescriptionVi,
            List<String> evidenceIds,
            List<String> findingIds,
            List<String> requirementIds) {
    }

    public record Rewrite(
            List<String> findingIds,
            String evidenceId,
            String original,
            String replacementKo,
            String reasonVi) {
    }

    public record Upgrade(
            String content,
            List<Rewrite> rewrites) {
    }

    public record VerifiedEnvelope(
            String learnerAnswerNfc,
            String sourceHash,
            List<Evidence> evidence,
            List<Coverage> coverage,
            List<Finding> findings,
            List<RubricJudgment> rubrics,
            Upgrade upgrade) {
    }
}
