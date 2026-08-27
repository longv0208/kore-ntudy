package com.ksh.features.flashcards.controller;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.ai.client.AiClient;
import com.ksh.features.ai.client.AiClientException;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ksh.features.flashcards.service.CardService;
import com.ksh.features.flashcards.service.DeckService;
import com.ksh.features.flashcards.service.DeckPublicLinkService;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import com.ksh.features.flashcards.repository.FlashcardDeckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.ksh.common.IConstant.DEFAULT_DECK_PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the flashcard controllers: page routes, authz (404/403),
 * and the AJAX endpoints (card save, review, Excel import).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlashcardControllerTest {

    private static final String OWNER = "student@ksh.edu.vn";
    private static final String MEMBER = "sv02@ksh.edu.vn";
    private static final String OUTSIDER = "sv01@ksh.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private DeckService deckService;
    @Autowired private DeckPublicLinkService publicLinkService;
    @Autowired private CardService cardService;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FlashcardRepository flashcardRepository;
    @Autowired private FlashcardDeckRepository deckRepository;
    @Autowired private DepartmentRepository subjectRepository;
    @MockitoBean private AiClient aiClient;

    private Long deckId;
    private Long cardId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow();
        User member = userRepository.findByEmailIgnoreCase(MEMBER).orElseThrow();
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        ClassEntity clazz = saveClass(lecturer, "FC ctrl class", "FCCTL");
        enroll(owner, clazz);
        enroll(member, clazz);

        deckId = deckService.createDeck(owner.getId(), new DeckForm("Bộ thẻ ctrl", null));
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "front", "back")));
        deckService.share(deckId, owner.getId(), clazz.getId());
        cardId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();
        when(aiClient.chatJsonObject(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenReturn("""
                        {"cards":[{"front":"Hàng đợi","back":"Cấu trúc FIFO"},
                                  {"front":"Ngăn xếp","back":"Cấu trúc LIFO"}]}""");
    }

    @Test
    @WithUserDetails(OWNER)
    void list_page_ok() throws Exception {
        mockMvc.perform(get("/my/flashcards")).andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(OWNER)
    void list_search_keeps_query_and_filters_by_subject_code() throws Exception {
        User owner = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow();
        String code = "FCT" + System.nanoTime();
        Department subject = subjectRepository.saveAndFlush(new Department(
                "Flashcards tìm kiếm", code, null, true));
        deckService.createDeck(owner.getId(), new DeckForm("Bộ theo môn", null, subject.getId()));

        mockMvc.perform(get("/my/flashcards").param("q", code))
                .andExpect(status().isOk())
                .andExpect(model().attribute("q", code))
                .andExpect(content().string(containsString(code)))
                .andExpect(content().string(containsString("Bộ theo môn")));
    }

    @Test
    @WithUserDetails(OWNER)
    void create_form_lists_only_active_subjects_and_submission_persists_subject() throws Exception {
        String activeCode = "FCA" + System.nanoTime();
        String hiddenCode = "FCH" + System.nanoTime();
        Department active = subjectRepository.saveAndFlush(new Department(
                "Môn flashcard active", activeCode, null, true));
        subjectRepository.saveAndFlush(new Department(
                "Môn flashcard hidden", hiddenCode, null, false));

        mockMvc.perform(get("/my/flashcards/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(activeCode)))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(hiddenCode))));

        mockMvc.perform(post("/my/flashcards").with(csrf())
                        .param("title", "Bộ thẻ có mã môn")
                        .param("description", "")
                        .param("subjectId", active.getId().toString())
                        .param("cardsJson", "[]"))
                .andExpect(status().is3xxRedirection());

        assertThat(deckRepository.findFirstByOwnerIdAndTitleOrderByIdAsc(
                        userRepository.findByEmailIgnoreCase(OWNER).orElseThrow().getId(),
                        "Bộ thẻ có mã môn").orElseThrow().getSubjectId())
                .isEqualTo(active.getId());
    }

    @Test
    @WithUserDetails(OWNER)
    void list_second_page_selects_page_and_renders_pager() throws Exception {
        // Seed enough own decks to guarantee a second SSR page (page size = 12).
        Long ownerId = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow().getId();
        for (int i = 0; i < DEFAULT_DECK_PAGE_SIZE + 1; i++) {
            deckService.createDeck(ownerId, new DeckForm("Bộ trang " + i, null));
        }
        mockMvc.perform(get("/my/flashcards").param("page", "1"))
                .andExpect(status().isOk())
                // The controller must surface the requested SSR page to the view.
                .andExpect(model().attribute("ownDecksPage", hasProperty("number", is(1))))
                // With >1 page the shared numbered pager fragment is rendered.
                .andExpect(content().string(containsString("pager-item")));
    }

    @Test
    @WithUserDetails(OWNER)
    void detail_page_ok() throws Exception {
        mockMvc.perform(get("/my/flashcards/" + deckId)).andExpect(status().isOk());
    }

    @Test
    void enabled_public_link_is_readable_without_authentication() throws Exception {
        Long ownerId = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow().getId();
        String token = publicLinkService.enable(deckId, ownerId);

        mockMvc.perform(get("/s/" + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bộ thẻ công khai trên Korean Study Hub")))
                .andExpect(content().string(containsString("front")))
                .andExpect(content().string(containsString("back")));
    }

    @Test
    void public_link_exposes_anonymous_flip_learn_and_play_modes() throws Exception {
        Long ownerId = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow().getId();
        String token = publicLinkService.enable(deckId, ownerId);

        mockMvc.perform(get("/s/" + token + "/flip"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("LẬT THẺ")));
        mockMvc.perform(get("/s/" + token + "/learn"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PHÒNG LUYỆN TẬP")));
        mockMvc.perform(get("/s/" + token + "/match"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GHÉP CẶP")));
    }

    @Test
    void disabled_public_link_returns_not_found_without_leaking_deck() throws Exception {
        Long ownerId = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow().getId();
        String token = publicLinkService.enable(deckId, ownerId);
        publicLinkService.disable(deckId, ownerId);

        mockMvc.perform(get("/s/" + token)).andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(OWNER)
    void new_edit_flip_review_pages_render() throws Exception {
        mockMvc.perform(get("/my/flashcards/new")).andExpect(status().isOk());
        mockMvc.perform(get("/my/flashcards/" + deckId + "/edit")).andExpect(status().isOk());
        mockMvc.perform(get("/my/flashcards/" + deckId + "/flip")).andExpect(status().isOk());
        mockMvc.perform(get("/my/flashcards/" + deckId + "/review")).andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(OUTSIDER)
    void outsider_flip_returns_404() throws Exception {
        mockMvc.perform(get("/my/flashcards/" + deckId + "/flip"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(OWNER)
    void owner_save_cards_ok() throws Exception {
        mockMvc.perform(post("/api/flashcards/" + deckId + "/cards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"front\":\"a\",\"back\":\"b\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    @WithUserDetails(MEMBER)
    void member_save_cards_forbidden() throws Exception {
        mockMvc.perform(post("/api/flashcards/" + deckId + "/cards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"front\":\"a\",\"back\":\"b\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void blank_card_side_returns_400() throws Exception {
        mockMvc.perform(post("/api/flashcards/" + deckId + "/cards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"front\":\"   \",\"back\":\"b\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void owner_review_ok() throws Exception {
        mockMvc.perform(post("/api/flashcards/cards/" + cardId + "/review").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quality\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.dueRemaining").exists());
    }

    @Test
    @WithUserDetails(OUTSIDER)
    void outsider_review_returns_404() throws Exception {
        mockMvc.perform(post("/api/flashcards/cards/" + cardId + "/review").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quality\":4}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(OWNER)
    void owner_import_excel_returns_parsed_count() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cards.xlsx",
                XLSX_MIME, xlsxBytes());
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/import")
                        .file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    @WithUserDetails(MEMBER)
    void member_import_excel_forbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cards.xlsx",
                XLSX_MIME, xlsxBytes());
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/import")
                        .file(file).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void non_excel_import_returns_400() throws Exception {
        MockMultipartFile bad = new MockMultipartFile("file", "cards.xlsx",
                XLSX_MIME, "not excel".getBytes());
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/import")
                        .file(bad).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void import_template_download_ok() throws Exception {
        mockMvc.perform(get("/api/flashcards/import-template"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME));
    }

    @Test
    @WithUserDetails(OWNER)
    void ai_generation_returns_unsaved_rows_and_persists_nothing() throws Exception {
        long before = flashcardRepository.count();

        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/ai-generate")
                        .with(csrf())
                        .param("count", "5")
                        .param("language", "tiếng việt")
                        .param("text", "Hàng đợi theo FIFO, ngăn xếp theo LIFO."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.cards[0].front").value("Hàng đợi"))
                .andExpect(jsonPath("$.data.cards[1].back").value("Cấu trúc LIFO"));

        assertEquals(before, flashcardRepository.count(),
                "AI output must stay in the editor until the existing save flow runs");
    }

    @Test
    @WithUserDetails(MEMBER)
    void ai_generation_is_owner_only() throws Exception {
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/ai-generate")
                        .with(csrf())
                        .param("count", "5")
                        .param("text", "Không được gửi nội dung này cho provider"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void ai_generation_requires_csrf_and_valid_material() throws Exception {
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/ai-generate")
                        .param("count", "5")
                        .param("text", "Nội dung"))
                .andExpect(status().isForbidden());

        MockMultipartFile fakePdf = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/ai-generate")
                        .file(fakePdf).with(csrf()).param("count", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void ai_provider_failure_is_a_retryable_gateway_error() throws Exception {
        when(aiClient.chatJsonObject(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenThrow(new AiClientException("Chưa cấu hình AI provider nào đang bật."));

        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/ai-generate")
                        .with(csrf()).param("count", "5").param("text", "Nội dung"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message", containsString("Chưa cấu hình AI provider")));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Builds a 2-column .xlsx (header + 2 data rows) as bytes. */
    private static byte[] xlsxBytes() throws IOException {
        String[][] grid = {
                {"Mặt trước", "Mặt sau"},
                {"apple", "quả táo"},
                {"book", "quyển sách"},
        };
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Thẻ");
            for (int r = 0; r < grid.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < grid[r].length; c++) {
                    row.createCell(c).setCellValue(grid[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void enroll(User u, ClassEntity clazz) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.REQUEST, null));
    }

    private ClassEntity saveClass(User lecturer, String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
