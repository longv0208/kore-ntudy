package com.ksh.features.questionbank.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Disposable-DB MockMvc coverage for subject-scoped import template, preview, and confirm. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuestionBankImportControllerTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionBankItemRepository itemRepository;
    @Autowired private QuestionBankOptionRepository optionRepository;
    @Autowired private LessonTemplateRepository lessonRepository;

    private Long lecturerId;
    private Long subjectId;
    private String subjectCode;
    private Long lessonTemplateId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        lecturerId = lecturer.getId();
        subjectId = lecturer.getSubjectId();
        subjectCode = subjectRepository.findById(subjectId).orElseThrow().getCode();
        lessonTemplateId = lessonRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subjectId)
                .stream()
                .findFirst()
                .orElseThrow()
                .getId();
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void downloaded_template_is_pre_scoped_to_the_actor_subject() throws Exception {
        MvcResult download = mockMvc.perform(get("/lecturer/question-bank/import/template"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME))
                .andReturn();

        byte[] workbookBytes = download.getResponse().getContentAsByteArray();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Mã môn");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo(subjectCode);
        }

        MockMultipartFile downloaded = new MockMultipartFile(
                "file", "question-bank-template.xlsx", XLSX_MIME, workbookBytes);
        mockMvc.perform(multipart("/lecturer/question-bank/import/preview")
                        .file(downloaded)
                        .param("lessonTemplateId", lessonTemplateId.toString())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(2))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andExpect(jsonPath("$.confirmable").value(true));
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void preview_and_confirm_persist_valid_same_subject_rows() throws Exception {
        long before = itemRepository.findBySubjectIdOrderByUpdatedAtDescIdDesc(subjectId).size();
        MockMultipartFile file = workbookFile(subjectCode, "A");

        MvcResult previewResult = mockMvc.perform(
                        multipart("/lecturer/question-bank/import/preview")
                                .file(file)
                                .param("lessonTemplateId", lessonTemplateId.toString())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(1))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andReturn();

        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        mockMvc.perform(post("/lecturer/question-bank/import/confirm")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + preview.get("sessionId").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.workflowStatus").value("REVIEW"));

        var items = itemRepository.findBySubjectIdOrderByUpdatedAtDescIdDesc(subjectId);
        assertThat(items).hasSize((int) before + 1);
        QuestionBankItem imported = items.get(0);
        assertThat(imported.getSubjectId()).isEqualTo(subjectId);
        assertThat(imported.getContributorId()).isEqualTo(lecturerId);
        assertThat(optionRepository.findByItemIdOrderBySortOrderAscIdAsc(imported.getId())).hasSize(4);
    }

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void preview_blocks_a_different_subject_code() throws Exception {
        mockMvc.perform(multipart("/lecturer/question-bank/import/preview")
                        .file(workbookFile("OTHER101", "A"))
                        .param("lessonTemplateId", lessonTemplateId.toString())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(0))
                .andExpect(jsonPath("$.errorRows").value(1))
                .andExpect(jsonPath("$.confirmable").value(false))
                .andExpect(jsonPath("$.rows[0].message").value("Mã môn phải là " + subjectCode));
    }

    private static MockMultipartFile workbookFile(String code, String correct) throws IOException {
        String[][] grid = {
                {"Mã môn", "Loại câu hỏi", "Nội dung câu hỏi", "Giải thích",
                        "Đáp án A", "Đáp án B", "Đáp án C", "Đáp án D", "Đáp án đúng"},
                {code, "MCQ", "Đạo hàm của x^2 là gì?", "Áp dụng quy tắc lũy thừa",
                        "2x", "x", "x^2", "2", correct}
        };
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Cau hoi");
            for (int r = 0; r < grid.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < grid[r].length; c++) {
                    row.createCell(c).setCellValue(grid[r][c]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "bank.xlsx", XLSX_MIME, out.toByteArray());
        }
    }
}
