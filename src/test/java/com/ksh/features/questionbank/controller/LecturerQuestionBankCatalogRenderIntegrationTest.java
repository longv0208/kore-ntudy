package com.ksh.features.questionbank.controller;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** MySQL-backed render contract for the lecturer Question Bank catalog. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LecturerQuestionBankCatalogRenderIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private QuestionBankItemRepository itemRepository;

    @Test
    @WithUserDetails("lecturer@ksh.edu.vn")
    void catalog_aggregates_real_data_and_renders_without_semester_filter() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        itemRepository.saveAndFlush(new QuestionBankItem(
                lecturer.getSubjectId(), lecturer.getId(),
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_APPROVED,
                "<p>Câu hỏi regression cho catalog</p>", "<p>Giải thích</p>"));

        mockMvc.perform(get("/lecturer/question-bank")
                        .param("bankStatus", "ALL")
                        .param("sort", "UPDATED_DESC"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/list"))
                .andExpect(model().attribute("catalogMode", true))
                .andExpect(model().attributeExists("subjectCatalog", "catalogMetrics"))
                .andExpect(content().string(containsString("Ngân hàng câu hỏi")))
                .andExpect(content().string(containsString("Môn học đang hoạt động")))
                .andExpect(content().string(containsString("Mở ngân hàng")))
                .andExpect(content().string(containsString("1")))
                .andExpect(content().string(not(containsString("name=\"semester\""))));
    }
}
