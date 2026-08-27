package com.ksh.features.practice;

import com.fasterxml.jackson.databind.JsonNode;
import com.ksh.entities.PracticeQuestion;
import com.ksh.entities.PracticeSet;
import com.ksh.entities.User;
import com.ksh.entities.ClassEntity;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.practice.ai.readinglistening.ReadingListeningExplanationClient;
import com.ksh.features.practice.ai.readinglistening.QuestionExplanationRetryService;
import com.ksh.features.practice.ai.writing.WritingAssessmentPolicyBundle;
import com.ksh.features.practice.ai.writing.WritingContractTestFixtures;
import com.ksh.features.practice.ai.writing.WritingEvaluationClient;
import com.ksh.features.practice.ai.writing.WritingEvaluationNormalizer;
import com.ksh.features.practice.ai.writing.WritingScoringPolicy;
import com.ksh.features.practice.repository.PracticeQuestionRepository;
import com.ksh.features.practice.repository.PracticeSetRepository;
import com.ksh.features.practice.repository.PracticeAttemptRepository;
import com.ksh.features.practice.repository.PracticeTestRepository;
import com.ksh.features.practice.repository.PracticeSectionRepository;
import com.ksh.entities.PracticeAttempt;
import com.ksh.entities.PracticeTest;
import com.ksh.entities.PracticeSection;
import com.ksh.entities.PracticeQuestionGroup;
import com.ksh.entities.PracticeSpeakingMedia;
import com.ksh.entities.PracticeSpeakingMediaStatus;
import com.ksh.entities.PracticeSpeakingStorageProvider;
import com.ksh.entities.QuestionExplanationArtifact;
import com.ksh.entities.QuestionExplanationGenerationTask;
import com.ksh.entities.QuestionVersionExplanationBinding;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.repository.PracticeQuestionGroupRepository;
import com.ksh.features.practice.repository.PracticeSpeakingMediaCleanupTaskRepository;
import com.ksh.features.practice.repository.PracticeSpeakingMediaRepository;
import com.ksh.features.practice.repository.QuestionExplanationArtifactRepository;
import com.ksh.features.practice.repository.QuestionExplanationGenerationTaskRepository;
import com.ksh.features.practice.repository.QuestionVersionExplanationBindingRepository;
import com.ksh.features.practice.service.PracticeAttemptConflictException;
import com.ksh.features.practice.service.PracticeAttemptDiscardService;
import com.ksh.features.practice.service.PracticeAttemptEvaluationJobTransactions;
import com.ksh.features.practice.service.PracticeAttemptEvaluationOutcome;
import com.ksh.features.practice.service.PracticeAttemptDeadlineProcessor;
import com.ksh.features.practice.service.PracticeAttemptDeadlineTransactions;
import com.ksh.features.practice.service.PracticeAttemptStatePolicy;
import com.ksh.features.practice.service.PracticeDetailPageService;
import com.ksh.features.practice.service.PracticePublishedVersionService;
import com.ksh.features.practice.service.PracticeProgressService;
import com.ksh.features.practice.service.PracticeService;
import com.ksh.features.practice.result.PracticeResultAssembler;
import com.ksh.features.practice.result.PracticeResultDetailAssembler;
import com.ksh.features.practice.manage.service.PracticePublisherService;
import com.ksh.features.practice.manage.service.PublishedPracticeGraphMutationBlockedException;
import com.ksh.features.practice.dto.PracticeDtos.PracticeSetTestCard;
import com.ksh.features.practice.dto.PracticeDtos.PracticeSkillAttemptCard;
import com.ksh.features.practice.dto.PracticeDtos.PracticeCatalogBatch;
import com.ksh.features.practice.dto.PracticeDtos.PracticeCatalogCard;
import com.ksh.features.practice.dto.PracticeDtos.PracticeQuestionRow;
import com.ksh.features.practice.dto.PracticeDtos.PracticeSetView;
import com.ksh.features.practice.dto.PracticeDtos.ProgressFilterState;
import com.ksh.features.practice.dto.PracticeDtos.ObjectiveDetailPayload;
import com.ksh.features.practice.dto.PracticeDtos.ObjectiveSingleChoiceDetail;
import com.ksh.features.practice.dto.PracticeDtos.SpeakingDetailPayload;
import com.ksh.features.practice.dto.PracticeDtos.WritingDetailPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PracticeIntegrationTest {

    private static final String
            PRODUCTION_SHAPED_WRITING_CONTRACT_IDENTITY =
            "ksh-writing-evaluation-v2|"
                    + "policy-component|".repeat(34);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PracticeSetRepository setRepository;

    @Autowired
    private ClassRepository classRepository;

    @Autowired
    private PracticeQuestionRepository questionRepository;

    @Autowired
    private PracticeAttemptRepository attemptRepository;

    @Autowired
    private com.ksh.features.practice.repository
            .PracticeAttemptEvaluationJobRepository
            attemptEvaluationJobRepository;

    @Autowired
    private PracticeTestRepository testRepository;

    @Autowired
    private PracticeQuestionGroupRepository groupRepository;

    @Autowired
    private PracticeSectionRepository sectionRepository;

    @Autowired
    private com.ksh.features.practice.repository.PracticeSetVersionRepository
            setVersionRepository;

    @Autowired
    private com.ksh.features.practice.repository.PracticeTestVersionRepository
            testVersionRepository;

    @Autowired
    private com.ksh.features.practice.repository.PracticeSectionVersionRepository
            sectionVersionRepository;

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private PracticeAttemptEvaluationJobTransactions
            attemptEvaluationJobTransactions;

    @Autowired
    private PracticeProgressService progressService;

    @Autowired
    private PracticeResultAssembler resultAssembler;

    @Autowired
    private PracticeResultDetailAssembler resultDetailAssembler;

    @Autowired
    private PracticeDetailPageService detailPageService;

    @Autowired
    private PracticePublishedVersionService publishedVersionService;

    @Autowired
    private com.ksh.features.practice.repository.PracticePublishedVersionRepository publishedVersionRepository;

    @Autowired
    private com.ksh.features.practice.repository.PracticeQuestionVersionRepository
            questionVersionRepository;

    @Autowired
    private QuestionVersionExplanationBindingRepository
            explanationBindingRepository;

    @Autowired
    private QuestionExplanationArtifactRepository
            explanationArtifactRepository;

    @Autowired
    private QuestionExplanationGenerationTaskRepository
            explanationTaskRepository;

    @Autowired
    private QuestionExplanationRetryService explanationRetryService;

    @Autowired
    private PracticeAttemptDiscardService attemptDiscardService;

    @Autowired
    private PracticeAttemptDeadlineTransactions
            attemptDeadlineTransactions;

    @Autowired
    private PracticePublisherService publisherService;

    @Autowired
    private com.ksh.features.practice.ai.readinglistening
            .ObjectiveExplanationEditorialService
            objectiveExplanationEditorialService;

    @Autowired
    private PracticeSpeakingMediaRepository speakingMediaRepository;

    @Autowired
    private PracticeSpeakingMediaCleanupTaskRepository cleanupTaskRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private com.ksh.features.practice.repository.PracticeDraftRepository draftRepository;

    @Autowired
    private com.ksh.features.practice.repository.PracticeEditLogRepository editLogRepository;

    @Autowired
    private com.ksh.features.practice.manage.service.PracticeRevisionService revisionService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private WritingEvaluationClient writingEvaluationClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ksh.features.practice.ai.speaking
            .SpeakingEvaluationClient speakingEvaluationClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ksh.features.practice.ai.speaking.transcription
            .SpeakingTranscriptionClient speakingTranscriptionClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ReadingListeningExplanationClient readingListeningExplanationClient;

    private User student;
    private User lecturer;
    private PracticeSet practiceSet;
    private PracticeQuestion question;
    private PracticeTest defaultTest;
    private PracticeSection defaultSection;

    @BeforeEach
    void setUp() {
        student = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();

        attemptRepository.deleteAll();
        when(writingEvaluationClient.evaluationContractIdentity())
                .thenReturn(
                        PRODUCTION_SHAPED_WRITING_CONTRACT_IDENTITY);
        when(readingListeningExplanationClient.model()).thenReturn("test-rl-model");
        when(readingListeningExplanationClient.promptVersion()).thenReturn("prompt-v1");
        when(readingListeningExplanationClient.schemaVersion()).thenReturn("schema-v1");
        when(readingListeningExplanationClient.explanationLanguage()).thenReturn("vi");
        when(readingListeningExplanationClient.cleanAndValidateJson(
                anyString(), any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Seed a published practice set
        practiceSet = new PracticeSet(
                "TOPIK II - Đọc hiểu 35",
                "Mô tả đề thi đọc hiểu TOPIK II kì 35",
                "READING",
                "GLOBAL",
                null,
                "practice-pdfs/test.pdf",
                "{}",
                "PUBLISHED",
                lecturer.getId()
        );
        practiceSet = setRepository.saveAndFlush(practiceSet);

        // Seed a default test
        defaultTest = new PracticeTest(practiceSet.getId(), "Test 1", "Desc", 1, 40);
        defaultTest = testRepository.saveAndFlush(defaultTest);

        // Seed a default section
        defaultSection = new PracticeSection(practiceSet.getId(), "Phần Đọc", "READING", "SINGLE_CHOICE", "Đọc kỹ", 40, BigDecimal.TEN, 1);
        defaultSection.setTestId(defaultTest.getId());
        defaultSection = sectionRepository.saveAndFlush(defaultSection);

        PracticeQuestionGroup defaultGroup = groupRepository.saveAndFlush(
                new PracticeQuestionGroup(practiceSet.getId(), "Phần Đọc", 1, 1,
                        "Đọc kỹ", null, null, 1));
        defaultGroup.setSectionId(defaultSection.getId());
        defaultGroup = groupRepository.saveAndFlush(defaultGroup);

        // Seed a question for the set
        question = new PracticeQuestion(
                practiceSet.getId(),
                1,
                "SINGLE_CHOICE",
                "Câu hỏi 1",
                "[\"Đáp án A\", \"Đáp án B\"]",
                "1",
                "Giải thích đáp án đúng",
                BigDecimal.valueOf(2.5),
                0
        );
        question.setGroupId(defaultGroup.getId());
        question = questionRepository.saveAndFlush(question);

        publishVersion(practiceSet.getId());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testIndexAuthenticated() throws Exception {
        mockMvc.perform(get("/practice"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/index"))
                .andExpect(model().attributeExists("catalog"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    PracticeCatalogCard card = catalog.items().stream()
                            .filter(item -> practiceSet.getId().equals(item.id()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(card.testCount()).isEqualTo(1);
                    assertThat(card.completedTests()).isZero();
                    assertThat(card.state()).isEqualTo("NOT_STARTED");
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0/1")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void catalogPersistsSearchesAndRendersExactVietnameseKoreanUtf8() throws Exception {
        String exactTitle = "Luyện đọc tiếng Hàn · 한국어 읽기 연습";
        String exactDescription =
                "Giữ nguyên dấu tiếng Việt và 한글 từ cơ sở dữ liệu đến HTML.";
        practiceSet.setTitle(exactTitle);
        practiceSet.setDescription(exactDescription);
        setRepository.saveAndFlush(practiceSet);
        entityManager.clear();

        PracticeSet reloaded = setRepository.findById(practiceSet.getId())
                .orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo(exactTitle);
        assertThat(reloaded.getDescription()).isEqualTo(exactDescription);

        mockMvc.perform(get("/practice").param("q", "한국어 읽기"))
                .andExpect(status().isOk())
                .andExpect(content().encoding("UTF-8"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(exactTitle)));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void indexCountsARealTestOnlyAfterEverySectionIsCompleted() throws Exception {
        PracticeSection listeningSection = new PracticeSection(
                practiceSet.getId(), "Phần Nghe", "LISTENING", "SINGLE_CHOICE",
                "Nghe kỹ", 40, BigDecimal.TEN, 2);
        listeningSection.setTestId(defaultTest.getId());
        listeningSection = sectionRepository.saveAndFlush(listeningSection);

        PracticeAttempt readingAttempt = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(),
                "READING", defaultSection.getId());
        readingAttempt.markSubmitted(BigDecimal.TEN, BigDecimal.TEN, "{}");
        attemptRepository.saveAndFlush(readingAttempt);

        mockMvc.perform(get("/practice"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    PracticeCatalogCard card = catalog.items().stream()
                            .filter(item -> practiceSet.getId().equals(item.id()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(card.completedTests()).isZero();
                });

        PracticeAttempt listeningAttempt = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(),
                "LISTENING", listeningSection.getId());
        listeningAttempt.markGraded(BigDecimal.TEN, BigDecimal.TEN, "{}", "{}");
        attemptRepository.saveAndFlush(listeningAttempt);

        mockMvc.perform(get("/practice"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    PracticeCatalogCard card = catalog.items().stream()
                            .filter(item -> practiceSet.getId().equals(item.id()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(card.completedTests()).isEqualTo(1);
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1/1")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void catalogFiltersOnServerAndNavigatesRealBoundedPages() throws Exception {
        String marker = "Lazy catalog " + System.nanoTime();
        for (int index = 1; index <= 13; index++) {
            setRepository.saveAndFlush(new PracticeSet(
                    marker + " " + index,
                    "Bộ đề kiểm tra lazy loading",
                    PracticeSet.SKILL_READING,
                    PracticeSet.SCOPE_GLOBAL,
                    null,
                    null,
                    "{}",
                    PracticeSet.STATUS_PUBLISHED,
                    lecturer.getId()));
        }

        mockMvc.perform(get("/practice")
                        .param("q", marker)
                        .param("skill", PracticeSet.SKILL_READING))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/index"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    assertThat(catalog.search()).isEqualTo(marker);
                    assertThat(catalog.skill()).isEqualTo(PracticeSet.SKILL_READING);
                    assertThat(catalog.items()).hasSize(12);
                    assertThat(catalog.totalElements()).isEqualTo(13);
                    assertThat(catalog.hasMore()).isTrue();
                    assertThat(catalog.nextBatch()).isEqualTo(1);
                })
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("pc-pagination")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("batch=1")));

        mockMvc.perform(get("/practice")
                        .param("q", marker)
                        .param("skill", PracticeSet.SKILL_READING)
                        .param("batch", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/index"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch)
                            result.getModelAndView().getModel().get("catalog");
                    assertThat(catalog.items()).hasSize(1);
                    assertThat(catalog.batch()).isEqualTo(1);
                    assertThat(catalog.hasPrevious()).isTrue();
                    assertThat(catalog.hasMore()).isFalse();
                    assertThat(catalog.firstItemNumber()).isEqualTo(13);
                    assertThat(catalog.lastItemNumber()).isEqualTo(13);
                })
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("batch=0")));

        mockMvc.perform(get("/practice")
                        .param("q", marker)
                        .param("skill", PracticeSet.SKILL_READING)
                        .param("batch", "999"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/index"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch)
                            result.getModelAndView().getModel().get("catalog");
                    assertThat(catalog.items()).hasSize(1);
                    assertThat(catalog.batch()).isEqualTo(1);
                    assertThat(catalog.firstItemNumber()).isEqualTo(13);
                    assertThat(catalog.lastItemNumber()).isEqualTo(13);
                    assertThat(catalog.totalElements()).isEqualTo(13);
                })
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "0–0 trên 13"))));

        // The bounded fragment route remains compatible for authorized callers,
        // but the learner page no longer depends on JavaScript to reach it.
        mockMvc.perform(get("/practice/catalog")
                        .param("q", marker)
                        .param("skill", PracticeSet.SKILL_READING)
                        .param("batch", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/fragments/catalog-cards :: cards"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    assertThat(catalog.items()).hasSize(1);
                    assertThat(catalog.hasMore()).isFalse();
                });

        mockMvc.perform(get("/practice")
                        .param("q", marker)
                        .param("skill", PracticeSet.SKILL_SPEAKING))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    assertThat(catalog.items()).isEmpty();
                    assertThat(catalog.totalElements()).isZero();
                });
    }

    @Test
    void catalogStateProjectionBoundsTenThousandAttemptHistoryToOneCandidate() {
        PracticeSet volumeSet = setRepository.saveAndFlush(new PracticeSet(
                "Bộ đề kiểm tra lịch sử lớn",
                "Bằng chứng query Phase 13G",
                PracticeSet.SKILL_READING,
                PracticeSet.SCOPE_GLOBAL,
                null,
                null,
                "{}",
                PracticeSet.STATUS_PUBLISHED,
                lecturer.getId()));
        PracticeTest volumeTest = testRepository.saveAndFlush(new PracticeTest(
                volumeSet.getId(), "Test lịch sử lớn", null, 1, 40));
        PracticeSection volumeSection = new PracticeSection(
                volumeSet.getId(), "Phần Đọc", PracticeSet.SKILL_READING,
                "SINGLE_CHOICE", null, 40, BigDecimal.TEN, 1);
        volumeSection.setTestId(volumeTest.getId());
        volumeSection = sectionRepository.saveAndFlush(volumeSection);

        String digits = """
                (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2
                 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                 UNION ALL SELECT 9)
                """.strip();
        String insertHistory = """
                INSERT INTO practice_attempts (
                    user_id, set_id, test_id, skill, section_id,
                    status, analysis_status, started_at, deadline_at,
                    created_at, updated_at
                )
                SELECT
                    ?, ?, ?, 'READING', ?,
                    'IN_PROGRESS', 'NOT_REQUESTED',
                    DATE_ADD('2026-01-01 00:00:00',
                        INTERVAL numbers.n SECOND),
                    '2099-01-01 00:00:00',
                    DATE_ADD('2026-01-01 00:00:00',
                        INTERVAL numbers.n SECOND),
                    DATE_ADD('2026-01-01 00:00:00',
                        INTERVAL numbers.n SECOND)
                FROM (
                    SELECT ones.n
                         + tens.n * 10
                         + hundreds.n * 100
                         + thousands.n * 1000 AS n
                    FROM %s ones
                    CROSS JOIN %s tens
                    CROSS JOIN %s hundreds
                    CROSS JOIN %s thousands
                ) numbers
                ORDER BY numbers.n
                """.formatted(digits, digits, digits, digits);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.update(
                insertHistory,
                student.getId(),
                volumeSet.getId(),
                volumeTest.getId(),
                volumeSection.getId()))
                .isEqualTo(10_000);

        Long newestAttemptId = jdbc.queryForObject(
                """
                SELECT id
                FROM practice_attempts
                WHERE user_id = ?
                  AND set_id = ?
                ORDER BY activity_at DESC, id DESC
                LIMIT 1
                """,
                Long.class,
                student.getId(),
                volumeSet.getId());
        List<PracticeAttemptRepository.CatalogAttemptStateProjection> rows =
                attemptRepository.findCatalogAttemptStateCandidates(
                        student.getId(),
                        List.of(volumeSet.getId()),
                        PracticeAttempt.STATUS_DISCARDED);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getAttemptId()).isEqualTo(newestAttemptId);
            assertThat(row.getSetId()).isEqualTo(volumeSet.getId());
            assertThat(row.getStatePriority()).isEqualTo(1);
        });
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void globalResumeSurvivesCurrentSearchSkillPageAndIsNotRenderedByLazyFragment()
            throws Exception {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        String absentSearch = "không-khớp-" + System.nanoTime();

        mockMvc.perform(get("/practice")
                        .param("q", absentSearch)
                        .param("skill", PracticeSet.SKILL_SPEAKING))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    PracticeCatalogBatch catalog =
                            (PracticeCatalogBatch) result.getModelAndView()
                                    .getModel().get("catalog");
                    assertThat(catalog.items()).isEmpty();
                    assertThat(catalog.globalResume()).isNotNull();
                    assertThat(catalog.globalResume().attemptId())
                            .isEqualTo(attemptId);
                    assertThat(catalog.globalResume().setTitle())
                            .isEqualTo(practiceSet.getTitle());
                })
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "pc-resume-banner")));

        mockMvc.perform(get("/practice/catalog")
                        .param("q", absentSearch)
                        .param("skill", PracticeSet.SKILL_SPEAKING)
                        .param("batch", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name(
                        "practice/fragments/catalog-cards :: cards"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "pc-resume-banner"))));
    }

    @Test
    void globalResumeRepositoryUsesIdAsStableTieBreakForEqualActivityTime() {
        var lock = publishedVersionService.latestLock(
                        practiceSet.getId(),
                        defaultTest.getId(),
                        defaultSection.getId())
                .orElseThrow();
        PracticeAttempt first = new PracticeAttempt(
                student.getId(),
                practiceSet.getId(),
                defaultTest.getId(),
                "READING",
                defaultSection.getId());
        first.lockPublishedVersion(
                lock.publishedVersionId(),
                lock.setVersionId(),
                lock.testVersionId(),
                lock.sectionVersionId());
        first = attemptRepository.saveAndFlush(first);
        PracticeAttempt second = new PracticeAttempt(
                student.getId(),
                practiceSet.getId(),
                defaultTest.getId(),
                "READING",
                defaultSection.getId());
        second.lockPublishedVersion(
                lock.publishedVersionId(),
                lock.setVersionId(),
                lock.testVersionId(),
                lock.sectionVersionId());
        second = attemptRepository.saveAndFlush(second);
        LocalDateTime tied = LocalDateTime.parse("2026-07-25T12:00:00");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                "UPDATE practice_attempts SET updated_at = ? WHERE id IN (?, ?)",
                tied, first.getId(), second.getId());
        Long expectedNewestId = Math.max(first.getId(), second.getId());

        List<PracticeAttemptRepository.GlobalResumeProjection> candidates =
                attemptRepository.findGlobalResumeCandidates(
                        student.getId(),
                        List.of(-1L),
                        LocalDateTime.now(),
                        org.springframework.data.domain.PageRequest.of(0, 1));

        assertThat(candidates).singleElement().satisfies(candidate ->
                assertThat(candidate.getAttemptId())
                        .isEqualTo(expectedNewestId));
    }

    @Test
    void expiredAttemptIsExcludedFromGlobalAndSharedResumePolicy() {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        PracticeAttempt attempt =
                attemptRepository.findById(attemptId).orElseThrow();
        attempt.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        attemptRepository.saveAndFlush(attempt);

        assertThat(attemptRepository.findGlobalResumeCandidates(
                student.getId(),
                List.of(-1L),
                LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, 1)))
                .isEmpty();
        assertThat(new PracticeAttemptStatePolicy()
                .resumeEligibility(attempt, true).rejection())
                .isEqualTo(
                        PracticeAttemptStatePolicy.ResumeRejection
                                .DEADLINE_EXPIRED);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void catalogAndDetailGetsRemainReadOnlyAndProviderFree()
            throws Exception {
        long attemptsBefore = attemptRepository.count();
        clearInvocations(
                writingEvaluationClient,
                readingListeningExplanationClient);

        mockMvc.perform(get("/practice"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/index"));
        mockMvc.perform(get(
                        "/practice/sets/" + practiceSet.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/set-detail"));
        mockMvc.perform(get(
                        "/practice/sets/" + practiceSet.getId()
                                + "/tests/" + defaultTest.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/test-detail"));

        assertThat(attemptRepository.count()).isEqualTo(attemptsBefore);
        verifyNoInteractions(
                writingEvaluationClient,
                readingListeningExplanationClient);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void writingTaskCatalogFilterRoundTripsThroughInitialAndLazyAuthorizedPages()
            throws Exception {
        String marker = "Writing task catalog " + System.nanoTime();
        for (int index = 1; index <= 13; index++) {
            PracticeSet set = setRepository.saveAndFlush(new PracticeSet(
                    marker + " Q51 " + index,
                    "Bộ luyện viết theo tác vụ",
                    PracticeSet.SKILL_WRITING,
                    PracticeSet.SCOPE_GLOBAL,
                    null,
                    null,
                    "{}",
                    PracticeSet.STATUS_PUBLISHED,
                    lecturer.getId()));
            PracticeQuestion q51 = new PracticeQuestion(
                    set.getId(), 51, PracticeQuestion.TYPE_ESSAY, "Câu 51",
                    null, null, null, BigDecimal.TEN, 1);
            q51.setWritingTaskType(WritingTaskType.Q51);
            questionRepository.saveAndFlush(q51);
        }
        PracticeSet q54Set = setRepository.saveAndFlush(new PracticeSet(
                marker + " Q54",
                "Bộ luyện viết theo tác vụ",
                PracticeSet.SKILL_WRITING,
                PracticeSet.SCOPE_GLOBAL,
                null,
                null,
                "{}",
                PracticeSet.STATUS_PUBLISHED,
                lecturer.getId()));
        PracticeQuestion q54 = new PracticeQuestion(
                q54Set.getId(), 54, PracticeQuestion.TYPE_ESSAY, "Câu 54",
                null, null, null, BigDecimal.valueOf(50), 1);
        q54.setWritingTaskType(WritingTaskType.Q54);
        questionRepository.saveAndFlush(q54);

        mockMvc.perform(get("/practice")
                        .param("q", marker)
                        .param("skill", "WRITING")
                        .param("writingTask", "Q51"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/index"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch)
                            result.getModelAndView().getModel().get("catalog");
                    assertThat(catalog.skill()).isEqualTo("WRITING");
                    assertThat(catalog.writingTask()).isEqualTo("Q51");
                    assertThat(catalog.items()).hasSize(12);
                    assertThat(catalog.totalElements()).isEqualTo(13);
                    assertThat(catalog.hasMore()).isTrue();
                })
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("name=\"writingTask\"")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("value=\"Q51\"")));

        mockMvc.perform(get("/practice/catalog")
                        .param("q", marker)
                        .param("skill", "WRITING")
                        .param("writingTask", "Q51")
                        .param("batch", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/fragments/catalog-cards :: cards"))
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch)
                            result.getModelAndView().getModel().get("catalog");
                    assertThat(catalog.writingTask()).isEqualTo("Q51");
                    assertThat(catalog.items()).hasSize(1);
                    assertThat(catalog.hasMore()).isFalse();
                });

        mockMvc.perform(get("/practice")
                        .param("q", marker)
                        .param("skill", "WRITING")
                        .param("writingTask", "Q54"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch)
                            result.getModelAndView().getModel().get("catalog");
                    assertThat(catalog.items()).extracting(PracticeCatalogCard::id)
                            .containsExactly(q54Set.getId());
                });
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void unrelatedClassSetIsHiddenFromCatalogAndDirectLearnerRoutes() throws Exception {
        ClassEntity unrelatedClass = classRepository.saveAndFlush(new ClassEntity(
                "Lớp riêng " + System.nanoTime(),
                lecturer.getId(),
                lecturer.getId(),
                null,
                LocalDate.now(),
                LocalDate.now().plusMonths(1),
                30));
        String title = "Bộ đề lớp riêng " + System.nanoTime();
        PracticeSet classSet = setRepository.saveAndFlush(new PracticeSet(
                title,
                "Chỉ thành viên lớp được xem",
                PracticeSet.SKILL_READING,
                PracticeSet.SCOPE_CLASS,
                unrelatedClass.getId(),
                null,
                "{}",
                PracticeSet.STATUS_PUBLISHED,
                lecturer.getId()));
        PracticeTest classTest = testRepository.saveAndFlush(
                new PracticeTest(
                        classSet.getId(), "Test lớp riêng", null, 1, 30));
        PracticeSection classSection = new PracticeSection(
                classSet.getId(),
                "Phần Đọc lớp riêng",
                "READING",
                "SINGLE_CHOICE",
                null,
                30,
                BigDecimal.TEN,
                1);
        classSection.setTestId(classTest.getId());
        classSection = sectionRepository.saveAndFlush(classSection);
        publishVersion(classSet.getId());
        var lock = publishedVersionService.latestLock(
                        classSet.getId(),
                        classTest.getId(),
                        classSection.getId())
                .orElseThrow();
        PracticeAttempt unauthorizedAttempt = new PracticeAttempt(
                student.getId(),
                classSet.getId(),
                classTest.getId(),
                "READING",
                classSection.getId());
        unauthorizedAttempt.lockPublishedVersion(
                lock.publishedVersionId(),
                lock.setVersionId(),
                lock.testVersionId(),
                lock.sectionVersionId());
        attemptRepository.saveAndFlush(unauthorizedAttempt);

        mockMvc.perform(get("/practice").param("q", title))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    PracticeCatalogBatch catalog = (PracticeCatalogBatch) result.getModelAndView()
                            .getModel().get("catalog");
                    assertThat(catalog.items()).isEmpty();
                    assertThat(catalog.globalResume()).isNull();
                })
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data-set-id=\"" + classSet.getId() + "\""))));

        mockMvc.perform(get("/practice/sets/" + classSet.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testSetDetailView() throws Exception {
        mockMvc.perform(get("/practice/sets/" + practiceSet.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/set-detail"))
                .andExpect(model().attributeExists("view"))
                .andExpect(model().attributeExists("testCards", "setSkills"))
                .andExpect(model().attributeDoesNotExist("submissions"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void setDetailLinksUseActualPracticeTestIds() throws Exception {
        PracticeTest secondTest = testRepository.saveAndFlush(
                new PracticeTest(practiceSet.getId(), "Test riêng", "Bài luyện thứ hai", 2, 30));

        mockMvc.perform(get("/practice/sets/" + practiceSet.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/practice/sets/" + practiceSet.getId() + "/tests/" + defaultTest.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/practice/sets/" + practiceSet.getId() + "/tests/" + secondTest.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "testId=" + practiceSet.getId() + ")"))));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testSetDetailUsesPerTestProgressAndIgnoresOtherUsersAndSets() throws Exception {
        PracticeAttempt currentUserAttempt = new PracticeAttempt(student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        currentUserAttempt.markSubmitted(BigDecimal.valueOf(8.5), BigDecimal.TEN, "{\"" + question.getId() + "\":\"1\"}");
        attemptRepository.saveAndFlush(currentUserAttempt);

        PracticeAttempt otherUserAttempt = new PracticeAttempt(lecturer.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        otherUserAttempt.markSubmitted(BigDecimal.valueOf(9.5), BigDecimal.TEN, "{}");
        attemptRepository.saveAndFlush(otherUserAttempt);

        PracticeSet otherSet = setRepository.saveAndFlush(new PracticeSet(
                "Other Set", "Desc", "READING",  "GLOBAL", null, null, "{}", "PUBLISHED", lecturer.getId()));
        PracticeAttempt otherSetAttempt = new PracticeAttempt(student.getId(), otherSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        otherSetAttempt.markSubmitted(BigDecimal.valueOf(7.5), BigDecimal.TEN, "{}");
        attemptRepository.saveAndFlush(otherSetAttempt);

        var canonicalLock = publishedVersionService.latestLock(
                        practiceSet.getId(),
                        defaultTest.getId(),
                        defaultSection.getId())
                .orElseThrow();
        PracticeAttempt activeAttempt = new PracticeAttempt(student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        activeAttempt.lockPublishedVersion(
                canonicalLock.publishedVersionId(),
                canonicalLock.setVersionId(),
                canonicalLock.testVersionId(),
                canonicalLock.sectionVersionId());
        attemptRepository.saveAndFlush(activeAttempt);

        PracticeAttempt newestCurrentUserAttempt = new PracticeAttempt(student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        newestCurrentUserAttempt.markGraded(BigDecimal.valueOf(9.0), BigDecimal.TEN, "{\"" + question.getId() + "\":\"1\"}", "{}");
        attemptRepository.saveAndFlush(newestCurrentUserAttempt);

        mockMvc.perform(get("/practice/sets/" + practiceSet.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/set-detail"))
                .andExpect(result -> {
                    @SuppressWarnings("unchecked")
                    List<PracticeSetTestCard> cards = (List<PracticeSetTestCard>)
                            result.getModelAndView().getModel().get("testCards");
                    assertThat(cards).singleElement().satisfies(card -> {
                        assertThat(card.id()).isEqualTo(defaultTest.getId());
                        assertThat(card.completedSkillCount()).isEqualTo(1);
                        assertThat(card.totalSkillCount()).isEqualTo(1);
                        assertThat(card.state()).isEqualTo("IN_PROGRESS");
                        assertThat(card.resumeAttemptId()).isEqualTo(activeAttempt.getId());
                    });
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1/1 kỹ năng")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void retiredSubmissionResultRouteIsNotMapped() throws Exception {
        mockMvc.perform(get("/practice/submissions/123"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testTestDetailView() throws Exception {
        var canonicalLock = publishedVersionService.latestLock(
                        practiceSet.getId(),
                        defaultTest.getId(),
                        defaultSection.getId())
                .orElseThrow();
        PracticeAttempt oldest = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(),
                "READING", defaultSection.getId());
        oldest.lockPublishedVersion(
                canonicalLock.publishedVersionId(),
                canonicalLock.setVersionId(),
                canonicalLock.testVersionId(),
                canonicalLock.sectionVersionId());
        oldest.markSubmitted(BigDecimal.valueOf(6), BigDecimal.TEN, "{}");
        attemptRepository.saveAndFlush(oldest);

        PracticeAttempt middle = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(),
                "READING", defaultSection.getId());
        middle.lockPublishedVersion(
                canonicalLock.publishedVersionId(),
                canonicalLock.setVersionId(),
                canonicalLock.testVersionId(),
                canonicalLock.sectionVersionId());
        middle.markSubmitted(BigDecimal.valueOf(7), BigDecimal.TEN, "{}");
        attemptRepository.saveAndFlush(middle);

        PracticeAttempt newest = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(),
                "READING", defaultSection.getId());
        newest.lockPublishedVersion(
                canonicalLock.publishedVersionId(),
                canonicalLock.setVersionId(),
                canonicalLock.testVersionId(),
                canonicalLock.sectionVersionId());
        newest.markGraded(BigDecimal.valueOf(9), BigDecimal.TEN, "{}", "{}");
        attemptRepository.saveAndFlush(newest);

        PracticeAttempt inProgress = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(),
                "READING", defaultSection.getId());
        inProgress.lockPublishedVersion(
                canonicalLock.publishedVersionId(),
                canonicalLock.setVersionId(),
                canonicalLock.testVersionId(),
                canonicalLock.sectionVersionId());
        PracticeAttempt savedInProgress =
                attemptRepository.saveAndFlush(inProgress);

        mockMvc.perform(get("/practice/sets/" + practiceSet.getId() + "/tests/" + defaultTest.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/test-detail"))
                .andExpect(model().attributeExists("view"))
                .andExpect(model().attribute("selectedTest", new com.ksh.features.practice.dto.PracticeDtos.PracticeTestRow(
                        defaultTest.getId(), practiceSet.getId(), defaultTest.getTitle(), defaultTest.getDescription(),
                        defaultTest.getDisplayOrder(), defaultTest.getEstimatedMinutes())))
                .andExpect(model().attributeExists("skillCards", "speakingMediaUploadEnabled"))
                .andExpect(model().attributeDoesNotExist("sections", "submissions", "inProgressAttempts"))
                .andExpect(result -> {
                    @SuppressWarnings("unchecked")
                    List<PracticeSkillAttemptCard> cards = (List<PracticeSkillAttemptCard>)
                            result.getModelAndView().getModel().get("skillCards");
                    assertThat(cards).singleElement().satisfies(card -> {
                        assertThat(card.sectionId()).isEqualTo(defaultSection.getId());
                        assertThat(card.inProgressAttemptId())
                                .isEqualTo(savedInProgress.getId());
                        assertThat(card.completedAttempts()).hasSize(3);
                        assertThat(card.completedAttempts())
                                .extracting(attempt -> attempt.initiallyVisible())
                                .containsExactly(true, true, false);
                        assertThat(card.completedAttempts().get(0).id()).isEqualTo(newest.getId());
                        assertThat(card.completedAttempts().get(2).id()).isEqualTo(oldest.getId());
                        assertThat(card.latestScoreLabel()).isEqualTo("9/10");
                        assertThat(card.hiddenAttemptCount()).isEqualTo(1);
                    });
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-attempt-toggle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/practice/attempts/" + newest.getId() + "/result")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void retiredModeRouteIsNotMapped() throws Exception {
        mockMvc.perform(get("/practice/" + practiceSet.getId() + "/mode"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void retiredRoomRouteIsNotMapped() throws Exception {
        mockMvc.perform(get("/practice/" + practiceSet.getId() + "/room"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void resultBackLinkUsesAttemptTestId() throws Exception {
        mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + defaultTest.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(defaultSection.getId()))
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection());

        PracticeAttempt attempt = attemptRepository.findAll().get(0);
        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion()))
                        .param("answer_" + question.getId(), "1"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/practice/sets/" + practiceSet.getId() + "/tests/" + defaultTest.getId())));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void submitGetRedirectsBackToCanonicalPlayerInsteadOfReturning500()
            throws Exception {
        mockMvc.perform(post("/practice/sets/" + practiceSet.getId()
                        + "/tests/" + defaultTest.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(
                                defaultSection.getId()))
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection());

        PracticeAttempt attempt = attemptRepository.findAll().get(0);
        mockMvc.perform(get("/practice/attempts/" + attempt.getId()
                        + "/submit")
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/attempts/"
                        + attempt.getId() + "?mode=exam"))
                .andExpect(flash().attribute(
                        "warning",
                        "Hãy dùng nút Nộp bài trong phòng làm bài để gửi đáp án."));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testPlayerView() throws Exception {
        // Start attempt
        mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + defaultTest.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(defaultSection.getId()))
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/practice/attempts/*"));

        List<PracticeAttempt> attempts = attemptRepository.findAll();
        assertThat(attempts).isNotEmpty();
        PracticeAttempt attempt = attempts.get(0);

        Long lockedQuestionId = question.getId();
        questionRepository.delete(question);
        questionRepository.flush();
        PracticeQuestion replacement = new PracticeQuestion(
                practiceSet.getId(), 1, "SINGLE_CHOICE", "Live prompt must not replace locked content",
                "[\"Live A\",\"Live B\"]", "2", "Live explanation",
                BigDecimal.valueOf(2.5), 0);
        questionRepository.saveAndFlush(replacement);

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        get("/practice/attempts/" + attempt.getId()).param("mode", "exam"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/player"))
                .andExpect(model().attributeExists("view"))
                .andExpect(model().attribute("mode", "exam"))
                .andReturn();

        PracticeSetView playerView = (PracticeSetView) result.getModelAndView().getModel().get("view");
        PracticeQuestionRow deliveredQuestion = playerView.groups().get(0).questions().get(0);
        assertThat(deliveredQuestion.id()).isEqualTo(lockedQuestionId);
        assertThat(deliveredQuestion.prompt()).isEqualTo("Câu hỏi 1");
        assertThat(deliveredQuestion.answerKey()).isNull();
        assertThat(deliveredQuestion.explanation()).isNull();
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testSubmitAttemptAndGetResult() throws Exception {
        // Start attempt
        mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + defaultTest.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(defaultSection.getId()))
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection());

        List<PracticeAttempt> attempts = attemptRepository.findAll();
        assertThat(attempts).isNotEmpty();
        PracticeAttempt attempt = attempts.get(0);

        // Perform Submit
        String paramName = "answer_" + question.getId();
        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion()))
                        .param(paramName, "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/attempts/" + attempt.getId() + "/result"));

        // All skills share one canonical result shell.
        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result"))
                .andExpect(model().attributeExists("result"));

        // Perform GET detailed result view -> typed Objective Detail template
        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-objective"))
                .andExpect(model().attributeExists("resultDetail"));

        // Perform POST Re-evaluation
        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/re-evaluate")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/attempts/" + attempt.getId() + "/result"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testReadingResultGetsAreReadOnlyAndNeverInvokeProvider() throws Exception {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.markSubmitted(BigDecimal.ZERO, BigDecimal.valueOf(2.5), "{}");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result"));

        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-objective"));

        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result/detail")
                        .param("questionId", String.valueOf(question.getId())))
                .andExpect(status().isBadRequest());

        verify(readingListeningExplanationClient, never()).generate(any(), anyList());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void overviewAndDetailRejectIncoherentTerminalIdentityBeforePresenters()
            throws Exception {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        PracticeAttempt attempt =
                attemptRepository.findById(attemptId).orElseThrow();
        attempt.markSubmitted(
                BigDecimal.ZERO,
                BigDecimal.valueOf(2.5),
                "{}");
        PracticeSection alternate = new PracticeSection(
                practiceSet.getId(),
                "Phần Đọc không khớp snapshot",
                "READING",
                "SINGLE_CHOICE",
                "Đọc kỹ",
                40,
                BigDecimal.TEN,
                2);
        alternate.setTestId(defaultTest.getId());
        alternate = sectionRepository.saveAndFlush(alternate);
        attempt.setSectionId(alternate.getId());
        attemptRepository.saveAndFlush(attempt);
        clearInvocations(
                writingEvaluationClient,
                readingListeningExplanationClient);

        PracticeAttemptStatePolicy.PracticeResultNotAvailableException
                overviewRejection = assertThrows(
                PracticeAttemptStatePolicy
                        .PracticeResultNotAvailableException.class,
                () -> resultAssembler.assemble(attemptId, student.getId()));
        PracticeAttemptStatePolicy.PracticeResultNotAvailableException
                detailRejection = assertThrows(
                PracticeAttemptStatePolicy
                        .PracticeResultNotAvailableException.class,
                () -> resultDetailAssembler.assemble(
                        attemptId, student.getId(), null));

        assertThat(overviewRejection.getEligibility()).isEqualTo(
                PracticeAttemptStatePolicy.ResultEligibility
                        .INCONSISTENT_VERSION_IDENTITY);
        assertThat(detailRejection.getEligibility()).isEqualTo(
                PracticeAttemptStatePolicy.ResultEligibility
                        .INCONSISTENT_VERSION_IDENTITY);
        verifyNoInteractions(
                writingEvaluationClient,
                readingListeningExplanationClient);
    }

    private void publishVersion(Long setId) {
        List<PracticeQuestion> ungrouped = questionRepository
                .findBySetIdOrderByDisplayOrderAsc(setId).stream()
                .filter(candidate -> candidate.getGroupId() == null)
                .toList();
        if (!ungrouped.isEmpty()) {
            PracticeSection section = sectionRepository
                    .findBySetIdOrderByDisplayOrderAsc(setId).stream()
                    .findFirst()
                    .orElseThrow();
            PracticeQuestionGroup group = new PracticeQuestionGroup(
                    setId, "Fixture canonical group", 1, 1,
                    "Required immutable ownership for published fixtures", null, null, 1);
            group.setSectionId(section.getId());
            group = groupRepository.saveAndFlush(group);
            Long groupId = group.getId();
            ungrouped.forEach(candidate -> candidate.setGroupId(groupId));
            questionRepository.saveAllAndFlush(ungrouped);
        }
        publishedVersionService.createPublishedVersion(setId, lecturer.getId());
    }

    @Test
    void explanationBindingSupersessionKeepsHistoryAndExactlyOneActiveRow() {
        publishVersion(practiceSet.getId());
        Long publishedVersionId = publishedVersionRepository
                .findBySetIdOrderByVersionNumberDesc(practiceSet.getId())
                .get(0)
                .getId();
        Long questionVersionId = questionVersionRepository
                .findByPublishedVersionIdOrderBySectionVersionIdAscDisplayOrderAscQuestionNoAscIdAsc(
                        publishedVersionId)
                .get(0)
                .getId();
        String oldFingerprint = "a".repeat(63) + "1";
        String currentFingerprint = "b".repeat(63) + "2";
        explanationArtifactRepository.insertPendingIfAbsent(
                oldFingerprint,
                "READING",
                "SINGLE_CHOICE",
                "assessment-contract-v1",
                "test-rl-model",
                "prompt-v1",
                "schema-v1",
                "vi",
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                "{}");
        explanationArtifactRepository.insertPendingIfAbsent(
                currentFingerprint,
                "READING",
                "SINGLE_CHOICE",
                "assessment-contract-v1",
                "test-rl-model",
                "prompt-v2",
                "schema-v2",
                "vi",
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                "{}");
        QuestionExplanationArtifact oldArtifact =
                explanationArtifactRepository
                        .findByFingerprint(oldFingerprint)
                        .orElseThrow();
        QuestionExplanationArtifact currentArtifact =
                explanationArtifactRepository
                        .findByFingerprint(currentFingerprint)
                        .orElseThrow();

        assertThat(explanationBindingRepository.bindIfAbsent(
                questionVersionId,
                oldArtifact.getId(),
                "vi",
                oldFingerprint)).isEqualTo(1);
        assertThat(
                explanationBindingRepository
                        .supersedeActiveIfFingerprintChanged(
                                questionVersionId,
                                "vi",
                                currentFingerprint))
                .isEqualTo(1);
        assertThat(explanationBindingRepository.bindIfAbsent(
                questionVersionId,
                currentArtifact.getId(),
                "vi",
                currentFingerprint)).isEqualTo(1);

        QuestionVersionExplanationBinding active =
                explanationBindingRepository
                        .findByQuestionVersionIdAndExplanationLanguage(
                                questionVersionId,
                                "vi")
                        .orElseThrow();
        assertThat(active.getArtifactId())
                .isEqualTo(currentArtifact.getId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM question_version_explanation_bindings
                WHERE question_version_id = ?
                  AND explanation_language = 'vi'
                  AND binding_status = 'ACTIVE'
                """,
                Integer.class,
                questionVersionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM question_version_explanation_bindings
                WHERE question_version_id = ?
                  AND explanation_language = 'vi'
                  AND binding_status = 'SUPERSEDED'
                  AND artifact_id = ?
                """,
                Integer.class,
                questionVersionId,
                oldArtifact.getId())).isEqualTo(1);
    }

    private ExplanationRecoveryFixture failedRetryableExplanationFixture() {
        com.ksh.entities.PracticePublishedVersion published =
                publishedVersionRepository
                        .findBySetIdOrderByVersionNumberDesc(practiceSet.getId())
                        .get(0);
        com.ksh.entities.PracticeQuestionVersion questionVersion =
                questionVersionRepository
                        .findByPublishedVersionIdOrderBySectionVersionIdAscDisplayOrderAscQuestionNoAscIdAsc(
                                published.getId())
                        .get(0);
        String suffix = Long.toHexString(questionVersion.getId());
        String fingerprint = "f".repeat(64 - suffix.length()) + suffix;
        explanationArtifactRepository.insertPendingIfAbsent(
                fingerprint,
                "READING",
                "SINGLE_CHOICE",
                "assessment-contract-v1",
                "test-rl-model",
                "prompt-v1",
                "schema-v1",
                "vi",
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                "{}");
        QuestionExplanationArtifact artifact = explanationArtifactRepository
                .findByFingerprint(fingerprint)
                .orElseThrow();
        explanationBindingRepository.bindIfAbsent(
                questionVersion.getId(),
                artifact.getId(),
                "vi",
                fingerprint);
        QuestionVersionExplanationBinding binding = explanationBindingRepository
                .findByQuestionVersionIdAndExplanationLanguage(
                        questionVersion.getId(), "vi")
                .orElseThrow();
        explanationTaskRepository.insertPendingIfAbsent(
                artifact.getId(), questionVersion.getId(), 4);
        QuestionExplanationGenerationTask task = explanationTaskRepository
                .findByArtifactId(artifact.getId())
                .orElseThrow();
        LocalDateTime failedAt = LocalDateTime.now().minusMinutes(2);
        artifact.markFailed(
                "PROVIDER_TRANSPORT_ERROR",
                "raw provider diagnostic must never be rendered",
                failedAt);
        task.markFailure(
                "PROVIDER_TRANSPORT_ERROR",
                "raw provider diagnostic must never be rendered",
                false,
                null,
                failedAt);
        explanationArtifactRepository.saveAndFlush(artifact);
        explanationTaskRepository.saveAndFlush(task);
        return new ExplanationRecoveryFixture(
                questionVersion.getId(),
                binding.getArtifactId(),
                task.getId());
    }

    private record ExplanationRecoveryFixture(
            Long questionVersionId,
            Long artifactId,
            Long taskId) {
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testSubmitWritingAttemptAndGetResult() throws Exception {
        // Seed a published WRITING set
        PracticeSet writingSetSeed = new PracticeSet(
                "TOPIK II - Viết 35",
                "Mô tả đề thi viết TOPIK II kì 35",
                "WRITING",
                "GLOBAL",
                null,
                "practice-pdfs/test.pdf",
                "{}",
                "PUBLISHED",
                lecturer.getId()
        );
        final PracticeSet writingSet = setRepository.saveAndFlush(writingSetSeed);

        PracticeTest writingTest = new PracticeTest(writingSet.getId(), "Test 1", "Desc", 1, 40);
        writingTest = testRepository.saveAndFlush(writingTest);

        PracticeSection writingSec = new PracticeSection(writingSet.getId(), "Phần Viết", "WRITING", "ESSAY", "Viết luận", 50, BigDecimal.TEN, 1);
        writingSec.setTestId(writingTest.getId());
        writingSec = sectionRepository.saveAndFlush(writingSec);

        PracticeQuestion writingQuestion = new PracticeQuestion(
                writingSet.getId(),
                51,
                "ESSAY",
                "Câu hỏi viết 51",
                "[]",
                "",
                "Giải thích đáp án đúng",
                BigDecimal.valueOf(10.0),
                0
        );
        writingQuestion.setWritingTaskType(WritingTaskType.Q51);
        writingQuestion.setGroupId(null);
        questionRepository.saveAndFlush(writingQuestion);
        publishVersion(writingSet.getId());

        // Start attempt
        mockMvc.perform(post("/practice/sets/" + writingSet.getId() + "/tests/" + writingTest.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(writingSec.getId()))
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection());

        List<PracticeAttempt> attempts = attemptRepository.findAll();
        assertThat(attempts).isNotEmpty();
        PracticeAttempt attempt = attempts.stream()
                .filter(a -> a.getSetId().equals(writingSet.getId()))
                .findFirst().orElseThrow();

        // Perform Submit
        String paramName = "answer_" + writingQuestion.getId();
        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion()))
                        .param(paramName, "Tôi học tiếng Hàn."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/attempts/" + attempt.getId() + "/result"));

        // Perform GET result view -> should redirect to result template for WRITING
        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result"))
                .andExpect(model().attributeExists("result"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Phân tích bài viết")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Chưa có điểm số khả dụng")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Đánh giá đang chạy ở nền")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Ô 1 - Nội dung và ngữ cảnh"))));

        // Perform GET detailed result view -> typed Writing Detail template
        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-writing"))
                .andExpect(model().attributeExists("resultDetail"))
                .andExpect(model().attributeDoesNotExist("questionsJson", "groupsJson"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-result-detail-kind=\"WRITING_DETAIL\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("JSON.parse"))));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testWritingHttpSubmissionQueuesBeforeEvaluation() throws Exception {
        PracticeSet writingSet = setRepository.saveAndFlush(new PracticeSet(
                "Writing Transaction Boundary",
                "Desc",
                "WRITING",
                "GLOBAL",
                null,
                null,
                "{}",
                "PUBLISHED",
                lecturer.getId()
        ));
        PracticeTest writingTest = testRepository.saveAndFlush(new PracticeTest(writingSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection writingSection = new PracticeSection(
                writingSet.getId(), "Writing Section", "WRITING", "ESSAY", "Instruction", 50, BigDecimal.TEN, 1);
        writingSection.setTestId(writingTest.getId());
        writingSection = sectionRepository.saveAndFlush(writingSection);

        PracticeQuestion writingQuestion = new PracticeQuestion(
                writingSet.getId(), 51, "ESSAY", "Prompt", "[]", "", "Explain", BigDecimal.TEN, 0);
        writingQuestion.setWritingTaskType(WritingTaskType.Q51);
        writingQuestion = questionRepository.saveAndFlush(writingQuestion);
        publishVersion(writingSet.getId());

        mockMvc.perform(post("/practice/sets/" + writingSet.getId() + "/tests/" + writingTest.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(writingSection.getId()))
                        .param("mode", "exam"))
                .andExpect(status().is3xxRedirection());

        PracticeAttempt attempt = attemptRepository.findAll().stream()
                .filter(a -> a.getSetId().equals(writingSet.getId()))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion()))
                        .param("answer_" + writingQuestion.getId(), "My writing answer"))
                .andExpect(status().is3xxRedirection());

        verify(writingEvaluationClient, never()).evaluate(
                anyLong(), anyString(), anyString(), anyBoolean(), any());
        PracticeAttempt queued = attemptRepository.findById(
                attempt.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(
                PracticeAttempt.STATUS_SUBMITTED);
        assertThat(queued.getAnalysisStatus()).isEqualTo(
                PracticeAttempt.ANALYSIS_QUEUED);
    }


    @Test
    void testPracticeAttemptLockVersionIncrementsOnUpdate() {
        PracticeAttempt attempt = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);
        Long initialVersion = attempt.getLockVersion();

        attempt.setAnswersJson("{\"1\":\"2\"}");
        attempt = attemptRepository.saveAndFlush(attempt);

        assertThat(initialVersion).isNotNull();
        assertThat(attempt.getLockVersion()).isGreaterThan(initialVersion);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testProfileRedirectsToProgress() throws Exception {
        mockMvc.perform(get("/practice/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/progress"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testProgressAuthenticated() throws Exception {
        mockMvc.perform(get("/practice/progress"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/progress"))
                .andExpect(model().attributeExists("overview"))
                .andExpect(model().attributeExists("analytics"))
                .andExpect(model().attributeExists("overviewJson"))
                .andExpect(model().attributeExists("analyticsJson"))
                .andExpect(model().attributeExists("progressState"))
                .andExpect(model().attributeExists("progressFilter"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void progressFilterNormalizesRoundTripsAndGetRemainsReadOnlyProviderFree()
            throws Exception {
        long attemptsBefore = attemptRepository.count();
        clearInvocations(writingEvaluationClient, readingListeningExplanationClient);

        mockMvc.perform(get("/practice/progress")
                        .param("tab", "test-practice")
                        .param("skill", "WRITING")
                        .param("writingTask", "Q53")
                        .param("profile", "not-a-canonical-cohort"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/progress"))
                .andExpect(result -> {
                    ProgressFilterState filter = (ProgressFilterState)
                            result.getModelAndView().getModel().get("progressFilter");
                    assertThat(filter.tab()).isEqualTo("test-practice");
                    assertThat(filter.skill().name()).isEqualTo("WRITING");
                    assertThat(filter.writingTask().name()).isEqualTo("Q53");
                    assertThat(filter.profileId()).isEqualTo("ALL");
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "name=\"writingTask\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Q53\"")));

        assertThat(attemptRepository.count()).isEqualTo(attemptsBefore);
        verifyNoInteractions(writingEvaluationClient, readingListeningExplanationClient);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testProgressDeniedForLecturer() throws Exception {
        mockMvc.perform(get("/practice/progress"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/practice/profile"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testProgressUsesPracticeAttemptSkillAndLinks() throws Exception {
        practiceSet.setSkill("MIXED");
        setRepository.saveAndFlush(practiceSet);

        PracticeSection writingSection = new PracticeSection(
                practiceSet.getId(), "Phần Viết", "WRITING", "ESSAY", "Viết luận", 50, BigDecimal.TEN, 2);
        writingSection.setTestId(defaultTest.getId());
        writingSection = sectionRepository.saveAndFlush(writingSection);

        PracticeQuestionGroup writingGroup = new PracticeQuestionGroup(
                practiceSet.getId(), "Phần Viết", 53, 53,
                "Viết đoạn văn", null, null, 2);
        writingGroup.setSectionId(writingSection.getId());
        writingGroup = groupRepository.saveAndFlush(writingGroup);

        PracticeQuestion writingQuestion = new PracticeQuestion(
                practiceSet.getId(), 53, "ESSAY", "Viết một đoạn văn ngắn.",
                "[]", "", "Yêu cầu viết đoạn văn.", BigDecimal.TEN, 2);
        writingQuestion.setWritingTaskType(WritingTaskType.Q53);
        writingQuestion.setGroupId(writingGroup.getId());
        questionRepository.saveAndFlush(writingQuestion);

        publishVersion(practiceSet.getId());

        Long readingAttemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        PracticeAttempt readingAttempt = attemptRepository.findById(readingAttemptId).orElseThrow();
        readingAttempt.markGraded(BigDecimal.valueOf(8), BigDecimal.TEN, "{}", "{}");
        readingAttempt = attemptRepository.saveAndFlush(readingAttempt);

        Long writingAttemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), writingSection.getId(), student.getId());
        PracticeAttempt writingAttempt = attemptRepository.findById(writingAttemptId).orElseThrow();
        writingAttempt.markGraded(BigDecimal.valueOf(7), BigDecimal.TEN, "{}", "{}");
        writingAttempt = attemptRepository.saveAndFlush(writingAttempt);

        mockMvc.perform(get("/practice/progress"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skill\\\":\\\"READING\\\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("skill\\\":\\\"WRITING\\\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("skill\\\":\\\"MIXED\\\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/practice/attempts/" + readingAttempt.getId() + "/result")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Luyện thêm")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("writingTask=ALL")));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturerExplanationRecoveryUsesBatchProjectionCsrfPrgPersistenceAndCooldown()
            throws Exception {
        ExplanationRecoveryFixture fixture =
                failedRetryableExplanationFixture();
        clearInvocations(readingListeningExplanationClient);

        mockMvc.perform(get("/practice/manage/revisions")
                        .param("setId", String.valueOf(practiceSet.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/manage/revisions"))
                .andExpect(model().attributeExists(
                        "explanationRecoveryRows",
                        "explanationRecoveryAuthorized"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "data-recovery-state=\"FAILED_RETRYABLE\"")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "/practice/manage/sets/"
                                        + practiceSet.getId()
                                        + "/explanations/"
                                        + fixture.questionVersionId()
                                        + "/retry")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "raw provider diagnostic"))));

        String retryPath = "/practice/manage/sets/"
                + practiceSet.getId()
                + "/explanations/"
                + fixture.questionVersionId()
                + "/retry";
        mockMvc.perform(post(retryPath))
                .andExpect(status().isForbidden());

        QuestionExplanationGenerationTask beforeRetry =
                explanationTaskRepository.findById(fixture.taskId())
                        .orElseThrow();
        assertThat(beforeRetry.getStatus())
                .isEqualTo(QuestionExplanationGenerationTask.STATUS_FAILED);
        assertThat(beforeRetry.getManualRetryCount()).isZero();

        mockMvc.perform(post(retryPath).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/manage/revisions?setId="
                                + practiceSet.getId()))
                .andExpect(flash().attribute(
                        "success", "Đã xếp lịch tạo lại giải thích."));

        QuestionExplanationArtifact queuedArtifact =
                explanationArtifactRepository.findById(fixture.artifactId())
                        .orElseThrow();
        QuestionExplanationGenerationTask queuedTask =
                explanationTaskRepository.findById(fixture.taskId())
                        .orElseThrow();
        assertThat(queuedArtifact.getStatus())
                .isEqualTo(QuestionExplanationArtifact.STATUS_PENDING);
        assertThat(queuedTask.getStatus())
                .isEqualTo(QuestionExplanationGenerationTask.STATUS_PENDING);
        assertThat(queuedTask.getManualRetryCount()).isEqualTo(1);
        assertThat(queuedTask.getLastRetryRequestedBy())
                .isEqualTo(lecturer.getId());

        LocalDateTime failedAgainAt = LocalDateTime.now();
        queuedArtifact.markFailed(
                "PROVIDER_TRANSPORT_ERROR",
                "raw provider diagnostic must never be rendered",
                failedAgainAt);
        queuedTask.markFailure(
                "PROVIDER_TRANSPORT_ERROR",
                "raw provider diagnostic must never be rendered",
                false,
                null,
                failedAgainAt);
        explanationArtifactRepository.saveAndFlush(queuedArtifact);
        explanationTaskRepository.saveAndFlush(queuedTask);

        mockMvc.perform(post(retryPath).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/manage/revisions?setId="
                                + practiceSet.getId()))
                .andExpect(flash().attribute(
                        "error",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString(
                                        "thời gian chờ"),
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString(
                                                "raw provider diagnostic")))));

        QuestionExplanationGenerationTask rateLimitedTask =
                explanationTaskRepository.findById(fixture.taskId())
                        .orElseThrow();
        assertThat(rateLimitedTask.getStatus())
                .isEqualTo(QuestionExplanationGenerationTask.STATUS_FAILED);
        assertThat(rateLimitedTask.getManualRetryCount()).isEqualTo(1);
        verifyNoInteractions(readingListeningExplanationClient);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentExplanationRetriesUseFreshLockedStateAndQueueExactlyOnce()
            throws Exception {
        ExplanationRecoveryFixture fixture = requiresNewTransaction().execute(
                status -> failedRetryableExplanationFixture());
        assertThat(fixture).isNotNull();
        clearInvocations(readingListeningExplanationClient);

        CountDownLatch bothTransactionsPreloaded = new CountDownLatch(2);
        CountDownLatch releaseRetries = new CountDownLatch(1);
        AtomicReference<Connection> firstConnection = new AtomicReference<>();
        AtomicReference<Connection> secondConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<QuestionExplanationRetryService.RetryResult> first =
                    executor.submit(() -> requiresNewTransaction().execute(status -> {
                        firstConnection.set(
                                DataSourceUtils.getConnection(dataSource));
                        QuestionExplanationGenerationTask staleTask =
                                explanationTaskRepository
                                        .findById(fixture.taskId())
                                        .orElseThrow();
                        QuestionExplanationArtifact staleArtifact =
                                explanationArtifactRepository
                                        .findById(fixture.artifactId())
                                        .orElseThrow();
                        assertThat(staleTask.getStatus()).isEqualTo(
                                QuestionExplanationGenerationTask.STATUS_FAILED);
                        assertThat(staleTask.getManualRetryCount()).isZero();
                        assertThat(staleArtifact.getStatus()).isEqualTo(
                                QuestionExplanationArtifact.STATUS_FAILED);
                        bothTransactionsPreloaded.countDown();
                        awaitLatch(releaseRetries);
                        return explanationRetryService.retryQuestionVersion(
                                practiceSet.getId(),
                                fixture.questionVersionId(),
                                lecturer.getId());
                    }));
            Future<QuestionExplanationRetryService.RetryResult> second =
                    executor.submit(() -> requiresNewTransaction().execute(status -> {
                        secondConnection.set(
                                DataSourceUtils.getConnection(dataSource));
                        QuestionExplanationGenerationTask staleTask =
                                explanationTaskRepository
                                        .findById(fixture.taskId())
                                        .orElseThrow();
                        QuestionExplanationArtifact staleArtifact =
                                explanationArtifactRepository
                                        .findById(fixture.artifactId())
                                        .orElseThrow();
                        assertThat(staleTask.getStatus()).isEqualTo(
                                QuestionExplanationGenerationTask.STATUS_FAILED);
                        assertThat(staleTask.getManualRetryCount()).isZero();
                        assertThat(staleArtifact.getStatus()).isEqualTo(
                                QuestionExplanationArtifact.STATUS_FAILED);
                        bothTransactionsPreloaded.countDown();
                        awaitLatch(releaseRetries);
                        return explanationRetryService.retryQuestionVersion(
                                practiceSet.getId(),
                                fixture.questionVersionId(),
                                lecturer.getId());
                    }));

            assertTrue(bothTransactionsPreloaded.await(5, TimeUnit.SECONDS));
            releaseRetries.countDown();
            QuestionExplanationRetryService.RetryResult firstResult =
                    first.get(5, TimeUnit.SECONDS);
            QuestionExplanationRetryService.RetryResult secondResult =
                    second.get(5, TimeUnit.SECONDS);

            assertNotSame(firstConnection.get(), secondConnection.get());
            assertThat(List.of(firstResult, secondResult))
                    .extracting(QuestionExplanationRetryService.RetryResult::status)
                    .containsOnly("PENDING");
            assertThat(List.of(firstResult, secondResult).stream()
                    .filter(QuestionExplanationRetryService.RetryResult::queued)
                    .count()).isEqualTo(1);

            QuestionExplanationArtifact artifact =
                    explanationArtifactRepository
                            .findById(fixture.artifactId())
                            .orElseThrow();
            QuestionExplanationGenerationTask task =
                    explanationTaskRepository
                            .findById(fixture.taskId())
                            .orElseThrow();
            assertThat(artifact.getStatus()).isEqualTo(
                    QuestionExplanationArtifact.STATUS_PENDING);
            assertThat(task.getStatus()).isEqualTo(
                    QuestionExplanationGenerationTask.STATUS_PENDING);
            assertThat(task.getManualRetryCount()).isEqualTo(1);
            assertThat(task.getLastRetryRequestedBy()).isEqualTo(
                    lecturer.getId());
            verifyNoInteractions(readingListeningExplanationClient);
        } finally {
            releaseRetries.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void studentCannotQueueExplanationRecoveryThroughSsrOrRest()
            throws Exception {
        assertCurrentUserCannotQueueExplanationRecovery();
    }

    @Test
    @WithUserDetails("leader@ksh.edu.vn")
    void leaderCannotQueueExplanationRecoveryThroughSsrOrRest()
            throws Exception {
        assertCurrentUserCannotQueueExplanationRecovery();
    }

    @Test
    @WithUserDetails("admin@ksh.edu.vn")
    void adminCannotQueueExplanationRecoveryThroughSsrOrRest()
            throws Exception {
        assertCurrentUserCannotQueueExplanationRecovery();
    }

    private void assertCurrentUserCannotQueueExplanationRecovery()
            throws Exception {
        ExplanationRecoveryFixture fixture =
                failedRetryableExplanationFixture();
        clearInvocations(readingListeningExplanationClient);

        mockMvc.perform(post(
                        "/practice/manage/sets/"
                                + practiceSet.getId()
                                + "/explanations/"
                                + fixture.questionVersionId()
                                + "/retry")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/practice/manage/explanations/"
                                + fixture.artifactId()
                                + "/retry")
                        .with(csrf()))
                .andExpect(status().isForbidden());

        QuestionExplanationArtifact artifact =
                explanationArtifactRepository.findById(fixture.artifactId())
                        .orElseThrow();
        QuestionExplanationGenerationTask task =
                explanationTaskRepository.findById(fixture.taskId())
                        .orElseThrow();
        assertThat(artifact.getStatus())
                .isEqualTo(QuestionExplanationArtifact.STATUS_FAILED);
        assertThat(task.getStatus())
                .isEqualTo(QuestionExplanationGenerationTask.STATUS_FAILED);
        assertThat(task.getManualRetryCount()).isZero();
        verifyNoInteractions(readingListeningExplanationClient);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testProgressInProgressAttemptShowsContinueOnly() throws Exception {
        var lock = publishedVersionService.latestLock(
                        practiceSet.getId(),
                        defaultTest.getId(),
                        defaultSection.getId())
                .orElseThrow();
        PracticeAttempt attempt = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        attempt.lockPublishedVersion(
                lock.publishedVersionId(),
                lock.setVersionId(),
                lock.testVersionId(),
                lock.sectionVersionId());
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(get("/practice/progress"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/practice/attempts/" + attempt.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/practice/attempts/" + attempt.getId() + "/result"))));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testProgressStaleInProgressAttemptHasNoResumeOrResultLink()
            throws Exception {
        PracticeAttempt stale = new PracticeAttempt(
                student.getId(),
                practiceSet.getId(),
                defaultTest.getId(),
                "READING",
                defaultSection.getId());
        stale = attemptRepository.saveAndFlush(stale);

        mockMvc.perform(get("/practice/progress"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "Cần bắt đầu lại")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "/practice/attempts/"
                                                + stale.getId()
                                                + "\">Tiếp tục"))))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "/practice/attempts/"
                                                + stale.getId()
                                                + "/result"))));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testProgressDoesNotShowOtherUsersAttemptsOrCreateSubmission() throws Exception {
        PracticeAttempt otherUserAttempt = new PracticeAttempt(
                lecturer.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        otherUserAttempt.markGraded(BigDecimal.valueOf(8), BigDecimal.TEN, "{}", "{}");
        otherUserAttempt = attemptRepository.saveAndFlush(otherUserAttempt);
        mockMvc.perform(get("/practice/progress"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/practice/attempts/" + otherUserAttempt.getId()))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Chưa có hoạt động luyện tập")));

    }

    @Test
    void progressNativeProjectionsKeepAllTimeIdentityDurationAndActivityOrderCoherent() {
        com.ksh.entities.PracticePublishedVersion published =
                publishedVersionRepository
                        .findFirstBySetIdAndStatusOrderByVersionNumberDesc(
                                practiceSet.getId(),
                                com.ksh.entities.PracticePublishedVersion.STATUS_PUBLISHED)
                        .orElseThrow();
        com.ksh.entities.PracticeSetVersion setVersion =
                setVersionRepository.findByPublishedVersionId(published.getId())
                        .orElseThrow();
        com.ksh.entities.PracticeTestVersion testVersion =
                testVersionRepository.findByPublishedVersionIdAndTestId(
                                published.getId(), defaultTest.getId())
                        .orElseThrow();
        com.ksh.entities.PracticeSectionVersion sectionVersion =
                sectionVersionRepository.findByPublishedVersionIdAndSectionId(
                                published.getId(), defaultSection.getId())
                        .orElseThrow();

        PracticeSection mismatchedLiveSection = new PracticeSection(
                practiceSet.getId(),
                "Mismatched live section",
                "READING",
                "SINGLE_CHOICE",
                "No immutable match",
                40,
                BigDecimal.TEN,
                99);
        mismatchedLiveSection.setTestId(defaultTest.getId());
        mismatchedLiveSection = sectionRepository.saveAndFlush(mismatchedLiveSection);

        List<PracticeAttempt> attempts = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            PracticeAttempt coherent = new PracticeAttempt(
                    student.getId(),
                    practiceSet.getId(),
                    defaultTest.getId(),
                    "READING",
                    defaultSection.getId());
            coherent.lockPublishedVersion(
                    published.getId(),
                    setVersion.getId(),
                    testVersion.getId(),
                    sectionVersion.getId());
            coherent.markGraded(
                    BigDecimal.ONE, BigDecimal.valueOf(2), "{}", "{}");
            attempts.add(attemptRepository.saveAndFlush(coherent));
        }
        PracticeAttempt mismatched = new PracticeAttempt(
                student.getId(),
                practiceSet.getId(),
                defaultTest.getId(),
                "READING",
                mismatchedLiveSection.getId());
        mismatched.lockPublishedVersion(
                published.getId(),
                setVersion.getId(),
                testVersion.getId(),
                sectionVersion.getId());
        mismatched.markGraded(
                BigDecimal.ONE, BigDecimal.valueOf(2), "{}", "{}");
        mismatched = attemptRepository.saveAndFlush(mismatched);
        Long mismatchedAttemptId = mismatched.getId();
        attempts.add(mismatched);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime baseActivity = now.minusDays(10);
        LocalDateTime newestActivity = now.minusMinutes(1);
        LocalDateTime secondActivity = now.minusDays(1);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                UPDATE practice_attempts
                   SET started_at = ?,
                       submitted_at = ?,
                       created_at = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """,
                baseActivity.minusMinutes(10),
                baseActivity,
                baseActivity.minusDays(30),
                baseActivity,
                student.getId());
        PracticeAttempt oldCreatedRecentlySubmitted = attempts.get(0);
        PracticeAttempt newerCreatedEarlierSubmitted = attempts.get(1);
        jdbc.update("""
                UPDATE practice_attempts
                   SET started_at = ?,
                       submitted_at = ?,
                       created_at = ?,
                       updated_at = ?
                 WHERE id = ?
                """,
                newestActivity.minusMinutes(10),
                newestActivity,
                baseActivity.minusDays(60),
                newestActivity,
                oldCreatedRecentlySubmitted.getId());
        jdbc.update("""
                UPDATE practice_attempts
                   SET started_at = ?,
                       submitted_at = ?,
                       created_at = ?,
                       updated_at = ?
                 WHERE id = ?
                """,
                secondActivity.minusMinutes(10),
                secondActivity,
                now,
                secondActivity,
                newerCreatedEarlierSubmitted.getId());
        jdbc.update("""
                UPDATE practice_attempts
                   SET started_at = submitted_at
                 WHERE id = ?
                """, mismatchedAttemptId);
        entityManager.clear();

        PracticeAttemptRepository.ProgressAllTimeProjection allTime =
                attemptRepository.findProgressAllTime(
                        student.getId(), PracticeAttempt.STATUS_DISCARDED);
        PracticeAttemptRepository.ProgressSkillProjection reading =
                attemptRepository.findProgressAllTimeBySkill(
                                student.getId(), PracticeAttempt.STATUS_DISCARDED)
                        .stream()
                        .filter(row -> "READING".equals(row.getSkill()))
                        .findFirst()
                        .orElseThrow();
        List<PracticeAttempt> recent = attemptRepository.findRecentProgressAttempts(
                student.getId(),
                PracticeAttempt.STATUS_DISCARDED,
                org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(allTime.getActivityCount()).isEqualTo(101L);
        assertThat(allTime.getCompletedCount()).isEqualTo(101L);
        assertThat(allTime.getInProgressCount()).isZero();
        assertThat(allTime.getValidDurationCount()).isEqualTo(100L);
        assertThat(allTime.getExcludedDurationCount()).isEqualTo(1L);
        assertThat(allTime.getTotalValidMinutes()).isEqualTo(1000L);
        assertThat(allTime.getObservedFrom()).isEqualTo(baseActivity);
        assertThat(allTime.getObservedTo()).isEqualTo(newestActivity);
        assertThat(allTime.getAsOf()).isNotNull();

        assertThat(reading.getActivityCount()).isEqualTo(101L);
        assertThat(reading.getEligibleScoreCount()).isEqualTo(100L);
        assertThat(reading.getExcludedScoreCount()).isEqualTo(1L);
        assertThat(reading.getEarnedPoints()).isEqualByComparingTo("100.00");
        assertThat(reading.getPossiblePoints()).isEqualByComparingTo("200.00");
        assertThat(reading.getObservedFrom()).isEqualTo(baseActivity);
        assertThat(reading.getObservedTo()).isEqualTo(newestActivity);
        assertThat(reading.getAsOf()).isNotNull();

        assertThat(recent).hasSize(100);
        assertThat(recent.get(0).getId())
                .isEqualTo(oldCreatedRecentlySubmitted.getId());
        assertThat(recent.get(1).getId())
                .isEqualTo(newerCreatedEarlierSubmitted.getId());

        var page = progressService.getProgressPageData(
                student.getId(), "Student", "");
        assertThat(page.overview().attemptCounts().total()).isEqualTo(101);
        assertThat(page.overview().allTimeWindow().observedFrom())
                .isEqualTo(baseActivity);
        assertThat(page.overview().allTimeWindow().observedTo())
                .isEqualTo(newestActivity);
        assertThat(page.overview().allTimeWindow().lastObservedAt())
                .isEqualTo(newestActivity);
        assertThat(page.overview().allTimeWindow().asOf())
                .isAfterOrEqualTo(newestActivity);
        assertThat(page.overview().recentDetailWindow().returnedCount()).isEqualTo(100);
        assertThat(page.overview().recentDetailWindow().truncated()).isTrue();
        assertThat(page.overview().recentDetailWindow().observedFrom())
                .isEqualTo(baseActivity);
        assertThat(page.overview().recentDetailWindow().observedTo())
                .isEqualTo(newestActivity);
        assertThat(page.overview().recentDetailWindow().lastObservedAt())
                .isEqualTo(newestActivity);
        assertThat(page.overview().recentDetailWindow().asOf()).isNotNull();

        var readingMetric = page.overview().skillMetrics().stream()
                .filter(metric -> "READING".equals(metric.skill()))
                .findFirst()
                .orElseThrow();
        assertThat(readingMetric.scoreFact().sampleSize()).isEqualTo(100);
        assertThat(readingMetric.scoreFact().numerator())
                .isEqualByComparingTo("100.00");
        assertThat(readingMetric.scoreFact().denominator())
                .isEqualByComparingTo("200.00");
        assertThat(readingMetric.observationWindow().observedFrom())
                .isEqualTo(baseActivity);
        assertThat(readingMetric.observationWindow().observedTo())
                .isEqualTo(newestActivity);
        assertThat(readingMetric.observationWindow().lastObservedAt())
                .isEqualTo(newestActivity);
        assertThat(readingMetric.observationWindow().asOf())
                .isAfterOrEqualTo(newestActivity);

        assertThat(page.analytics().history())
                .filteredOn(row -> row.id().equals(mismatchedAttemptId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.identityReason())
                            .isEqualTo(
                                    com.ksh.features.practice.dto.PracticeDtos
                                            .ProgressExclusionReason.LEGACY_UNVERIFIED);
                    assertThat(row.score()).isNull();
                    assertThat(row.totalPoints()).isNull();
                    assertThat(row.scoreFact().value()).isNull();
                });
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testUploadDeniedForStudent() throws Exception {
        mockMvc.perform(get("/practice/manage/import"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testUploadAllowedForLecturer() throws Exception {
        com.ksh.entities.PracticeDraft importDraft = draftRepository.saveAndFlush(
                new com.ksh.entities.PracticeDraft(
                        "Nháp Text/PDF",
                        "",
                        "GLOBAL",
                        null,
                        "DRAFT",
                        lecturer.getId(),
                        "{\"sections\":[{\"title\":\"Phần Đọc\","
                                + "\"skill\":\"READING\",\"testNo\":1,"
                                + "\"lessonCode\":\"R1\"}]}"));
        mockMvc.perform(get("/practice/manage/import")
                        .param("draftId", importDraft.getId().toString())
                        .param("testNo", "1")
                        .param("skill", "READING")
                        .param("lessonCode", "R1"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/manage/import-wizard"));

        // The targetless legacy bookmark is retired instead of opening a
        // half-configured Basic import surface.
        mockMvc.perform(get("/practice/manage/upload"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testManualDraftForLecturer() throws Exception {
        com.ksh.entities.PracticeDraft emptyDraft = draftRepository.saveAndFlush(
                new com.ksh.entities.PracticeDraft(
                        "Nháp trống giữ nguyên",
                        "",
                        "GLOBAL",
                        null,
                        "DRAFT",
                        lecturer.getId(),
                        "{\"sections\":[]}"));
        mockMvc.perform(get("/practice/manage"))
                .andExpect(status().isOk());
        assertThat(draftRepository.existsById(emptyDraft.getId())).isTrue();

        mockMvc.perform(get("/practice/manage/create"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/manage"));

        mockMvc.perform(post("/practice/manage/create").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/practice/manage/drafts/*"));

        // The legacy GET bookmark is read-only and returns to the dashboard.
        mockMvc.perform(get("/practice/manage/manual"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/manage"));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testPublishDraft() throws Exception {
        String draftJson = """
        {
          "document": {
            "detectedCategory": "TOPIK_II",
            "title": "Đề mới xuất bản",
            "confidence": 1.0
          },
          "sections": [
            {
              "title": "Phần Đọc",
              "skill": "READING",
              "durationMinutes": 40,
              "groups": [
                {
                  "label": "1",
                  "questionFrom": 1,
                  "questionTo": 1,
                  "instruction": "Chỉ dẫn",
                  "questions": [
                    {
                      "clientId": "question-1",
                      "questionNo": 1,
                      "questionType": "SINGLE_CHOICE",
                      "prompt": "Câu 1",
                      "options": ["A", "B"],
                      "answer": { "value": "1" },
                      "explanationVi": "Vì đúng",
                      "explanationStrategy": {
                        "registryVersion": "rl-explanation-strategy-registry-v1",
                        "strategyCode": "EVIDENCE_ONLY",
                        "strategyVersion": "v1"
                      },
                      "points": 5.0
                    }
                  ]
                }
              ]
            }
          ]
        }
        """;

        com.ksh.entities.PracticeDraft draft = new com.ksh.entities.PracticeDraft(
                "Draft test", "Desc", "GLOBAL", null, "DRAFT", lecturer.getId(), draftJson
        );
        draft = draftRepository.saveAndFlush(draft);
        approveObjectiveExplanation(draft, "question-1");

        mockMvc.perform(post("/practice/manage/drafts/" + draft.getId() + "/publish")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/practice/sets/*"));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testOptimisticLockingConflict() throws Exception {
        com.ksh.entities.PracticeDraft draft = new com.ksh.entities.PracticeDraft(
                "Lock Test", "Desc", "GLOBAL", null, "DRAFT", lecturer.getId(), "{}"
        );
        draft = draftRepository.saveAndFlush(draft);
        int originalVersion = draft.getVersion();

        mockMvc.perform(post("/practice/manage/drafts/" + draft.getId() + "/autosave")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"draftJson\":\"{}\",\"title\":\"Lock Test Sửa\",\"version\":" + originalVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(originalVersion + 1));

        mockMvc.perform(post("/practice/manage/drafts/" + draft.getId() + "/autosave")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"draftJson\":\"{}\",\"title\":\"Ghi đè lỗi\",\"version\":" + originalVersion + "}"))
                .andExpect(status().is4xxClientError()) // HTTP 409 Conflict
                .andExpect(jsonPath("$.status").value("conflict"));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testPublishEditAndRestoreRevision() throws Exception {
        String draftJson = """
        {
          "document": {
            "detectedCategory": "TOPIK_II",
            "title": "Học liệu gốc",
            "confidence": 1.0
          },
          "sections": [
            {
              "title": "Phần Đọc",
              "skill": "READING",
              "durationMinutes": 40,
              "groups": [
                {
                  "label": "1",
                  "questionFrom": 1,
                  "questionTo": 1,
                  "instruction": "Chỉ dẫn",
                  "questions": [
                    {
                      "clientId": "question-1",
                      "questionNo": 1,
                      "questionType": "SINGLE_CHOICE",
                      "prompt": "Câu 1 ban đầu",
                      "options": ["A", "B"],
                      "answer": { "value": "1" },
                      "explanationVi": "Vì đúng",
                      "explanationStrategy": {
                        "registryVersion": "rl-explanation-strategy-registry-v1",
                        "strategyCode": "EVIDENCE_ONLY",
                        "strategyVersion": "v1"
                      },
                      "points": 5.0
                    }
                  ]
                }
              ]
            }
          ]
        }
        """;

        com.ksh.entities.PracticeDraft draft = new com.ksh.entities.PracticeDraft(
                "Học liệu gốc", "Desc", "GLOBAL", null, "DRAFT", lecturer.getId(), draftJson
        );
        draft = draftRepository.saveAndFlush(draft);
        approveObjectiveExplanation(draft, "question-1");

        // 1. Publish first time
        mockMvc.perform(post("/practice/manage/drafts/" + draft.getId() + "/publish").with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<PracticeSet> sets = setRepository.findAll();
        PracticeSet publishedSet = sets.stream()
                .filter(s -> "Học liệu gốc".equals(s.getTitle()))
                .findFirst().orElseThrow();

        // 2. Edit existing set -> redirects to /practice/manage/drafts/{id}
        mockMvc.perform(get("/practice/manage/sets/" + publishedSet.getId() + "/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/sets/" + publishedSet.getId()));

        mockMvc.perform(post("/practice/manage/sets/" + publishedSet.getId() + "/edit")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<com.ksh.entities.PracticeDraft> drafts = draftRepository.findByOwnerIdOrderByUpdatedAtDesc(lecturer.getId());
        com.ksh.entities.PracticeDraft editDraft = drafts.stream()
                .filter(d -> d.getPublishedSetId() != null && d.getPublishedSetId().equals(publishedSet.getId()))
                .findFirst().orElseThrow();

        // Modify a question prompt in the edit draft JSON
        String updatedJson = draftJson.replace("Câu 1 ban đầu", "Câu 1 đã sửa");
        
        mockMvc.perform(post("/practice/manage/drafts/" + editDraft.getId() + "/autosave")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"draftJson\":" + objectMapper.writeValueAsString(updatedJson) + ",\"title\":\"Học liệu đã sửa\",\"version\":" + editDraft.getVersion() + "}"))
                .andExpect(status().isOk());
        approveObjectiveExplanation(editDraft, "question-1");

        // 3. Publish modified draft to update original set
        mockMvc.perform(post("/practice/manage/drafts/" + editDraft.getId() + "/publish").with(csrf()))
                .andExpect(status().is3xxRedirection());

        // 4. Assert a revision log entry was recorded
        List<com.ksh.entities.PracticeEditLog> logs = editLogRepository.findBySetIdOrderByEditedAtDesc(publishedSet.getId());
        assertThat(logs).isNotEmpty();
        com.ksh.entities.PracticeEditLog lastLog = logs.stream()
                .filter(l -> "QUESTIONS,METADATA".equals(l.getEditType()) || "QUESTIONS".equals(l.getEditType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No edit log found with QUESTIONS or QUESTIONS,METADATA edit type"));
        assertThat(lastLog.getBeforeSnapshotJson()).contains("Câu 1 ban đầu");
        assertThat(lastLog.getAfterSnapshotJson()).contains("Câu 1 đã sửa");

        // 5. Restore the revision
        revisionService.restoreRevision(lastLog.getId(), lecturer.getId());

        // Assert the questions in the active published set have reverted to "Câu 1 ban đầu"
        List<PracticeQuestion> revertedQs = questionRepository.findBySetIdOrderByDisplayOrderAsc(publishedSet.getId());
        assertThat(revertedQs).isNotEmpty();
        assertThat(revertedQs.get(0).getPrompt()).isEqualTo("Câu 1 ban đầu");
    }


    @Test
    void restoreRevisionWithLearnerAttemptBlocksBeforeGraphMutation() {
        PracticeAttempt attempt = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        attemptRepository.saveAndFlush(attempt);
        com.ksh.entities.PracticeEditLog log = createRestoreLog(practiceSet.getId(), "unsafe restore");
        long logCountBefore = editLogRepository.findBySetIdOrderByEditedAtDesc(practiceSet.getId()).size();
        List<PracticeQuestion> questionsBefore = questionRepository.findBySetIdOrderByDisplayOrderAsc(practiceSet.getId());
        String titleBefore = setRepository.findById(practiceSet.getId()).orElseThrow().getTitle();

        assertThrows(
                com.ksh.features.practice.manage.service.PublishedPracticeGraphMutationBlockedException.class,
                () -> revisionService.restoreRevision(log.getId(), lecturer.getId())
        );

        assertThat(questionRepository.findBySetIdOrderByDisplayOrderAsc(practiceSet.getId()))
                .extracting(PracticeQuestion::getId)
                .containsExactlyElementsOf(questionsBefore.stream().map(PracticeQuestion::getId).toList());
        assertThat(setRepository.findById(practiceSet.getId()).orElseThrow().getTitle()).isEqualTo(titleBefore);
        assertThat(editLogRepository.findBySetIdOrderByEditedAtDesc(practiceSet.getId())).hasSize((int) logCountBefore);
    }

    @Test
    void attemptHistoryExistenceIncludesEveryAttemptStatus() {
        for (String status : List.of(
                PracticeAttempt.STATUS_IN_PROGRESS,
                PracticeAttempt.STATUS_SUBMITTED,
                PracticeAttempt.STATUS_GRADED,
                PracticeAttempt.STATUS_DISCARDED
        )) {
            attemptRepository.deleteAll();
            PracticeAttempt attempt = new PracticeAttempt(
                    student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
            if (PracticeAttempt.STATUS_DISCARDED.equals(status)) {
                attempt.discard(java.time.LocalDateTime.now());
            } else {
                attempt.setStatus(status);
            }
            attemptRepository.saveAndFlush(attempt);

            assertThat(attemptRepository.existsBySetId(practiceSet.getId())).isTrue();
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void setPessimisticLockBlocksSecondDatabaseTransactionUntilCommit() throws Exception {
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        AtomicReference<Connection> firstConnection = new AtomicReference<>();
        AtomicReference<Connection> secondConnection = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> requiresNewTransaction().executeWithoutResult(status -> {
                firstConnection.set(DataSourceUtils.getConnection(dataSource));
                setRepository.findByIdForUpdate(practiceSet.getId()).orElseThrow();
                firstLockAcquired.countDown();
                awaitLatch(releaseFirstLock);
            }));
            assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> requiresNewTransaction().executeWithoutResult(status -> {
                secondConnection.set(DataSourceUtils.getConnection(dataSource));
                setRepository.findByIdForUpdate(practiceSet.getId()).orElseThrow();
            }));

            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
            releaseFirstLock.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertNotSame(firstConnection.get(), secondConnection.get());
        } finally {
            releaseFirstLock.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void startWinsAgainstRepublishAndVersionLockedAttemptAllowsNewVersion() throws Exception {
        com.ksh.entities.PracticeDraft draft = createRepublishDraft(practiceSet.getId(), "Versioned republish");
        List<Long> questionIdsBefore = questionIds(practiceSet.getId());
        int versionCountBefore = publishedVersionRepository
                .findBySetIdOrderByVersionNumberDesc(practiceSet.getId()).size();
        Long attemptVersionBeforeRepublish = publishedVersionRepository
                .findFirstBySetIdOrderByVersionNumberDesc(practiceSet.getId()).orElseThrow().getId();
        CountDownLatch attemptCreated = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> start = executor.submit(() -> requiresNewTransaction().execute(status -> {
                Long attemptId = practiceService.startAttempt(
                        practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
                attemptRepository.flush();
                attemptCreated.countDown();
                awaitLatch(releaseStart);
                return attemptId;
            }));
            assertTrue(attemptCreated.await(5, TimeUnit.SECONDS));

            Future<Long> republish = executor.submit(() -> publisherService.publish(draft.getId(), lecturer.getId()));
            assertThrows(TimeoutException.class, () -> republish.get(250, TimeUnit.MILLISECONDS));
            releaseStart.countDown();

            Long attemptId = start.get(5, TimeUnit.SECONDS);
            assertEquals(practiceSet.getId(), republish.get(15, TimeUnit.SECONDS));

            PracticeAttempt lockedAttempt = attemptRepository.findById(attemptId).orElseThrow();
            assertThat(lockedAttempt.getPublishedVersionId()).isEqualTo(attemptVersionBeforeRepublish);
            assertThat(lockedAttempt.getSetVersionId()).isNotNull();
            assertThat(lockedAttempt.getTestVersionId()).isNotNull();
            assertThat(lockedAttempt.getSectionVersionId()).isNotNull();
            assertThat(publishedVersionRepository.findBySetIdOrderByVersionNumberDesc(practiceSet.getId()))
                    .hasSize(versionCountBefore + 1);
            assertThat(publishedVersionRepository.findFirstBySetIdOrderByVersionNumberDesc(practiceSet.getId()))
                    .get()
                    .extracting(com.ksh.entities.PracticePublishedVersion::getId)
                    .isNotEqualTo(attemptVersionBeforeRepublish);
            assertThat(questionIds(practiceSet.getId())).isNotEqualTo(questionIdsBefore);
        } finally {
            releaseStart.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void restoreWithoutAttemptsReplacesLiveGraph() {
        Long oldSectionId = defaultSection.getId();
        Long restoreLogId = createRestoreLog(practiceSet.getId(), "Restored graph").getId();
        revisionService.restoreRevision(restoreLogId, lecturer.getId());

        assertFalse(sectionRepository.existsById(oldSectionId));
        assertThat(sectionRepository.findBySetIdOrderByDisplayOrderAsc(practiceSet.getId()))
                .singleElement()
                .extracting(PracticeSection::getTitle)
                .isEqualTo("Restored section");
        assertThat(questionRepository.findBySetIdOrderByDisplayOrderAsc(practiceSet.getId()))
                .singleElement()
                .extracting(PracticeQuestion::getPrompt)
                .isEqualTo("Restored prompt");
        assertFalse(attemptRepository.existsBySetId(practiceSet.getId()));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void resumeWinsAgainstRepublishAndPinsExistingAttemptBeforeNewVersion() throws Exception {
        Long existingAttemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        PracticeAttempt existing = attemptRepository.findById(existingAttemptId).orElseThrow();
        com.ksh.entities.PracticeDraft draft = createRepublishDraft(practiceSet.getId(), "Versioned resume republish");
        int versionCountBefore = publishedVersionRepository
                .findBySetIdOrderByVersionNumberDesc(practiceSet.getId()).size();
        CountDownLatch resumed = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> resume = executor.submit(() -> requiresNewTransaction().execute(status -> {
                Long attemptId = practiceService.startAttempt(
                        practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
                resumed.countDown();
                awaitLatch(releaseResume);
                return attemptId;
            }));
            assertTrue(resumed.await(5, TimeUnit.SECONDS));

            Future<Long> republish = executor.submit(() -> publisherService.publish(draft.getId(), lecturer.getId()));
            assertThrows(TimeoutException.class, () -> republish.get(250, TimeUnit.MILLISECONDS));
            releaseResume.countDown();

            assertEquals(existing.getId(), resume.get(5, TimeUnit.SECONDS));
            assertEquals(practiceSet.getId(), republish.get(15, TimeUnit.SECONDS));
            assertEquals(1, attemptRepository.findAll().stream()
                    .filter(a -> practiceSet.getId().equals(a.getSetId()))
                    .count());
            PracticeAttempt lockedAttempt = attemptRepository.findById(existing.getId()).orElseThrow();
            assertThat(lockedAttempt.getPublishedVersionId()).isNotNull();
            assertThat(lockedAttempt.getSetVersionId()).isNotNull();
            assertThat(lockedAttempt.getTestVersionId()).isNotNull();
            assertThat(lockedAttempt.getSectionVersionId()).isNotNull();
            assertThat(publishedVersionRepository.findBySetIdOrderByVersionNumberDesc(practiceSet.getId()))
                    .hasSize(versionCountBefore + 1);
            assertThat(publishedVersionRepository.findFirstBySetIdOrderByVersionNumberDesc(practiceSet.getId()))
                    .get()
                    .extracting(com.ksh.entities.PracticePublishedVersion::getId)
                    .isNotEqualTo(lockedAttempt.getPublishedVersionId());
        } finally {
            releaseResume.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    void readingVersionLockedResultRemainsIdenticalWhenLiveGraphIsRestored() {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        PracticeAttempt attempt =
                attemptRepository.findById(attemptId).orElseThrow();
        attempt.markSubmitted(BigDecimal.valueOf(2.5), BigDecimal.valueOf(2.5),
                "{\"" + question.getId() + "\":\"1\"}");
        attempt = attemptRepository.saveAndFlush(attempt);
        var before = resultDetailAssembler.assemble(
                attempt.getId(), student.getId(), null);
        List<Long> idsBefore = questionIds(practiceSet.getId());
        var log = createRestoreLog(practiceSet.getId(), "Versioned reading restore");

        revisionService.restoreRevision(log.getId(), lecturer.getId());

        var after = resultDetailAssembler.assemble(
                attempt.getId(), student.getId(), null);
        assertEquals(before, after);
        ObjectiveDetailPayload readingPayload =
                (ObjectiveDetailPayload) after.payload();
        ObjectiveSingleChoiceDetail readingQuestion =
                (ObjectiveSingleChoiceDetail) readingPayload.questions().get(0);
        assertEquals(question.getId(), readingQuestion.core().questionId());
        assertTrue(readingQuestion.options().get(0).learnerSelected());
        assertThat(questionIds(practiceSet.getId())).isNotEqualTo(idsBefore);
    }

    @Test
    void listeningVersionLockedResultRemainsIdenticalWhenLiveGraphIsRepublished() {
        ListeningAttemptFixture fixture = createListeningAttemptFixture("Listening history guard");
        var before = resultDetailAssembler.assemble(
                fixture.attemptId(), student.getId(), null);
        com.ksh.entities.PracticeDraft draft = createRepublishDraft(
                fixture.setId(), "Versioned listening republish");
        int versionCountBefore = publishedVersionRepository
                .findBySetIdOrderByVersionNumberDesc(fixture.setId()).size();

        assertEquals(fixture.setId(), publisherService.publish(draft.getId(), lecturer.getId()));

        var after = resultDetailAssembler.assemble(
                fixture.attemptId(), student.getId(), null);
        assertEquals(before, after);
        ObjectiveDetailPayload listeningPayload =
                (ObjectiveDetailPayload) after.payload();
        ObjectiveSingleChoiceDetail listeningQuestion =
                (ObjectiveSingleChoiceDetail) listeningPayload.questions().get(0);
        assertEquals(fixture.questionId(), listeningQuestion.core().questionId());
        assertTrue(listeningQuestion.options().get(0).learnerSelected());
        assertThat(publishedVersionRepository.findBySetIdOrderByVersionNumberDesc(fixture.setId()))
                .hasSize(versionCountBefore + 1);
    }

    @Test
    void writingVersionLockedResultRemainsIdenticalWhenLiveGraphIsRestored() {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Writing history guard", true);
        var before = resultDetailAssembler.assemble(
                fixture.attemptId(), student.getId(), fixture.questionId());
        var log = createRestoreLog(fixture.setId(), "Versioned writing restore");

        revisionService.restoreRevision(log.getId(), lecturer.getId());

        var after = resultDetailAssembler.assemble(
                fixture.attemptId(), student.getId(), fixture.questionId());
        assertEquals(before, after);
        WritingDetailPayload writingPayload =
                (WritingDetailPayload) after.payload();
        assertEquals(fixture.questionId(), writingPayload.tasks().get(0).questionId());
        assertEquals(fixture.prompt(), writingPayload.tasks().get(0).prompt());
        assertEquals("Existing answer", writingPayload.tasks().get(0).learnerAnswer());
        assertEquals(
                fixture.oldFeedbackJson(),
                attemptRepository.findById(fixture.attemptId())
                        .orElseThrow()
                        .getAiFeedbackJson());
        assertThat(questionRepository.findBySetIdOrderByDisplayOrderAsc(fixture.setId()))
                .singleElement()
                .extracting(PracticeQuestion::getPrompt)
                .isEqualTo("Restored prompt");
    }

    @Test
    void speakingMediaAndResultRemainIntactWhenRepublishIsBlockedBeforeForeignKeyDelete() {
        SpeakingAttemptFixture fixture = createSpeakingAttemptFixture("Speaking history guard");
        PracticeSpeakingMedia media = speakingMediaRepository.saveAndFlush(PracticeSpeakingMedia.ready(
                fixture.attemptId(), fixture.questionId(), PracticeSpeakingStorageProvider.LOCAL,
                "PRACTICE_SPEAKING",
                "test/guard-" + java.util.UUID.randomUUID() + ".webm", "audio/webm", "webm", "opus",
                100L, 1000L, "a".repeat(64)));
        var before = resultDetailAssembler.assemble(
                fixture.attemptId(), student.getId(), fixture.questionId());
        com.ksh.entities.PracticeDraft draft = createRepublishDraft(fixture.setId(), "Unsafe speaking republish");
        long cleanupCountBefore = cleanupTaskRepository.count();
        long logCountBefore = editLogRepository.findBySetIdOrderByEditedAtDesc(fixture.setId()).size();
        String titleBefore = setRepository.findById(fixture.setId()).orElseThrow().getTitle();

        assertThrows(PublishedPracticeGraphMutationBlockedException.class,
                () -> publisherService.publish(draft.getId(), lecturer.getId()));

        var after = resultDetailAssembler.assemble(
                fixture.attemptId(), student.getId(), fixture.questionId());
        assertEquals(before, after);
        SpeakingDetailPayload speakingPayload =
                (SpeakingDetailPayload) after.payload();
        assertEquals(fixture.questionId(), speakingPayload.tasks().get(0).questionId());
        assertEquals(
                "Existing spoken answer",
                speakingPayload.tasks().get(0).learnerSubmissionText());
        assertTrue(questionRepository.existsById(fixture.questionId()));
        PracticeSpeakingMedia unchanged = speakingMediaRepository.findById(media.getId()).orElseThrow();
        assertEquals(PracticeSpeakingMediaStatus.READY, unchanged.getStatus());
        assertEquals(fixture.questionId(), unchanged.getQuestionId());
        assertEquals(cleanupCountBefore, cleanupTaskRepository.count());
        assertEquals(logCountBefore, editLogRepository.findBySetIdOrderByEditedAtDesc(fixture.setId()).size());
        assertEquals(titleBefore, setRepository.findById(fixture.setId()).orElseThrow().getTitle());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testSectionAttemptsFlow() throws Exception {
        // 1. Create and save a PracticeTest
        PracticeTest test = new PracticeTest(practiceSet.getId(), "Test 1", "Desc", 1, 40);
        test = testRepository.saveAndFlush(test);

        // 2. Create Reading and Writing sections
        PracticeSection readingSec = new PracticeSection(practiceSet.getId(), "Phần Đọc", "READING", "MCQ", "Đọc kỹ", 40, BigDecimal.TEN, 1);
        readingSec.setTestId(test.getId());
        readingSec = sectionRepository.saveAndFlush(readingSec);

        PracticeSection writingSec = new PracticeSection(practiceSet.getId(), "Phần Viết", "WRITING", "ESSAY", "Viết luận", 50, BigDecimal.TEN, 2);
        writingSec.setTestId(test.getId());
        writingSec = sectionRepository.saveAndFlush(writingSec);

        // Seed question groups for the sections to satisfy multi-section requirements
        PracticeQuestionGroup readingGroup = new PracticeQuestionGroup(practiceSet.getId(), "Phần Đọc", 1, 1, "Đọc văn bản", null, null, 1);
        readingGroup.setSectionId(readingSec.getId());
        readingGroup = groupRepository.saveAndFlush(readingGroup);

        PracticeQuestionGroup writingGroup = new PracticeQuestionGroup(practiceSet.getId(), "Phần Viết", 2, 2, "Viết luận", null, null, 2);
        writingGroup.setSectionId(writingSec.getId());
        writingGroup = groupRepository.saveAndFlush(writingGroup);
        question.setGroupId(readingGroup.getId());
        questionRepository.saveAndFlush(question);
        PracticeQuestion writingQuestion = new PracticeQuestion(
                practiceSet.getId(), 53, "ESSAY", "Viết một đoạn văn ngắn.",
                "[]", "", "Yêu cầu viết đoạn văn.", BigDecimal.TEN, 2);
        writingQuestion.setWritingTaskType(WritingTaskType.Q53);
        writingQuestion.setGroupId(writingGroup.getId());
        questionRepository.saveAndFlush(writingQuestion);
        publishVersion(practiceSet.getId());

        // --- Test 1: Start Reading ---
        // Post request to create attempt for Reading section
        String redirectUrl = mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + test.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(readingSec.getId()))
                        .param("mode", "practice"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirectUrl).contains("/practice/attempts/");
        String attemptIdStr = redirectUrl.substring(redirectUrl.indexOf("/attempts/") + 10, redirectUrl.indexOf("?"));
        Long readingAttemptId = Long.parseLong(attemptIdStr);

        // Verify the canonical PracticeAttempt was created correctly.
        PracticeAttempt readingAttempt = attemptRepository.findById(readingAttemptId).orElseThrow();
        assertThat(readingAttempt.getSectionId()).isEqualTo(readingSec.getId());
        assertThat(readingAttempt.getSkill()).isEqualTo("READING");
        assertThat(readingAttempt.getTestId()).isEqualTo(test.getId());
        assertThat(readingAttempt.getStatus()).isEqualTo("IN_PROGRESS");

        // --- Test 2: Start Writing ---
        String redirectUrl2 = mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + test.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(writingSec.getId()))
                        .param("mode", "practice"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        String attemptIdStr2 = redirectUrl2.substring(redirectUrl2.indexOf("/attempts/") + 10, redirectUrl2.indexOf("?"));
        Long writingAttemptId = Long.parseLong(attemptIdStr2);
        PracticeAttempt writingAttempt = attemptRepository.findById(writingAttemptId).orElseThrow();
        assertThat(writingAttempt.getSectionId()).isEqualTo(writingSec.getId());
        assertThat(writingAttempt.getSkill()).isEqualTo("WRITING");
        assertThat(writingAttempt.getId()).isNotEqualTo(readingAttemptId);

        // --- Test 3: Restart Reading (reuses existing IN_PROGRESS attempt) ---
        String redirectUrl3 = mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + test.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(readingSec.getId()))
                        .param("mode", "practice"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        String attemptIdStr3 = redirectUrl3.substring(redirectUrl3.indexOf("/attempts/") + 10, redirectUrl3.indexOf("?"));
        Long readingAttemptId3 = Long.parseLong(attemptIdStr3);
        assertThat(readingAttemptId3).isEqualTo(readingAttemptId);

        // --- Test 4: SectionId mismatch testId ---
        PracticeTest test2 = new PracticeTest(practiceSet.getId(), "Test 2", "Desc", 2, 40);
        test2 = testRepository.saveAndFlush(test2);
        PracticeSection mismatchedSec = new PracticeSection(practiceSet.getId(), "Mismatched", "READING", "MCQ", "Desc", 40, BigDecimal.TEN, 3);
        mismatchedSec.setTestId(test2.getId());
        mismatchedSec = sectionRepository.saveAndFlush(mismatchedSec);

        mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + test.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(mismatchedSec.getId())))
                .andExpect(status().is4xxClientError());

        // --- Test 5: SectionId mismatch setId ---
        PracticeSet anotherSet = new PracticeSet("Another", "Desc", "READING",  "GLOBAL", null, null, null, "PUBLISHED", lecturer.getId());
        anotherSet = setRepository.saveAndFlush(anotherSet);
        PracticeSection anotherSetSec = new PracticeSection(anotherSet.getId(), "Phần Khác", "READING", "MCQ", "Desc", 40, BigDecimal.TEN, 1);
        anotherSetSec.setTestId(test.getId());
        anotherSetSec = sectionRepository.saveAndFlush(anotherSetSec);

        mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + test.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(anotherSetSec.getId())))
                .andExpect(status().is4xxClientError());

        // --- Test 6: Set skill=MIXED, section skill=READING -> attempt skill is READING ---
        practiceSet.setSkill("MIXED");
        setRepository.saveAndFlush(practiceSet);

        String redirectUrlMixed = mockMvc.perform(post("/practice/sets/" + practiceSet.getId() + "/tests/" + test.getId() + "/attempts")
                        .with(csrf())
                        .param("sectionId", String.valueOf(readingSec.getId()))
                        .param("mode", "practice"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        String attemptIdStrMixed = redirectUrlMixed.substring(redirectUrlMixed.indexOf("/attempts/") + 10, redirectUrlMixed.indexOf("?"));
        Long mixedAttemptId = Long.parseLong(attemptIdStrMixed);
        PracticeAttempt mixedAttempt = attemptRepository.findById(mixedAttemptId).orElseThrow();
        assertThat(mixedAttempt.getSkill()).isEqualTo("READING");

        // --- Test 7: Player access (only loads current section) ---
        mockMvc.perform(get("/practice/attempts/" + readingAttemptId))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/player"))
                .andExpect(model().attributeExists("view"))
                .andExpect(model().attribute("activeSectionTitle", "Phần Đọc"))
                .andExpect(model().attribute("activeSectionSkill", "READING"));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void testPlayerAccessDeniedForOtherUser() throws Exception {
        PracticeAttempt attempt = new PracticeAttempt(student.getId(), practiceSet.getId(), 1L, "READING", 1L);
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(get("/practice/attempts/" + attempt.getId()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void directPlayerFailsClosedForMissingIncompatibleAndIncoherentLocks()
            throws Exception {
        clearInvocations(
                writingEvaluationClient,
                readingListeningExplanationClient);

        PracticeAttempt missingLock = attemptRepository.saveAndFlush(
                new PracticeAttempt(
                        student.getId(),
                        practiceSet.getId(),
                        defaultTest.getId(),
                        "READING",
                        defaultSection.getId()));
        mockMvc.perform(get("/practice/attempts/" + missingLock.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/sets/" + practiceSet.getId()
                                + "/tests/" + defaultTest.getId()))
                .andExpect(flash().attribute(
                        "error",
                        org.hamcrest.Matchers.containsString(
                                "bắt đầu lượt mới")));
        assertThat(attemptRepository.findById(missingLock.getId()).orElseThrow()
                .getStatus()).isEqualTo(PracticeAttempt.STATUS_IN_PROGRESS);

        Long incompatibleId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        PracticeAttempt incompatible =
                attemptRepository.findById(incompatibleId).orElseThrow();
        incompatible.setVersionCompatibilityStatus("STALE");
        attemptRepository.saveAndFlush(incompatible);
        mockMvc.perform(get("/practice/attempts/" + incompatibleId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/sets/" + practiceSet.getId()
                                + "/tests/" + defaultTest.getId()))
                .andExpect(flash().attribute(
                        "error",
                        org.hamcrest.Matchers.containsString(
                                "không còn tương thích")));
        assertThat(attemptRepository.findById(incompatibleId).orElseThrow()
                .getStatus()).isEqualTo(PracticeAttempt.STATUS_IN_PROGRESS);

        Long incoherentId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        assertThat(incoherentId).isNotEqualTo(incompatibleId);
        assertThat(attemptRepository.findById(incompatibleId).orElseThrow()
                .getStatus()).isEqualTo(PracticeAttempt.STATUS_DISCARDED);
        PracticeSection alternate = new PracticeSection(
                practiceSet.getId(),
                "Phần Đọc khác",
                "READING",
                "SINGLE_CHOICE",
                "Đọc kỹ",
                40,
                BigDecimal.TEN,
                2);
        alternate.setTestId(defaultTest.getId());
        alternate = sectionRepository.saveAndFlush(alternate);
        PracticeAttempt incoherent =
                attemptRepository.findById(incoherentId).orElseThrow();
        incoherent.setSectionId(alternate.getId());
        attemptRepository.saveAndFlush(incoherent);

        mockMvc.perform(get("/practice/attempts/" + incoherentId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/sets/" + practiceSet.getId()
                                + "/tests/" + defaultTest.getId()))
                .andExpect(flash().attribute(
                        "error",
                        org.hamcrest.Matchers.containsString(
                                "không nhất quán")));
        assertThat(attemptRepository.findById(incoherentId).orElseThrow()
                .getStatus()).isEqualTo(PracticeAttempt.STATUS_IN_PROGRESS);
        verifyNoInteractions(
                writingEvaluationClient,
                readingListeningExplanationClient);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void expiredPlayerGetIsReadOnlyWhileServerDeadlineProcessingRemainsAuthoritative()
            throws Exception {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(),
                defaultTest.getId(),
                defaultSection.getId(),
                student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        attemptRepository.saveAndFlush(attempt);
        clearInvocations(writingEvaluationClient, readingListeningExplanationClient);

        mockMvc.perform(get("/practice/attempts/" + attemptId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/practice/sets/" + practiceSet.getId()
                                + "/tests/" + defaultTest.getId()))
                .andExpect(flash().attribute(
                        "info",
                        org.hamcrest.Matchers.containsString("Đã hết giờ")));

        PracticeAttempt unchanged = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PracticeAttempt.STATUS_IN_PROGRESS);
        verifyNoInteractions(writingEvaluationClient, readingListeningExplanationClient);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void restartingExpiredWritingFinalizesSavedSnapshotAndCreatesFreshAttempt()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Expired Writing Restart", false);
        PracticeAttempt expired = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        expired.setAnswersJson(
                "{\"" + fixture.questionId()
                        + "\":\"저장된 답안\"}");
        expired.setDeadlineAt(
                LocalDateTime.now().minusSeconds(1));
        attemptRepository.saveAndFlush(expired);
        clearInvocations(writingEvaluationClient);

        mockMvc.perform(post(
                        "/practice/sets/" + fixture.setId()
                                + "/tests/" + fixture.testId()
                                + "/attempts")
                        .with(csrf())
                        .param(
                                "sectionId",
                                String.valueOf(fixture.sectionId()))
                        .param("mode", "practice"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(
                        "/practice/attempts/*?mode=practice"));

        PracticeAttempt finalized = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        assertThat(finalized.getStatus())
                .isEqualTo(PracticeAttempt.STATUS_SUBMITTED);
        assertThat(finalized.getAnswersJson())
                .contains("저장된 답안");
        assertThat(attemptEvaluationJobRepository
                .findByAttemptId(fixture.attemptId()))
                .isPresent();

        PracticeAttempt restarted = attemptRepository
                .findFirstByUserIdAndTestIdAndSectionIdAndStatusOrderByCreatedAtDesc(
                        student.getId(),
                        fixture.testId(),
                        fixture.sectionId(),
                        PracticeAttempt.STATUS_IN_PROGRESS)
                .orElseThrow();
        assertThat(restarted.getId())
                .isNotEqualTo(fixture.attemptId());
        assertThat(restarted.getPublishedVersionId())
                .isEqualTo(finalized.getPublishedVersionId());
        verifyNoWritingEvaluationCall();
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testDiscardAttempt() throws Exception {
        PracticeAttempt attempt = new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/discard")
                        .with(csrf())
                        .param("setId", String.valueOf(practiceSet.getId()))
                        .param("testId", String.valueOf(defaultTest.getId())))
                .andExpect(status().is3xxRedirection());

        PracticeAttempt discarded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(discarded.getStatus()).isEqualTo(PracticeAttempt.STATUS_DISCARDED);
        assertThat(discarded.getDiscardedAt()).isNotNull();

        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/discard")
                        .with(csrf())
                        .param("setId", String.valueOf(practiceSet.getId()))
                        .param("testId", String.valueOf(defaultTest.getId())))
                .andExpect(status().is3xxRedirection());
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getDiscardedAt())
                .isEqualTo(discarded.getDiscardedAt());

        mockMvc.perform(get("/practice/attempts/" + attempt.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        com.ksh.features.practice.web.PracticeRoutes
                                .testDetailPath(
                                        practiceSet.getId(),
                                        defaultTest.getId())));
        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result"))
                .andExpect(status().isNotFound());
        assertThat(detailPageService.buildSkillCards(
                defaultTest.getId(), List.of(defaultSection), student.getId()).get(0).completedAttempts())
                .noneMatch(row -> row.id().equals(discarded.getId()));
        assertThat(progressService.getProgressPageData(student.getId(), "Student", "")
                .overview().totalAttempts())
                .isZero();

        Long restartedId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        assertThat(restartedId).isNotEqualTo(attempt.getId());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testDiscardAttemptRequiresCsrf() throws Exception {
        PracticeAttempt attempt = attemptRepository.saveAndFlush(new PracticeAttempt(
                student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId()));

        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/discard")
                        .param("setId", String.valueOf(practiceSet.getId()))
                        .param("testId", String.valueOf(defaultTest.getId())))
                .andExpect(status().isForbidden());

        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(PracticeAttempt.STATUS_IN_PROGRESS);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn") // different user
    void testDiscardAttemptDeniedForOtherUser() throws Exception {
        PracticeAttempt attempt = new PracticeAttempt(student.getId(), practiceSet.getId(), 1L, "READING", 1L);
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/discard")
                        .with(csrf())
                        .param("setId", String.valueOf(practiceSet.getId()))
                        .param("testId", "1"))
                .andExpect(status().is4xxClientError());

        assertThat(attemptRepository.findById(attempt.getId())).isPresent();
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testDiscardAttemptDeniedForSubmittedAndGraded() throws Exception {
        PracticeAttempt attemptSubmitted = new PracticeAttempt(student.getId(), practiceSet.getId(), 1L, "READING", 1L);
        attemptSubmitted.setStatus("SUBMITTED");
        attemptSubmitted = attemptRepository.saveAndFlush(attemptSubmitted);

        mockMvc.perform(post("/practice/attempts/" + attemptSubmitted.getId() + "/discard")
                        .with(csrf())
                        .param("setId", String.valueOf(practiceSet.getId()))
                        .param("testId", "1"))
                .andExpect(status().is4xxClientError());

        assertThat(attemptRepository.findById(attemptSubmitted.getId())).isPresent();

        PracticeAttempt attemptGraded = new PracticeAttempt(student.getId(), practiceSet.getId(), 1L, "READING", 1L);
        attemptGraded.setStatus("GRADED");
        attemptGraded = attemptRepository.saveAndFlush(attemptGraded);

        mockMvc.perform(post("/practice/attempts/" + attemptGraded.getId() + "/discard")
                        .with(csrf())
                        .param("setId", String.valueOf(practiceSet.getId()))
                        .param("testId", "1"))
                .andExpect(status().is4xxClientError());

        assertThat(attemptRepository.findById(attemptGraded.getId())).isPresent();
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn") // different user
    void testSubmitAttemptDeniedForOtherUser() throws Exception {
        PracticeAttempt attempt = new PracticeAttempt(student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion()))
                        .param("answer_" + question.getId(), "1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn") // different user
    void testResultAccessDeniedForOtherUser() throws Exception {
        PracticeAttempt attempt = new PracticeAttempt(student.getId(), practiceSet.getId(), defaultTest.getId(), "READING", defaultSection.getId());
        attempt.setStatus("SUBMITTED");
        attempt = attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result/detail"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testMixedSetSkillBasedRouting() throws Exception {
        // Set skill=MIXED
        practiceSet.setSkill("MIXED");
        setRepository.saveAndFlush(practiceSet);

        publishVersion(practiceSet.getId());

        // 1. Reading attempt -> canonical overview & objective detail
        Long readingAttemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        PracticeAttempt readingAttempt = attemptRepository.findById(readingAttemptId).orElseThrow();
        readingAttempt.markSubmitted(BigDecimal.ZERO, BigDecimal.valueOf(2.5), "{}");
        readingAttempt = attemptRepository.saveAndFlush(readingAttempt);

        mockMvc.perform(get("/practice/attempts/" + readingAttempt.getId() + "/result"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result"));

        mockMvc.perform(get("/practice/attempts/" + readingAttempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-objective"));

        // 2. Writing attempt -> result & result-detail
        PracticeSection writingSection = new PracticeSection(practiceSet.getId(), "Phần Viết", "WRITING", "ESSAY", "Viết luận", 50, BigDecimal.TEN, 2);
        writingSection.setTestId(defaultTest.getId());
        writingSection = sectionRepository.saveAndFlush(writingSection);

        PracticeQuestionGroup group2 = new PracticeQuestionGroup(practiceSet.getId(), "Phần 2", 2, 2, "Viết đoạn văn", null, null, 2);
        group2.setSectionId(writingSection.getId());
        group2 = groupRepository.saveAndFlush(group2);
        PracticeQuestion writingQuestion = new PracticeQuestion(
                practiceSet.getId(), 53, "ESSAY", "Viết một đoạn văn ngắn.",
                "[]", "", "Yêu cầu viết đoạn văn.", BigDecimal.TEN, 2);
        writingQuestion.setWritingTaskType(WritingTaskType.Q53);
        writingQuestion.setGroupId(group2.getId());
        questionRepository.saveAndFlush(writingQuestion);

        publishVersion(practiceSet.getId());
        Long writingAttemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), writingSection.getId(), student.getId());
        PracticeAttempt writingAttempt = attemptRepository.findById(writingAttemptId).orElseThrow();
        writingAttempt.markGraded(BigDecimal.valueOf(70), BigDecimal.TEN, "{}", "{}");
        writingAttempt = attemptRepository.saveAndFlush(writingAttempt);

        mockMvc.perform(get("/practice/attempts/" + writingAttempt.getId() + "/result"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result"));

        mockMvc.perform(get("/practice/attempts/" + writingAttempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-writing"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void speakingAttemptPreflightDiscardsLegacySnapshotMissingDeliveryJson() throws Exception {
        SpeakingAttemptFixture fixture = createLegacySpeakingInProgressAttempt("Legacy speaking missing delivery");

        IllegalStateException invalidDelivery = assertThrows(
                IllegalStateException.class,
                () -> practiceService.getSpeakingPlayerDelivery(fixture.attemptId(), student.getId()));
        assertThat(invalidDelivery)
                .hasMessageContaining("Speaking question has invalid immutable delivery")
                .hasRootCauseMessage("Câu Speaking v1 thiếu audio đề bài bất biến.");

        mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/speaking-check"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/sets/" + fixture.setId()
                        + "/tests/" + fixture.testId()))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString(
                        "Nội dung Speaking này chưa có audio hoặc thời lượng hợp lệ")));

        PracticeAttempt discarded = attemptRepository.findById(fixture.attemptId()).orElseThrow();
        assertThat(discarded.getStatus()).isEqualTo(PracticeAttempt.STATUS_DISCARDED);
        assertThat(discarded.getDiscardedAt()).isNotNull();
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void listeningPreflightUnlocksImmutableAttemptOnlyForCompletedSession() throws Exception {
        PracticeSet listeningSet = setRepository.saveAndFlush(new PracticeSet(
                "Listening preflight integration", "Desc", "LISTENING", "GLOBAL",
                null, null, "{}", "PUBLISHED", lecturer.getId()));
        PracticeTest listeningTest = testRepository.saveAndFlush(new PracticeTest(
                listeningSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection listeningSection = new PracticeSection(
                listeningSet.getId(), "Phần Nghe", "LISTENING", "SINGLE_CHOICE",
                "Nghe và chọn đáp án", 40, BigDecimal.TEN, 1);
        listeningSection.setTestId(listeningTest.getId());
        listeningSection.setDeliveryJson("""
                {"schemaVersion":"practice-section-delivery-v1",
                 "listeningDelivery":{"checkAudioReference":"/practice/materials/12/content"}}
                """);
        listeningSection = sectionRepository.saveAndFlush(listeningSection);

        PracticeQuestionGroup group = new PracticeQuestionGroup(
                listeningSet.getId(), "L1.1", 1, 1, "Nghe đoạn hội thoại.",
                "/practice/materials/13/content", null, 1);
        group.setSectionId(listeningSection.getId());
        group = groupRepository.saveAndFlush(group);
        PracticeQuestion listeningQuestion = new PracticeQuestion(
                listeningSet.getId(), 1, PracticeQuestion.TYPE_SINGLE_CHOICE,
                "Đáp án nào đúng?", "[\"A\",\"B\"]", "1", "Giải thích",
                BigDecimal.TEN, 0);
        listeningQuestion.setGroupId(group.getId());
        questionRepository.saveAndFlush(listeningQuestion);
        publishVersion(listeningSet.getId());

        String livePreflightPath = "/practice/sets/" + listeningSet.getId()
                + "/tests/" + listeningTest.getId()
                + "/sections/" + listeningSection.getId() + "/listening-check";
        MockHttpSession completedSession = new MockHttpSession();
        mockMvc.perform(get(livePreflightPath).session(completedSession))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/listening-preflight"))
                .andExpect(model().attribute(
                        "listeningCheckAudioReference", "/practice/materials/12/content"));

        String completedRedirect = mockMvc.perform(
                        post(livePreflightPath).session(completedSession).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        PracticeAttempt attempt = attemptRepository.findAll().stream()
                .filter(candidate -> listeningSet.getId().equals(candidate.getSetId()))
                .findFirst()
                .orElseThrow();
        String attemptPath = "/practice/attempts/" + attempt.getId();
        assertThat(completedRedirect).isEqualTo(attemptPath + "?mode=practice");
        mockMvc.perform(get(attemptPath).session(completedSession))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/player"));

        mockMvc.perform(get(attemptPath).session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(attemptPath + "/listening-check"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testReadingResultDetailUsesTypedImmutableBoundary() throws Exception {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        PracticeAttempt readingAttempt = attemptRepository.findById(attemptId).orElseThrow();
        readingAttempt.markSubmitted(BigDecimal.ZERO, BigDecimal.valueOf(2.5), "{}");
        readingAttempt = attemptRepository.saveAndFlush(readingAttempt);

        mockMvc.perform(get("/practice/attempts/" + readingAttempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-objective"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-result-detail-kind=\"OBJECTIVE_DETAIL\"")));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testReadingResultDetailWithoutImmutableSnapshotFailsClosed() {
        PracticeSet emptySet = new PracticeSet(
                "Empty Reading Detail Set", "Desc", "READING",  "GLOBAL", null, null, null, "PUBLISHED", lecturer.getId()
        );
        emptySet = setRepository.saveAndFlush(emptySet);

        PracticeTest emptyTest = new PracticeTest(emptySet.getId(), "Empty Test", "Desc", 1, 40);
        emptyTest = testRepository.saveAndFlush(emptyTest);

        PracticeSection emptySection = new PracticeSection(
                emptySet.getId(), "Empty Section", "READING", "DEFAULT", "Desc", 50, BigDecimal.TEN, 1
        );
        emptySection.setTestId(emptyTest.getId());
        emptySection = sectionRepository.saveAndFlush(emptySection);

        PracticeAttempt readingAttempt = new PracticeAttempt(
                student.getId(), emptySet.getId(), emptyTest.getId(), "READING", emptySection.getId());
        readingAttempt.setStatus("SUBMITTED");
        readingAttempt = attemptRepository.saveAndFlush(readingAttempt);

        Long attemptId = readingAttempt.getId();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resultDetailAssembler.assemble(attemptId, student.getId(), null));
        assertThat(error.getMessage()).contains("khóa phiên bản bất biến đầy đủ");
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testMultipleSectionsIsolatesScoring() throws Exception {
        // Seed a published WRITING set
        PracticeSet writingSet = new PracticeSet(
                "Multi-Section Writing Set", "Desc", "WRITING",  "GLOBAL", null, null, null, "PUBLISHED", lecturer.getId()
        );
        writingSet = setRepository.saveAndFlush(writingSet);

        PracticeTest test = new PracticeTest(writingSet.getId(), "Test 1", "Desc", 1, 40);
        test = testRepository.saveAndFlush(test);

        // Section A (Points = 10)
        PracticeSection sectionA = new PracticeSection(writingSet.getId(), "Section A", "WRITING", "ESSAY", "Desc", 50, BigDecimal.TEN, 1);
        sectionA.setTestId(test.getId());
        sectionA = sectionRepository.saveAndFlush(sectionA);

        // Section B (Points = 20)
        PracticeSection sectionB = new PracticeSection(writingSet.getId(), "Section B", "WRITING", "ESSAY", "Desc", 50, BigDecimal.valueOf(20.0), 2);
        sectionB.setTestId(test.getId());
        sectionB = sectionRepository.saveAndFlush(sectionB);

        // Group A and Question A
        PracticeQuestionGroup groupA = new PracticeQuestionGroup(writingSet.getId(), "Group A", 1, 1, "Desc", null, null, 1);
        groupA.setSectionId(sectionA.getId());
        groupA = groupRepository.saveAndFlush(groupA);

        PracticeQuestion qA = new PracticeQuestion(writingSet.getId(), 51, "ESSAY", "Prompt A", "[]", "", "Explain", BigDecimal.valueOf(10.0), 0);
        qA.setWritingTaskType(WritingTaskType.Q51);
        qA.setGroupId(groupA.getId());
        qA = questionRepository.saveAndFlush(qA);

        // Group B and Question B
        PracticeQuestionGroup groupB = new PracticeQuestionGroup(writingSet.getId(), "Group B", 2, 2, "Desc", null, null, 2);
        groupB.setSectionId(sectionB.getId());
        groupB = groupRepository.saveAndFlush(groupB);

        PracticeQuestion qB = new PracticeQuestion(writingSet.getId(), 52, "ESSAY", "Prompt B", "[]", "", "Explain", BigDecimal.valueOf(20.0), 0);
        qB.setWritingTaskType(WritingTaskType.Q52);
        qB.setGroupId(groupB.getId());
        qB = questionRepository.saveAndFlush(qB);

        // Start attempt on Section A
        PracticeAttempt attempt = new PracticeAttempt(student.getId(), writingSet.getId(), test.getId(), "WRITING", sectionA.getId());
        attempt.setStatus("IN_PROGRESS");
        attempt = attemptRepository.saveAndFlush(attempt);

        // Submit for Section A attempt
        mockMvc.perform(post("/practice/attempts/" + attempt.getId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion()))
                        .param("answer_" + qA.getId(), "Student Answer A"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/attempts/" + attempt.getId() + "/result"));

        // Queue-time denominator is frozen from Section A only. Provider work
        // has not run inside the learner request.
        PracticeAttempt queuedAttempt = attemptRepository.findById(
                attempt.getId()).orElseThrow();
        assertEquals(0, queuedAttempt.getTotalPoints().compareTo(
                BigDecimal.valueOf(10.0)));
        assertNull(queuedAttempt.getScore());
        assertEquals(
                PracticeAttempt.ANALYSIS_QUEUED,
                queuedAttempt.getAnalysisStatus());
        verify(writingEvaluationClient, never()).evaluate(
                anyLong(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void unansweredQ51AndQ52SubmitAsEmptyAnswersWithoutHttp500() throws Exception {
        PracticeSet set = setRepository.saveAndFlush(new PracticeSet(
                "Blank Q51 Q52 submission", "Desc", "WRITING", "GLOBAL",
                null, null, "{}", "PUBLISHED", lecturer.getId()));
        PracticeTest test = testRepository.saveAndFlush(new PracticeTest(
                set.getId(), "Writing", "Desc", 1, 40));
        PracticeSection section = new PracticeSection(
                set.getId(), "Writing", "WRITING", "ESSAY", "Desc", 50,
                BigDecimal.valueOf(20), 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);
        PracticeQuestionGroup group = new PracticeQuestionGroup(
                set.getId(), "Q51-Q52", 51, 52, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);

        PracticeQuestion q51 = new PracticeQuestion(
                set.getId(), 51, "ESSAY", "Prompt Q51", "[]", "", "Explain",
                BigDecimal.TEN, 1);
        q51.setWritingTaskType(WritingTaskType.Q51);
        q51.setGroupId(group.getId());
        questionRepository.saveAndFlush(q51);
        PracticeQuestion q52 = new PracticeQuestion(
                set.getId(), 52, "ESSAY", "Prompt Q52", "[]", "", "Explain",
                BigDecimal.TEN, 2);
        q52.setWritingTaskType(WritingTaskType.Q52);
        q52.setGroupId(group.getId());
        questionRepository.saveAndFlush(q52);
        publishVersion(set.getId());

        Long attemptId = practiceService.startAttempt(
                set.getId(), test.getId(), section.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();

        mockMvc.perform(post("/practice/attempts/" + attemptId + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(attempt.getLockVersion())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/attempts/" + attemptId + "/result"));

        PracticeAttempt submitted = attemptRepository.findById(attemptId).orElseThrow();
        assertNotEquals(PracticeAttempt.STATUS_IN_PROGRESS, submitted.getStatus());
        assertFalse(submitted.getAnswersJson().contains("\"responses\":null"));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testResultRenderSecurityEscaping() throws Exception {
        // Seed a published WRITING set
        PracticeSet writingSet = new PracticeSet(
                "Security Writing Set", "Desc", "WRITING",  "GLOBAL", null, null, null, "PUBLISHED", lecturer.getId()
        );
        writingSet = setRepository.saveAndFlush(writingSet);

        PracticeTest test = new PracticeTest(writingSet.getId(), "Test 1", "Desc", 1, 40);
        test = testRepository.saveAndFlush(test);

        PracticeSection section = new PracticeSection(writingSet.getId(), "Section 1", "WRITING", "ESSAY", "Desc", 50, BigDecimal.TEN, 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);

        PracticeQuestionGroup group = new PracticeQuestionGroup(writingSet.getId(), "Group 1", 1, 1, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);

        PracticeQuestion q = new PracticeQuestion(writingSet.getId(), 51, "ESSAY", "Prompt 1", "[]", "", "Explain", BigDecimal.valueOf(10.0), 0);
        q.setWritingTaskType(WritingTaskType.Q51);
        q.setGroupId(group.getId());
        q = questionRepository.saveAndFlush(q);
        publishVersion(writingSet.getId());

        // Malicious input payload containing characters to escape
        String maliciousAnswer = "Tôi học tiếng Hàn 한국어 </script> <script>alert('hack')</script> \"quotes\" \\ backslash \n newline";

        // Start through the immutable published-version boundary, then attach
        // the malicious stored payload to the locked attempt.
        Long attemptId = practiceService.startAttempt(
                writingSet.getId(), test.getId(), section.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();

        // Write the structures
        Map<String, String> answersMap = Map.of(String.valueOf(q.getId()), maliciousAnswer);
        String answersJson = objectMapper.writeValueAsString(answersMap);

        com.fasterxml.jackson.databind.node.ObjectNode qFeedback =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        objectMapper.readTree(currentWritingFeedback(
                                WritingTaskType.Q51,
                                "8",
                                "Bài tốt </script> <script>alert(1)</script> "
                                        + "\"nháy\" \\ gạch",
                                maliciousAnswer));
        qFeedback.put("upgraded_answer", "Nâng cấp </script> <script>alert(2)</script>");

        com.fasterxml.jackson.databind.node.ObjectNode feedbackMap =
                objectMapper.createObjectNode();
        feedbackMap.set(String.valueOf(q.getId()), qFeedback);
        String aiFeedbackJson =
                objectMapper.writeValueAsString(feedbackMap);

        attempt.markGraded(BigDecimal.valueOf(80.00), BigDecimal.valueOf(10.0), answersJson, aiFeedbackJson);
        attempt = attemptRepository.saveAndFlush(attempt);

        // Load result detail page
        mockMvc.perform(get("/practice/attempts/" + attempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                // The typed server-rendered boundary does not serialize provider feedback into a script.
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("</script> <script>alert"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("JSON.parse"))))
                .andExpect(view().name("practice/result-detail-writing"));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testConcurrentWritingSubmitOnlyOneCommit() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Concurrent Submit Writing", false);
        CyclicBarrier evaluatorBarrier = new CyclicBarrier(2);
        AtomicInteger evaluatorCalls = new AtomicInteger();
        when(writingEvaluationClient.evaluate(eq(student.getId()), eq(fixture.prompt()), anyString(), eq(false), any()))
                .thenAnswer(invocation -> {
                    evaluatorCalls.incrementAndGet();
                    evaluatorBarrier.await(5, TimeUnit.SECONDS);
                    return currentWritingFeedback(
                            WritingTaskType.Q51,
                            "8",
                            "Đánh giá đồng thời",
                            invocation.getArgument(2, String.class));
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> submit = () -> {
                try {
                    return practiceService.submitAttempt(
                            fixture.attemptId(),
                            student.getId(),
                            Map.of("answer_" + fixture.questionId(), "Concurrent answer")
                    );
                } catch (Exception ex) {
                    return ex;
                }
            };

            Future<Object> first = executor.submit(submit);
            Future<Object> second = executor.submit(submit);
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);

            long successes = List.of(firstResult, secondResult).stream()
                    .filter(result -> result instanceof Long)
                    .count();
            long conflicts = List.of(firstResult, secondResult).stream()
                    .filter(result -> result instanceof PracticeAttemptConflictException)
                    .count();

            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(2, evaluatorCalls.get());

            PracticeAttempt finalAttempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
            assertEquals("GRADED", finalAttempt.getStatus());
            assertEquals(0, finalAttempt.getScore().compareTo(BigDecimal.valueOf(80.00)));
            assertTrue(objectMapper.readTree(finalAttempt.getAiFeedbackJson()).has(String.valueOf(fixture.questionId())));
        } finally {
            executor.shutdownNow();
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingSubmitAutosaveConflictReturnsHttp409AndKeepsAutosavedAnswers() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Autosave Conflict Writing", false);
        try {
        Long staleVersion = attemptRepository.findById(
                fixture.attemptId()).orElseThrow().getLockVersion();
        practiceService.saveInProgressAnswers(
                fixture.attemptId(),
                student.getId(),
                staleVersion,
                Map.of("answer_" + fixture.questionId(), "Autosaved answer"));

        mockMvc.perform(post("/practice/attempts/" + fixture.attemptId() + "/submit")
                        .with(csrf())
                        .param("expectedLockVersion", String.valueOf(staleVersion))
                        .param("answer_" + fixture.questionId(), "Submitted answer"))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bài làm đã thay đổi")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Submitted answer"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(fixture.prompt()))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("lockVersion"))));

        PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
        assertEquals("IN_PROGRESS", attempt.getStatus());
        assertTrue(attempt.getAnswersJson().contains("Autosaved answer"));
        assertFalse(attempt.getAnswersJson().contains("Submitted answer"));
        verify(writingEvaluationClient, never()).evaluate(
                anyLong(), anyString(), anyString(), anyBoolean(), any());
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingSubmitAfterDiscardDoesNotRecreateAttempt() {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Discard Conflict Writing", false);
        try {
        when(writingEvaluationClient.evaluate(eq(student.getId()), eq(fixture.prompt()), anyString(), eq(false), any()))
                .thenAnswer(invocation -> {
                    attemptDiscardService.discardForOwner(fixture.attemptId(), student.getId());
                    return currentWritingFeedback(
                            WritingTaskType.Q51,
                            "8",
                            "Đánh giá trước xung đột",
                            invocation.getArgument(2, String.class));
                });

        assertThrows(PracticeAttemptConflictException.class,
                () -> practiceService.submitAttempt(
                        fixture.attemptId(),
                        student.getId(),
                        Map.of("answer_" + fixture.questionId(), "Submitted answer")
                ));

        assertEquals(PracticeAttempt.STATUS_DISCARDED,
                attemptRepository.findById(fixture.attemptId()).orElseThrow().getStatus());
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }


    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingReEvaluateStaleAnswersConflictPreservesOldResultAndRunsOutsideTransaction() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Reevaluate Conflict Writing", true);
        final boolean[] evaluatorSawTransaction = {true};
        try {
        when(writingEvaluationClient.evaluate(eq(student.getId()), eq(fixture.prompt()), anyString(), eq(true), any()))
                .thenAnswer(invocation -> {
                    evaluatorSawTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
                    TransactionTemplate template = new TransactionTemplate(transactionManager);
                    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    template.execute(status -> {
                        PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
                        attempt.setAnswersJson("{\"" + fixture.questionId() + "\":\"Changed after snapshot\"}");
                        attemptRepository.saveAndFlush(attempt);
                        return null;
                    });
                    return currentWritingFeedback(
                            WritingTaskType.Q51,
                            "9",
                            "Đánh giá lại",
                            invocation.getArgument(2, String.class));
                });

        PracticeAttemptConflictException ex = assertThrows(PracticeAttemptConflictException.class,
                () -> practiceService.reEvaluate(fixture.attemptId(), student.getId()));

        assertTrue(ex.getMessage().contains("Bài làm đã thay đổi"));
        assertFalse(evaluatorSawTransaction[0]);
        PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
        assertEquals("GRADED", attempt.getStatus());
        assertEquals(0, attempt.getScore().compareTo(BigDecimal.valueOf(80.00)));
        assertEquals(objectMapper.readTree(fixture.oldFeedbackJson()), objectMapper.readTree(attempt.getAiFeedbackJson()));
        assertTrue(attempt.getAnswersJson().contains("Changed after snapshot"));
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }


    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingQuestionReEvaluateEndpointQueuesQuestionIdWithoutProviderCall() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Question Reevaluate Writing", true);
        try {
            String beforeAnswersJson = attemptRepository.findById(fixture.attemptId()).orElseThrow().getAnswersJson();
            clearInvocations(writingEvaluationClient);

            mockMvc.perform(post("/practice/attempts/" + fixture.attemptId() + "/re-evaluate")
                            .with(csrf())
                            .param("questionId", String.valueOf(fixture.questionId())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/practice/attempts/" + fixture.attemptId() + "/result/detail?questionId=" + fixture.questionId()))
                    .andExpect(flash().attribute(
                            "success",
                            "Đã xếp lịch chấm lại. Kết quả hiện tại được giữ nguyên cho đến khi có đánh giá mới."));

            PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
            assertEquals("GRADED", attempt.getStatus());
            assertEquals(beforeAnswersJson, attempt.getAnswersJson());
            assertEquals(
                    PracticeAttempt.ANALYSIS_QUEUED,
                    attempt.getAnalysisStatus());
            var job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            assertEquals(
                    com.ksh.entities.PracticeAttemptEvaluationJob
                            .OPERATION_QUESTION_REEVALUATE,
                    job.getOperation());
            assertEquals(fixture.questionId(), job.getTargetQuestionId());
            verifyNoWritingEvaluationCall();

            mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/result/detail")
                            .param("questionId", String.valueOf(fixture.questionId())))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-writing"))
                    .andExpect(model().attributeExists("resultDetail"))
                    .andExpect(model().attributeDoesNotExist("questionsJson", "groupsJson"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "data-result-detail-kind=\"WRITING_DETAIL\"")));
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingResultDetailRendersPerQuestionReEvaluateForm() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Question Reevaluate UI", true);
        try {
            mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/result/detail")
                            .param("questionId", String.valueOf(fixture.questionId())))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-writing"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"questionReEvaluateForm\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("method=\"post\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("/practice/attempts/" + fixture.attemptId() + "/re-evaluate")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"questionId\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Chấm lại câu này")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("JSON.parse"))));
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingResultDetailInvalidQuestionIdFallsBackToFirstQuestion() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Invalid Active Question UI", true);
        try {
            mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/result/detail")
                            .param("questionId", "999999999"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-writing"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(fixture.prompt())));
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingResultDetailDoesNotRestoreMcqQuestionId() throws Exception {
        WritingMixedAttemptFixture fixture = createWritingMixedAttemptFixture("MCQ Active Question UI");
        try {
            mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/result/detail")
                            .param("questionId", String.valueOf(fixture.mcqQuestionId())))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-writing"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "questionId=" + fixture.essayQuestionId())))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                            "questionId=" + fixture.mcqQuestionId()))));
        } finally {
            deleteWritingMixedAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingResultDetailForeignQuestionIdFallsBackWithoutLeak() throws Exception {
        WritingAttemptFixture target = createWritingAttemptFixture("Target Active Question UI", true);
        WritingAttemptFixture foreign = createWritingAttemptFixture("Foreign Active Question UI", true);
        try {
            mockMvc.perform(get("/practice/attempts/" + target.attemptId() + "/result/detail")
                            .param("questionId", String.valueOf(foreign.questionId())))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-writing"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Prompt Target Active Question UI")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Prompt Foreign Active Question UI"))));
        } finally {
            deleteWritingAttemptFixture(target);
            deleteWritingAttemptFixture(foreign);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingFullReEvaluateEndpointQueuesWithoutProviderCallAndRedirectsOverview() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Full Reevaluate Regression UI", true);
        try {
            clearInvocations(writingEvaluationClient);

            mockMvc.perform(post("/practice/attempts/" + fixture.attemptId() + "/re-evaluate")
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/practice/attempts/" + fixture.attemptId() + "/result"));

            assertThat(attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId()))
                    .get()
                    .extracting(
                            com.ksh.entities.PracticeAttemptEvaluationJob
                                    ::getOperation)
                    .isEqualTo(
                            com.ksh.entities.PracticeAttemptEvaluationJob
                                    .OPERATION_FULL_REEVALUATE);
            verifyNoWritingEvaluationCall();
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testReadingResultDetailDoesNotRenderPerQuestionReEvaluateForm() throws Exception {
        Long attemptId = practiceService.startAttempt(
                practiceSet.getId(), defaultTest.getId(), defaultSection.getId(), student.getId());
        PracticeAttempt readingAttempt = attemptRepository.findById(attemptId).orElseThrow();
        readingAttempt.markSubmitted(BigDecimal.ZERO, BigDecimal.valueOf(2.5), "{}");
        readingAttempt = attemptRepository.saveAndFlush(readingAttempt);

        mockMvc.perform(get("/practice/attempts/" + readingAttempt.getId() + "/result/detail"))
                .andExpect(status().isOk())
                .andExpect(view().name("practice/result-detail-objective"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<form id=\"questionReEvaluateForm\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Chấm lại câu này"))));
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testListeningResultDetailDoesNotRenderPerQuestionReEvaluateForm() throws Exception {
        ListeningAttemptFixture fixture = createListeningAttemptFixture("Listening Reevaluate UI");
        try {
            mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/result/detail"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-objective"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<form id=\"questionReEvaluateForm\""))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Chấm lại câu này"))));
        } finally {
            deleteListeningAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testSpeakingResultDetailDoesNotRenderPerQuestionReEvaluateForm() throws Exception {
        SpeakingAttemptFixture fixture = createSpeakingAttemptFixture("Speaking Reevaluate UI");
        try {
            mockMvc.perform(get("/practice/attempts/" + fixture.attemptId() + "/result/detail"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("practice/result-detail-speaking"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "data-result-detail-kind=\"SPEAKING_DETAIL\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Không có điểm Nói tổng hợp"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<form id=\"questionReEvaluateForm\""))));
        } finally {
            deleteSpeakingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingQuestionReEvaluateDuplicateRequestIsIdempotent() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Question Reevaluate Idempotency UI", true);
        try {
            clearInvocations(writingEvaluationClient);
            String endpoint =
                    "/practice/attempts/" + fixture.attemptId()
                            + "/re-evaluate";
            mockMvc.perform(post(endpoint)
                            .with(csrf())
                            .param("questionId", String.valueOf(fixture.questionId())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("success"));

            mockMvc.perform(post(endpoint)
                            .with(csrf())
                            .param("questionId", String.valueOf(fixture.questionId())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/practice/attempts/" + fixture.attemptId() + "/result/detail?questionId=" + fixture.questionId()))
                    .andExpect(flash().attribute(
                            "info",
                            "Yêu cầu chấm lại đang được xử lý."));

            assertThat(attemptEvaluationJobRepository.count()).isEqualTo(1);
            verifyNoWritingEvaluationCall();
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation =
            org.springframework.transaction.annotation.Propagation
                    .NOT_SUPPORTED)
    void writingManualReEvaluationHasLifetimeProviderCostQuota() {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Writing Manual Retry Quota", true);
        try {
            clearInvocations(writingEvaluationClient);
            PracticeService.ReEvaluationRequestResult initial =
                    practiceService.requestReEvaluation(
                            fixture.attemptId(),
                            fixture.questionId(),
                            student.getId());
            assertThat(initial.status()).isEqualTo("QUEUED");

            var job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            job.markTerminal(
                    com.ksh.entities.PracticeAttemptEvaluationJob
                            .STATUS_SUCCEEDED,
                    "{}",
                    null,
                    false,
                    LocalDateTime.now());
            attemptEvaluationJobRepository.saveAndFlush(job);

            assertThat(practiceService.requestReEvaluation(
                    fixture.attemptId(),
                    fixture.questionId(),
                    student.getId()).status())
                    .isEqualTo("QUEUED");
            job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            job.markTerminal(
                    com.ksh.entities.PracticeAttemptEvaluationJob
                            .STATUS_SUCCEEDED,
                    "{}",
                    null,
                    false,
                    LocalDateTime.now());
            org.springframework.test.util.ReflectionTestUtils.setField(
                    job,
                    "lastRetryRequestedAt",
                    LocalDateTime.now().minusMinutes(2));
            attemptEvaluationJobRepository.saveAndFlush(job);

            assertThat(practiceService.requestReEvaluation(
                    fixture.attemptId(),
                    fixture.questionId(),
                    student.getId()).status())
                    .isEqualTo("QUEUED");
            job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            job.markTerminal(
                    com.ksh.entities.PracticeAttemptEvaluationJob
                            .STATUS_SUCCEEDED,
                    "{}",
                    null,
                    false,
                    LocalDateTime.now());
            org.springframework.test.util.ReflectionTestUtils.setField(
                    job,
                    "lastRetryRequestedAt",
                    LocalDateTime.now().minusMinutes(2));
            attemptEvaluationJobRepository.saveAndFlush(job);

            PracticeService.ReEvaluationRequestResult exhausted =
                    practiceService.requestReEvaluation(
                            fixture.attemptId(),
                            fixture.questionId(),
                            student.getId());
            assertThat(exhausted.status())
                    .isEqualTo("RETRY_LIMIT_REACHED");
            assertThat(attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow()
                    .getManualRetryCount())
                    .isEqualTo(2);
            verifyNoWritingEvaluationCall();
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testWritingQuestionReEvaluateRequiresCsrf() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Question Reevaluate CSRF", true);
        try {
            mockMvc.perform(post("/practice/attempts/" + fixture.attemptId() + "/re-evaluate")
                            .param("questionId", String.valueOf(fixture.questionId())))
                    .andExpect(status().isForbidden());
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingQuestionReEvaluateStaleAnswersConflictPreservesOldResultAndRunsOutsideTransaction() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Question Reevaluate Stale Answers", true);
        final boolean[] evaluatorSawTransaction = {true};
        try {
            when(writingEvaluationClient.evaluate(eq(student.getId()), eq(fixture.prompt()), anyString(), eq(true), any()))
                    .thenAnswer(invocation -> {
                        evaluatorSawTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
                        TransactionTemplate template = new TransactionTemplate(transactionManager);
                        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        template.execute(status -> {
                            PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
                            attempt.setAnswersJson("{\"" + fixture.questionId() + "\":\"Changed after snapshot\"}");
                            attemptRepository.saveAndFlush(attempt);
                            return null;
                        });
                        return currentWritingFeedback(
                                WritingTaskType.Q51,
                                "9",
                                "Đánh giá lại câu hỏi",
                                invocation.getArgument(2, String.class));
                    });

            assertThrows(PracticeAttemptConflictException.class,
                    () -> practiceService.reEvaluateQuestion(fixture.attemptId(), fixture.questionId(), student.getId()));

            assertFalse(evaluatorSawTransaction[0]);
            PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
            assertEquals("GRADED", attempt.getStatus());
            assertEquals(0, attempt.getScore().compareTo(BigDecimal.valueOf(80.00)));
            assertEquals(objectMapper.readTree(fixture.oldFeedbackJson()), objectMapper.readTree(attempt.getAiFeedbackJson()));
            assertTrue(attempt.getAnswersJson().contains("Changed after snapshot"));
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testWritingQuestionReEvaluateAfterDiscardDoesNotRecreateAttempt() {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Question Reevaluate Discard", true);
        try {
            when(writingEvaluationClient.evaluate(eq(student.getId()), eq(fixture.prompt()), anyString(), eq(true), any()))
                    .thenAnswer(invocation -> {
                        TransactionTemplate template = new TransactionTemplate(transactionManager);
                        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        template.execute(status -> {
                            PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
                            attemptRepository.delete(attempt);
                            attemptRepository.flush();
                            return null;
                        });
                        return currentWritingFeedback(
                                WritingTaskType.Q51,
                                "9",
                                "Đánh giá trước khi bài làm bị xóa",
                                invocation.getArgument(2, String.class));
                    });

            assertThrows(jakarta.persistence.EntityNotFoundException.class,
                    () -> practiceService.reEvaluateQuestion(fixture.attemptId(), fixture.questionId(), student.getId()));

            assertTrue(attemptRepository.findById(fixture.attemptId()).isEmpty());
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void testConcurrentWritingQuestionReEvaluateSameTargetOnlyOneCommit() throws Exception {
        WritingAttemptFixture fixture = createWritingAttemptFixture("Concurrent Question Reevaluate Writing", true);
        CyclicBarrier evaluatorBarrier = new CyclicBarrier(2);
        AtomicInteger evaluatorCalls = new AtomicInteger();
        when(writingEvaluationClient.evaluate(eq(student.getId()), eq(fixture.prompt()), anyString(), eq(true), any()))
                .thenAnswer(invocation -> {
                    evaluatorCalls.incrementAndGet();
                    evaluatorBarrier.await(5, TimeUnit.SECONDS);
                    return currentWritingFeedback(
                            WritingTaskType.Q51,
                            "9",
                            "Đánh giá lại đồng thời",
                            invocation.getArgument(2, String.class));
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> reEvaluateQuestion = () -> {
                try {
                    return practiceService.reEvaluateQuestion(fixture.attemptId(), fixture.questionId(), student.getId());
                } catch (Exception ex) {
                    return ex;
                }
            };

            Future<Object> first = executor.submit(reEvaluateQuestion);
            Future<Object> second = executor.submit(reEvaluateQuestion);
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);

            long successes = List.of(firstResult, secondResult).stream()
                    .filter(result -> result instanceof Long)
                    .count();
            long conflicts = List.of(firstResult, secondResult).stream()
                    .filter(result -> result instanceof PracticeAttemptConflictException)
                    .count();

            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(2, evaluatorCalls.get());

            PracticeAttempt finalAttempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
            assertEquals("GRADED", finalAttempt.getStatus());
            assertEquals(0, finalAttempt.getScore().compareTo(BigDecimal.valueOf(90.00)));
            JsonNode feedback = objectMapper.readTree(finalAttempt.getAiFeedbackJson());
            assertTrue(feedback.has(String.valueOf(fixture.questionId())));
            assertEquals(9.0, feedback.get(String.valueOf(fixture.questionId())).path("raw_score").asDouble());
        } finally {
            executor.shutdownNow();
            deleteWritingAttemptFixture(fixture);
        }
    }


    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void testRestRouteReturns404() throws Exception {
        mockMvc.perform(get("/practice/attempts/1/rest").param("nextSectionIndex", "1"))
                .andExpect(status().isNotFound());
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(10);
        return template;
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test coordination latch", e);
        }
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private <T extends Throwable> T assertFutureCause(Future<?> future, Class<T> expectedType)
            throws InterruptedException, TimeoutException {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS)
        );
        assertTrue(expectedType.isInstance(exception.getCause()),
                () -> "Expected " + expectedType.getSimpleName() + " but got " + exception.getCause());
        return expectedType.cast(exception.getCause());
    }

    private List<Long> questionIds(Long setId) {
        return questionRepository.findBySetIdOrderByDisplayOrderAsc(setId).stream()
                .map(PracticeQuestion::getId)
                .toList();
    }

    private com.ksh.entities.PracticeDraft createRepublishDraft(Long setId, String title) {
        String draftJson = """
                {
                  "document": {"title":"Replacement","confidence":1.0},
                  "sections": [{
                    "title":"Replacement section","skill":"READING","durationMinutes":40,
                    "groups":[{"label":"1","questionFrom":1,"questionTo":1,"instruction":"Instruction",
                      "questions":[{"clientId":"question-1","questionNo":1,"questionType":"SINGLE_CHOICE","prompt":"Replacement prompt",
                        "options":["A","B"],"answer":{"value":"1"},"explanationVi":"Because",
                        "explanationStrategy":{"registryVersion":"rl-explanation-strategy-registry-v1",
                          "strategyCode":"EVIDENCE_ONLY","strategyVersion":"v1"},"points":5.0}]
                    }]
                  }]
                }
                """;
        com.ksh.entities.PracticeDraft draft = new com.ksh.entities.PracticeDraft(
                title, "Desc", "GLOBAL", null, "DRAFT", lecturer.getId(), draftJson);
        draft.setPublishedSetId(setId);
        draft = draftRepository.saveAndFlush(draft);
        approveObjectiveExplanation(draft, "question-1");
        return draft;
    }

    private void approveObjectiveExplanation(
            com.ksh.entities.PracticeDraft draft,
            String questionClientId) {
        var revision = objectiveExplanationEditorialService.saveEditedDraft(
                draft.getId(),
                questionClientId,
                "{\"fixture\":\"strict-validation-owned-by-client-tests\"}",
                lecturer.getId());
        objectiveExplanationEditorialService.approve(
                draft.getId(),
                questionClientId,
                revision.revisionId(),
                lecturer.getId());
    }

    private com.ksh.entities.PracticeEditLog createRestoreLog(Long setId, String title) {
        String snapshot = """
                {
                  "document":{"title":"Restored","description":"Restored description"},
                  "sections":[{
                    "title":"Restored section","skill":"READING","durationMinutes":40,"totalPoints":5.0,
                    "groups":[{"label":"1","instruction":"Instruction",
                      "questions":[{"questionNo":1,"questionType":"SINGLE_CHOICE","prompt":"Restored prompt",
                        "options":["A","B"],"answerKey":"1","explanationVi":"Because","points":5.0}]
                    }]
                  }]
                }
                """;
        return editLogRepository.saveAndFlush(new com.ksh.entities.PracticeEditLog(
                setId, lecturer.getId(), title, "{}", snapshot, "{}", "QUESTIONS"));
    }

    @Test
    void learnerAutosaveUsesCasWhitelistsQuestionsAndHydratesResume() {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Autosave CAS", false);
        PracticeAttempt before = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        Long originalVersion = before.getLockVersion();

        PracticeService.AttemptAnswerSaveResult saved =
                practiceService.saveInProgressAnswers(
                        fixture.attemptId(),
                        student.getId(),
                        originalVersion,
                        Map.of(
                                "answer_" + fixture.questionId(),
                                "저는 한국어로 답합니다.",
                                "answer_999999999",
                                "foreign"));

        assertThat(saved.lockVersion()).isGreaterThan(originalVersion);
        assertThat(saved.answers())
                .containsEntry(
                        fixture.questionId().toString(),
                        "저는 한국어로 답합니다.")
                .doesNotContainKey("999999999");
        PracticeService.AttemptPlayerView resumed =
                practiceService.getAttemptPlayerView(
                        fixture.attemptId(), student.getId());
        assertThat(resumed.savedAnswers())
                .containsEntry(
                        fixture.questionId().toString(),
                        "저는 한국어로 답합니다.");
        assertThatThrownBy(() ->
                practiceService.saveInProgressAnswers(
                        fixture.attemptId(),
                        student.getId(),
                        originalVersion,
                        Map.of(
                                "answer_" + fixture.questionId(),
                                "stale overwrite")))
                .isInstanceOf(PracticeAttemptConflictException.class);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void learnerAutosaveMultipartPutReturnsTypedSavedConflictAndDeadlineStates()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Autosave HTTP", false);
        PracticeAttempt before = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        Long originalVersion = before.getLockVersion();
        String path = "/practice/attempts/"
                + fixture.attemptId() + "/answers";

        mockMvc.perform(multipart(path)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .with(csrf())
                        .param(
                                "expectedLockVersion",
                                String.valueOf(originalVersion))
                        .param(
                                "answer_" + fixture.questionId(),
                                "HTTP 저장 답안")
                        .param("answer_999999999", "foreign")
                        .param("foreign_field", "ignored"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath(
                        "$.answers['"
                                + fixture.questionId() + "']")
                        .value("HTTP 저장 답안"))
                .andExpect(jsonPath(
                        "$.answers['999999999']")
                        .doesNotExist());

        mockMvc.perform(multipart(path)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .with(csrf())
                        .param(
                                "expectedLockVersion",
                                String.valueOf(originalVersion))
                        .param(
                                "answer_" + fixture.questionId(),
                                "stale overwrite"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICT"));

        PracticeAttempt current = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        current.setDeadlineAt(
                LocalDateTime.now().minusSeconds(1));
        current = attemptRepository.saveAndFlush(current);
        Long expiredLockVersion = current.getLockVersion();

        mockMvc.perform(multipart(path)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .with(csrf())
                        .param(
                                "expectedLockVersion",
                                String.valueOf(expiredLockVersion))
                        .param(
                                "answer_" + fixture.questionId(),
                                "late overwrite"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status")
                        .value("DEADLINE_EXPIRED"));
    }

    @Test
    void writingSubmitPersistsQueuedJobBeforeAnyProviderCall() {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Async Submit", false);
        PracticeAttempt before = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        clearInvocations(writingEvaluationClient);

        Long submittedId = practiceService.submitAttempt(
                fixture.attemptId(),
                student.getId(),
                Map.of(
                        "answer_" + fixture.questionId(),
                        "비동기 평가 답안"),
                before.getLockVersion());

        assertThat(submittedId).isEqualTo(fixture.attemptId());
        PracticeAttempt submitted = attemptRepository
                .findById(submittedId).orElseThrow();
        assertThat(submitted.getStatus())
                .isEqualTo(PracticeAttempt.STATUS_SUBMITTED);
        assertThat(submitted.getAnalysisStatus())
                .isEqualTo(PracticeAttempt.ANALYSIS_QUEUED);
        assertThat(submitted.getAnswersJson())
                .contains("비동기 평가 답안");
        com.ksh.entities.PracticeAttemptEvaluationJob job =
                attemptEvaluationJobRepository
                        .findByAttemptId(submittedId)
                        .orElseThrow();
        assertThat(job.getJobStatus())
                .isEqualTo(
                        com.ksh.entities.PracticeAttemptEvaluationJob
                                .STATUS_QUEUED);
        assertThat(job.getEvaluationContractIdentity())
                .isEqualTo(
                        PRODUCTION_SHAPED_WRITING_CONTRACT_IDENTITY);
        verifyNoWritingEvaluationCall();
    }

    @Test
    @Transactional(propagation =
            org.springframework.transaction.annotation.Propagation
                    .NOT_SUPPORTED)
    void writingWorkerClaimsEvaluatesAndCommitsOutsideLearnerRequest()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Async Worker", false);
        try {
            PracticeAttempt before = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            final boolean[] evaluatorSawTransaction = {true};
            when(writingEvaluationClient.evaluate(
                    eq(student.getId()),
                    eq(fixture.prompt()),
                    anyString(),
                    eq(false),
                    any()))
                    .thenAnswer(invocation -> {
                        evaluatorSawTransaction[0] =
                                TransactionSynchronizationManager
                                        .isActualTransactionActive();
                        return currentWritingFeedback(
                                WritingTaskType.Q51,
                                "8",
                                "Đánh giá bất đồng bộ",
                                invocation.getArgument(2, String.class));
                    });

            practiceService.submitAttempt(
                    fixture.attemptId(),
                    student.getId(),
                    Map.of(
                            "answer_" + fixture.questionId(),
                            "비동기 작업 답안"),
                    before.getLockVersion());
            var job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            var claim = attemptEvaluationJobTransactions.claim(
                            job.getId(),
                            "integration-worker",
                            job.getNextAttemptAt())
                    .orElseThrow();

            PracticeAttemptEvaluationOutcome outcome =
                    practiceService.evaluateClaimedAttempt(claim);
            assertThat(attemptEvaluationJobTransactions.complete(
                    claim,
                    outcome,
                    objectMapper.writeValueAsString(outcome),
                    LocalDateTime.now())).isTrue();

            assertThat(evaluatorSawTransaction[0]).isFalse();
            PracticeAttempt completed = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            assertThat(completed.getStatus())
                    .isEqualTo(PracticeAttempt.STATUS_GRADED);
            assertThat(completed.getAnalysisStatus())
                    .isEqualTo(PracticeAttempt.ANALYSIS_SUCCEEDED);
            assertThat(attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow()
                    .getJobStatus())
                    .isEqualTo(
                            com.ksh.entities
                                    .PracticeAttemptEvaluationJob
                                    .STATUS_SUCCEEDED);
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation =
            org.springframework.transaction.annotation.Propagation
                    .NOT_SUPPORTED)
    void writingTransientSubmitRetryKeepsResultPendingWithoutFailurePayload()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Async Retry Pending", false);
        try {
            when(writingEvaluationClient.evaluate(
                    eq(student.getId()),
                    eq(fixture.prompt()),
                    anyString(),
                    eq(false),
                    any()))
                    .thenReturn(currentWritingUnavailable(
                            WritingTaskType.Q51,
                            "EVALUATION_UNAVAILABLE",
                            "PROVIDER_TRANSPORT_ERROR",
                            true));
            PracticeAttempt before = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            practiceService.submitAttempt(
                    fixture.attemptId(),
                    student.getId(),
                    Map.of(
                            "answer_" + fixture.questionId(),
                            "재시도할 답안"),
                    before.getLockVersion());
            var job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            var claim = attemptEvaluationJobTransactions.claim(
                            job.getId(),
                            "integration-retry-worker",
                            job.getNextAttemptAt())
                    .orElseThrow();

            PracticeAttemptEvaluationOutcome outcome =
                    practiceService.evaluateClaimedAttempt(claim);
            assertThat(outcome.terminalStatus())
                    .isEqualTo(
                            PracticeAttemptEvaluationOutcome.UNAVAILABLE);
            assertThat(outcome.retryable()).isTrue();
            assertThat(attemptEvaluationJobTransactions.complete(
                    claim,
                    outcome,
                    objectMapper.writeValueAsString(outcome),
                    LocalDateTime.now())).isTrue();

            PracticeAttempt retrying = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            assertThat(retrying.getStatus())
                    .isEqualTo(PracticeAttempt.STATUS_SUBMITTED);
            assertThat(retrying.getAnalysisStatus())
                    .isEqualTo(PracticeAttempt.ANALYSIS_QUEUED);
            assertThat(retrying.getAiFeedbackJson()).isNull();
            assertThat(attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow()
                    .getJobStatus())
                    .isEqualTo(
                            com.ksh.entities
                                    .PracticeAttemptEvaluationJob
                                    .STATUS_RETRY_WAIT);
            assertThat(resultAssembler.assemble(
                    fixture.attemptId(), student.getId())
                    .feedback().state())
                    .isEqualTo("PENDING");
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation =
            org.springframework.transaction.annotation.Propagation
                    .NOT_SUPPORTED)
    void writingPermanentUnavailableCompletesWithoutAutomaticRetry()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Missing Writing Key", false);
        try {
            when(writingEvaluationClient.evaluate(
                    eq(student.getId()),
                    eq(fixture.prompt()),
                    anyString(),
                    eq(false),
                    any()))
                    .thenReturn(currentWritingUnavailable(
                            WritingTaskType.Q51,
                            "EVALUATION_UNAVAILABLE",
                            "MISSING_API_KEY",
                            false));
            PracticeAttempt before = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            practiceService.submitAttempt(
                    fixture.attemptId(),
                    student.getId(),
                    Map.of(
                            "answer_" + fixture.questionId(),
                            "키가 없어도 보존할 답안"),
                    before.getLockVersion());
            var job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            var claim = attemptEvaluationJobTransactions.claim(
                            job.getId(),
                            "integration-unavailable-worker",
                            job.getNextAttemptAt())
                    .orElseThrow();
            PracticeAttemptEvaluationOutcome outcome =
                    practiceService.evaluateClaimedAttempt(claim);

            assertThat(outcome.retryable()).isFalse();
            assertThat(attemptEvaluationJobTransactions.complete(
                    claim,
                    outcome,
                    objectMapper.writeValueAsString(outcome),
                    LocalDateTime.now())).isTrue();
            assertThat(attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow()
                    .getJobStatus())
                    .isEqualTo(
                            com.ksh.entities
                                    .PracticeAttemptEvaluationJob
                                    .STATUS_UNAVAILABLE);
            PracticeAttempt terminal = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            assertThat(terminal.getAnalysisStatus())
                    .isEqualTo(
                            PracticeAttempt.ANALYSIS_UNAVAILABLE);
            assertThat(terminal.getAnalysisErrorCode())
                    .isEqualTo("MISSING_API_KEY");
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation =
            org.springframework.transaction.annotation.Propagation
                    .NOT_SUPPORTED)
    void writingQuestionRetryPreservesPreviousGradedFeedback()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Question Retry Preservation", true);
        try {
            when(writingEvaluationClient.evaluate(
                    eq(student.getId()),
                    eq(fixture.prompt()),
                    anyString(),
                    eq(true),
                    any()))
                    .thenReturn(currentWritingUnavailable(
                            WritingTaskType.Q51,
                            "EVALUATION_UNAVAILABLE",
                            "PROVIDER_TRANSPORT_ERROR",
                            true));
            assertThat(practiceService.requestReEvaluation(
                    fixture.attemptId(),
                    fixture.questionId(),
                    student.getId()).status())
                    .isEqualTo("QUEUED");
            var job = attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow();
            var claim = attemptEvaluationJobTransactions.claim(
                            job.getId(),
                            "integration-question-retry-worker",
                            job.getNextAttemptAt())
                    .orElseThrow();
            PracticeAttemptEvaluationOutcome outcome =
                    practiceService.evaluateClaimedAttempt(claim);

            assertThat(outcome.retryable()).isTrue();
            assertThat(objectMapper.readTree(outcome.feedbackJson()))
                    .isEqualTo(objectMapper.readTree(
                            fixture.oldFeedbackJson()));
            assertThat(attemptEvaluationJobTransactions.complete(
                    claim,
                    outcome,
                    objectMapper.writeValueAsString(outcome),
                    LocalDateTime.now())).isTrue();

            PracticeAttempt preserved = attemptRepository
                    .findById(fixture.attemptId()).orElseThrow();
            assertThat(preserved.getStatus())
                    .isEqualTo(PracticeAttempt.STATUS_GRADED);
            assertThat(preserved.getAnalysisStatus())
                    .isEqualTo(PracticeAttempt.ANALYSIS_QUEUED);
            assertThat(objectMapper.readTree(
                    preserved.getAiFeedbackJson()))
                    .isEqualTo(objectMapper.readTree(
                            fixture.oldFeedbackJson()));
            assertThat(attemptEvaluationJobRepository
                    .findByAttemptId(fixture.attemptId())
                    .orElseThrow()
                    .getJobStatus())
                    .isEqualTo(
                            com.ksh.entities
                                    .PracticeAttemptEvaluationJob
                                    .STATUS_RETRY_WAIT);
        } finally {
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    @Transactional(propagation =
            org.springframework.transaction.annotation.Propagation
                    .NOT_SUPPORTED)
    void concurrentFirstWritingReEvaluationIsIdempotent()
            throws Exception {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Concurrent Durable Re-evaluation", true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Callable<PracticeService.ReEvaluationRequestResult> request =
                    () -> {
                        start.await(5, TimeUnit.SECONDS);
                        return practiceService.requestReEvaluation(
                                fixture.attemptId(),
                                fixture.questionId(),
                                student.getId());
                    };
            Future<PracticeService.ReEvaluationRequestResult> first =
                    executor.submit(request);
            Future<PracticeService.ReEvaluationRequestResult> second =
                    executor.submit(request);

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS).status(),
                    second.get(10, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(
                            "QUEUED",
                            "ALREADY_QUEUED");
            assertThat(attemptEvaluationJobRepository.count())
                    .isEqualTo(1);
            verifyNoWritingEvaluationCall();
        } finally {
            executor.shutdownNow();
            deleteWritingAttemptFixture(fixture);
        }
    }

    @Test
    void expiredSubmitUsesOnlyLastServerSavedAnswerSnapshot() {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Deadline", false);
        PracticeAttempt attempt = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        attempt.setAnswersJson(
                "{\"" + fixture.questionId()
                        + "\":\"답안 저장됨\"}");
        attempt.setDeadlineAt(
                LocalDateTime.now().minusSeconds(1));
        attempt = attemptRepository.saveAndFlush(attempt);
        clearInvocations(writingEvaluationClient);

        practiceService.submitAttempt(
                fixture.attemptId(),
                student.getId(),
                Map.of(
                        "answer_" + fixture.questionId(),
                        "기한 후 변경"),
                attempt.getLockVersion());

        PracticeAttempt submitted = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        assertThat(submitted.getAnswersJson())
                .contains("답안 저장됨")
                .doesNotContain("기한 후 변경");
        assertThat(submitted.getAnalysisStatus())
                .isEqualTo(PracticeAttempt.ANALYSIS_QUEUED);
        verifyNoWritingEvaluationCall();
    }

    @Test
    void deadlineProcessorFinalizesClosedBrowserWritingAttemptFromServerSnapshot() {
        WritingAttemptFixture fixture =
                createWritingAttemptFixture(
                        "Post13H Deadline Reconciler", false);
        PracticeAttempt attempt = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        attempt.setAnswersJson(
                "{\"" + fixture.questionId()
                        + "\":\"닫힌 브라우저 저장 답안\"}");
        attempt.setDeadlineAt(
                LocalDateTime.now().minusSeconds(1));
        attemptRepository.saveAndFlush(attempt);
        clearInvocations(writingEvaluationClient);
        PracticeAttemptDeadlineProcessor processor =
                new PracticeAttemptDeadlineProcessor(
                        attemptRepository,
                        practiceService,
                        attemptDiscardService,
                        attemptDeadlineTransactions);

        assertThat(processor.processExpired(10)).isEqualTo(1);

        PracticeAttempt submitted = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        assertThat(submitted.getStatus())
                .isEqualTo(PracticeAttempt.STATUS_SUBMITTED);
        assertThat(submitted.getAnswersJson())
                .contains("닫힌 브라우저 저장 답안");
        assertThat(attemptEvaluationJobRepository
                .findByAttemptId(fixture.attemptId()))
                .get()
                .extracting(
                        com.ksh.entities.PracticeAttemptEvaluationJob
                                ::getJobStatus)
                .isEqualTo(
                        com.ksh.entities.PracticeAttemptEvaluationJob
                                .STATUS_QUEUED);
        verifyNoWritingEvaluationCall();
    }

    @Test
    void deadlineProcessorSkipsBackedOffPoisonRowAndFinalizesLaterAttempt() {
        WritingAttemptFixture poison =
                createWritingAttemptFixture(
                        "Post13H Deadline Poison", false);
        WritingAttemptFixture healthy =
                createWritingAttemptFixture(
                        "Post13H Deadline Healthy", false);
        try {
            LocalDateTime now = LocalDateTime.now();
            PracticeAttempt poisonedAttempt = attemptRepository
                    .findById(poison.attemptId()).orElseThrow();
            poisonedAttempt.setDeadlineAt(now.minusMinutes(2));
            poisonedAttempt.recordDeadlineReconcileFailure(
                    "MalformedSnapshotException", now);
            attemptRepository.saveAndFlush(poisonedAttempt);

            PracticeAttempt healthyAttempt = attemptRepository
                    .findById(healthy.attemptId()).orElseThrow();
            healthyAttempt.setAnswersJson(
                    "{\"" + healthy.questionId()
                            + "\":\"후속 저장 답안\"}");
            healthyAttempt.setDeadlineAt(now.minusMinutes(1));
            attemptRepository.saveAndFlush(healthyAttempt);
            clearInvocations(writingEvaluationClient);
            PracticeAttemptDeadlineProcessor processor =
                    new PracticeAttemptDeadlineProcessor(
                            attemptRepository,
                            practiceService,
                            attemptDiscardService,
                            attemptDeadlineTransactions);

            assertThat(processor.processExpired(10)).isEqualTo(1);

            assertThat(attemptRepository
                    .findById(poison.attemptId()).orElseThrow()
                    .getStatus())
                    .isEqualTo(PracticeAttempt.STATUS_IN_PROGRESS);
            assertThat(attemptRepository
                    .findById(healthy.attemptId()).orElseThrow()
                    .getStatus())
                    .isEqualTo(PracticeAttempt.STATUS_SUBMITTED);
            verifyNoWritingEvaluationCall();
        } finally {
            deleteWritingAttemptFixture(healthy);
            deleteWritingAttemptFixture(poison);
        }
    }

    @Test
    void disabledSpeakingTerminalStateIsUnavailableNotPending() {
        SpeakingAttemptFixture fixture =
                createLegacySpeakingInProgressAttempt(
                        "Post13H Disabled Speaking");
        PracticeAttempt attempt = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        speakingMediaRepository.saveAndFlush(
                PracticeSpeakingMedia.ready(
                        fixture.attemptId(),
                        fixture.questionId(),
                        PracticeSpeakingStorageProvider.LOCAL,
                        "PRACTICE_SPEAKING",
                        "test/disabled-"
                                + java.util.UUID.randomUUID()
                                + ".webm",
                        "audio/webm",
                        "webm",
                        "opus",
                        100L,
                        1000L,
                        "d".repeat(64)));
        clearInvocations(
                speakingEvaluationClient,
                speakingTranscriptionClient);

        practiceService.submitAttempt(
                fixture.attemptId(),
                student.getId(),
                Map.of(
                        "answer_" + fixture.questionId(),
                        "AUDIO_SUBMITTED"),
                attempt.getLockVersion());

        PracticeAttempt terminal = attemptRepository
                .findById(fixture.attemptId()).orElseThrow();
        assertThat(terminal.getAnalysisErrorCode())
                .isEqualTo("SPEAKING_AI_DISABLED");
        assertThat(terminal.getAnalysisStatus())
                .isEqualTo(PracticeAttempt.ANALYSIS_UNAVAILABLE);
        assertThat(PracticeAttemptStatePolicy.INSTANCE
                .presentation(terminal, true)
                .state())
                .isEqualTo(
                        PracticeAttemptStatePolicy.DisplayState.UNAVAILABLE);
        assertThat(attemptEvaluationJobRepository
                .findByAttemptId(fixture.attemptId()))
                .get()
                .extracting(
                        com.ksh.entities.PracticeAttemptEvaluationJob
                                ::getJobStatus)
                .isEqualTo(
                        com.ksh.entities.PracticeAttemptEvaluationJob
                                .STATUS_UNAVAILABLE);

        var result = resultAssembler.assemble(
                fixture.attemptId(), student.getId());

        assertThat(result.state().code())
                .isEqualTo("UNAVAILABLE");
        assertThat(result.feedback().state())
                .isEqualTo("UNAVAILABLE");
        assertThat(result.score().available()).isFalse();
        verifyNoInteractions(speakingEvaluationClient);
    }

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void discardedSpeakingAttemptRedirectsToTestDetailNotMissingResult()
            throws Exception {
        SpeakingAttemptFixture fixture =
                createLegacySpeakingInProgressAttempt(
                        "Post13H Discarded Speaking Route");
        try {
            attemptDiscardService.discardForOwner(
                    fixture.attemptId(), student.getId());

            mockMvc.perform(get(
                            "/practice/attempts/"
                                    + fixture.attemptId()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(
                            com.ksh.features.practice.web
                                    .PracticeRoutes.testDetailPath(
                                            fixture.setId(),
                                            fixture.testId())));

            mockMvc.perform(get(
                            "/practice/attempts/"
                                    + fixture.attemptId()
                                    + "/submit"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(
                            com.ksh.features.practice.web
                                    .PracticeRoutes.testDetailPath(
                                            fixture.setId(),
                                            fixture.testId())));
        } finally {
            deleteSpeakingAttemptFixture(fixture);
        }
    }

    private WritingAttemptFixture createWritingAttemptFixture(String title, boolean graded) {
        PracticeSet writingSet = setRepository.saveAndFlush(new PracticeSet(
                title, "Desc", "WRITING", "GLOBAL", null, null, "{}", "PUBLISHED", lecturer.getId()
        ));
        PracticeTest test = testRepository.saveAndFlush(new PracticeTest(writingSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection section = new PracticeSection(writingSet.getId(), "Writing Section", "WRITING", "ESSAY", "Desc", 50, BigDecimal.TEN, 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);
        PracticeQuestionGroup group = new PracticeQuestionGroup(writingSet.getId(), "Group 1", 1, 1, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);
        PracticeQuestion question = new PracticeQuestion(writingSet.getId(), 51, "ESSAY", "Prompt " + title, "[]", "", "Explain", BigDecimal.TEN, 0);
        question.setWritingTaskType(WritingTaskType.Q51);
        question.setGroupId(group.getId());
        question = questionRepository.saveAndFlush(question);

        publishVersion(writingSet.getId());
        Long attemptId = practiceService.startAttempt(
                writingSet.getId(), test.getId(), section.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        String answersJson = "{\"" + question.getId() + "\":\"Existing answer\"}";
        String oldFeedbackJson = "{\"" + question.getId() + "\":"
                + currentWritingFeedback(
                        WritingTaskType.Q51,
                        "8",
                        "Kết quả đã lưu",
                        "Existing answer")
                + "}";
        if (graded) {
            attempt.markGraded(BigDecimal.valueOf(80.00), BigDecimal.TEN, answersJson, oldFeedbackJson);
        } else {
            attempt.setStatus(PracticeAttempt.STATUS_IN_PROGRESS);
            attempt.setAnswersJson("{}");
        }
        attempt = attemptRepository.saveAndFlush(attempt);
        return new WritingAttemptFixture(
                writingSet.getId(),
                test.getId(),
                section.getId(),
                group.getId(),
                question.getId(),
                attempt.getId(),
                question.getPrompt(),
                oldFeedbackJson
        );
    }

    private void deleteWritingAttemptFixture(WritingAttemptFixture fixture) {
        attemptRepository.findById(fixture.attemptId()).ifPresent(attemptRepository::delete);
        attemptRepository.flush();
        deletePublishedVersionFixture(fixture.setId());
        questionRepository.findById(fixture.questionId()).ifPresent(questionRepository::delete);
        groupRepository.findById(fixture.groupId()).ifPresent(groupRepository::delete);
        sectionRepository.findById(fixture.sectionId()).ifPresent(sectionRepository::delete);
        testRepository.findById(fixture.testId()).ifPresent(testRepository::delete);
        setRepository.findById(fixture.setId()).ifPresent(setRepository::delete);
    }

    private record WritingAttemptFixture(
            Long setId,
            Long testId,
            Long sectionId,
            Long groupId,
            Long questionId,
            Long attemptId,
            String prompt,
            String oldFeedbackJson
    ) {
    }


    private WritingMixedAttemptFixture createWritingMixedAttemptFixture(String title) {
        PracticeSet writingSet = setRepository.saveAndFlush(new PracticeSet(
                title, "Desc", "WRITING", "GLOBAL", null, null, "{}", "PUBLISHED", lecturer.getId()
        ));
        PracticeTest test = testRepository.saveAndFlush(new PracticeTest(writingSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection section = new PracticeSection(writingSet.getId(), "Writing Section", "WRITING", "MIXED", "Desc", 50, BigDecimal.valueOf(20), 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);
        PracticeQuestionGroup group = new PracticeQuestionGroup(writingSet.getId(), "Group 1", 1, 1, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);

        PracticeQuestion mcq = new PracticeQuestion(writingSet.getId(), 50, "SINGLE_CHOICE", "Prompt MCQ " + title, "[\"A\",\"B\"]", "1", "Explain", BigDecimal.TEN, 0);
        mcq.setGroupId(group.getId());
        mcq = questionRepository.saveAndFlush(mcq);

        PracticeQuestion essay = new PracticeQuestion(writingSet.getId(), 51, "ESSAY", "Prompt Essay " + title, "[]", "", "Explain", BigDecimal.TEN, 1);
        essay.setWritingTaskType(WritingTaskType.Q51);
        essay.setGroupId(group.getId());
        essay = questionRepository.saveAndFlush(essay);

        publishVersion(writingSet.getId());
        Long attemptId = practiceService.startAttempt(
                writingSet.getId(), test.getId(), section.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        String answersJson = "{\"" + mcq.getId() + "\":\"1\",\"" + essay.getId() + "\":\"Existing essay\"}";
        String feedbackJson = "{\"" + essay.getId() + "\":"
                + currentWritingFeedback(
                        WritingTaskType.Q51,
                        "8",
                        "Kết quả đã lưu",
                        "Existing essay")
                + "}";
        attempt.markGraded(BigDecimal.valueOf(90.00), BigDecimal.valueOf(20), answersJson, feedbackJson);
        attempt = attemptRepository.saveAndFlush(attempt);

        return new WritingMixedAttemptFixture(
                writingSet.getId(),
                test.getId(),
                section.getId(),
                group.getId(),
                mcq.getId(),
                essay.getId(),
                attempt.getId()
        );
    }

    private void deleteWritingMixedAttemptFixture(WritingMixedAttemptFixture fixture) {
        attemptRepository.findById(fixture.attemptId()).ifPresent(attemptRepository::delete);
        deletePublishedVersionFixture(fixture.setId());
        questionRepository.findById(fixture.mcqQuestionId()).ifPresent(questionRepository::delete);
        questionRepository.findById(fixture.essayQuestionId()).ifPresent(questionRepository::delete);
        groupRepository.findById(fixture.groupId()).ifPresent(groupRepository::delete);
        sectionRepository.findById(fixture.sectionId()).ifPresent(sectionRepository::delete);
        testRepository.findById(fixture.testId()).ifPresent(testRepository::delete);
        setRepository.findById(fixture.setId()).ifPresent(setRepository::delete);
    }

    private record WritingMixedAttemptFixture(
            Long setId,
            Long testId,
            Long sectionId,
            Long groupId,
            Long mcqQuestionId,
            Long essayQuestionId,
            Long attemptId
    ) {
    }

    private ListeningAttemptFixture createListeningAttemptFixture(String title) {
        PracticeSet listeningSet = setRepository.saveAndFlush(new PracticeSet(
                title, "Desc", "LISTENING", "GLOBAL", null, null, "{}", "PUBLISHED", lecturer.getId()
        ));
        PracticeTest test = testRepository.saveAndFlush(new PracticeTest(listeningSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection section = new PracticeSection(listeningSet.getId(), "Listening Section", "LISTENING", "MCQ", "Desc", 50, BigDecimal.TEN, 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);
        PracticeQuestionGroup group = new PracticeQuestionGroup(listeningSet.getId(), "Group 1", 1, 1, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);
        PracticeQuestion question = new PracticeQuestion(listeningSet.getId(), 1, "SINGLE_CHOICE", "Prompt " + title, "[\"A\",\"B\"]", "1", "Explain", BigDecimal.TEN, 0);
        question.setGroupId(group.getId());
        question = questionRepository.saveAndFlush(question);

        publishVersion(listeningSet.getId());
        Long attemptId = practiceService.startAttempt(
                listeningSet.getId(), test.getId(), section.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.markSubmitted(BigDecimal.TEN, BigDecimal.TEN, "{\"" + question.getId() + "\":\"1\"}");
        attempt = attemptRepository.saveAndFlush(attempt);

        return new ListeningAttemptFixture(
                listeningSet.getId(),
                test.getId(),
                section.getId(),
                group.getId(),
                question.getId(),
                attempt.getId()
        );
    }

    private void deleteListeningAttemptFixture(ListeningAttemptFixture fixture) {
        attemptRepository.findById(fixture.attemptId()).ifPresent(attemptRepository::delete);
        deletePublishedVersionFixture(fixture.setId());
        questionRepository.findById(fixture.questionId()).ifPresent(questionRepository::delete);
        groupRepository.findById(fixture.groupId()).ifPresent(groupRepository::delete);
        sectionRepository.findById(fixture.sectionId()).ifPresent(sectionRepository::delete);
        testRepository.findById(fixture.testId()).ifPresent(testRepository::delete);
        setRepository.findById(fixture.setId()).ifPresent(setRepository::delete);
    }

    private record ListeningAttemptFixture(
            Long setId,
            Long testId,
            Long sectionId,
            Long groupId,
            Long questionId,
            Long attemptId
    ) {
    }

    private SpeakingAttemptFixture createSpeakingAttemptFixture(String title) {
        PracticeSet speakingSet = setRepository.saveAndFlush(new PracticeSet(
                title, "Desc", "SPEAKING", "GLOBAL", null, null, "{}", "PUBLISHED", lecturer.getId()
        ));
        PracticeTest test = testRepository.saveAndFlush(new PracticeTest(speakingSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection section = new PracticeSection(speakingSet.getId(), "Speaking Section", "SPEAKING", "ORAL", "Desc", 50, BigDecimal.TEN, 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);
        PracticeQuestionGroup group = new PracticeQuestionGroup(speakingSet.getId(), "Group 1", 1, 1, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);
        PracticeQuestion question = new PracticeQuestion(speakingSet.getId(), 1, "SPEAKING", "Prompt " + title, "[]", "", "Explain", BigDecimal.TEN, 0);
        question.setGroupId(group.getId());
        question = questionRepository.saveAndFlush(question);

        publishVersion(speakingSet.getId());
        Long attemptId = practiceService.startAttempt(
                speakingSet.getId(), test.getId(), section.getId(), student.getId());
        PracticeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.markGraded(
                BigDecimal.valueOf(80.00),
                BigDecimal.TEN,
                "{\"" + question.getId() + "\":\"Existing spoken answer\"}",
                "{\"raw_score\":8.0,\"raw_score_max\":10.0}");
        attempt = attemptRepository.saveAndFlush(attempt);

        return new SpeakingAttemptFixture(
                speakingSet.getId(),
                test.getId(),
                section.getId(),
                group.getId(),
                question.getId(),
                attempt.getId()
        );
    }

    private SpeakingAttemptFixture createLegacySpeakingInProgressAttempt(String title) {
        PracticeSet speakingSet = setRepository.saveAndFlush(new PracticeSet(
                title, "Desc", "SPEAKING", "GLOBAL", null, null, "{}", "PUBLISHED", lecturer.getId()
        ));
        PracticeTest test = testRepository.saveAndFlush(new PracticeTest(speakingSet.getId(), "Test 1", "Desc", 1, 40));
        PracticeSection section = new PracticeSection(
                speakingSet.getId(), "Speaking Section", "SPEAKING", "ORAL", "Desc", 50, BigDecimal.TEN, 1);
        section.setTestId(test.getId());
        section = sectionRepository.saveAndFlush(section);
        PracticeQuestionGroup group = new PracticeQuestionGroup(speakingSet.getId(), "Group 1", 1, 1, "Desc", null, null, 1);
        group.setSectionId(section.getId());
        group = groupRepository.saveAndFlush(group);
        PracticeQuestion question = new PracticeQuestion(
                speakingSet.getId(),
                1,
                PracticeQuestion.TYPE_SPEAKING,
                "Prompt " + title,
                "[]",
                "",
                "Explain",
                BigDecimal.TEN,
                0);
        question.setGroupId(group.getId());
        question = questionRepository.saveAndFlush(question);
        publishVersion(speakingSet.getId());

        Long attemptId = practiceService.startAttempt(
                speakingSet.getId(), test.getId(), section.getId(), student.getId());

        return new SpeakingAttemptFixture(
                speakingSet.getId(),
                test.getId(),
                section.getId(),
                group.getId(),
                question.getId(),
                attemptId
        );
    }

    private void deleteSpeakingAttemptFixture(SpeakingAttemptFixture fixture) {
        attemptRepository.findById(fixture.attemptId()).ifPresent(attemptRepository::delete);
        attemptRepository.flush();
        deletePublishedVersionFixture(fixture.setId());
        questionRepository.findById(fixture.questionId()).ifPresent(questionRepository::delete);
        groupRepository.findById(fixture.groupId()).ifPresent(groupRepository::delete);
        sectionRepository.findById(fixture.sectionId()).ifPresent(sectionRepository::delete);
        testRepository.findById(fixture.testId()).ifPresent(testRepository::delete);
        setRepository.findById(fixture.setId()).ifPresent(setRepository::delete);
    }

    private void deletePublishedVersionFixture(Long setId) {
        List<Long> versionIds = publishedVersionRepository.findBySetIdOrderByVersionNumberDesc(setId).stream()
                .map(com.ksh.entities.PracticePublishedVersion::getId)
                .toList();
        if (versionIds.isEmpty()) {
            return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Long versionId : versionIds) {
            jdbc.update("DELETE FROM practice_material_references WHERE published_version_id = ?", versionId);
            jdbc.update("DELETE FROM practice_question_versions WHERE published_version_id = ?", versionId);
            jdbc.update("DELETE FROM practice_question_group_versions WHERE published_version_id = ?", versionId);
            jdbc.update("DELETE FROM practice_section_versions WHERE published_version_id = ?", versionId);
            jdbc.update("DELETE FROM practice_test_versions WHERE published_version_id = ?", versionId);
            jdbc.update("DELETE FROM practice_set_versions WHERE published_version_id = ?", versionId);
            jdbc.update("DELETE FROM practice_published_versions WHERE id = ?", versionId);
        }
    }

    private void verifyNoWritingEvaluationCall() {
        verify(writingEvaluationClient, never()).evaluate(
                anyLong(),
                anyString(),
                anyString(),
                anyBoolean(),
                any(WritingTaskType.class));
        verify(writingEvaluationClient, never()).evaluate(
                anyLong(),
                anyString(),
                anyString(),
                anyBoolean(),
                any(WritingTaskType.class),
                any());
    }

    private String currentWritingFeedback(
            WritingTaskType taskType,
            String rawScore,
            String summary,
            String learnerAnswer
    ) {
        int earned = new BigDecimal(rawScore).intValueExact();
        com.fasterxml.jackson.databind.node.ObjectNode providerEnvelope =
                WritingContractTestFixtures.zeroEnvelope(
                        objectMapper, taskType.name(), learnerAnswer);
        WritingContractTestFixtures.applyRawScore(
                providerEnvelope,
                taskType.name(),
                learnerAnswer,
                earned);
        String normalized = new WritingEvaluationNormalizer(
                objectMapper).normalize(
                providerEnvelope.toString(),
                taskType.name(),
                learnerAnswer,
                null);
        if (normalized.contains("\"score_available\":false")) {
            throw new IllegalStateException(
                    "Production-shaped Writing fixture was rejected: "
                            + summary);
        }
        return normalized;
    }

    private String currentWritingUnavailable(
            WritingTaskType taskType,
            String status,
            String reason,
            boolean retryable
    ) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                objectMapper.createObjectNode();
        node.put("task_type", taskType.name());
        node.put("engine", "KSH_WRITING_EVALUATOR_STATUS");
        node.put(
                "policy_bundle_id",
                WritingAssessmentPolicyBundle.POLICY_BUNDLE_ID);
        node.put("evaluation_status", status);
        node.put("evaluation_source", "PROVIDER");
        node.put("evaluation_reason", reason);
        node.put("evaluation_retryable", retryable);
        node.put("score_available", false);
        node.set("result_completeness", objectMapper.valueToTree(
                com.ksh.features.practice.ai.contract
                        .PracticeAiResultCompleteness.unavailable(reason, 0)
                        .toMap()));
        return node.toString();
    }

    private record SpeakingAttemptFixture(
            Long setId,
            Long testId,
            Long sectionId,
            Long groupId,
            Long questionId,
            Long attemptId
    ) {
    }
}
