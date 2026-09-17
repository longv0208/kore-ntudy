package com.ksh.features.practice.ai.writing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.ai.OpenAiProperties;
import com.ksh.features.practice.ai.media.AiImageEvidence;
import com.ksh.features.practice.ai.metrics.PracticeAiMetrics;
import com.ksh.features.practice.ai.transport.PracticeStructuredGenerationPort;
import com.ksh.features.practice.ai.transport.PracticeStructuredGenerationResponse;
import com.ksh.features.practice.ai.transport.TestPracticeStructuredGenerationPort;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WritingEvaluationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WritingRuleEngine ruleEngine = new WritingRuleEngine();
    private final WritingEvaluationNormalizer normalizer = new WritingEvaluationNormalizer(objectMapper);

    private static final Long USER_ID = 42L;
    @Test
    void testEmptyInputIsDefinitelyInvalid() {
        var analysis = new WritingRuleEngine.RuleAnalysis("Q53", 0, "글자 수: 0자.", List.of());
        assertTrue(WritingEvaluationClient.isDefinitelyInvalid(null, analysis));
        assertTrue(WritingEvaluationClient.isDefinitelyInvalid("", analysis));
        assertTrue(WritingEvaluationClient.isDefinitelyInvalid("   ", analysis));
    }

    @Test
    void testNoHangulIsDefinitelyInvalid() {
        var analysis = new WritingRuleEngine.RuleAnalysis("Q53", 10, "글자 수: 10자.", List.of());
        assertTrue(WritingEvaluationClient.isDefinitelyInvalid("asdfasdf", analysis));
        assertTrue(WritingEvaluationClient.isDefinitelyInvalid("hello world 123", analysis));
    }

    @Test
    void deterministicBlankReturnsInvalidRawZeroWithoutProviderCacheOrMock() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        TestPracticeStructuredGenerationPort port =
                structuredPort("{}", new AtomicInteger());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, port
        );

        JsonNode root = objectMapper.readTree(client.evaluate(USER_ID, "Bai 53 viet", "   ", false, WritingTaskType.Q53));

        assertEquals("INVALID_LEARNER_RESPONSE", root.path("evaluation_status").asText());
        assertEquals("BACKEND_RULE", root.path("evaluation_source").asText());
        assertEquals("BLANK_ANSWER", root.path("evaluation_reason").asText());
        assertTrue(root.path("score_available").asBoolean(false));
        assertEquals(0.0, root.path("raw_score").asDouble());
        assertEquals(30.0, root.path("raw_score_max").asDouble());
        assertTrue(root.path("summary").asText().startsWith("[INVALID_LEARNER_RESPONSE]"));
        assertThat(port.calls()).isZero();
        verify(cacheService, never()).get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deterministicNoHangulReturnsInvalidRawZeroWithoutProviderCacheOrMock() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        TestPracticeStructuredGenerationPort port =
                structuredPort("{}", new AtomicInteger());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, port
        );

        JsonNode root = objectMapper.readTree(client.evaluate(USER_ID, "Bai 51 viet", "hello world 123", false, WritingTaskType.Q51));

        assertEquals("INVALID_LEARNER_RESPONSE", root.path("evaluation_status").asText());
        assertEquals("BACKEND_RULE", root.path("evaluation_source").asText());
        assertEquals("NO_HANGUL", root.path("evaluation_reason").asText());
        assertTrue(root.path("score_available").asBoolean(false));
        assertEquals(0.0, root.path("raw_score").asDouble());
        assertEquals(10.0, root.path("raw_score_max").asDouble());
        assertThat(port.calls()).isZero();
        verify(cacheService, never()).get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testValidKoreanNotInvalid() {
        var analysis = new WritingRuleEngine.RuleAnalysis("Q53", 10, "OK: 글자 수 250자.", List.of());
        assertFalse(WritingEvaluationClient.isDefinitelyInvalid("한국어를 공부합니다", analysis));
        assertFalse(WritingEvaluationClient.isDefinitelyInvalid(
                java.text.Normalizer.normalize(
                        "한국어를 공부합니다",
                        java.text.Normalizer.Form.NFD),
                analysis));
    }

    @Test
    void testQ51_52ShortAnswerNotInvalid() {
        var analysis = new WritingRuleEngine.RuleAnalysis("Q51_52", 3, "글자 수: 3자.", List.of());
        assertFalse(WritingEvaluationClient.isDefinitelyInvalid("있다", analysis));
    }

    @Test
    void testSameInputProducesSameCacheKey() {
        String key1 = WritingEvaluationCacheService.key("prompt", "answer", "Q53", "model", "v2.0", "v2.0", "v2.0");
        String key2 = WritingEvaluationCacheService.key("prompt", "answer", "Q53", "model", "v2.0", "v2.0", "v2.0");
        assertEquals(key1, key2);
    }

    @Test
    void testDifferentRubricVersionCacheMiss() {
        String key1 = WritingEvaluationCacheService.key("p", "a", "Q53", "m", "v2.0", "v1.0", "v2.0");
        String key2 = WritingEvaluationCacheService.key("p", "a", "Q53", "m", "v2.0", "v2.0", "v2.0");
        assertNotEquals(key1, key2);
    }

    @Test
    void exactQ51AndQ52IdentityCreatesDifferentCacheKeys() {
        String q51 = WritingEvaluationCacheService.key("same", "same", "Q51", "model", "v3.0", "v3.0", "v3.0");
        String q52 = WritingEvaluationCacheService.key("same", "same", "Q52", "model", "v3.0", "v3.0", "v3.0");
        assertNotEquals(q51, q52);
        assertEquals(WritingPromptRules.rubricNamesForTask("Q51"), WritingPromptRules.rubricNamesForTask("Q52"));
    }

    @Test
    void testUnifiedPromptContainsAllSections() {
        String prompt = WritingPromptRules.buildUnifiedPrompt("Q53", false);
        assertTrue(prompt.contains("RUBRIC SCORES"));
        assertTrue(prompt.contains("STRENGTHS & NEEDS"));
        assertTrue(prompt.contains(
                "Không tự trả về score tổng, total_score, raw_score"));
    }

    @Test
    void testUnifiedPromptAuditMode() {
        String promptNormal = WritingPromptRules.buildUnifiedPrompt("Q53", false);
        String promptAudit = WritingPromptRules.buildUnifiedPrompt("Q53", true);
        assertFalse(promptNormal.contains("AUDIT MODE"));
        assertTrue(promptAudit.contains("AUDIT MODE"));
    }

    @Test
    void testVersionConstantsExist() {
        assertNotNull(WritingPromptRules.PROMPT_VERSION);
        assertNotNull(WritingPromptRules.RUBRIC_VERSION);
        assertNotNull(WritingPromptRules.EVALUATION_SCHEMA_VERSION);
        assertEquals("v7.3", WritingPromptRules.PROMPT_VERSION);
        assertEquals("v5.2", WritingPromptRules.RUBRIC_VERSION);
        assertEquals("v6.1", WritingPromptRules.EVALUATION_SCHEMA_VERSION);
    }

    @Test
    void writingStructuredPortCarriesGovernedQuestionImage() {
        AiImageEvidence image = new AiImageEvidence(
                4L, "image/jpeg", "data:image/jpeg;base64,anBn", "image-sha", 3);
        TestPracticeStructuredGenerationPort port = structuredPort(
                aiResponse(),
                new AtomicInteger());
        com.ksh.features.practice.ai.media.AiQuestionImageResolver resolver =
                mock(com.ksh.features.practice.ai.media.AiQuestionImageResolver.class);
        when(resolver.resolve("image-ref", USER_ID))
                .thenReturn(Optional.of(image));
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"),
                objectMapper,
                normalizer,
                ruleEngine,
                new WritingTaskResolver(),
                mock(WritingEvaluationCacheService.class),
                resolver,
                PracticeAiMetrics.noop(),
                port);

        client.evaluate(
                USER_ID,
                "Bài 53 viết",
                "한국어를 공부합니다",
                false,
                WritingTaskType.Q53,
                "image-ref");

        assertThat(port.lastRequest().images()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.role()).isEqualTo("QUESTION_IMAGE");
                    assertThat(evidence.sha256()).isEqualTo("image-sha");
                    assertThat(evidence.dataUrl())
                            .isEqualTo("data:image/jpeg;base64,anBn");
                    assertThat(evidence.detail()).isEqualTo("high");
                });
    }

    @Test
    void providerAllowlistUsesExactTaskAndExcludesLegacyCriteria() {
        List<String> q51 = WritingEvaluationClient.allowedRubric("Q51").stream()
                .map(row -> (String) row.get("criterionId"))
                .toList();
        List<String> q52 = WritingEvaluationClient.allowedRubric("Q52").stream()
                .map(row -> (String) row.get("criterionId"))
                .toList();
        List<String> q53 = WritingEvaluationClient.allowedRubric("Q53").stream()
                .map(row -> (String) row.get("criterionId"))
                .toList();
        List<String> q54 = WritingEvaluationClient.allowedRubric("Q54").stream()
                .map(row -> (String) row.get("criterionId"))
                .toList();

        assertThat(q51)
                .hasSize(16)
                .containsExactlyInAnyOrder(
                        "W_ACCURATE_SPELLING_SPACING",
                        "W_FORMAL_REGISTER_CONSISTENCY",
                        "W_FORMAL_VOCABULARY_USAGE",
                        "W_NATURAL_KOREAN_EXPRESSIONS",
                        "W_CLOZE_CONTEXT_FIT",
                        "W_CONNECTIVE_ENDING_ACCURACY",
                        "W_SENTENCE_COMPLETION_NATURALNESS",
                        "W_CLOZE_GRAMMAR_COMPATIBILITY",
                        "W_CLOZE_REGISTER_MATCH",
                        "W_VOCABULARY_ERRORS",
                        "W_GRAMMAR_ERRORS",
                        "W_PARTICLE_ERRORS",
                        "W_AWKWARD_UNNATURAL_EXPRESSIONS",
                        "W_SENTENCE_STRUCTURE_ISSUES",
                        "W_REGISTER_CONSISTENCY_ISSUES",
                        "W_SPELLING_SPACING_ERRORS");
        assertThat(q52).containsExactlyElementsOf(q51);
        assertThat(q53)
                .hasSize(25)
                .contains(
                        "W_LENGTH_REQUIREMENT_MET",
                        "W_TASK_REQUIREMENT_COVERAGE",
                        "W_LOGICAL_ORGANIZATION",
                        "W_ACCURATE_DATA_DESCRIPTION",
                        "W_TASK_REQUIREMENT_MISSING",
                        "W_Q53_DATA_FLOW_ISSUES")
                .doesNotContain(
                        "W_CLEAR_THESIS_OR_MAIN_IDEA",
                        "W_RELEVANT_EXAMPLES_OR_REASONS",
                        "W_FABRICATED_OR_INACCURATE_DATA",
                        "W_WON_GO_JI");
        assertThat(q54)
                .hasSize(27)
                .contains(
                        "W_LENGTH_REQUIREMENT_MET",
                        "W_TASK_REQUIREMENT_COVERAGE",
                        "W_LOGICAL_ORGANIZATION",
                        "W_CLEAR_THESIS_OR_MAIN_IDEA",
                        "W_RELEVANT_EXAMPLES_OR_REASONS",
                        "W_INSUFFICIENT_IDEA_DEVELOPMENT",
                        "W_UNSUPPORTED_CLAIM",
                        "W_WEAK_PARAGRAPH_ORGANIZATION")
                .doesNotContain(
                        "W_ACCURATE_DATA_DESCRIPTION",
                        "W_Q53_DATA_FLOW_ISSUES",
                        "W_FABRICATED_OR_INACCURATE_DATA",
                        "W_WON_GO_JI");
        assertThat(WritingEvaluationClient.allowedRubric("Q53"))
                .allSatisfy(row -> assertThat(row)
                        .containsKeys(
                                "vietnameseLabel",
                                "koreanLabel",
                                "polarity",
                                "category",
                                "allowedSubtypes",
                                "exactScoringCriterionId",
                                "allowedScoringCriterionIds",
                                "evidenceScopes"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Q51", "Q52", "Q53", "Q54"})
    @SuppressWarnings("unchecked")
    void observableProviderRequestUsesTaskSpecificFindingSchema(
            String taskType) throws Exception {
        WritingEvaluationCacheService cacheService =
                mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        TestPracticeStructuredGenerationPort port =
                structuredPort(aiResponse(), new AtomicInteger());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer,
                ruleEngine, cacheService, port);

        String answer = "Q51".equals(taskType) || "Q52".equals(taskType)
                ? "있다"
                : "한국어를 공부합니다";
        client.evaluate(USER_ID, "Bài " + taskType, answer, false,
                WritingTaskType.valueOf(taskType));

        assertThat(port.lastRequest()).isNotNull();
        assertThat(port.lastRequest().responseSchemaName())
                .isEqualTo("ksh_writing_unified");
        assertThat(port.lastRequest().input())
                .containsEntry("task_type", taskType);

        Map<String, Object> properties =
                (Map<String, Object>) port.lastRequest().responseSchema()
                        .get("properties");
        Map<String, Object> findings =
                (Map<String, Object>) properties.get("findings");
        Map<String, Object> findingItems =
                (Map<String, Object>) findings.get("items");
        List<Map<String, Object>> shapes =
                (List<Map<String, Object>>) findingItems.get("anyOf");
        Set<String> expectedCriteria = WritingEvaluationClient
                .allowedRubric(taskType).stream()
                .map(row -> (String) row.get("criterionId"))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> actualCriteria = shapes.stream()
                .map(shape -> (Map<String, Object>) shape.get("properties"))
                .map(shapeProperties -> (Map<String, Object>)
                        shapeProperties.get("criterionId"))
                .flatMap(criterion -> ((List<String>) criterion.get("enum"))
                        .stream())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actualCriteria).containsExactlyInAnyOrderElementsOf(
                expectedCriteria);
        assertThat(shapes).allSatisfy(shape -> {
            Map<String, Object> shapeProperties =
                    (Map<String, Object>) shape.get("properties");
            Map<String, Object> criterion =
                    (Map<String, Object>) shapeProperties.get("criterionId");
            assertThat((List<String>) criterion.get("enum"))
                    .allMatch(expectedCriteria::contains);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void strictFindingSchemaAndEvaluationIdentityCarryTheCompletePolicyBundle() {
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"),
                objectMapper,
                normalizer,
                ruleEngine,
                mock(WritingEvaluationCacheService.class),
                TestPracticeStructuredGenerationPort.unavailable(
                        "openai-primary",
                        "model"));

        Map<String, Object> schema = ReflectionTestUtils.invokeMethod(
                client, "unifiedSchema", "Q53");
        Map<String, Object> properties =
                (Map<String, Object>) schema.get("properties");
        Map<String, Object> findings =
                (Map<String, Object>) properties.get("findings");
        Map<String, Object> finding =
                (Map<String, Object>) findings.get("items");
        List<Map<String, Object>> shapes =
                (List<Map<String, Object>>) finding.get("anyOf");
        assertThat(shapes).hasSizeGreaterThan(4);
        assertThat(shapes).allSatisfy(shape -> {
            assertThat((List<String>) shape.get("required"))
                    .contains(
                            "findingId", "polarity", "operation",
                            "criterionId", "subtype",
                            "scoringCriterionId", "errorCategory",
                            "evidenceIds", "requirementIds", "impact",
                            "frequency", "confidence", "observability");
            Map<String, Object> shapeProperties =
                    (Map<String, Object>) shape.get("properties");
            assertThat((List<String>) ((Map<String, Object>)
                    shapeProperties.get("criterionId")).get("enum"))
                    .isNotEmpty()
                    .doesNotContain("W_WON_GO_JI");
            assertThat((List<String>) ((Map<String, Object>)
                    shapeProperties.get("subtype")).get("enum"))
                    .isNotEmpty();
            String polarity = ((List<String>) ((Map<String, Object>)
                    shapeProperties.get("polarity")).get("enum")).get(0);
            List<String> operations = (List<String>) ((Map<String, Object>)
                    shapeProperties.get("operation")).get("enum");
            Map<String, Object> evidenceIds =
                    (Map<String, Object>) shapeProperties.get("evidenceIds");
            if (operations.contains("KEEP")) {
                assertThat(polarity).isEqualTo("STRENGTH");
                assertThat((Integer) evidenceIds.get("minItems"))
                        .isBetween(0, 1);
                assertThat((Integer) evidenceIds.get("maxItems"))
                        .isBetween(0, 1);
            } else if (operations.contains("MISSING")) {
                assertThat(polarity).isEqualTo("IMPROVEMENT");
                assertThat(evidenceIds)
                        .containsEntry("minItems", 0)
                        .containsEntry("maxItems", 0);
            } else {
                assertThat(polarity).isEqualTo("IMPROVEMENT");
                assertThat(evidenceIds)
                        .containsEntry("minItems", 1)
                        .containsEntry("maxItems", 1);
            }
        });
        assertThat(shapes.stream()
                .map(shape -> (Map<String, Object>) shape.get("properties"))
                .map(shapeProperties -> (Map<String, Object>)
                        shapeProperties.get("operation"))
                .flatMap(operation -> ((List<String>)
                        operation.get("enum")).stream())
                .distinct()
                .toList())
                .containsExactlyInAnyOrder(
                        "KEEP", "MISSING", "REPLACE", "REDUNDANT");
        assertThat(client.evaluationContractIdentity())
                .contains(
                        WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID,
                        WritingAssessmentPolicyBundle.NORMALIZER_VERSION,
                        WritingDiagnosticContract.VERSION)
                .hasSizeGreaterThan(500)
                .hasSizeLessThanOrEqualTo(
                        com.ksh.entities.PracticeAttemptEvaluationJob
                                .MAX_EVALUATION_CONTRACT_IDENTITY_LENGTH);
    }

    @Test
    void testCacheHitBeforeApiKeyAndRehydratesStudentText() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        String cachedValue = cachedProviderResult("Q53", "한국어");
        when(cacheService.get(eq(USER_ID), anyString(), anyString(), anyString(), eq("model"), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(cachedValue));

        OpenAiProperties properties = properties("", "model");
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties, objectMapper, normalizer, ruleEngine, cacheService, structuredPort("{}", new AtomicInteger())
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        assertTrue(result.contains("\"student_text\":\"한국어\""));
        assertTrue(result.contains("\"score\":0.0"));
    }

    @Test
    void cacheHitPreservesScoreStatusReasonAndMarksSourceCache() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        String cachedValue = cachedProviderResult(
                "Q53", "\uD55C\uAD6D\uC5B4\uB97C \uACF5\uBD80\uD569\uB2C8\uB2E4");
        when(cacheService.get(eq(USER_ID), anyString(), anyString(), eq("Q53"), eq("model"), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(cachedValue));
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort("{}", new AtomicInteger())
        );

        String learnerAnswer = "\uD55C\uAD6D\uC5B4\uB97C \uACF5\uBD80\uD569\uB2C8\uB2E4";
        JsonNode root = objectMapper.readTree(client.evaluate(USER_ID, "Bai 53 viet", learnerAnswer, false, WritingTaskType.Q53));

        assertEquals(learnerAnswer, root.path("student_text").asText());
        assertEquals(0.0, root.path("score").asDouble());
        assertEquals(0.0, root.path("raw_score").asDouble());
        assertEquals(30.0, root.path("raw_score_max").asDouble());
        assertEquals("EVALUATED", root.path("evaluation_status").asText());
        assertEquals("CACHE", root.path("evaluation_source").asText());
        assertEquals("PROVIDER",
                root.path("evaluation_origin_source").asText());
        assertEquals("NONE", root.path("evaluation_reason").asText());
        assertTrue(root.path("score_available").asBoolean(false));
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void providerDisabledCacheMissReturnsUnavailableAndDoesNotPersist() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        OpenAiProperties properties = properties("", "model");
        TestPracticeStructuredGenerationPort port =
                TestPracticeStructuredGenerationPort.unavailable(
                        "openai-primary",
                        "model");
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties, objectMapper, normalizer, ruleEngine,
                cacheService, port
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("EVALUATION_UNAVAILABLE", root.path("evaluation_status").asText());
        assertEquals("MISSING_API_KEY", root.path("evaluation_reason").asText());
        assertFalse(root.path("evaluation_retryable").asBoolean(true));
        assertFalse(root.path("score_available").asBoolean(true));
        assertFalse(root.has("raw_score"));
        assertThat(port.calls()).isZero();
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testProviderExceptionReturnsUnavailableAndDoesNotPersist() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        OpenAiProperties properties = properties("valid-key", "model");
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties, objectMapper, normalizer, ruleEngine, cacheService, throwingPort()
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("EVALUATION_UNAVAILABLE", root.path("evaluation_status").asText());
        assertEquals("PROVIDER_UNEXPECTED_ERROR", root.path("evaluation_reason").asText());
        assertFalse(root.path("evaluation_retryable").asBoolean(true));
        assertFalse(root.path("score_available").asBoolean(true));
        assertFalse(root.has("raw_score"));
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void httpErrorLogOmitsProviderBodyPromptAndLearnerAnswerButKeepsSafeMetadata() {
        String prompt = "PRIVATE_PROMPT_TEXT 쓰기 문제";
        String learnerAnswer = "LEARNER_PRIVATE_ANSWER 한국어";
        String providerBody = "PRIVATE_PROVIDER_RESPONSE " + prompt + " " + learnerAnswer;
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("SECRET_API_KEY_VALUE", "safe-model"), objectMapper, normalizer, ruleEngine,
                cacheService, httpErrorPort(providerBody)
        );

        String logs = captureLogs(WritingEvaluationClient.class, () ->
                client.evaluate(USER_ID, prompt, learnerAnswer, false));

        assertFalse(logs.contains("PRIVATE_PROVIDER_RESPONSE"));
        assertFalse(logs.contains("PRIVATE_PROMPT_TEXT"));
        assertFalse(logs.contains("LEARNER_PRIVATE_ANSWER"));
        assertFalse(logs.contains("SECRET_API_KEY_VALUE"));
        assertTrue(logs.contains("status=400"));
        assertTrue(logs.contains("model=safe-model"));
        assertTrue(logs.contains("taskType="));
    }

    @Test
    void permanentProviderHttpFailureIsNotRetryable() throws Exception {
        WritingEvaluationCacheService cacheService =
                mock(WritingEvaluationCacheService.class);
        when(cacheService.get(
                any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"),
                objectMapper,
                normalizer,
                ruleEngine,
                cacheService,
                httpErrorPort("permanent bad request"));

        JsonNode root = objectMapper.readTree(client.evaluate(
                USER_ID,
                "Bài 53 viết",
                "한국어를 공부합니다",
                false,
                WritingTaskType.Q53));

        assertEquals(
                "EVALUATION_UNAVAILABLE",
                root.path("evaluation_status").asText());
        assertEquals(
                "PROVIDER_HTTP_ERROR",
                root.path("evaluation_reason").asText());
        assertFalse(
                root.path("evaluation_retryable").asBoolean(true));
        verify(cacheService, never()).put(
                any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    @Test
    void retryableProviderHttp429IsMarkedRetryableAndNotCached() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer,
                ruleEngine, cacheService, httpErrorPort(HttpStatus.TOO_MANY_REQUESTS,
                        "rate limited"));

        JsonNode root = objectMapper.readTree(client.evaluate(
                USER_ID, "Bài 53 viết", "한국어를 공부합니다", false, WritingTaskType.Q53));

        assertThat(root.path("evaluation_status").asText())
                .isEqualTo("EVALUATION_UNAVAILABLE");
        assertThat(root.path("evaluation_reason").asText())
                .isEqualTo("PROVIDER_HTTP_ERROR");
        assertThat(root.path("evaluation_retryable").asBoolean()).isTrue();
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void retryableProviderHttp503IsMarkedRetryableAndNotCached() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer,
                ruleEngine, cacheService, httpErrorPort(HttpStatus.SERVICE_UNAVAILABLE,
                        "temporarily unavailable"));

        JsonNode root = objectMapper.readTree(client.evaluate(
                USER_ID, "Bài 53 viết", "한국어를 공부합니다", false, WritingTaskType.Q53));

        assertThat(root.path("evaluation_status").asText())
                .isEqualTo("EVALUATION_UNAVAILABLE");
        assertThat(root.path("evaluation_reason").asText())
                .isEqualTo("PROVIDER_HTTP_ERROR");
        assertThat(root.path("evaluation_retryable").asBoolean()).isTrue();
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void workerInterruptionPropagatesWithoutPublishingUnavailableOutcome() {
        WritingEvaluationCacheService cacheService =
                mock(WritingEvaluationCacheService.class);
        when(cacheService.get(
                any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        TestPracticeStructuredGenerationPort port =
                TestPracticeStructuredGenerationPort.throwing(
                        "openai-primary",
                        "model",
                        new com.ksh.features.practice.ai.transport
                                .PracticeAiContractException(
                                        "EVALUATION_INTERRUPTED",
                                        false));
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"),
                objectMapper,
                normalizer,
                ruleEngine,
                cacheService,
                port);

        RuntimeException interrupted = assertThrows(
                RuntimeException.class,
                () -> client.evaluate(
                        USER_ID,
                        "Bài 53 viết",
                        "한국어를 공부합니다",
                        false,
                        WritingTaskType.Q53));

        assertEquals(
                "Writing evaluation was interrupted.",
                interrupted.getMessage());
        assertThat(port.calls()).isEqualTo(1);
    }

    @Test
    void transportFailureLogOmitsExceptionMessageButKeepsSafeMetadata() {
        String prompt = "PRIVATE_PROMPT_TEXT 쓰기 문제";
        String learnerAnswer = "LEARNER_PRIVATE_ANSWER 한국어";
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("SECRET_API_KEY_VALUE", "safe-model"), objectMapper, normalizer, ruleEngine,
                cacheService, resourceAccessErrorPort("PRIVATE_PROVIDER_RESPONSE " + prompt + " " + learnerAnswer)
        );

        String logs = captureLogs(WritingEvaluationClient.class, () ->
                client.evaluate(USER_ID, prompt, learnerAnswer, false));

        assertFalse(logs.contains("PRIVATE_PROVIDER_RESPONSE"));
        assertFalse(logs.contains("PRIVATE_PROMPT_TEXT"));
        assertFalse(logs.contains("LEARNER_PRIVATE_ANSWER"));
        assertFalse(logs.contains("SECRET_API_KEY_VALUE"));
        assertTrue(logs.contains("category=transport"));
        assertTrue(logs.contains("model=safe-model"));
        assertTrue(logs.contains("taskType="));
    }

    @Test
    void testAiSuccessPersistsSanitizedResult() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        AtomicInteger callCount = new AtomicInteger(0);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), callCount)
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어를 공부합니다", false);

        assertNotNull(result);
        assertEquals(1, callCount.get());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(cacheService).put(eq(USER_ID), anyString(), anyString(), eq("Q53"), eq("model"),
                eq(WritingPromptRules.PROMPT_VERSION),
                eq(WritingPromptRules.RUBRIC_VERSION),
                eq(WritingPromptRules.EVALUATION_SCHEMA_VERSION + ":"
                        + WritingPromptRules.EVALUATION_CONTRACT_VERSION),
                payload.capture());
        JsonNode cached = objectMapper.readTree(payload.getValue());
        assertFalse(cached.has("student_text"));
        assertEquals("KSH_WRITING_EVALUATOR_V3", cached.path("engine").asText());
        assertEquals("EVALUATED", cached.path("evaluation_status").asText());
        assertEquals("PROVIDER", cached.path("evaluation_source").asText());
        assertEquals("NONE", cached.path("evaluation_reason").asText());
        assertTrue(cached.path("score_available").asBoolean(false));
        assertTrue(cached.path("raw_score").isNumber());
        assertTrue(cached.path("raw_score_max").isNumber());
        assertEquals(
                WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID,
                cached.path("policy_bundle_id").asText());
    }

    @Test
    void contractFailureDoesNotPersistCache() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService,
                TestPracticeStructuredGenerationPort.throwing(
                        "openai-primary",
                        "model",
                        new com.ksh.features.practice.ai.transport
                                .PracticeAiContractException(
                                        "PROVIDER_MALFORMED_STRUCTURED_OUTPUT",
                                        false))
        );

        JsonNode root = objectMapper.readTree(client.evaluate(USER_ID, "Bai 53 viet",
                "\uD55C\uAD6D\uC5B4\uB97C \uACF5\uBD80\uD569\uB2C8\uB2E4", false, WritingTaskType.Q53));

        assertEquals("EVALUATION_CONTRACT_FAILED", root.path("evaluation_status").asText());
        assertEquals(
                "PROVIDER_MALFORMED_STRUCTURED_OUTPUT",
                root.path("evaluation_reason").asText());
        assertFalse(root.path("score_available").asBoolean(true));
        assertFalse(root.has("raw_score"));
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void explicitMetadataOverridesConflictingPromptOnProviderSuccess() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), new AtomicInteger())
        );

        String result = client.evaluate(USER_ID, "Bài 51 điền chỗ trống", "한국어를 공부합니다", false,
                WritingTaskType.Q53);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("Q53", root.path("task_type").asText());
        assertEquals(30.0, root.path("raw_score_max").asDouble());
        verify(cacheService).put(eq(USER_ID), anyString(), anyString(), eq("Q53"), eq("model"),
                eq(WritingPromptRules.PROMPT_VERSION),
                eq(WritingPromptRules.RUBRIC_VERSION),
                eq(WritingPromptRules.EVALUATION_SCHEMA_VERSION + ":"
                        + WritingPromptRules.EVALUATION_CONTRACT_VERSION),
                anyString());
    }

    @Test
    void explicitQ52KeepsIdentityInCacheAndResult() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        String cachedValue = cachedProviderResult("Q52", "있다");
        when(cacheService.get(eq(USER_ID), anyString(), anyString(), eq("Q52"), eq("model"), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(cachedValue));
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort("{}", new AtomicInteger())
        );

        String result = client.evaluate(USER_ID, "Bài 53 biểu đồ", "있다", false, WritingTaskType.Q52);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("Q52", root.path("task_type").asText());
        assertEquals(10.0, root.path("raw_score_max").asDouble());
    }

    @Test
    void explicitMetadataControlsSpamShortcutProfile() throws Exception {
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                mock(WritingEvaluationCacheService.class), throwingPort()
        );

        String result = client.evaluate(USER_ID, "Bài viết chung", "asdf", false, WritingTaskType.Q54);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("Q54", root.path("task_type").asText());
        assertEquals(50.0, root.path("raw_score_max").asDouble());
    }

    @Test
    void explicitMetadataControlsProviderFailureUnavailableProfile() throws Exception {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, throwingPort()
        );

        String result = client.evaluate(USER_ID, "Bài 53 biểu đồ", "한국어", false, WritingTaskType.Q54);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("Q54", root.path("task_type").asText());
        assertEquals("EVALUATION_UNAVAILABLE", root.path("evaluation_status").asText());
        assertEquals("PROVIDER_UNEXPECTED_ERROR", root.path("evaluation_reason").asText());
        assertFalse(root.path("score_available").asBoolean(true));
        assertFalse(root.has("raw_score"));
        assertFalse(root.has("raw_score_max"));
    }

    @Test
    void testReEvaluateBypassesReadAndOverwritesOnSuccess() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        AtomicInteger callCount = new AtomicInteger(0);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), callCount)
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어를 공부합니다", true);

        assertNotNull(result);
        assertEquals(1, callCount.get());
        verify(cacheService, never()).get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cacheService).put(eq(USER_ID), anyString(), anyString(), eq("Q53"), eq("model"),
                eq(WritingPromptRules.PROMPT_VERSION),
                eq(WritingPromptRules.RUBRIC_VERSION),
                eq(WritingPromptRules.EVALUATION_SCHEMA_VERSION + ":"
                        + WritingPromptRules.EVALUATION_CONTRACT_VERSION),
                anyString());
    }

    @Test
    void reEvaluatePayloadMarksAuditModeForProvider() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        AtomicInteger callCount = new AtomicInteger(0);
        TestPracticeStructuredGenerationPort port = structuredPort(aiResponse(), callCount);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, port);

        client.evaluate(USER_ID, "Bài 53 viết", "한국어를 공부합니다", true);

        assertThat(port.lastRequest().input())
                .containsEntry("is_re_evaluation", true)
                .containsEntry("audit_mode", true);
        verify(cacheService, never()).get(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void questionImageShaIsPartOfCachePromptIdentity() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        var resolver = mock(com.ksh.features.practice.ai.media.AiQuestionImageResolver.class);
        when(resolver.resolve(eq("image-a"), eq(USER_ID)))
                .thenReturn(Optional.of(new AiImageEvidence(1L, "image/png", "data:image/png;base64,YQ==", "sha-a", 1)));
        when(resolver.resolve(eq("image-b"), eq(USER_ID)))
                .thenReturn(Optional.of(new AiImageEvidence(2L, "image/png", "data:image/png;base64,Yg==", "sha-b", 1)));
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("", "model"), objectMapper, normalizer, ruleEngine,
                new WritingTaskResolver(), cacheService, resolver, PracticeAiMetrics.noop(),
                TestPracticeStructuredGenerationPort.unavailable("openai-primary", "model"));

        client.evaluate(USER_ID, "Bài 53 viết", "한국어를 공부합니다", false, WritingTaskType.Q53, "image-a");
        client.evaluate(USER_ID, "Bài 53 viết", "한국어를 공부합니다", false, WritingTaskType.Q53, "image-b");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(cacheService, times(2)).get(eq(USER_ID), prompts.capture(), anyString(), eq("Q53"),
                eq("model"), anyString(), anyString(), anyString());
        assertThat(prompts.getAllValues()).containsExactly(
                "Bài 53 viết\n[KSH_QUESTION_IMAGE_SHA256:sha-a]",
                "Bài 53 viết\n[KSH_QUESTION_IMAGE_SHA256:sha-b]");
    }

    @Test
    void testReEvaluateFailureDoesNotOverwriteOldCache() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, throwingPort()
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", true);

        assertTrue(result.contains("\"evaluation_status\":\"EVALUATION_UNAVAILABLE\""));
        assertTrue(result.contains("\"evaluation_reason\":\"PROVIDER_UNEXPECTED_ERROR\""));
        assertFalse(result.contains("\"raw_score\""));
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testReadFailureTreatsAsMissAndProviderStillRuns() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db read down"));
        AtomicInteger callCount = new AtomicInteger(0);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), callCount)
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        assertNotNull(result);
        assertEquals(1, callCount.get());
    }

    @Test
    void testWriteFailureReturnsValidProviderResult() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("db write down")).when(cacheService)
                .put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), new AtomicInteger())
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        assertTrue(result.contains("\"engine\":\"KSH_WRITING_EVALUATOR_V3\""));
    }

    @Test
    void testMalformedCachedJsonIgnoredDeletedAndProviderTried() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("{malformed"));
        AtomicInteger callCount = new AtomicInteger(0);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), callCount)
        );

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        assertNotNull(result);
        assertEquals(1, callCount.get());
        verify(cacheService).delete(eq(USER_ID), anyString(), anyString(), eq("Q53"), eq("model"),
                eq(WritingPromptRules.PROMPT_VERSION),
                eq(WritingPromptRules.RUBRIC_VERSION),
                eq(WritingPromptRules.EVALUATION_SCHEMA_VERSION + ":"
                        + WritingPromptRules.EVALUATION_CONTRACT_VERSION));
    }

    @Test
    void malformedCacheDeleteFailureDoesNotBlockProviderEvaluation() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("{malformed"));
        doThrow(new RuntimeException("db delete down")).when(cacheService)
                .delete(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        AtomicInteger callCount = new AtomicInteger(0);
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("valid-key", "model"), objectMapper, normalizer, ruleEngine,
                cacheService, structuredPort(aiResponse(), callCount)
        );

        String result = client.evaluate(USER_ID, "Bai 53 viet",
                "\uD55C\uAD6D\uC5B4\uB97C \uACF5\uBD80\uD569\uB2C8\uB2E4", false, WritingTaskType.Q53);

        assertNotNull(result);
        assertTrue(result.contains("\"engine\":\"KSH_WRITING_EVALUATOR_V3\""));
        assertEquals(1, callCount.get());
    }

    @Test
    void convenienceEvaluateOverloadBypassesPersistentCacheScope() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        WritingEvaluationClient client = new WritingEvaluationClient(
                properties("", "model"),
                objectMapper,
                normalizer,
                ruleEngine,
                cacheService,
                TestPracticeStructuredGenerationPort.unavailable(
                        "openai-primary",
                        "model")
        );

        String result = client.evaluate("Bài 53 viết", "한국어", false);

        assertNotNull(result);
        verify(cacheService).get(isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cacheService, never()).put(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cacheHitRecordsNoProviderMetric() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        String cachedValue = cachedProviderResult("Q53", "한국어");
        when(cacheService.get(eq(USER_ID), anyString(), anyString(), anyString(), eq("model"), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(cachedValue));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WritingEvaluationClient client = clientWithMetrics(
                properties("", "model"), cacheService, structuredPort("{}", new AtomicInteger()), registry);

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        assertNotNull(result);
        assertTrue(registry.find(PracticeAiMetrics.PROVIDER_OPERATIONS).meters().isEmpty());
    }

    @Test
    void providerDisabledRecordsOneProviderFailureMetric() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WritingEvaluationClient client = clientWithMetrics(
                properties("", "model"),
                cacheService,
                TestPracticeStructuredGenerationPort.unavailable(
                        "openai-primary",
                        "model"),
                registry);

        client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);
        assertEquals(1.0, registry.counter(PracticeAiMetrics.PROVIDER_OPERATIONS,
                "feature", "writing", "outcome", "failure").count());
        assertEquals(1L, registry.timer(PracticeAiMetrics.PROVIDER_DURATION,
                "feature", "writing", "outcome", "failure").count());
    }

    @Test
    void acceptedProviderOutputRecordsOneProviderSuccessMetric() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WritingEvaluationClient client = clientWithMetrics(
                properties("valid-key", "model"), cacheService, structuredPort(aiResponse(), new AtomicInteger()), registry);

        String result = client.evaluate(USER_ID, "Bài 53 viết", "한국어를 공부합니다", false);

        assertNotNull(result);
        assertEquals(1.0, registry.counter(PracticeAiMetrics.PROVIDER_OPERATIONS,
                "feature", "writing", "outcome", "success").count());
        assertEquals(1L, registry.timer(PracticeAiMetrics.PROVIDER_DURATION,
                "feature", "writing", "outcome", "success").count());
    }

    @Test
    void malformedCachedJsonRecordsParseMalformedMetric() {
        WritingEvaluationCacheService cacheService = mock(WritingEvaluationCacheService.class);
        when(cacheService.get(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("{malformed"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WritingEvaluationClient client = clientWithMetrics(
                properties("valid-key", "model"), cacheService, structuredPort(aiResponse(), new AtomicInteger()), registry);

        client.evaluate(USER_ID, "Bài 53 viết", "한국어", false);

        assertEquals(1.0, registry.counter(PracticeAiMetrics.CACHE_OPERATIONS,
                "cache", "writing", "operation", "parse", "outcome", "malformed").count());
    }

    private String cachedProviderResult(
            String taskType,
            String learnerAnswer) {
        String providerJson;
        try {
            providerJson = objectMapper.writeValueAsString(
                    WritingContractTestFixtures.zeroEnvelope(
                            objectMapper, taskType, learnerAnswer));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return normalizer.sanitizeForCache(
                normalizer.normalize(
                        providerJson, taskType, learnerAnswer, null));
    }

    private OpenAiProperties properties(String apiKey, String model) {
        OpenAiProperties properties = mock(OpenAiProperties.class);
        when(properties.evaluatorModel()).thenReturn(model);
        when(properties.apiKey()).thenReturn(apiKey);
        when(properties.baseUrl()).thenReturn("http://localhost");
        when(properties.connectTimeout()).thenReturn(Duration.ofSeconds(5));
        when(properties.readTimeout()).thenReturn(Duration.ofSeconds(60));
        return properties;
    }

    private WritingEvaluationClient clientWithMetrics(OpenAiProperties properties,
                                                      WritingEvaluationCacheService cacheService,
                                                      PracticeStructuredGenerationPort port,
                                                      SimpleMeterRegistry registry) {
        return new WritingEvaluationClient(
                properties,
                objectMapper,
                normalizer,
                ruleEngine,
                new WritingTaskResolver(),
                cacheService,
                null,
                new PracticeAiMetrics(registry),
                port);
    }

    private String aiResponse() {
        return "__STRICT_WRITING_FIXTURE__";
    }

    private TestPracticeStructuredGenerationPort throwingPort() {
        return TestPracticeStructuredGenerationPort.throwing(
                "openai-primary",
                "model",
                new RuntimeException("API connection error"));
    }

    private TestPracticeStructuredGenerationPort resourceAccessErrorPort(
            String message) {
        return TestPracticeStructuredGenerationPort.throwing(
                "openai-primary",
                "safe-model",
                new org.springframework.web.client.ResourceAccessException(
                        message));
    }

    private TestPracticeStructuredGenerationPort httpErrorPort(
            String responseBody) {
        return httpErrorPort(HttpStatus.BAD_REQUEST, responseBody);
    }

    private TestPracticeStructuredGenerationPort httpErrorPort(
            HttpStatus status,
            String responseBody) {
        var exception = status.is4xxClientError()
                ? new org.springframework.web.client.HttpClientErrorException(
                        status, status.getReasonPhrase(),
                        responseBody.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
                : new org.springframework.web.client.HttpServerErrorException(
                        status, status.getReasonPhrase(),
                        responseBody.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);
        return TestPracticeStructuredGenerationPort.throwing(
                "openai-primary", "safe-model", exception);
    }

    private TestPracticeStructuredGenerationPort structuredPort(
            String responseJson,
            AtomicInteger postCallCount) {
        return TestPracticeStructuredGenerationPort.available(
                "openai-primary",
                "model",
                request -> {
                    postCallCount.incrementAndGet();
                    try {
                        JsonNode response = "__STRICT_WRITING_FIXTURE__"
                                .equals(responseJson)
                                ? WritingContractTestFixtures.zeroEnvelope(
                                objectMapper,
                                String.valueOf(request.input()
                                        .get("task_type")),
                                String.valueOf(request.input()
                                        .get("learner_answer")))
                                : objectMapper.readTree(responseJson);
                        return new PracticeStructuredGenerationResponse(
                                response,
                                "openai-primary",
                                "model",
                                "stop",
                                "writing-test");
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
    }

    private static String captureLogs(Class<?> loggerClass, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        StringBuilder logs = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            logs.append(event.getFormattedMessage()).append('\n');
        }
        return logs.toString();
    }
}
