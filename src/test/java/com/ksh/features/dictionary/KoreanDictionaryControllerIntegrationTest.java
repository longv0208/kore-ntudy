package com.ksh.features.dictionary;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardDeckRepository;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KoreanDictionaryControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FlashcardDeckRepository deckRepository;
    @Autowired private FlashcardRepository cardRepository;

    @Test
    @WithUserDetails("student@ksh.edu.vn")
    void createsPersonalDeckAndFirstCardFromDictionaryPopup() throws Exception {
        User student = userRepository.findByEmailIgnoreCase("student@ksh.edu.vn").orElseThrow();
        String title = "Từ điển tích hợp 한글";

        mockMvc.perform(post("/api/korean-dictionary/flashcards")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newDeckTitle": "Từ điển tích hợp 한글",
                                  "word": "문화",
                                  "meaningVi": "văn hóa — phần giải thích dài",
                                  "pronunciation": "문화",
                                  "partOfSpeech": "danh từ",
                                  "dictionaryUrl": "https://krdict.korean.go.kr"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.deckTitle").value(title))
                .andExpect(jsonPath("$.data.deckCreated").value(true))
                .andExpect(jsonPath("$.data.alreadySaved").value(false));

        FlashcardDeck deck = deckRepository
                .findFirstByOwnerIdAndTitleOrderByIdAsc(student.getId(), title)
                .orElseThrow();
        assertThat(deck.getVisibility()).isEqualTo(FlashcardDeck.VISIBILITY_PRIVATE);
        assertThat(cardRepository.findFirstByDeckIdAndFrontText(deck.getId(), "문화"))
                .get()
                .extracting(card -> card.getBackText())
                .isEqualTo("văn hóa");
    }
}
