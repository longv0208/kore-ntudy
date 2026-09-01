package com.ksh.features.tests;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ksh.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ksh.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ksh.features.tests.entity.Question;
import com.ksh.features.tests.entity.QuestionOption;
import com.ksh.features.tests.repository.QuestionOptionRepository;
import com.ksh.features.tests.repository.QuestionRepository;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.features.tests.service.LecturerExamService;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LecturerExamDistributionIntegrationTest {

    private static final String LECTURER_EMAIL = "lecturer@ksh.edu.vn";
    private static final String SHARED_CONSUMER_EMAIL = "ChoA@fe.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private TestRepository testRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository optionRepository;
    @Autowired private LecturerExamService examService;

    private User lecturer;
    private User sharedConsumer;
    private ClassEntity sourceClass;
    private Long sourceTestId;
    private String title;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase(LECTURER_EMAIL).orElseThrow();
        sharedConsumer = userRepository.findByEmailIgnoreCase(SHARED_CONSUMER_EMAIL).orElseThrow();
        assertThat(lecturer.getSubjectId()).isNotNull();
        sourceClass = activeClass("Lớp nguồn", lecturer.getSubjectId());
        title = "Đề phân phối " + UUID.randomUUID().toString().substring(0, 8);
        sourceTestId = examService.save(lecturer.getId(), examForm(lecturer.getSubjectId(), sourceClass.getId(), title,
                com.ksh.features.tests.entity.Test.STATUS_PUBLISHED));
    }

    @Test
    @WithUserDetails(LECTURER_EMAIL)
    void distributionPageAndPostCopyFinishedTestToMultipleSameSubjectClasses() throws Exception {
        ClassEntity first = activeClass("KOR target A", lecturer.getSubjectId());
        ClassEntity second = activeClass("KOR target B", lecturer.getSubjectId());

        mockMvc.perform(get("/lecturer/tests/" + sourceTestId + "/distribute"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("KOR target A")))
                .andExpect(content().string(containsString("Phân phối bài test")));

        mockMvc.perform(post("/lecturer/tests/" + sourceTestId + "/distribute").with(csrf())
                        .param("classIds", first.getId().toString(), second.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/tests"))
                .andExpect(flash().attribute("flashSuccess", "Đã phân phối bài test tới 2 lớp"));

        var firstCopies = testRepository.findByClassId(first.getId(), PageRequest.of(0, 20));
        var secondCopies = testRepository.findByClassId(second.getId(), PageRequest.of(0, 20));
        assertThat(firstCopies.getContent()).singleElement().satisfies(copy -> {
            assertThat(copy.getTitle()).isEqualTo(title);
            assertThat(copy.getStatus()).isEqualTo(com.ksh.features.tests.entity.Test.STATUS_PUBLISHED);
            assertThat(copy.getCreatedBy()).isEqualTo(lecturer.getId());
        });
        assertThat(secondCopies.getContent()).singleElement()
                .extracting(com.ksh.features.tests.entity.Test::getTitle)
                .isEqualTo(title);

        Long copyId = firstCopies.getContent().get(0).getId();
        List<Question> sourceQuestions = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(sourceTestId);
        List<Question> copiedQuestions = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(copyId);
        assertThat(copiedQuestions).hasSameSizeAs(sourceQuestions);
        assertThat(copiedQuestions).extracting(Question::getId)
                .doesNotContainAnyElementsOf(sourceQuestions.stream().map(Question::getId).toList());
        List<QuestionOption> copiedOptions = optionRepository
                .findByQuestionIdOrderBySortOrderAscIdAsc(copiedQuestions.get(0).getId());
        assertThat(copiedOptions).extracting(QuestionOption::getContent)
                .containsExactly("Đúng", "Sai");
    }

    @Test
    void distributionIsAtomicAndRejectsDifferentSubject() {
        Department otherSubject = departmentRepository.saveAndFlush(new Department(
                "Môn khác " + UUID.randomUUID().toString().substring(0, 6),
                "OTH" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                null, true));
        ClassEntity valid = activeClass("KOR valid target", lecturer.getSubjectId());
        ClassEntity invalid = activeClass("Other subject target", otherSubject.getId());

        assertThatThrownBy(() -> examService.distributePublished(
                lecturer.getId(), Role.LECTURER, sourceTestId,
                List.of(valid.getId(), invalid.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cùng mã môn");

        assertThat(testRepository.existsByClassIdAndTitleIgnoreCase(valid.getId(), title)).isFalse();
        assertThat(testRepository.existsByClassIdAndTitleIgnoreCase(invalid.getId(), title)).isFalse();
    }

    @Test
    void draftTestCannotEnterFinishedDistributionFlow() {
        Long draftId = examService.save(lecturer.getId(), examForm(
                lecturer.getSubjectId(), sourceClass.getId(), title + " nháp",
                com.ksh.features.tests.entity.Test.STATUS_DRAFT));

        assertThatThrownBy(() -> examService.distributionView(
                lecturer.getId(), Role.LECTURER, draftId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã hoàn tất và xuất bản");
    }

    @Test
    void independentTestBankItemCanBeCreatedWithoutClassThenDistributedLater() {
        String independentTitle = "Đề độc lập " + UUID.randomUUID().toString().substring(0, 8);
        Long independentId = examService.save(lecturer.getId(), examForm(
                lecturer.getSubjectId(), null, independentTitle,
                com.ksh.features.tests.entity.Test.STATUS_PUBLISHED));

        com.ksh.features.tests.entity.Test source = testRepository.findById(independentId).orElseThrow();
        assertThat(source.getClassId()).isNull();
        assertThat(source.getSubjectId()).isEqualTo(lecturer.getSubjectId());

        ClassEntity target = activeClass("KOR independent target", lecturer.getSubjectId());
        var view = examService.distributionView(lecturer.getId(), Role.LECTURER, independentId);
        assertThat(view.targetClasses()).extracting("id").contains(target.getId());

        var result = examService.distributePublished(
                lecturer.getId(), Role.LECTURER, independentId, List.of(target.getId()));
        assertThat(result.distributedCount()).isEqualTo(1);
        assertThat(testRepository.findById(result.testIds().get(0)).orElseThrow().getClassId())
                .isEqualTo(target.getId());
    }

    @Test
    @WithUserDetails(SHARED_CONSUMER_EMAIL)
    void anotherLecturerCanPreviewAndDistributePublishedSharedBankSource() throws Exception {
        ClassEntity recipient = activeClass(
                "KOR shared recipient", lecturer.getSubjectId(), sharedConsumer);

        // The counters above the catalog must use the same visible scope as the
        // list.  A shared published source is usable by another lecturer even
        // though it remains read-only to them.
        var sharedCatalog = examService.listOwned(sharedConsumer.getId(), 0);
        var sharedMetrics = examService.metricsFor(sharedConsumer.getId());
        assertThat(sharedCatalog.getTotalElements()).isGreaterThan(0);
        assertThat(sharedMetrics.totalTests()).isEqualTo(sharedCatalog.getTotalElements());
        assertThat(sharedMetrics.publishedTests()).isGreaterThan(0);

        mockMvc.perform(get("/lecturer/tests/" + sourceTestId + "/distribute"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("KOR shared recipient")));

        mockMvc.perform(get("/lecturer/tests/" + sourceTestId + "/preview"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/lecturer/tests/" + sourceTestId + "/distribute").with(csrf())
                        .param("classIds", recipient.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/tests"));

        assertThat(testRepository.findByClassId(recipient.getId(), PageRequest.of(0, 20))
                .getContent()).singleElement().satisfies(copy -> {
            assertThat(copy.getTitle()).isEqualTo(title);
            assertThat(copy.getCreatedBy()).isEqualTo(sharedConsumer.getId());
        });
    }

    @Test
    void foreignDraftAndArchivedTestsStayOutOfAnotherLecturersCatalog() {
        Long draftId = examService.save(lecturer.getId(), examForm(
                lecturer.getSubjectId(), sourceClass.getId(), title + " foreign draft",
                com.ksh.features.tests.entity.Test.STATUS_DRAFT));
        Long archivedId = examService.save(lecturer.getId(), examForm(
                lecturer.getSubjectId(), sourceClass.getId(), title + " foreign archived",
                com.ksh.features.tests.entity.Test.STATUS_DRAFT));
        var archived = testRepository.findById(archivedId).orElseThrow();
        archived.setStatus(com.ksh.features.tests.entity.Test.STATUS_ARCHIVED);
        testRepository.saveAndFlush(archived);

        // Even a class the consumer manages must not make another lecturer's
        // private authoring states visible. Only PUBLISHED non-Practice rows
        // are shared through the common Test Bank.
        ClassEntity consumerClass = activeClass("Consumer private scope",
                lecturer.getSubjectId(), sharedConsumer);
        Long classLocalDraftId = persistForeignTest(consumerClass,
                com.ksh.features.tests.entity.Test.STATUS_DRAFT,
                "foreign class draft");
        Long classLocalArchivedId = persistForeignTest(consumerClass,
                com.ksh.features.tests.entity.Test.STATUS_ARCHIVED,
                "foreign class archived");
        Long classLocalPublishedId = persistForeignTest(consumerClass,
                com.ksh.features.tests.entity.Test.STATUS_PUBLISHED,
                "shared class published");

        var visibleRows = examService.listOwned(sharedConsumer.getId(), 0).getContent();
        assertThat(visibleRows)
                .extracting(com.ksh.features.tests.dto.LecturerTestDtos.LecturerExamRow::id)
                .contains(classLocalPublishedId)
                .doesNotContain(draftId, archivedId, classLocalDraftId, classLocalArchivedId);
        assertThat(visibleRows).filteredOn(row -> row.id().equals(classLocalPublishedId))
                .singleElement()
                .satisfies(row -> assertThat(row.canManage()).isTrue());
    }

    private Long persistForeignTest(ClassEntity clazz, String status, String suffix) {
        com.ksh.features.tests.entity.Test test = new com.ksh.features.tests.entity.Test(
                lecturer.getId(), com.ksh.features.tests.entity.Test.TYPE_MOCK);
        test.setTitle(title + " " + suffix);
        test.setSubjectId(clazz.getSubjectId());
        test.setClassId(clazz.getId());
        test.setStatus(status);
        return testRepository.saveAndFlush(test).getId();
    }

    private ClassEntity activeClass(String name, Long subjectId) {
        return activeClass(name, subjectId, lecturer);
    }

    private ClassEntity activeClass(String name, Long subjectId, User owner) {
        ClassEntity clazz = new ClassEntity(name, owner.getId(), owner.getId(),
                null, null, null, 100);
        clazz.setSubjectId(subjectId);
        clazz.approve(lecturer.getId(), LocalDateTime.now());
        return classRepository.saveAndFlush(clazz);
    }

    private static ExamForm examForm(Long subjectId, Long classId, String title, String status) {
        List<QuestionForm> questions = List.of(
                new QuestionForm(null, "MCQ", "1 + 1 = 2?", "Giải thích",
                        new BigDecimal("2.00"), List.of(
                        new OptionForm(null, "Đúng", true),
                        new OptionForm(null, "Sai", false))));
        return new ExamForm(null, title, "Nội dung đề", subjectId, classId, "MOCK", status,
                "FIXED_WINDOW", 30, LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusDays(3), new BigDecimal("1.00"),
                false, false, null, null, questions, false);
    }
}
