package com.ksh.features.dictionary;

import com.ksh.features.dictionary.KoreanDictionaryDtos.SaveRequest;
import com.ksh.features.flashcards.entity.Flashcard;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardDeckRepository;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KoreanDictionaryLearningServiceTest {
    @Test void rejectsSavingIntoAnotherUsersDeck() {
        KoreanDictionaryClient client = mock(KoreanDictionaryClient.class);
        FlashcardDeckRepository decks = mock(FlashcardDeckRepository.class);
        FlashcardRepository cards = mock(FlashcardRepository.class);
        FlashcardDeck deck = mock(FlashcardDeck.class);
        when(deck.getOwnerId()).thenReturn(99L);
        when(decks.findById(5L)).thenReturn(Optional.of(deck));
        KoreanDictionaryLearningService service = new KoreanDictionaryLearningService(client, decks, cards);
        assertThatThrownBy(() -> service.save(1L,
                new SaveRequest(5L, "문화", "văn hóa", null, null, null)))
                .hasMessageContaining("Không tìm thấy bộ thẻ");
    }

    @Test void createsPersonalDeckAndFirstCardInOneOperation() {
        KoreanDictionaryClient client = mock(KoreanDictionaryClient.class);
        FlashcardDeckRepository decks = mock(FlashcardDeckRepository.class);
        FlashcardRepository cards = mock(FlashcardRepository.class);
        FlashcardDeck savedDeck = mock(FlashcardDeck.class);
        Flashcard savedCard = mock(Flashcard.class);
        when(savedDeck.getId()).thenReturn(8L);
        when(savedDeck.getTitle()).thenReturn("Từ mới hôm nay");
        when(decks.save(any(FlashcardDeck.class))).thenReturn(savedDeck);
        when(cards.countByDeckId(8L)).thenReturn(0L);
        when(cards.findFirstByDeckIdAndFrontText(8L, "문화")).thenReturn(Optional.empty());
        when(savedCard.getId()).thenReturn(81L);
        when(cards.save(any(Flashcard.class))).thenReturn(savedCard);
        KoreanDictionaryLearningService service = new KoreanDictionaryLearningService(client, decks, cards);

        var result = service.save(1L,
                new SaveRequest(null, "  Từ mới hôm nay  ", "문화", "văn hóa",
                        null, null, null));

        assertThat(result.deckId()).isEqualTo(8L);
        assertThat(result.deckTitle()).isEqualTo("Từ mới hôm nay");
        assertThat(result.deckCreated()).isTrue();
        assertThat(result.alreadySaved()).isFalse();
        verify(decks).save(any(FlashcardDeck.class));
        verify(cards).findFirstByDeckIdAndFrontText(eq(8L), eq("문화"));
        verify(cards).save(any(Flashcard.class));
    }

    @Test void rejectsRequestThatSelectsAndCreatesDeckAtTheSameTime() {
        KoreanDictionaryLearningService service = new KoreanDictionaryLearningService(
                mock(KoreanDictionaryClient.class),
                mock(FlashcardDeckRepository.class),
                mock(FlashcardRepository.class));

        assertThatThrownBy(() -> service.save(1L,
                new SaveRequest(5L, "Bộ mới", "문화", "văn hóa", null, null, null)))
                .hasMessageContaining("Chỉ chọn một bộ thẻ");
    }
}
