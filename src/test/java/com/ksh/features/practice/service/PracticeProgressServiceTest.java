package com.ksh.features.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ksh.entities.PracticeAttempt;
import com.ksh.entities.PracticePublishedVersion;
import com.ksh.entities.PracticeQuestion;
import com.ksh.entities.PracticeQuestionVersion;
import com.ksh.entities.PracticeSectionVersion;
import com.ksh.entities.PracticeSetVersion;
import com.ksh.entities.PracticeTestVersion;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.ai.writing.WritingFeedbackContractParser;
import com.ksh.features.practice.ai.writing.WritingContractTestFixtures;
import com.ksh.features.practice.dto.PracticeDtos.PracticeProgressPageData;
import com.ksh.features.practice.dto.PracticeDtos.ProgressAvailability;
import com.ksh.features.practice.dto.PracticeDtos.ProgressExclusionReason;
import com.ksh.features.practice.dto.PracticeDtos.SkillMetric;
import com.ksh.features.practice.dto.PracticeDtos.WritingTaskProgressSeam;
import com.ksh.features.practice.repository.PracticeAttemptRepository;
import com.ksh.features.practice.repository.PracticePublishedVersionRepository;
import com.ksh.features.practice.repository.PracticeQuestionVersionRepository;
import com.ksh.features.practice.repository.PracticeSectionVersionRepository;
import com.ksh.features.practice.repository.PracticeSetVersionRepository;
import com.ksh.features.practice.repository.PracticeTestVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PracticeProgressServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private PracticeAttemptRepository attemptRepository;
    @Mock
    private PracticePublishedVersionRepository publishedVersionRepository;
    @Mock
    private PracticeSetVersionRepository setVersionRepository;
    @Mock
    private PracticeTestVersionRepository testVersionRepository;
    @Mock
    private PracticeSectionVersionRepository sectionVersionRepository;
    @Mock
    private PracticeQuestionVersionRepository questionVersionRepository;

    private PracticeProgressService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new PracticeProgressService(
                attemptRepository,
                publishedVersionRepository,
                setVersionRepository,
                testVersionRepository,
                sectionVersionRepository,
                questionVersionRepository,
                new WritingFeedbackContractParser(objectMapper),
                objectMapper,
                Clock.fixed(Instant.parse("2026-07-25T02:00:00Z"), ZoneOffset.UTC));
        lenient().when(attemptRepository.findProgressAllTime(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(allTime(0, 0, 0, 0, 0, 0, 0));
        lenient().when(attemptRepository.findProgressAllTimeBySkill(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(List.of());
        lenient().when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(attemptRepository.findProgressWritingAttempts(
                eq(USER_ID),
                eq(PracticeAttempt.STATUS_DISCARDED),
                any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(publishedVersionRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(setVersionRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(testVersionRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(sectionVersionRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(questionVersionRepository
                .findBySectionVersionIdInOrderBySectionVersionIdAscDisplayOrderAscQuestionNoAscIdAsc(
                        any())).thenReturn(List.of());
    }

    @Test
    void zeroAttemptsKeepScoreDeltaLevelAndDurationUnavailableInsteadOfZero() {
        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().totalAttempts()).isZero();
        assertThat(page.overview().totalCompletedTests()).isZero();
        assertThat(page.overview().attemptCounts().inProgress()).isZero();
        assertThat(page.overview().currentLevel()).isNull();
        assertThat(page.overview().levelFact().availability())
                .isEqualTo(ProgressAvailability.UNAVAILABLE);
        assertThat(page.overview().totalPracticeMinutes()).isNull();
        assertThat(page.overview().durationFact().value()).isNull();
        assertThat(page.overview().recentAverageScore()).isNull();
        assertThat(page.overview().recentScoreFact().value()).isNull();
        assertThat(page.overview().skillMetrics())
                .allSatisfy(metric -> {
                    assertThat(metric.normalizedScore()).isNull();
                    assertThat(metric.deltaFromLastPeriod()).isNull();
                    assertThat(metric.deltaFact().availability())
                            .isEqualTo(ProgressAvailability.UNAVAILABLE);
                });
    }

    @Test
    void recentHistorySeparatesCompletedInProgressAndFailedAttempts() {
        PracticeAttempt completed = new PracticeAttempt(
                USER_ID, 1L, 2L, "READING", 3L);
        completed.lockPublishedVersion(10L, 11L, 12L, 13L);
        completed.markGraded(new BigDecimal("8"), new BigDecimal("10"), "{}", "{}");
        org.springframework.test.util.ReflectionTestUtils.setField(completed, "id", 301L);
        setTemporalField(completed, "submittedAt", LocalDateTime.parse("2026-07-23T09:00:00"));

        PracticeAttempt inProgress = new PracticeAttempt(
                USER_ID, 1L, 2L, "READING", 3L);
        inProgress.lockPublishedVersion(10L, 11L, 12L, 13L);
        org.springframework.test.util.ReflectionTestUtils.setField(inProgress, "id", 302L);
        setTemporalField(inProgress, "updatedAt", LocalDateTime.parse("2026-07-24T09:00:00"));

        PracticeAttempt failed = new PracticeAttempt(
                USER_ID, 4L, 5L, "WRITING", 6L);
        LocalDateTime failedAt = LocalDateTime.parse("2026-07-25T01:00:00");
        failed.markSubmittedForAnalysis(BigDecimal.TEN, "{}", failedAt);
        failed.markAnalysisFailed("PROVIDER_TIMEOUT", failedAt);
        org.springframework.test.util.ReflectionTestUtils.setField(failed, "id", 303L);

        when(attemptRepository.findProgressAllTime(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(allTime(3, 2, 1, 0, 1, 0, 30));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(failed, inProgress, completed));
        stubCanonicalVersionIdentity();

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().attemptCounts().total()).isEqualTo(3);
        assertThat(page.overview().attemptCounts().completed()).isEqualTo(2);
        assertThat(page.overview().attemptCounts().inProgress()).isEqualTo(1);
        assertThat(page.overview().recentAverageScore()).isEqualTo(80.0);
        assertThat(page.analytics().history()).extracting(row -> row.state())
                .containsExactly("FAILED", "IN_PROGRESS", "SCORED");
        assertThat(page.analytics().history().get(0).resultEligible()).isFalse();
        assertThat(page.analytics().history().get(1).resumable()).isTrue();
        assertThat(page.analytics().history().get(1).resultEligible()).isFalse();
        assertThat(page.analytics().history().get(2).resultEligible()).isTrue();
        assertThat(page.analytics().history().get(2).score())
                .isEqualByComparingTo("8");
        assertThat(page.analytics().history().get(2).totalPoints())
                .isEqualByComparingTo("10");
        assertThat(page.analytics().scoreTrend()).singleElement()
                .satisfies(point -> assertThat(point.normalizedScore()).isEqualTo(80.0));
    }

    @Test
    void allTimeCountIsNotDefinedByTheBoundedRecentOneHundred() {
        when(attemptRepository.findProgressAllTime(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(allTime(101, 100, 1, 0, 100, 0, 2500));
        PracticeAttempt recentAttempt =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(java.util.Collections.nCopies(100, recentAttempt));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().attemptCounts().total()).isEqualTo(101);
        assertThat(page.overview().attemptCounts().completed()).isEqualTo(100);
        assertThat(page.overview().attemptCounts().inProgress()).isEqualTo(1);
        assertThat(page.overview().recentDetailWindow().bounded()).isTrue();
        assertThat(page.overview().recentDetailWindow().limit()).isEqualTo(100);
        assertThat(page.overview().recentDetailWindow().returnedCount()).isEqualTo(100);
        assertThat(page.overview().recentDetailWindow().truncated()).isTrue();
        assertThat(page.overview().recentDetailWindow().label())
                .contains("tối đa 100");
        verify(attemptRepository).findRecentProgressAttempts(
                eq(USER_ID),
                eq(PracticeAttempt.STATUS_DISCARDED),
                org.mockito.ArgumentMatchers.argThat(pageable ->
                        pageable.getPageSize() == PracticeProgressService.RECENT_DETAIL_LIMIT));
    }

    @Test
    void invalidOrMissingDurationIsExcludedWithCoverageAndNeverBecomesThirtyMinutes() {
        when(attemptRepository.findProgressAllTime(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(allTime(2, 2, 0, 0, 0, 2, 0));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().totalPracticeMinutes()).isNull();
        assertThat(page.overview().durationFact().value()).isNull();
        assertThat(page.overview().durationFact().coverage().eligibleCount()).isZero();
        assertThat(page.overview().durationFact().coverage().excludedCount()).isEqualTo(2);
        assertThat(page.overview().durationFact().coverage().exclusions())
                .singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(ProgressExclusionReason.MISSING_OR_INVALID_DURATION);
    }

    @Test
    void recentInvalidDurationStaysNullInHeatmapWithTypedCoverage() throws Exception {
        PracticeAttempt invalid =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        invalid.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        setTemporalField(invalid, "startedAt", LocalDateTime.parse("2026-07-25T01:00:00"));
        setTemporalField(invalid, "submittedAt", LocalDateTime.parse("2026-07-25T01:00:00"));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(invalid));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().heatmap())
                .filteredOn(cell -> "2026-07-25".equals(cell.date()))
                .singleElement()
                .satisfies(cell -> {
                    assertThat(cell.attemptCount()).isEqualTo(1);
                    assertThat(cell.totalMinutes()).isNull();
                    assertThat(cell.durationCoverage().activityCount()).isEqualTo(1);
                    assertThat(cell.durationCoverage().eligibleCount()).isZero();
                    assertThat(cell.durationCoverage().excludedCount()).isEqualTo(1);
                    assertThat(cell.durationCoverage().exclusions())
                            .singleElement()
                            .satisfies(exclusion -> {
                                assertThat(exclusion.reason()).isEqualTo(
                                        ProgressExclusionReason
                                                .MISSING_OR_INVALID_DURATION);
                                assertThat(exclusion.activityCount()).isEqualTo(1);
                            });
                });
        ObjectMapper mapper = productionLikeObjectMapper();
        com.fasterxml.jackson.databind.JsonNode serialized =
                mapper.readTree(mapper.writeValueAsString(page.overview()));
        assertThat(serialized.path("recentDetailWindow").path("asOf").asText())
                .isEqualTo("2026-07-25T02:00:00");
        com.fasterxml.jackson.databind.JsonNode serializedDay =
                java.util.stream.StreamSupport.stream(
                                serialized.path("heatmap").spliterator(), false)
                        .filter(node -> "2026-07-25".equals(node.path("date").asText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(serializedDay.path("totalMinutes").isNull()).isTrue();
        assertThat(serializedDay.path("durationCoverage")
                .path("exclusions").get(0).path("reason").asText())
                .isEqualTo("MISSING_OR_INVALID_DURATION");
    }

    @Test
    void inProgressOnlyHeatmapDayHasTypedInapplicableDurationCoverage()
            throws Exception {
        PracticeAttempt inProgress =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        setTemporalField(
                inProgress, "updatedAt", LocalDateTime.parse("2026-07-25T01:15:00"));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(inProgress));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");
        var cell = page.overview().heatmap().stream()
                .filter(day -> "2026-07-25".equals(day.date()))
                .findFirst()
                .orElseThrow();

        assertThat(cell.attemptCount()).isEqualTo(1);
        assertThat(cell.totalMinutes()).isNull();
        assertThat(cell.durationCoverage().activityCount()).isEqualTo(1);
        assertThat(cell.durationCoverage().eligibleCount()).isZero();
        assertThat(cell.durationCoverage().excludedCount()).isEqualTo(1);
        assertThat(cell.durationCoverage().activityCount()).isEqualTo(
                cell.durationCoverage().eligibleCount()
                        + cell.durationCoverage().excludedCount());
        assertThat(cell.durationCoverage().exclusions())
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.reason()).isEqualTo(
                            ProgressExclusionReason
                                    .DURATION_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY);
                    assertThat(exclusion.activityCount()).isEqualTo(1);
                });

        ObjectMapper mapper = productionLikeObjectMapper();
        var serialized = mapper.readTree(mapper.writeValueAsString(cell));
        assertThat(serialized.path("totalMinutes").isNull()).isTrue();
        assertThat(serialized.path("durationCoverage").path("activityCount").asLong())
                .isEqualTo(1);
        assertThat(serialized.path("durationCoverage").path("eligibleCount").asLong())
                .isZero();
        assertThat(serialized.path("durationCoverage").path("excludedCount").asLong())
                .isEqualTo(1);
        assertThat(serialized.path("durationCoverage").path("exclusions").get(0)
                .path("reason").asText())
                .isEqualTo("DURATION_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY");
    }

    @Test
    void staleInProgressIsReportedAsOtherWhileCanonicalLockRemainsResumable() {
        PracticeAttempt canonical =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        canonical.lockPublishedVersion(10L, 11L, 12L, 13L);
        org.springframework.test.util.ReflectionTestUtils.setField(
                canonical, "id", 100L);
        setTemporalField(
                canonical,
                "updatedAt",
                LocalDateTime.parse("2026-07-25T01:15:00"));
        PracticeAttempt stale =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 4L);
        setTemporalField(
                stale,
                "updatedAt",
                LocalDateTime.parse("2026-07-25T01:30:00"));
        when(attemptRepository.findProgressAllTime(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(allTime(2, 0, 1, 1, 0, 0, 0));
        when(attemptRepository.findProgressAllTimeBySkill(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(List.of(skill(
                        "READING", 2, 0, 1, 1,
                        0, 0, null, null)));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID),
                eq(PracticeAttempt.STATUS_DISCARDED),
                any(Pageable.class)))
                .thenReturn(List.of(stale, canonical));
        stubCanonicalVersionIdentity();

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().attemptCounts().inProgress()).isEqualTo(1);
        assertThat(page.overview().attemptCounts().other()).isEqualTo(1);
        assertThat(page.overview().recentHistory().get(0).state())
                .isEqualTo("STALE");
        assertThat(page.overview().recentHistory().get(0).resumable())
                .isFalse();
        assertThat(page.overview().recentHistory().get(0).resultEligible())
                .isFalse();
        assertThat(page.overview().recentHistory().get(1).state())
                .isEqualTo("IN_PROGRESS");
        assertThat(page.overview().recentHistory().get(1).resumable())
                .isTrue();
        assertThat(page.overview().recentHistory().get(1).resultEligible())
                .isFalse();
        assertThat(page.analytics().weeklySkillMetrics())
                .filteredOn(metric -> "READING".equals(metric.skill()))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.attemptCounts().inProgress()).isEqualTo(1);
                    assertThat(metric.attemptCounts().other()).isEqualTo(1);
                });
    }

    @Test
    void mixedHeatmapDayReconcilesValidInvalidAndInProgressDurationCoverage()
            throws Exception {
        PracticeAttempt valid =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        valid.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        setTemporalField(valid, "startedAt", LocalDateTime.parse("2026-07-25T00:30:00"));
        setTemporalField(valid, "submittedAt", LocalDateTime.parse("2026-07-25T01:00:00"));

        PracticeAttempt invalid =
                new PracticeAttempt(USER_ID, 1L, 2L, "LISTENING", 3L);
        invalid.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        setTemporalField(invalid, "startedAt", LocalDateTime.parse("2026-07-25T01:30:00"));
        setTemporalField(invalid, "submittedAt", LocalDateTime.parse("2026-07-25T01:30:00"));

        PracticeAttempt inProgress =
                new PracticeAttempt(USER_ID, 1L, 2L, "WRITING", 3L);
        setTemporalField(
                inProgress, "updatedAt", LocalDateTime.parse("2026-07-25T01:45:00"));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(inProgress, invalid, valid));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");
        var cell = page.overview().heatmap().stream()
                .filter(day -> "2026-07-25".equals(day.date()))
                .findFirst()
                .orElseThrow();

        assertThat(cell.attemptCount()).isEqualTo(3);
        assertThat(cell.totalMinutes()).isEqualTo(30L);
        assertThat(cell.durationCoverage().activityCount()).isEqualTo(3);
        assertThat(cell.durationCoverage().eligibleCount()).isEqualTo(1);
        assertThat(cell.durationCoverage().excludedCount()).isEqualTo(2);
        assertThat(cell.durationCoverage().activityCount()).isEqualTo(
                cell.durationCoverage().eligibleCount()
                        + cell.durationCoverage().excludedCount());
        assertThat(cell.durationCoverage().exclusions())
                .extracting(exclusion -> exclusion.reason(), exclusion -> exclusion.activityCount())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ProgressExclusionReason.MISSING_OR_INVALID_DURATION, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                ProgressExclusionReason
                                        .DURATION_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY,
                                1L));
        assertThat(cell.durationCoverage().exclusions().stream()
                .mapToLong(exclusion -> exclusion.activityCount())
                .sum()).isEqualTo(cell.durationCoverage().excludedCount());

        ObjectMapper mapper = productionLikeObjectMapper();
        var serialized = mapper.readTree(mapper.writeValueAsString(cell));
        assertThat(serialized.path("totalMinutes").asLong()).isEqualTo(30);
        assertThat(serialized.path("durationCoverage").path("activityCount").asLong())
                .isEqualTo(3);
        assertThat(serialized.path("durationCoverage").path("eligibleCount").asLong())
                .isEqualTo(1);
        assertThat(serialized.path("durationCoverage").path("excludedCount").asLong())
                .isEqualTo(2);
        assertThat(serialized.path("durationCoverage").path("exclusions").toString())
                .contains(
                        "MISSING_OR_INVALID_DURATION",
                        "DURATION_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY");
    }

    @Test
    void objectiveAggregatePreservesEarnedAndPossiblePartialCredit() {
        PracticeAttemptRepository.ProgressSkillProjection reading =
                skill("READING", 1, 1, 0, 0, 1, 0,
                        new BigDecimal("1.00"), new BigDecimal("2.00"));
        when(attemptRepository.findProgressAllTimeBySkill(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(List.of(reading));

        SkillMetric metric = service.getProgressPageData(USER_ID, "Learner", "")
                .overview().skillMetrics().stream()
                .filter(row -> "READING".equals(row.skill()))
                .findFirst()
                .orElseThrow();

        assertThat(metric.normalizedScore()).isEqualTo(50.0);
        assertThat(metric.scoreFact().numerator()).isEqualByComparingTo("1.00");
        assertThat(metric.scoreFact().denominator()).isEqualByComparingTo("2.00");
        assertThat(metric.scoreFact().unit()).isEqualTo("PERCENTAGE");
        assertThat(metric.scoreFact().profileId())
                .isEqualTo("OBJECTIVE_EARNED_OVER_POSSIBLE_V1");
    }

    @Test
    void speakingExposesActivityAndCoverageButNoNumericAggregate() {
        PracticeAttemptRepository.ProgressSkillProjection speaking =
                skill("SPEAKING", 2, 2, 0, 0, 0, 0,
                        new BigDecimal("184"), new BigDecimal("200"));
        when(attemptRepository.findProgressAllTimeBySkill(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(List.of(speaking));
        PracticeAttempt legacyNumericSpeaking =
                new PracticeAttempt(USER_ID, 1L, 2L, "SPEAKING", 3L);
        legacyNumericSpeaking.lockPublishedVersion(10L, 11L, 12L, 13L);
        legacyNumericSpeaking.markGraded(
                new BigDecimal("92"), new BigDecimal("100"), "{}", "{}");
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(legacyNumericSpeaking));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");
        SkillMetric metric = page.overview().skillMetrics().stream()
                .filter(row -> "SPEAKING".equals(row.skill()))
                .findFirst()
                .orElseThrow();

        assertThat(metric.attemptCount()).isEqualTo(2);
        assertThat(metric.normalizedScore()).isNull();
        assertThat(metric.deltaFromLastPeriod()).isNull();
        assertThat(metric.scoreFact().availability())
                .isEqualTo(ProgressAvailability.NOT_SCORABLE);
        assertThat(metric.scoreFact().value()).isNull();
        assertThat(metric.scoreFact().numerator()).isNull();
        assertThat(metric.scoreFact().denominator()).isNull();
        assertThat(metric.scoreFact().coverage().activityCount()).isEqualTo(2);
        assertThat(metric.scoreFact().coverage().eligibleCount()).isZero();
        assertThat(metric.scoreFact().coverage().excludedCount()).isEqualTo(2);
        assertThat(metric.scoreFact().coverage().activityCount()).isEqualTo(
                metric.scoreFact().coverage().eligibleCount()
                        + metric.scoreFact().coverage().excludedCount());
        assertThat(metric.scoreFact().coverage().exclusions())
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.reason()).isEqualTo(
                            ProgressExclusionReason
                                    .SPEAKING_NUMERIC_AGGREGATION_NOT_SUPPORTED);
                    assertThat(exclusion.activityCount()).isEqualTo(2);
                });
        assertThat(page.analytics().scoreTrend())
                .noneMatch(point -> "SPEAKING".equals(point.skill()));
        assertThat(page.analytics().questionTypePerf())
                .noneMatch(row -> "SPEAKING".equals(row.skill()));
        assertThat(page.analytics().history()).singleElement().satisfies(row -> {
            assertThat(row.score()).isNull();
            assertThat(row.totalPoints()).isNull();
            assertThat(row.scoreFact().availability())
                    .isEqualTo(ProgressAvailability.NOT_SCORABLE);
            assertThat(row.scoreFact().value()).isNull();
        });
    }

    @Test
    void writingAndSpeakingCoveragePartitionCompletedAndIncompleteActivity() {
        when(attemptRepository.findProgressAllTimeBySkill(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(List.of(
                        skill("WRITING", 3, 1, 1, 1, 0, 0, null, null),
                        skill("SPEAKING", 2, 1, 1, 0, 0, 0, null, null)));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");
        SkillMetric writing = page.overview().skillMetrics().stream()
                .filter(row -> "WRITING".equals(row.skill()))
                .findFirst()
                .orElseThrow();
        SkillMetric speaking = page.overview().skillMetrics().stream()
                .filter(row -> "SPEAKING".equals(row.skill()))
                .findFirst()
                .orElseThrow();

        assertThat(writing.scoreFact().availability())
                .isEqualTo(ProgressAvailability.NOT_SCORABLE);
        assertThat(writing.scoreFact().coverage().activityCount()).isEqualTo(3);
        assertThat(writing.scoreFact().coverage().eligibleCount()).isZero();
        assertThat(writing.scoreFact().coverage().excludedCount()).isEqualTo(3);
        assertThat(writing.scoreFact().coverage().exclusions())
                .extracting(exclusion -> exclusion.reason(), exclusion -> exclusion.activityCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ProgressExclusionReason
                                        .WRITING_SKILL_AGGREGATION_REQUIRES_TASK_COHORT,
                                1L),
                        org.assertj.core.groups.Tuple.tuple(
                                ProgressExclusionReason
                                        .SCORE_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY,
                                2L));
        assertThat(speaking.scoreFact().availability())
                .isEqualTo(ProgressAvailability.NOT_SCORABLE);
        assertThat(speaking.normalizedScore()).isNull();
        assertThat(speaking.scoreFact().coverage().activityCount()).isEqualTo(2);
        assertThat(speaking.scoreFact().coverage().eligibleCount()).isZero();
        assertThat(speaking.scoreFact().coverage().excludedCount()).isEqualTo(2);
        assertThat(speaking.scoreFact().coverage().exclusions())
                .extracting(exclusion -> exclusion.reason(), exclusion -> exclusion.activityCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ProgressExclusionReason
                                        .SPEAKING_NUMERIC_AGGREGATION_NOT_SUPPORTED,
                                1L),
                        org.assertj.core.groups.Tuple.tuple(
                                ProgressExclusionReason
                                        .SCORE_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY,
                                1L));
    }

    @Test
    void recentHistoryUsesImmutableVersionIdentityAndLabelsIncompleteLocks() {
        PracticeAttempt locked =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        locked.lockPublishedVersion(10L, 11L, 12L, 13L);
        locked.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        setTemporalField(
                locked,
                "submittedAt",
                LocalDateTime.parse("2026-07-14T10:00:00"));
        PracticeAttempt incomplete =
                new PracticeAttempt(USER_ID, 4L, 5L, "LISTENING", 6L);
        incomplete.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        setTemporalField(
                incomplete,
                "submittedAt",
                LocalDateTime.parse("2026-07-14T09:00:00"));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(locked, incomplete));

        PracticeSetVersion set = mock(PracticeSetVersion.class);
        PracticePublishedVersion published = mock(PracticePublishedVersion.class);
        when(published.getId()).thenReturn(10L);
        when(published.getSetId()).thenReturn(1L);
        when(set.getId()).thenReturn(11L);
        when(set.getPublishedVersionId()).thenReturn(10L);
        when(set.getSetId()).thenReturn(1L);
        when(set.getTitle()).thenReturn("Set snapshot");
        PracticeTestVersion test = mock(PracticeTestVersion.class);
        when(test.getId()).thenReturn(12L);
        when(test.getPublishedVersionId()).thenReturn(10L);
        when(test.getSetVersionId()).thenReturn(11L);
        when(test.getTestId()).thenReturn(2L);
        when(test.getTitle()).thenReturn("Test snapshot");
        PracticeSectionVersion section = mock(PracticeSectionVersion.class);
        when(section.getId()).thenReturn(13L);
        when(section.getPublishedVersionId()).thenReturn(10L);
        when(section.getTestVersionId()).thenReturn(12L);
        when(section.getSectionId()).thenReturn(3L);
        when(section.getSkill()).thenReturn("READING");
        when(section.getTitle()).thenReturn("Section snapshot");
        when(publishedVersionRepository.findAllById(any())).thenReturn(List.of(published));
        when(setVersionRepository.findAllById(any())).thenReturn(List.of(set));
        when(testVersionRepository.findAllById(any())).thenReturn(List.of(test));
        when(sectionVersionRepository.findAllById(any())).thenReturn(List.of(section));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.analytics().history().get(0).title())
                .isEqualTo("Set snapshot - Test snapshot - Section snapshot");
        assertThat(page.analytics().history().get(0).identityAvailability())
                .isEqualTo(ProgressAvailability.AVAILABLE);
        assertThat(page.analytics().history().get(0).state())
                .isEqualTo("SCORED");
        assertThat(page.analytics().history().get(0).resumable())
                .isFalse();
        assertThat(page.analytics().history().get(0).resultEligible())
                .isTrue();
        assertThat(page.analytics().history().get(1).title())
                .startsWith("Lượt luyện tập lịch sử");
        assertThat(page.analytics().history().get(1).identityReason())
                .isEqualTo(ProgressExclusionReason.INCOMPLETE_VERSION_LOCK);
        assertThat(page.analytics().history().get(1).resultEligible())
                .isFalse();
    }

    @Test
    void mismatchedCompleteIdentityCannotEnterRecentScoreTrendOrHistoryScore() {
        PracticeAttempt mismatched =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        mismatched.lockPublishedVersion(10L, 11L, 12L, 13L);
        mismatched.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(mismatched));

        PracticePublishedVersion published = mock(PracticePublishedVersion.class);
        when(published.getId()).thenReturn(10L);
        when(published.getSetId()).thenReturn(1L);
        PracticeSetVersion wrongSet = mock(PracticeSetVersion.class);
        when(wrongSet.getId()).thenReturn(11L);
        when(wrongSet.getPublishedVersionId()).thenReturn(10L);
        when(wrongSet.getSetId()).thenReturn(999L);
        PracticeTestVersion test = mock(PracticeTestVersion.class);
        when(test.getId()).thenReturn(12L);
        PracticeSectionVersion section = mock(PracticeSectionVersion.class);
        when(section.getId()).thenReturn(13L);
        when(publishedVersionRepository.findAllById(any())).thenReturn(List.of(published));
        when(setVersionRepository.findAllById(any())).thenReturn(List.of(wrongSet));
        when(testVersionRepository.findAllById(any())).thenReturn(List.of(test));
        when(sectionVersionRepository.findAllById(any())).thenReturn(List.of(section));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.overview().recentAverageScore()).isNull();
        assertThat(page.overview().recentScoreFact().sampleSize()).isZero();
        assertThat(page.analytics().scoreTrend()).isEmpty();
        assertThat(page.analytics().history()).singleElement().satisfies(row -> {
            assertThat(row.identityReason())
                    .isEqualTo(ProgressExclusionReason.LEGACY_UNVERIFIED);
            assertThat(row.resultEligible()).isFalse();
            assertThat(row.score()).isNull();
            assertThat(row.totalPoints()).isNull();
            assertThat(row.scoreFact().value()).isNull();
            assertThat(row.scoreFact().coverage().exclusions())
                    .singleElement()
                    .extracting(exclusion -> exclusion.reason())
                    .isEqualTo(ProgressExclusionReason.LEGACY_UNVERIFIED);
        });
    }

    @Test
    void incompatibleTerminalAttemptNeverAdvertisesResultLink() {
        PracticeAttempt incompatible =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        incompatible.lockPublishedVersion(10L, 11L, 12L, 13L);
        incompatible.setVersionCompatibilityStatus("STALE");
        incompatible.markGraded(BigDecimal.ONE, BigDecimal.TEN, "{}", "{}");
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID),
                eq(PracticeAttempt.STATUS_DISCARDED),
                any(Pageable.class)))
                .thenReturn(List.of(incompatible));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.analytics().history())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.state()).isEqualTo("SCORED");
                    assertThat(row.resultEligible()).isFalse();
                });
    }

    @Test
    void allTimeSkillWindowsUseTheirOwnObservedRangeAndAsOf() {
        LocalDateTime readingFrom = LocalDateTime.parse("2026-07-01T08:00:00");
        LocalDateTime readingTo = LocalDateTime.parse("2026-07-02T08:00:00");
        LocalDateTime speakingFrom = LocalDateTime.parse("2026-07-20T08:00:00");
        LocalDateTime speakingTo = LocalDateTime.parse("2026-07-21T08:00:00");
        when(attemptRepository.findProgressAllTimeBySkill(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(List.of(
                        skill("READING", 1, 1, 0, 0, 1, 0,
                                BigDecimal.ONE, BigDecimal.TEN,
                                readingFrom, readingTo),
                        skill("SPEAKING", 2, 2, 0, 0, 0, 0,
                                null, null, speakingFrom, speakingTo)));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");
        SkillMetric reading = page.overview().skillMetrics().stream()
                .filter(metric -> "READING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();
        SkillMetric speaking = page.overview().skillMetrics().stream()
                .filter(metric -> "SPEAKING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();

        assertThat(reading.observationWindow().observedFrom()).isEqualTo(readingFrom);
        assertThat(reading.observationWindow().observedTo()).isEqualTo(readingTo);
        assertThat(reading.observationWindow().lastObservedAt()).isEqualTo(readingTo);
        assertThat(reading.observationWindow().asOf())
                .isEqualTo(LocalDateTime.parse("2026-07-25T02:00:00"));
        assertThat(speaking.observationWindow().observedFrom()).isEqualTo(speakingFrom);
        assertThat(speaking.observationWindow().observedTo()).isEqualTo(speakingTo);
        assertThat(speaking.observationWindow().lastObservedAt()).isEqualTo(speakingTo);
        assertThat(speaking.observationWindow())
                .isNotSameAs(reading.observationWindow());
    }

    @Test
    void weeklySkillWindowsAreIndependentWithinTheGlobalRecentSourceContext() {
        LocalDateTime readingActivity =
                LocalDateTime.parse("2026-07-20T09:00:00");
        LocalDateTime writingActivity =
                LocalDateTime.parse("2026-07-22T11:15:00");
        LocalDateTime listeningActivity =
                LocalDateTime.parse("2026-07-24T10:30:00");
        LocalDateTime speakingActivity =
                LocalDateTime.parse("2026-07-23T08:45:00");
        PracticeAttempt reading =
                new PracticeAttempt(USER_ID, 1L, 2L, "READING", 3L);
        PracticeAttempt writing =
                new PracticeAttempt(USER_ID, 1L, 2L, "WRITING", 3L);
        PracticeAttempt listening =
                new PracticeAttempt(USER_ID, 1L, 2L, "LISTENING", 3L);
        PracticeAttempt speaking =
                new PracticeAttempt(USER_ID, 1L, 2L, "SPEAKING", 3L);
        setTemporalField(reading, "updatedAt", readingActivity);
        setTemporalField(writing, "updatedAt", writingActivity);
        setTemporalField(listening, "updatedAt", listeningActivity);
        setTemporalField(speaking, "updatedAt", speakingActivity);
        when(attemptRepository.findProgressAllTime(
                USER_ID, PracticeAttempt.STATUS_DISCARDED))
                .thenReturn(allTime(101, 0, 101, 0, 0, 0, 0));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(listening, speaking, writing, reading));

        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");
        SkillMetric readingMetric = page.analytics().weeklySkillMetrics().stream()
                .filter(metric -> "READING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();
        SkillMetric listeningMetric = page.analytics().weeklySkillMetrics().stream()
                .filter(metric -> "LISTENING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();
        SkillMetric writingMetric = page.analytics().weeklySkillMetrics().stream()
                .filter(metric -> "WRITING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();
        SkillMetric speakingMetric = page.analytics().weeklySkillMetrics().stream()
                .filter(metric -> "SPEAKING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();

        assertThat(page.analytics().recentDetailWindow().truncated()).isTrue();
        assertThat(page.analytics().recentDetailWindow().limit()).isEqualTo(100);
        assertThat(page.analytics().recentDetailWindow().returnedCount()).isEqualTo(4);
        assertThat(page.analytics().recentDetailWindow().asOf())
                .isEqualTo(LocalDateTime.parse("2026-07-25T02:00:00"));
        assertThat(readingMetric.observationWindow().code())
                .isEqualTo("CURRENT_7_DAYS_READING_WITHIN_RECENT_DETAIL_LAST_100");
        assertThat(readingMetric.observationWindow().label())
                .contains(
                        "Cửa sổ 7 ngày",
                        "trong nguồn tối đa 100 hoạt động gần đây");
        assertThat(readingMetric.observationWindow().bounded()).isTrue();
        assertThat(readingMetric.observationWindow().limit()).isEqualTo(100);
        assertThat(readingMetric.observationWindow().returnedCount()).isEqualTo(1);
        assertThat(readingMetric.observationWindow().observedFrom())
                .isEqualTo(readingActivity);
        assertThat(readingMetric.observationWindow().observedTo())
                .isEqualTo(readingActivity);
        assertThat(readingMetric.observationWindow().lastObservedAt())
                .isEqualTo(readingActivity);
        assertThat(readingMetric.observationWindow().asOf())
                .isEqualTo(page.analytics().recentDetailWindow().asOf());
        assertThat(readingMetric.observationWindow().truncated()).isTrue();
        assertThat(readingMetric.scoreFact().observationWindow())
                .isEqualTo(readingMetric.observationWindow());

        assertThat(listeningMetric.observationWindow().code())
                .isEqualTo("CURRENT_7_DAYS_LISTENING_WITHIN_RECENT_DETAIL_LAST_100");
        assertThat(listeningMetric.observationWindow().bounded()).isTrue();
        assertThat(listeningMetric.observationWindow().limit()).isEqualTo(100);
        assertThat(listeningMetric.observationWindow().returnedCount()).isEqualTo(1);
        assertThat(listeningMetric.observationWindow().observedFrom())
                .isEqualTo(listeningActivity);
        assertThat(listeningMetric.observationWindow().observedTo())
                .isEqualTo(listeningActivity);
        assertThat(listeningMetric.observationWindow().lastObservedAt())
                .isEqualTo(listeningActivity);
        assertThat(listeningMetric.observationWindow().asOf())
                .isEqualTo(page.analytics().recentDetailWindow().asOf());
        assertThat(listeningMetric.observationWindow().truncated()).isTrue();
        assertThat(listeningMetric.observationWindow())
                .isNotSameAs(readingMetric.observationWindow());

        assertThat(writingMetric.observationWindow().code())
                .isEqualTo("CURRENT_7_DAYS_WRITING_WITHIN_RECENT_DETAIL_LAST_100");
        assertThat(writingMetric.observationWindow().bounded()).isTrue();
        assertThat(writingMetric.observationWindow().limit()).isEqualTo(100);
        assertThat(writingMetric.observationWindow().returnedCount()).isEqualTo(1);
        assertThat(writingMetric.observationWindow().observedFrom())
                .isEqualTo(writingActivity);
        assertThat(writingMetric.observationWindow().observedTo())
                .isEqualTo(writingActivity);
        assertThat(writingMetric.observationWindow().lastObservedAt())
                .isEqualTo(writingActivity);
        assertThat(writingMetric.observationWindow().asOf())
                .isEqualTo(page.analytics().recentDetailWindow().asOf());
        assertThat(writingMetric.observationWindow().truncated()).isTrue();
        assertThat(writingMetric.scoreFact().observationWindow())
                .isEqualTo(writingMetric.observationWindow());
        assertThat(writingMetric.scoreFact().coverage().activityCount()).isEqualTo(1);
        assertThat(writingMetric.scoreFact().coverage().eligibleCount()).isZero();
        assertThat(writingMetric.scoreFact().coverage().excludedCount()).isEqualTo(1);
        assertThat(writingMetric.scoreFact().coverage().activityCount()).isEqualTo(
                writingMetric.scoreFact().coverage().eligibleCount()
                        + writingMetric.scoreFact().coverage().excludedCount());
        assertThat(writingMetric.scoreFact().coverage().exclusions())
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.reason()).isEqualTo(
                            ProgressExclusionReason
                                    .SCORE_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY);
                    assertThat(exclusion.activityCount()).isEqualTo(1);
                });
        assertThat(writingMetric.observationWindow())
                .isNotSameAs(readingMetric.observationWindow());

        assertThat(speakingMetric.observationWindow().code())
                .isEqualTo("CURRENT_7_DAYS_SPEAKING_WITHIN_RECENT_DETAIL_LAST_100");
        assertThat(speakingMetric.observationWindow().bounded()).isTrue();
        assertThat(speakingMetric.observationWindow().limit()).isEqualTo(100);
        assertThat(speakingMetric.observationWindow().returnedCount()).isEqualTo(1);
        assertThat(speakingMetric.observationWindow().observedFrom())
                .isEqualTo(speakingActivity);
        assertThat(speakingMetric.observationWindow().observedTo())
                .isEqualTo(speakingActivity);
        assertThat(speakingMetric.observationWindow().lastObservedAt())
                .isEqualTo(speakingActivity);
        assertThat(speakingMetric.observationWindow().asOf())
                .isEqualTo(page.analytics().recentDetailWindow().asOf());
        assertThat(speakingMetric.observationWindow().truncated()).isTrue();
        assertThat(speakingMetric.normalizedScore()).isNull();
        assertThat(speakingMetric.scoreFact().availability())
                .isEqualTo(ProgressAvailability.NOT_SCORABLE);
        assertThat(speakingMetric.scoreFact().coverage().activityCount()).isEqualTo(1);
        assertThat(speakingMetric.scoreFact().coverage().eligibleCount()).isZero();
        assertThat(speakingMetric.scoreFact().coverage().excludedCount()).isEqualTo(1);
        assertThat(speakingMetric.scoreFact().coverage().activityCount()).isEqualTo(
                speakingMetric.scoreFact().coverage().eligibleCount()
                        + speakingMetric.scoreFact().coverage().excludedCount());
        assertThat(speakingMetric.scoreFact().coverage().exclusions())
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.reason()).isEqualTo(
                            ProgressExclusionReason
                                    .SCORE_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY);
                    assertThat(exclusion.activityCount()).isEqualTo(1);
                });
        assertThat(page.analytics().history())
                .filteredOn(row -> "WRITING".equals(row.skill())
                        || "SPEAKING".equals(row.skill()))
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.score()).isNull();
                    assertThat(row.totalPoints()).isNull();
                    assertThat(row.scoreFact().coverage().activityCount()).isEqualTo(1);
                    assertThat(row.scoreFact().coverage().eligibleCount()).isZero();
                    assertThat(row.scoreFact().coverage().excludedCount()).isEqualTo(1);
                    assertThat(row.scoreFact().coverage().exclusions())
                            .singleElement()
                            .extracting(exclusion -> exclusion.reason())
                            .isEqualTo(
                                    ProgressExclusionReason
                                            .SCORE_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY);
                });
    }

    @Test
    void emptyWritingTaskCohortsAreUnavailableRatherThanDeferredOrZero() {
        PracticeProgressPageData page = service.getProgressPageData(
                USER_ID, "Learner", "");

        assertThat(page.analytics().writingTaskSeams())
                .extracting(seam -> seam.taskType())
                .containsExactly("Q51", "Q52", "Q53", "Q54");
        assertThat(page.analytics().writingTaskSeams())
                .allSatisfy(seam -> {
                    assertThat(seam.availability())
                            .isEqualTo(ProgressAvailability.UNAVAILABLE);
                    assertThat(seam.cohorts()).isEmpty();
                    assertThat(seam.coverage().activityCount()).isZero();
                    assertThat(seam.coverage().eligibleCount()).isZero();
                    assertThat(seam.coverage().excludedCount()).isZero();
                    assertThat(seam.coverage().exclusions())
                            .singleElement()
                            .extracting(exclusion -> exclusion.reason())
                            .isEqualTo(ProgressExclusionReason.NO_ACTIVITY);
                });
        assertThat(page.analytics().writingAttemptCoverage().activityCount()).isZero();
        assertThat(page.analytics().writingAttemptCoverage().exclusions())
                .singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(ProgressExclusionReason.NO_ACTIVITY);
    }

    @Test
    void writingUsesImmutablePerQuestionEvidenceInsteadOfRepeatingAttemptScore()
            throws Exception {
        PracticeAttempt attempt = writingAttempt(writingPayload(Map.of(
                101L, currentWritingEntry(WritingTaskType.Q51, 8),
                103L, currentWritingEntry(WritingTaskType.Q53, 15))));
        attempt.markGraded(
                new BigDecimal("99"), new BigDecimal("40"), "{}",
                attempt.getAiFeedbackJson());
        PracticeQuestionVersion q51 = writingQuestion(
                101L, WritingTaskType.Q51, new BigDecimal("10"));
        PracticeQuestionVersion q53 = writingQuestion(
                103L, WritingTaskType.Q53, new BigDecimal("30"));
        stubWritingEvidence(List.of(attempt), List.of(q51, q53));
        when(attemptRepository.findRecentProgressAttempts(
                eq(USER_ID), eq(PracticeAttempt.STATUS_DISCARDED), any(Pageable.class)))
                .thenReturn(List.of(attempt));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");
        var q51Cohort = writingTask(page, "Q51").cohorts().get(0);
        var q53Cohort = writingTask(page, "Q53").cohorts().get(0);

        assertThat(q51Cohort.scoreFact().value()).isEqualByComparingTo("80.00");
        assertThat(q51Cohort.scoreFact().numerator()).isEqualByComparingTo("8");
        assertThat(q51Cohort.scoreFact().denominator()).isEqualByComparingTo("10");
        assertThat(q53Cohort.scoreFact().value()).isEqualByComparingTo("50.00");
        assertThat(q53Cohort.scoreFact().numerator()).isEqualByComparingTo("15");
        assertThat(q53Cohort.scoreFact().denominator()).isEqualByComparingTo("30");
        assertThat(q51Cohort.scoreFact().value()).isNotEqualByComparingTo("99");
        assertThat(q53Cohort.scoreFact().value()).isNotEqualByComparingTo("99");
        assertThat(q51Cohort.policyBundleId())
                .isEqualTo("KSH_WRITING_POLICY_BUNDLE_V3");
        assertThat(q51Cohort.scoringProfileId())
                .isEqualTo(
                        "WRITING:TASK_NATIVE_RUBRIC_V1:"
                                + "KSH_WRITING_EVALUATOR_V3:"
                                + "BUNDLE=KSH_WRITING_POLICY_BUNDLE_V3");
        assertThat(q51Cohort.cohortId())
                .contains("BUNDLE=KSH_WRITING_POLICY_BUNDLE_V3");
        assertThat(page.analytics().writingAttemptCoverage().eligibleCount())
                .isEqualTo(1);
        assertThat(page.analytics().history()).singleElement().satisfies(row -> {
            assertThat(row.score()).isNull();
            assertThat(row.totalPoints()).isNull();
            assertThat(row.scoreFact().value()).isNull();
        });
    }

    @Test
    void deterministicInvalidWritingZeroRemainsEligibleCurrentEvidence() {
        ObjectNode invalid = currentWritingEntry(
                WritingTaskType.Q51, 0);
        invalid.put("evaluation_status", "INVALID_LEARNER_RESPONSE");
        invalid.put("evaluation_source", "BACKEND_RULE");
        invalid.put("evaluation_reason", "BLANK_ANSWER");
        PracticeAttempt attempt = writingAttempt(
                writingPayload(Map.of(151L, invalid)));
        stubWritingEvidence(
                List.of(attempt),
                List.of(writingQuestion(
                        151L,
                        WritingTaskType.Q51,
                        BigDecimal.TEN)));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");
        var q51 = writingTask(page, "Q51");

        assertThat(q51.coverage().eligibleCount()).isEqualTo(1);
        assertThat(q51.coverage().excludedCount()).isZero();
        assertThat(q51.cohorts()).singleElement().satisfies(cohort -> {
            assertThat(cohort.scoreFact().value())
                    .isEqualByComparingTo("0.00");
            assertThat(cohort.scoreFact().numerator())
                    .isEqualByComparingTo("0");
            assertThat(cohort.scoreFact().denominator())
                    .isEqualByComparingTo("10");
        });
    }

    @Test
    void writingKeepsOnlyTheExactCurrentBundleInCurrentCohorts() {
        ObjectNode incompatible = currentWritingEntry(
                WritingTaskType.Q53, 20);
        incompatible.put("raw_score_max", 40);
        incompatible.put("policy_bundle_id", "bundle-b");
        PracticeAttempt attempt = writingAttempt(writingPayload(Map.of(
                201L, currentWritingEntry(WritingTaskType.Q53, 21),
                202L, incompatible)));
        stubWritingEvidence(
                List.of(attempt),
                List.of(
                        writingQuestion(
                                201L, WritingTaskType.Q53, new BigDecimal("30")),
                        writingQuestion(
                                202L, WritingTaskType.Q53, new BigDecimal("40"))));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");
        var q53 = writingTask(page, "Q53");

        assertThat(q53.cohorts()).singleElement()
                .satisfies(cohort -> assertThat(cohort.policyBundleId())
                        .isEqualTo("KSH_WRITING_POLICY_BUNDLE_V3"));
        assertThat(q53.cohorts().get(0).maximum())
                .isEqualByComparingTo("30");
        assertThat(q53.cohorts())
                .extracting(cohort -> cohort.scoreFact().value())
                .containsExactly(new BigDecimal("70.00"));
        assertThat(q53.cohorts())
                .allSatisfy(cohort -> {
                    assertThat(cohort.scoreFact().sampleSize()).isEqualTo(1);
                    assertThat(cohort.scoreFact().coverage().excludedCount())
                            .isZero();
                });
        assertThat(q53.coverage().activityCount()).isEqualTo(2);
        assertThat(q53.coverage().eligibleCount()).isEqualTo(1);
        assertThat(q53.coverage().excludedCount()).isEqualTo(1);
        assertThat(q53.coverage().exclusions()).singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(
                        ProgressExclusionReason.WRITING_LEGACY_SCORE_EVIDENCE);
    }

    @Test
    void writingMismatchAndUnavailableEvidenceAreExcludedNotZeroed() {
        PracticeAttempt attempt = writingAttempt("""
                {
                  "301":{
                    "raw_score":8,"raw_score_max":10,
                    "task_type":"Q52","engine":"KSH_WRITING_EVALUATOR_V2",
                    "scoring_contract":"TASK_NATIVE_RUBRIC_V1",
                    "policy_bundle_id":"KSH_WRITING_POLICY_BUNDLE_V2",
                    "evaluation_status":"EVALUATED","evaluation_source":"PROVIDER",
                    "evaluation_reason":"NONE","evaluation_retryable":false,
                    "score_available":true,
                    "result_completeness":{
                      "version":"practice-ai-result-completeness-v1",
                      "status":"COMPLETE","reason_code":"NONE",
                      "rejected_item_count":0}
                  },
                  "302":{
                    "task_type":"Q52","engine":"KSH_WRITING_EVALUATOR_STATUS",
                    "policy_bundle_id":"KSH_WRITING_POLICY_BUNDLE_V2",
                    "evaluation_status":"EVALUATION_UNAVAILABLE",
                    "evaluation_source":"PROVIDER","evaluation_reason":"HTTP_ERROR",
                    "evaluation_retryable":true,"score_available":false,
                    "result_completeness":{
                      "version":"practice-ai-result-completeness-v1",
                      "status":"UNAVAILABLE","reason_code":"HTTP_ERROR",
                      "rejected_item_count":0}
                  }
                }
                """);
        stubWritingEvidence(
                List.of(attempt),
                List.of(
                        writingQuestion(
                                301L, WritingTaskType.Q51, new BigDecimal("10")),
                        writingQuestion(
                                302L, WritingTaskType.Q52, new BigDecimal("10"))));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");

        assertWritingExclusion(
                page, "Q51", ProgressExclusionReason.WRITING_TASK_IDENTITY_MISMATCH);
        assertWritingExclusion(
                page,
                "Q52",
                ProgressExclusionReason.WRITING_EVALUATION_NOT_SCORE_BEARING);
        assertThat(page.analytics().writingAttemptCoverage().eligibleCount()).isZero();
        assertThat(page.analytics().writingAttemptCoverage().excludedCount())
                .isEqualTo(1);
        assertThat(page.analytics().writingTaskSeams())
                .flatExtracting(seam -> seam.cohorts())
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("excludedWritingEvidenceCases")
    void writingEvidenceFailuresUseTypedExclusionsWithoutCreatingZeroCohorts(
            String caseName,
            String feedbackJson,
            ProgressExclusionReason reason
    ) {
        PracticeAttempt attempt = writingAttempt(feedbackJson);
        stubWritingEvidence(
                List.of(attempt),
                List.of(writingQuestion(
                        401L, WritingTaskType.Q51, new BigDecimal("10"))));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");

        assertWritingExclusion(page, "Q51", reason);
        assertThat(page.analytics().writingAttemptCoverage().eligibleCount()).isZero();
        assertThat(page.analytics().writingAttemptCoverage().excludedCount())
                .isEqualTo(1);
        assertThat(page.analytics().writingAttemptCoverage().exclusions())
                .singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(reason);
        SkillMetric writing = page.overview().skillMetrics().stream()
                .filter(metric -> "WRITING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();
        assertThat(writing.normalizedScore()).isNull();
        assertThat(writing.scoreFact().value()).isNull();
        assertThat(writing.scoreFact().numerator()).isNull();
        assertThat(writing.scoreFact().denominator()).isNull();
    }

    @Test
    void missingImmutableWritingTaskIsExcludedWithoutGuessingFromFeedback() {
        PracticeAttempt attempt = writingAttempt("""
                {
                  "405":{
                    "raw_score":8,"raw_score_max":10,
                    "task_type":"Q51","engine":"KSH_WRITING_EVALUATOR_V2",
                    "scoring_contract":"TASK_NATIVE_RUBRIC_V1",
                    "policy_bundle_id":"KSH_WRITING_POLICY_BUNDLE_V2",
                    "evaluation_status":"EVALUATED","evaluation_source":"PROVIDER",
                    "evaluation_reason":"NONE","score_available":true
                  }
                }
                """);
        PracticeQuestionVersion question = mock(PracticeQuestionVersion.class);
        when(question.getPublishedVersionId()).thenReturn(10L);
        when(question.getSectionVersionId()).thenReturn(13L);
        when(question.getQuestionType()).thenReturn(PracticeQuestion.TYPE_ESSAY);
        when(question.getWritingTaskType()).thenReturn(null);
        stubWritingEvidence(
                List.of(attempt),
                List.of(question));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");

        assertThat(page.analytics().writingAttemptCoverage().eligibleCount()).isZero();
        assertThat(page.analytics().writingAttemptCoverage().excludedCount())
                .isEqualTo(1);
        assertThat(page.analytics().writingAttemptCoverage().exclusions())
                .singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(ProgressExclusionReason.WRITING_TASK_IDENTITY_MISSING);
        assertThat(page.analytics().writingTaskSeams())
                .allSatisfy(seam -> {
                    assertThat(seam.cohorts()).isEmpty();
                    assertThat(seam.coverage().activityCount()).isZero();
                });
    }

    @Test
    void incompleteWritingVersionLockIsExcludedWithoutGuessingTaskFromFeedback() {
        PracticeAttempt legacy =
                new PracticeAttempt(USER_ID, 1L, 2L, "WRITING", 3L);
        legacy.markGraded(
                new BigDecimal("88"),
                new BigDecimal("100"),
                "{}",
                """
                {"raw_score":8,"raw_score_max":10,"task_type":"Q51",
                 "engine":"KSH_WRITING_EVALUATOR_V2",
                 "scoring_contract":"TASK_NATIVE_RUBRIC_V1",
                 "evaluation_status":"EVALUATED","evaluation_source":"PROVIDER",
                 "score_available":true}
                """);
        when(attemptRepository.findProgressWritingAttempts(
                eq(USER_ID),
                eq(PracticeAttempt.STATUS_DISCARDED),
                any(Pageable.class)))
                .thenReturn(List.of(legacy));

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");

        assertThat(page.analytics().writingAttemptCoverage().activityCount())
                .isEqualTo(1);
        assertThat(page.analytics().writingAttemptCoverage().eligibleCount()).isZero();
        assertThat(page.analytics().writingAttemptCoverage().excludedCount())
                .isEqualTo(1);
        assertThat(page.analytics().writingAttemptCoverage().exclusions())
                .singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(ProgressExclusionReason.INCOMPLETE_VERSION_LOCK);
        assertThat(page.analytics().writingTaskSeams())
                .allSatisfy(seam -> assertThat(seam.coverage().activityCount())
                        .isZero());
        verify(questionVersionRepository, never())
                .findBySectionVersionIdInOrderBySectionVersionIdAscDisplayOrderAscQuestionNoAscIdAsc(
                        any());
    }

    @Test
    void writingEvidenceIsBoundedAndReportsTruncationAtRealisticVolume() {
        List<PracticeAttempt> attempts = IntStream.rangeClosed(
                        1, PracticeProgressService.WRITING_DETAIL_LIMIT + 1)
                .mapToObj(index -> new PracticeAttempt(
                        USER_ID,
                        10_000L + index,
                        20_000L + index,
                        "WRITING",
                        30_000L + index))
                .toList();
        when(attemptRepository.findProgressWritingAttempts(
                USER_ID,
                PracticeAttempt.STATUS_DISCARDED,
                PageRequest.of(
                        0,
                        PracticeProgressService.WRITING_DETAIL_LIMIT + 1)))
                .thenReturn(attempts);

        PracticeProgressPageData page =
                service.getProgressPageData(USER_ID, "Learner", "");

        assertThat(page.analytics().writingAttemptCoverage().activityCount())
                .isEqualTo(PracticeProgressService.WRITING_DETAIL_LIMIT);
        assertThat(page.analytics().writingAttemptCoverage().excludedCount())
                .isEqualTo(PracticeProgressService.WRITING_DETAIL_LIMIT);
        assertThat(page.analytics().writingTaskSeams())
                .allSatisfy(seam -> {
                    assertThat(seam.observationWindow().bounded()).isTrue();
                    assertThat(seam.observationWindow().limit())
                            .isEqualTo(
                                    PracticeProgressService.WRITING_DETAIL_LIMIT);
                    assertThat(seam.observationWindow().returnedCount())
                            .isEqualTo(
                                    PracticeProgressService.WRITING_DETAIL_LIMIT);
                    assertThat(seam.observationWindow().truncated()).isTrue();
                    assertThat(seam.observationWindow().code())
                            .startsWith("RECENT_WRITING_SOURCE_");
                    assertThat(seam.observationWindow().label())
                            .contains(
                                    "Nguồn chọn chung",
                                    "500 hoạt động Writing gần nhất");
                });
        verify(attemptRepository).findProgressWritingAttempts(
                USER_ID,
                PracticeAttempt.STATUS_DISCARDED,
                PageRequest.of(
                        0,
                        PracticeProgressService.WRITING_DETAIL_LIMIT + 1));
    }

    private static Stream<Arguments> excludedWritingEvidenceCases() {
        return Stream.of(
                Arguments.of(
                        "missing per-question entry",
                        "{}",
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MISSING),
                Arguments.of(
                        "malformed per-question entry",
                        """
                        {"401":{"raw_score":"bad","raw_score_max":10,
                        "task_type":"Q51"}}
                        """,
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "evaluated backend-rule cross-pair",
                        mutatedWritingPayload(node ->
                                node.put("evaluation_source", "BACKEND_RULE")),
                        ProgressExclusionReason.WRITING_SCORING_PROFILE_UNSUPPORTED),
                Arguments.of(
                        "invalid learner response provider cross-pair",
                        mutatedWritingPayload(node -> {
                            node.put("raw_score", 0);
                            node.put("evaluation_status",
                                    "INVALID_LEARNER_RESPONSE");
                            node.put("evaluation_source", "PROVIDER");
                            node.put("evaluation_reason", "BLANK_ANSWER");
                        }),
                        ProgressExclusionReason.WRITING_SCORING_PROFILE_UNSUPPORTED),
                Arguments.of(
                        "missing evaluation reason",
                        mutatedWritingPayload(node ->
                                node.remove("evaluation_reason")),
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "non-textual evaluation reason",
                        mutatedWritingPayload(node ->
                                node.put("evaluation_reason", 7)),
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "missing evaluation retryable",
                        mutatedWritingPayload(node ->
                                node.remove("evaluation_retryable")),
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "non-boolean evaluation retryable",
                        mutatedWritingPayload(node ->
                                node.put("evaluation_retryable", "false")),
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "score-bearing evaluation marked retryable",
                        mutatedWritingPayload(node ->
                                node.put("evaluation_retryable", true)),
                        ProgressExclusionReason.WRITING_SCORING_PROFILE_UNSUPPORTED),
                Arguments.of(
                        "evaluated provenance uses an unsupported reason",
                        mutatedWritingPayload(node ->
                                node.put("evaluation_reason", "CACHE_HIT")),
                        ProgressExclusionReason.WRITING_SCORING_PROFILE_UNSUPPORTED),
                Arguments.of(
                        "invalid learner provenance uses an unsupported reason",
                        mutatedWritingPayload(node -> {
                            node.put("raw_score", 0);
                            node.put("evaluation_status",
                                    "INVALID_LEARNER_RESPONSE");
                            node.put("evaluation_source", "BACKEND_RULE");
                            node.put("evaluation_reason", "PROVIDER_ERROR");
                        }),
                        ProgressExclusionReason.WRITING_SCORING_PROFILE_UNSUPPORTED),
                Arguments.of(
                        "missing score available",
                        mutatedWritingPayload(node ->
                                node.remove("score_available")),
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "non-boolean score available",
                        mutatedWritingPayload(node ->
                                node.put("score_available", "true")),
                        ProgressExclusionReason.WRITING_SCORE_EVIDENCE_MALFORMED),
                Arguments.of(
                        "unsupported scoring profile",
                        mutatedWritingPayload(node ->
                                node.put("scoring_contract",
                                        "TASK_NATIVE_RUBRIC_V2")),
                        ProgressExclusionReason.WRITING_SCORING_PROFILE_UNSUPPORTED),
                Arguments.of(
                        "immutable maximum mismatch",
                        mutatedWritingPayload(node ->
                                node.put("raw_score_max", 20)),
                        ProgressExclusionReason.WRITING_MAXIMUM_MISMATCH),
                Arguments.of(
                        "missing task identity in stored evidence",
                        mutatedWritingPayload(node ->
                                node.remove("task_type")),
                        ProgressExclusionReason.WRITING_TASK_IDENTITY_MISSING));
    }

    @Test
    void unavailablePageStateSerializesAsTypedStateRatherThanEmptyObject() throws Exception {
        PracticeProgressPageData unavailable = PracticeProgressPageData.unavailable(
                "Learner", "", ProgressExclusionReason.SERIALIZATION_UNAVAILABLE);

        String json = productionLikeObjectMapper().writeValueAsString(unavailable);

        assertThat(json).isNotEqualTo("{}");
        assertThat(json).contains(
                "\"availability\":\"UNAVAILABLE\"",
                "\"reason\":\"SERIALIZATION_UNAVAILABLE\"",
                "\"scoreTrend\":[]",
                "\"recentHistory\":[]");
    }

    private static String mutatedWritingPayload(
            Consumer<ObjectNode> mutation) {
        ObjectNode node = currentWritingEntry(
                WritingTaskType.Q51, 8);
        mutation.accept(node);
        return writingPayload(Map.of(401L, node));
    }

    private static String writingPayload(
            Map<Long, ObjectNode> entries) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        new LinkedHashMap<>(entries).forEach(
                (questionId, entry) ->
                        root.set(String.valueOf(questionId), entry));
        return root.toString();
    }

    private static ObjectNode currentWritingEntry(
            WritingTaskType taskType,
            int rawScore) {
        ObjectMapper mapper = new ObjectMapper();
        String learnerAnswer =
                WritingContractTestFixtures.scoreBearingLearnerAnswer(
                        taskType.name(), rawScore);
        String normalized = WritingContractTestFixtures.normalizedFeedback(
                mapper,
                taskType.name(),
                learnerAnswer,
                envelope -> WritingContractTestFixtures.applyRawScore(
                        envelope,
                        taskType.name(),
                        learnerAnswer,
                        rawScore));
        try {
            return (ObjectNode) mapper.readTree(normalized);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PracticeAttempt writingAttempt(String feedbackJson) {
        PracticeAttempt attempt =
                new PracticeAttempt(USER_ID, 1L, 2L, "WRITING", 3L);
        attempt.lockPublishedVersion(10L, 11L, 12L, 13L);
        attempt.markGraded(
                new BigDecimal("73"),
                new BigDecimal("100"),
                "{}",
                feedbackJson);
        return attempt;
    }

    private PracticeQuestionVersion writingQuestion(
            long questionId,
            WritingTaskType task,
            BigDecimal points
    ) {
        PracticeQuestionVersion question = mock(PracticeQuestionVersion.class);
        when(question.getPublishedVersionId()).thenReturn(10L);
        when(question.getSectionVersionId()).thenReturn(13L);
        when(question.getQuestionId()).thenReturn(questionId);
        when(question.getQuestionType()).thenReturn(PracticeQuestion.TYPE_ESSAY);
        when(question.getWritingTaskType()).thenReturn(task);
        lenient().when(question.getPoints()).thenReturn(points);
        return question;
    }

    private void stubWritingEvidence(
            List<PracticeAttempt> attempts,
            List<PracticeQuestionVersion> questions
    ) {
        PracticePublishedVersion published = mock(PracticePublishedVersion.class);
        when(published.getId()).thenReturn(10L);
        when(published.getSetId()).thenReturn(1L);
        PracticeSetVersion set = mock(PracticeSetVersion.class);
        when(set.getId()).thenReturn(11L);
        when(set.getPublishedVersionId()).thenReturn(10L);
        when(set.getSetId()).thenReturn(1L);
        PracticeTestVersion test = mock(PracticeTestVersion.class);
        when(test.getId()).thenReturn(12L);
        when(test.getPublishedVersionId()).thenReturn(10L);
        when(test.getSetVersionId()).thenReturn(11L);
        when(test.getTestId()).thenReturn(2L);
        PracticeSectionVersion section = mock(PracticeSectionVersion.class);
        when(section.getId()).thenReturn(13L);
        when(section.getPublishedVersionId()).thenReturn(10L);
        when(section.getTestVersionId()).thenReturn(12L);
        when(section.getSectionId()).thenReturn(3L);
        when(section.getSkill()).thenReturn("WRITING");
        when(publishedVersionRepository.findAllById(any()))
                .thenReturn(List.of(published));
        when(setVersionRepository.findAllById(any())).thenReturn(List.of(set));
        when(testVersionRepository.findAllById(any())).thenReturn(List.of(test));
        when(sectionVersionRepository.findAllById(any()))
                .thenReturn(List.of(section));
        when(questionVersionRepository
                .findBySectionVersionIdInOrderBySectionVersionIdAscDisplayOrderAscQuestionNoAscIdAsc(
                        any())).thenReturn(questions);
        when(attemptRepository.findProgressWritingAttempts(
                eq(USER_ID),
                eq(PracticeAttempt.STATUS_DISCARDED),
                any(Pageable.class)))
                .thenReturn(attempts);
    }

    private void stubCanonicalVersionIdentity() {
        PracticePublishedVersion published =
                mock(PracticePublishedVersion.class);
        when(published.getId()).thenReturn(10L);
        when(published.getSetId()).thenReturn(1L);
        PracticeSetVersion set = mock(PracticeSetVersion.class);
        when(set.getId()).thenReturn(11L);
        when(set.getPublishedVersionId()).thenReturn(10L);
        when(set.getSetId()).thenReturn(1L);
        PracticeTestVersion test = mock(PracticeTestVersion.class);
        when(test.getId()).thenReturn(12L);
        when(test.getPublishedVersionId()).thenReturn(10L);
        when(test.getSetVersionId()).thenReturn(11L);
        when(test.getTestId()).thenReturn(2L);
        PracticeSectionVersion section =
                mock(PracticeSectionVersion.class);
        when(section.getId()).thenReturn(13L);
        when(section.getPublishedVersionId()).thenReturn(10L);
        when(section.getTestVersionId()).thenReturn(12L);
        when(section.getSectionId()).thenReturn(3L);
        when(section.getSkill()).thenReturn("READING");
        when(publishedVersionRepository.findAllById(any()))
                .thenReturn(List.of(published));
        when(setVersionRepository.findAllById(any()))
                .thenReturn(List.of(set));
        when(testVersionRepository.findAllById(any()))
                .thenReturn(List.of(test));
        when(sectionVersionRepository.findAllById(any()))
                .thenReturn(List.of(section));
    }

    private WritingTaskProgressSeam writingTask(
            PracticeProgressPageData page,
            String task
    ) {
        return page.analytics().writingTaskSeams().stream()
                .filter(seam -> task.equals(seam.taskType()))
                .findFirst()
                .orElseThrow();
    }

    private void assertWritingExclusion(
            PracticeProgressPageData page,
            String task,
            ProgressExclusionReason reason
    ) {
        WritingTaskProgressSeam seam = writingTask(page, task);
        assertThat(seam.availability()).isEqualTo(ProgressAvailability.UNAVAILABLE);
        assertThat(seam.cohorts()).isEmpty();
        assertThat(seam.coverage().activityCount()).isEqualTo(1);
        assertThat(seam.coverage().eligibleCount()).isZero();
        assertThat(seam.coverage().excludedCount()).isEqualTo(1);
        assertThat(seam.coverage().exclusions())
                .singleElement()
                .extracting(exclusion -> exclusion.reason())
                .isEqualTo(reason);
    }

    private PracticeAttemptRepository.ProgressAllTimeProjection allTime(
            long total,
            long completed,
            long inProgress,
            long other,
            long validDurations,
            long excludedDurations,
            long totalMinutes
    ) {
        return new PracticeAttemptRepository.ProgressAllTimeProjection() {
            @Override public Long getActivityCount() { return total; }
            @Override public Long getCompletedCount() { return completed; }
            @Override public Long getInProgressCount() { return inProgress; }
            @Override public Long getOtherCount() { return other; }
            @Override public Long getValidDurationCount() { return validDurations; }
            @Override public Long getExcludedDurationCount() { return excludedDurations; }
            @Override public Long getTotalValidMinutes() { return totalMinutes; }
            @Override public java.time.LocalDateTime getObservedFrom() { return null; }
            @Override public java.time.LocalDateTime getObservedTo() { return null; }
            @Override public java.time.LocalDateTime getAsOf() { return null; }
        };
    }

    private ObjectMapper productionLikeObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private PracticeAttemptRepository.ProgressSkillProjection skill(
            String skill,
            long total,
            long completed,
            long inProgress,
            long other,
            long eligible,
            long excluded,
            BigDecimal earned,
            BigDecimal possible
    ) {
        return skill(
                skill, total, completed, inProgress, other, eligible, excluded,
                earned, possible, null, null);
    }

    private PracticeAttemptRepository.ProgressSkillProjection skill(
            String skill,
            long total,
            long completed,
            long inProgress,
            long other,
            long eligible,
            long excluded,
            BigDecimal earned,
            BigDecimal possible,
            LocalDateTime observedFrom,
            LocalDateTime observedTo
    ) {
        return new PracticeAttemptRepository.ProgressSkillProjection() {
            @Override public String getSkill() { return skill; }
            @Override public Long getActivityCount() { return total; }
            @Override public Long getCompletedCount() { return completed; }
            @Override public Long getInProgressCount() { return inProgress; }
            @Override public Long getOtherCount() { return other; }
            @Override public Long getEligibleScoreCount() { return eligible; }
            @Override public Long getExcludedScoreCount() { return excluded; }
            @Override public BigDecimal getEarnedPoints() { return earned; }
            @Override public BigDecimal getPossiblePoints() { return possible; }
            @Override public LocalDateTime getObservedFrom() { return observedFrom; }
            @Override public LocalDateTime getObservedTo() { return observedTo; }
            @Override public LocalDateTime getAsOf() { return null; }
        };
    }

    private void setTemporalField(
            PracticeAttempt attempt,
            String fieldName,
            LocalDateTime value
    ) {
        try {
            java.lang.reflect.Field field =
                    PracticeAttempt.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(attempt, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
