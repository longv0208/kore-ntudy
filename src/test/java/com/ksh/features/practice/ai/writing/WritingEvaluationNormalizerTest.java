package com.ksh.features.practice.ai.writing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.addEvidence;
import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.addFinding;
import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.applyRawScore;
import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.coverage;
import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.replaceIds;
import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.rubric;
import static com.ksh.features.practice.ai.writing.WritingContractTestFixtures.zeroEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WritingEvaluationNormalizerTest {

    private static final String Q53_ANSWER =
            "승용차 이용률은 45%에서 35%로 감소했고, 대중교통은 10%에서 5%로 줄었다. "
                    + "자전거는 20%에서 35%로 크게 증가했으며, 반면 도보는 25%로 같았다. "
                    + "이는 건강에 관심이 높아졌기 때문이라고 볼 수 있다.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WritingEvaluationNormalizer normalizer =
            new WritingEvaluationNormalizer(objectMapper);

    @Test
    void q53ProducesAnchoredScoreCoverageAndAtomicOneToOneLedger()
            throws Exception {
        ObjectNode provider = q53AtomicEnvelope();

        JsonNode root = normalize(provider, "Q53", Q53_ANSWER);

        assertThat(root.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(root.path("engine").asText())
                .isEqualTo("KSH_WRITING_EVALUATOR_V3");
        assertThat(root.path("raw_score").asInt()).isEqualTo(28);
        assertThat(root.path("raw_score_max").asInt()).isEqualTo(30);
        assertThat(root.path("task_coverage")).hasSize(6);
        assertThat(root.path("evidence_ledger")).hasSize(6);
        assertThat(root.path("annotations")).hasSize(6);
        assertThat(root.path("summary_vi").asText())
                .contains("5/6 yêu cầu", "5 điểm mạnh",
                        "1 điểm cần cải thiện");

        JsonNode improvement = root.path("needs_improvement").get(0);
        assertThat(improvement.path("findingId").asText())
                .isEqualTo("F_Q53_WALKING");
        assertThat(improvement.path("evidence").asText())
                .isEqualTo("도보는 25%로 같았다");
        assertThat(improvement.path("startOffset").asInt()).isEqualTo(76);
        assertThat(improvement.path("endOffset").asInt()).isEqualTo(88);
        assertThat(improvement.path("correction").asText())
                .isEqualTo("도보 이용률은 25%로 동일하게 유지되었다");
        assertThat(root.path("annotations").get(4).path("id").asText())
                .isEqualTo("F_Q53_WALKING");
        assertThat(root.path("sentence_rewrites").get(0)
                .path("findingIds").get(0).asText())
                .isEqualTo("F_Q53_WALKING");
    }

    @Test
    void exactQ53ReferencePhrasesRemainSeparateAtomicFindings()
            throws Exception {
        JsonNode root = normalize(
                q53AtomicEnvelope(), "Q53", Q53_ANSWER);

        assertThat(root.path("strengths"))
                .extracting(row -> row.path("evidence").asText())
                .containsExactly(
                        "45%에서 35%로 감소했고",
                        "10%에서 5%로 줄었다",
                        "20%에서 35%로 크게 증가했으며",
                        "반면",
                        "때문이라고 볼 수 있다");
    }

    @Test
    void rubricReferenceListsAreDerivedFromFindingsAndCoverage()
            throws Exception {
        JsonNode expected = normalize(q53AtomicEnvelope(), "Q53", Q53_ANSWER);
        ObjectNode tampered = q53AtomicEnvelope();
        for (JsonNode row : tampered.withArray("rubricScores")) {
            replaceIds((ObjectNode) row, "evidenceIds", "EV_CAR");
            replaceIds((ObjectNode) row, "findingIds", "F_CAR");
            replaceIds((ObjectNode) row, "requirementIds",
                    "Q53_DATA_2024");
        }

        JsonNode actual = normalize(tampered, "Q53", Q53_ANSWER);

        assertThat(actual.path("rubric_scores"))
                .isEqualTo(expected.path("rubric_scores"));
    }

    @Test
    void repeatedOccurrenceIsAcceptedOnlyWithExactProviderIdentity()
            throws Exception {
        String answer = "같았다. 같았다.";
        ObjectNode provider = zeroEnvelope(
                objectMapper, "Q53", answer);
        addEvidence(provider, "EV_SECOND", answer, "같았다", 5);
        addFinding(provider, "F_SECOND", "IMPROVEMENT", "REPLACE",
                "W_AWKWARD_UNNATURAL_EXPRESSIONS", "NATURALNESS",
                "W_LANGUAGE_EXPRESSION", "EV_SECOND", List.of(),
                "Lần xuất hiện thứ hai cần diễn đạt rõ hơn.",
                "동일하게 유지되었다", "MINOR");
        ObjectNode language = rubric(
                provider, "W_LANGUAGE_EXPRESSION");
        language.put("score", 8);
        replaceIds(language, "evidenceIds", "EV_SECOND");
        replaceIds(language, "findingIds", "F_SECOND");

        JsonNode accepted = normalize(provider, "Q53", answer);
        assertThat(accepted.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(accepted.path("annotations").get(0)
                .path("occurrenceIndex").asInt()).isEqualTo(2);

        provider.withArray("evidenceLedger").get(0)
                .path("occurrenceIndex");
        ((ObjectNode) provider.withArray("evidenceLedger").get(0))
                .put("occurrenceIndex", 1);
        assertContractFailed(normalize(provider, "Q53", answer));
    }

    @Test
    void exactOffsetsFailClosedInsteadOfGuessingEvidencePosition()
            throws Exception {
        ObjectNode provider = q53AtomicEnvelope();
        ((ObjectNode) provider.withArray("evidenceLedger").get(0))
                .put("startOffset", 10);

        assertContractFailed(normalize(provider, "Q53", Q53_ANSWER));
    }

    @Test
    void nonEmptyUpgradeWithoutRewritesNormalizesToEmptyUpgradeFields()
            throws Exception {
        ObjectNode provider = zeroEnvelope(
                objectMapper, "Q53", Q53_ANSWER);
        provider.with("upgradedAnswer").put(
                "content", "provider supplied rewrite without evidence");

        JsonNode normalized = normalize(provider, "Q53", Q53_ANSWER);

        assertThat(normalized.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(normalized.path("upgraded_answer").asText()).isEmpty();
        assertThat(normalized.path("corrected_version").asText()).isEmpty();
        assertThat(normalized.path("sentence_rewrites")).isEmpty();
    }

    @Test
    void maximumCriterionScoreRejectsConfirmedNegativeFinding()
            throws Exception {
        ObjectNode provider = q53AtomicEnvelope();
        rubric(provider, "W_LANGUAGE_EXPRESSION").put("score", 9);

        assertContractFailed(normalize(provider, "Q53", Q53_ANSWER));
    }

    @Test
    void clozePartialScoreRequiresOneAuthoritativeBlankRequirement()
            throws Exception {
        String answer = "문맥은 맞지만 어색합니다.";
        ObjectNode provider = zeroEnvelope(
                objectMapper, "Q51", answer);
        addEvidence(provider, "EV_Q51_BLANK_1", answer, "어색", 8);
        addFinding(
                provider,
                "F_Q51_BLANK_1_GRAMMAR",
                "IMPROVEMENT",
                "REPLACE",
                "W_CLOZE_GRAMMAR_COMPATIBILITY",
                "ENDINGS_CONJUGATION",
                "W_CLOZE_BLANK_1_GRAMMAR",
                "EV_Q51_BLANK_1",
                List.of("CLOZE_BLANK_1_CONTEXT"),
                "Ô thứ nhất dùng dạng kết thúc chưa tương thích.",
                "자연스럽습니다",
                "MODERATE");
        ObjectNode grammar = rubric(
                provider, "W_CLOZE_BLANK_1_GRAMMAR");
        grammar.put("score", 1);
        replaceIds(grammar, "evidenceIds", "EV_Q51_BLANK_1");
        replaceIds(
                grammar,
                "findingIds",
                "F_Q51_BLANK_1_GRAMMAR");

        JsonNode accepted = normalize(provider, "Q51", answer);
        assertThat(accepted.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(accepted.path("raw_score").asInt()).isEqualTo(1);
        assertThat(accepted.path("needs_improvement").get(0)
                .path("scoringCriterionId").asText())
                .isEqualTo("W_CLOZE_BLANK_1_GRAMMAR");

        ObjectNode noBlankAuthority = provider.deepCopy();
        replaceIds(
                (ObjectNode) noBlankAuthority.withArray("findings").get(0),
                "requirementIds");
        assertContractFailed(
                normalize(noBlankAuthority, "Q51", answer));

        ObjectNode ambiguousBlankAuthority = provider.deepCopy();
        replaceIds(
                (ObjectNode) ambiguousBlankAuthority.withArray("findings")
                        .get(0),
                "requirementIds",
                "CLOZE_BLANK_1_CONTEXT",
                "CLOZE_BLANK_2_CONTEXT");
        assertContractFailed(
                normalize(ambiguousBlankAuthority, "Q51", answer));
    }

    @Test
    void boundedWholeAnswerStrengthsSurviveTheProductionLedger()
            throws Exception {
        String answer = WritingContractTestFixtures
                .scoreBearingLearnerAnswer("Q54", 50);
        ObjectNode provider = zeroEnvelope(
                objectMapper, "Q54", answer);
        applyRawScore(provider, "Q54", answer, 50);
        addFinding(
                provider,
                "F_Q54_THESIS",
                "STRENGTH",
                "KEEP",
                "W_CLEAR_THESIS_OR_MAIN_IDEA",
                "THESIS_MAIN_IDEA",
                "W_CONTENT_TASK_ACHIEVEMENT",
                null,
                List.of("Q54_POSITION"),
                "Toàn bài duy trì một luận điểm rõ ràng.",
                "",
                "MODERATE");
        addFinding(
                provider,
                "F_Q54_LENGTH",
                "STRENGTH",
                "KEEP",
                "W_LENGTH_REQUIREMENT_MET",
                "TASK_LENGTH",
                null,
                null,
                List.of("Q54_LENGTH_600_700"),
                "Toàn bài đáp ứng dung lượng có thẩm quyền.",
                "",
                "MODERATE");
        replaceIds(
                rubric(provider, "W_CONTENT_TASK_ACHIEVEMENT"),
                "findingIds",
                "F_Q54_THESIS");

        JsonNode accepted = normalize(provider, "Q54", answer);

        assertThat(accepted.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(accepted.path("strengths"))
                .extracting(row -> row.path("criterionId").asText())
                .containsExactly(
                        "W_CLEAR_THESIS_OR_MAIN_IDEA",
                        "W_LENGTH_REQUIREMENT_MET");
        assertThat(accepted.path("strengths"))
                .allSatisfy(row -> {
                    assertThat(row.path("evidenceScope").asText())
                            .isEqualTo("WHOLE_ANSWER");
                    assertThat(row.path("evidence").asText()).isEmpty();
                    assertThat(row.path("startOffset").isNull()).isTrue();
                    assertThat(row.path("endOffset").isNull()).isTrue();
                });
    }

    @Test
    void missingOrUnknownRootFieldsFailClosed() throws Exception {
        ObjectNode missing = q53AtomicEnvelope();
        missing.remove("taskCoverage");
        assertContractFailed(normalize(missing, "Q53", Q53_ANSWER));

        ObjectNode extra = q53AtomicEnvelope();
        extra.put("summary", "Lời khen không có bằng chứng");
        assertContractFailed(normalize(extra, "Q53", Q53_ANSWER));
    }

    @Test
    void scoreAnchorsCoverEveryIntegerLevelForQ53AndQ54() {
        for (String taskType : List.of("Q53", "Q54")) {
            for (WritingScoringCriterion criterion
                    : WritingScoringPolicy.rubricFor(taskType).criteria()) {
                assertThat(WritingScoreAnchorPolicy.anchors(criterion))
                        .extracting(
                                WritingScoreAnchorPolicy.ScoreAnchor::score)
                        .containsExactlyElementsOf(
                                java.util.stream.IntStream.rangeClosed(
                                                0, criterion.maxScore())
                                        .boxed()
                                        .toList());
                assertThat(WritingScoreAnchorPolicy.anchors(criterion))
                        .extracting(anchor ->
                                anchor.performanceLevel().name())
                        .contains(
                                "LIMITED", "MODEST", "GOOD", "EXCELLENT");
            }
        }
    }

    @Test
    void validZeroAndIntermediateAnchorsRemainAccepted() throws Exception {
        JsonNode zero = normalize(
                zeroEnvelope(objectMapper, "Q53", Q53_ANSWER),
                "Q53",
                Q53_ANSWER);
        assertThat(zero.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(zero.path("raw_score").asInt()).isZero();
        assertThat(zero.path("score").asInt()).isZero();

        JsonNode intermediate = normalize(
                q53AtomicEnvelope(), "Q53", Q53_ANSWER);
        assertThat(intermediate.path("evaluation_status").asText())
                .isEqualTo("EVALUATED");
        assertThat(intermediate.path("rubric_scores"))
                .anySatisfy(row -> {
                    double score = row.path("score").asDouble();
                    double maxScore = row.path("maxScore").asDouble();
                    assertThat(score).isGreaterThan(0.0)
                            .isLessThan(maxScore);
                });
    }

    @Test
    void legacyFreeTextEnvelopeHasNoScoreAuthority() throws Exception {
        JsonNode root = objectMapper.readTree(normalizer.normalize(
                """
                {"summary":"legacy","rubric_scores":[
                  {"name":"Nội dung","score":9},
                  {"name":"Cấu trúc","score":9},
                  {"name":"Ngôn ngữ","score":9}
                ]}
                """,
                "Q53",
                "한국어 답안",
                null));

        assertContractFailed(root);
        assertThat(root.has("raw_score")).isFalse();
    }

    @Test
    void cacheRequiresExactBundleAndSourceHash() throws Exception {
        String normalized = normalizer.normalize(
                objectMapper.writeValueAsString(q53AtomicEnvelope()),
                "Q53",
                Q53_ANSWER,
                null);
        String cached = normalizer.sanitizeForCache(normalized);

        JsonNode hydrated = objectMapper.readTree(
                normalizer.rehydrateCachedResult(
                        cached, Q53_ANSWER, "Q53"));
        assertThat(hydrated.path("evaluation_source").asText())
                .isEqualTo("CACHE");

        assertThatThrownBy(() -> normalizer.rehydrateCachedResult(
                cached, "다른 답안입니다", "Q53"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deterministicInvalidAndUnavailableRemainProviderFreeTruth()
            throws Exception {
        JsonNode invalid = objectMapper.readTree(
                normalizer.spamResponse("Q54", ""));
        JsonNode unavailable = objectMapper.readTree(
                normalizer.providerUnavailable(
                        "MISSING_API_KEY", "Q54", "한국어", false));

        assertThat(invalid.path("raw_score").asInt()).isZero();
        assertThat(invalid.path("raw_score_max").asInt()).isEqualTo(50);
        assertThat(invalid.path("engine").asText())
                .isEqualTo("KSH_WRITING_EVALUATOR_V3");
        assertThat(invalid.path("ledger_contract_version").asText())
                .isEqualTo(WritingEvidenceLedgerVerifier.CONTRACT_VERSION);
        assertThat(invalid.path("score_anchor_version").asText())
                .isEqualTo(WritingScoreAnchorPolicy.VERSION);
        assertThat(invalid.path("task_requirement_version").asText())
                .isEqualTo(WritingTaskRequirementPolicy.VERSION);
        assertThat(invalid.path("source_normalization").asText())
                .isEqualTo(WritingEvidenceLedgerVerifier.SOURCE_NORMALIZATION);
        assertThat(invalid.path("source_hash").asText())
                .matches("[0-9a-f]{64}");
        invalid.path("rubric_scores").forEach(row ->
                assertThat(row.path("performanceLevel").asText())
                        .isEqualTo("LIMITED"));
        assertThat(invalid.path("evidence_ledger")).isEmpty();
        assertThat(invalid.path("task_coverage").isArray()).isTrue();
        assertThat(unavailable.path("score_available").asBoolean()).isFalse();
        assertThat(unavailable.has("raw_score")).isFalse();
    }

    @Test
    void deriveScoreUsesTaskNativeMaximumAndEmptyIsZero() {
        List<Map<String, Object>> rubrics = List.of(
                Map.of("score", 7.0, "maxScore", 12.0),
                Map.of("score", 8.0, "maxScore", 9.0),
                Map.of("score", 6.0, "maxScore", 9.0));

        assertThat(WritingEvaluationNormalizer
                .deriveScoreFromRubrics(rubrics)).isEqualTo(70.0);
        assertThat(WritingEvaluationNormalizer
                .deriveScoreFromRubrics(List.of())).isZero();
    }

    @Test
    void cacheabilityRejectsTamperedScoreAndWrongTaskType() throws Exception {
        String normalized = normalizer.normalize(
                objectMapper.writeValueAsString(q53AtomicEnvelope()),
                "Q53", Q53_ANSWER, null);
        ObjectNode tampered = (ObjectNode) objectMapper.readTree(normalized);
        tampered.put("raw_score", tampered.path("raw_score").asDouble() + 1.0);

        assertThat(normalizer.isCacheableAiResult(
                objectMapper.writeValueAsString(tampered))).isFalse();
        assertThat(normalizer.isCacheableAiResult(normalized)).isTrue();
        assertThatThrownBy(() -> normalizer.rehydrateCachedResult(
                normalizer.sanitizeForCache(normalized), Q53_ANSWER, "Q54"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cacheTrustRejectsStaleContractsAndTamperedFindingMetadata()
            throws Exception {
        String normalized = normalizer.normalize(
                objectMapper.writeValueAsString(q53AtomicEnvelope()),
                "Q53", Q53_ANSWER, null);
        List<Consumer<ObjectNode>> tamperings = List.of(
                node -> node.put("policy_bundle_id", "stale-policy"),
                node -> node.put("ledger_contract_version", "stale-ledger"),
                node -> node.put("scoring_contract", "stale-score-contract"),
                node -> ((ObjectNode) node.path("strengths").get(0))
                        .put("evidenceScope", "WHOLE_ANSWER"));

        for (Consumer<ObjectNode> tampering : tamperings) {
            ObjectNode tampered = (ObjectNode) objectMapper
                    .readTree(normalized);
            tampering.accept(tampered);
            String payload = objectMapper.writeValueAsString(tampered);

            assertThat(normalizer.isCacheableAiResult(payload)).isFalse();
            assertThatThrownBy(() -> normalizer.rehydrateCachedResult(
                    normalizer.sanitizeForCache(payload),
                    Q53_ANSWER,
                    "Q53"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void cachedPayloadWithStudentTextIsRejectedAndSanitizationRemovesIt() throws Exception {
        String normalized = normalizer.normalize(
                objectMapper.writeValueAsString(q53AtomicEnvelope()),
                "Q53", Q53_ANSWER, null);
        ObjectNode cachedWithStudentText = (ObjectNode) objectMapper.readTree(
                normalizer.sanitizeForCache(normalized));
        cachedWithStudentText.put("student_text", "tampered learner answer");

        assertThatThrownBy(() -> normalizer.rehydrateCachedResult(
                objectMapper.writeValueAsString(cachedWithStudentText),
                Q53_ANSWER, "Q53"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(normalizer.sanitizeForCache(normalized))
                .doesNotContain("student_text");
    }

    @Test
    void overlappingEvidenceSpansFailClosedBeforeScoreBecomesAvailable()
            throws Exception {
        ObjectNode provider = q53AtomicEnvelope();
        addEvidence(provider, "EV_OVERLAP", Q53_ANSWER,
                "45%에서 35%로 감소했고", 9);

        assertContractFailed(normalize(provider, "Q53", Q53_ANSWER));
    }

    @Test
    void oneEvidenceSpanCannotBeOwnedByTwoFindings() throws Exception {
        ObjectNode provider = q53AtomicEnvelope();
        ObjectNode transit = (ObjectNode) provider.withArray("findings").get(1);
        replaceIds(transit, "evidenceIds", "EV_CAR");

        assertContractFailed(normalize(provider, "Q53", Q53_ANSWER));
    }

    private ObjectNode q53AtomicEnvelope() {
        ObjectNode root = zeroEnvelope(
                objectMapper, "Q53", Q53_ANSWER);
        addEvidence(root, "EV_CAR", Q53_ANSWER,
                "45%에서 35%로 감소했고", 9);
        addEvidence(root, "EV_TRANSIT", Q53_ANSWER,
                "10%에서 5%로 줄었다", 32);
        addEvidence(root, "EV_BIKE", Q53_ANSWER,
                "20%에서 35%로 크게 증가했으며", 52);
        addEvidence(root, "EV_CONTRAST", Q53_ANSWER, "반면", 73);
        addEvidence(root, "EV_WALKING", Q53_ANSWER,
                "도보는 25%로 같았다", 76);
        addEvidence(root, "EV_CAUSE", Q53_ANSWER,
                "때문이라고 볼 수 있다", 106);

        addFinding(root, "F_CAR", "STRENGTH", "KEEP",
                "W_ACCURATE_DATA_DESCRIPTION", "DATA_ACCURACY",
                "W_CONTENT_TASK_ACHIEVEMENT", "EV_CAR",
                List.of("Q53_DATA_2024"),
                "Mô tả rõ xu hướng giảm của ô tô.", "", "MODERATE");
        addFinding(root, "F_TRANSIT", "STRENGTH", "KEEP",
                "W_ACCURATE_DATA_DESCRIPTION", "DATA_ACCURACY",
                "W_CONTENT_TASK_ACHIEVEMENT", "EV_TRANSIT",
                List.of("Q53_DATA_2026"),
                "Mô tả rõ xu hướng giảm của giao thông công cộng.",
                "", "MODERATE");
        addFinding(root, "F_BIKE", "STRENGTH", "KEEP",
                "W_ACCURATE_DATA_DESCRIPTION", "DATA_ACCURACY",
                "W_CONTENT_TASK_ACHIEVEMENT", "EV_BIKE",
                List.of("Q53_MAIN_CHANGES"),
                "Mô tả chính xác mức tăng của xe đạp.", "", "MODERATE");
        addFinding(root, "F_CONTRAST", "STRENGTH", "KEEP",
                "W_EFFECTIVE_TRANSITIONS", "TRANSITION_USE",
                "W_ORGANIZATION_COHERENCE", "EV_CONTRAST", List.of(),
                "Dùng dấu hiệu chuyển ý tương phản đúng chỗ.", "", "MINOR");
        addFinding(root, "F_Q53_WALKING", "IMPROVEMENT", "REPLACE",
                "W_AWKWARD_UNNATURAL_EXPRESSIONS", "NATURALNESS",
                "W_LANGUAGE_EXPRESSION", "EV_WALKING", List.of(),
                "Cụm này diễn đạt trạng thái không đổi chưa tự nhiên.",
                "도보 이용률은 25%로 동일하게 유지되었다", "MINOR");
        addFinding(root, "F_CAUSE", "STRENGTH", "KEEP",
                "W_NATURAL_KOREAN_EXPRESSIONS", "NATURALNESS",
                "W_LANGUAGE_EXPRESSION", "EV_CAUSE",
                List.of("Q53_PLAUSIBLE_CAUSE"),
                "Cấu trúc nêu nguyên nhân khả dĩ phù hợp.", "", "MODERATE");

        for (String requirement : List.of(
                "Q53_FOUR_TRANSPORT_MODES",
                "Q53_DATA_2024",
                "Q53_DATA_2026",
                "Q53_MAIN_CHANGES",
                "Q53_PLAUSIBLE_CAUSE")) {
            ObjectNode row = coverage(root, requirement);
            row.put("status", "MET");
            replaceIds(row, "evidenceIds",
                    switch (requirement) {
                        case "Q53_DATA_2024" -> "EV_CAR";
                        case "Q53_DATA_2026" -> "EV_TRANSIT";
                        case "Q53_MAIN_CHANGES" -> "EV_BIKE";
                        case "Q53_PLAUSIBLE_CAUSE" -> "EV_CAUSE";
                        default -> "EV_CAR";
                    });
        }

        ObjectNode content = rubric(
                root, "W_CONTENT_TASK_ACHIEVEMENT");
        content.put("score", 11);
        replaceIds(content, "evidenceIds",
                "EV_CAR", "EV_TRANSIT", "EV_BIKE", "EV_CAUSE");
        replaceIds(content, "findingIds",
                "F_CAR", "F_TRANSIT", "F_BIKE");

        ObjectNode organization = rubric(
                root, "W_ORGANIZATION_COHERENCE");
        organization.put("score", 9);
        replaceIds(organization, "evidenceIds", "EV_CONTRAST");
        replaceIds(organization, "findingIds", "F_CONTRAST");

        ObjectNode language = rubric(
                root, "W_LANGUAGE_EXPRESSION");
        language.put("score", 8);
        replaceIds(language, "evidenceIds",
                "EV_WALKING", "EV_CAUSE");
        replaceIds(language, "findingIds",
                "F_Q53_WALKING", "F_CAUSE");

        ObjectNode upgrade = (ObjectNode) root.path("upgradedAnswer");
        upgrade.put("content", Q53_ANSWER.replace(
                "도보는 25%로 같았다",
                "도보 이용률은 25%로 동일하게 유지되었다"));
        ObjectNode rewrite = upgrade.withArray("rewrites").addObject();
        replaceIds(rewrite, "findingIds", "F_Q53_WALKING");
        rewrite.put("evidenceId", "EV_WALKING");
        rewrite.put("replacementKo",
                "도보 이용률은 25%로 동일하게 유지되었다");
        rewrite.put("reasonVi",
                "Diễn đạt chính xác trạng thái tỷ lệ không đổi.");
        return root;
    }

    private JsonNode normalize(
            ObjectNode provider,
            String taskType,
            String answer) throws Exception {
        return objectMapper.readTree(normalizer.normalize(
                objectMapper.writeValueAsString(provider),
                taskType,
                answer,
                null));
    }

    private static void assertContractFailed(JsonNode root) {
        assertThat(root.path("evaluation_status").asText())
                .isEqualTo("EVALUATION_CONTRACT_FAILED");
        assertThat(root.path("score_available").asBoolean()).isFalse();
    }
}
