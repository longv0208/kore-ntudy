package com.ksh.features.dictionary;

import com.ksh.features.dictionary.KoreanDictionaryDtos.DeckOption;
import com.ksh.features.dictionary.KoreanDictionaryDtos.LookupResult;
import com.ksh.features.dictionary.KoreanDictionaryDtos.SaveRequest;
import com.ksh.features.dictionary.KoreanDictionaryDtos.SaveResult;
import com.ksh.features.flashcards.entity.Flashcard;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardDeckRepository;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class KoreanDictionaryLearningService {
    private static final Pattern HANGUL = Pattern.compile("[\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]");
    private static final Pattern EDGE = Pattern.compile("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$");
    private final KoreanDictionaryClient dictionaryClient;
    private final FlashcardDeckRepository deckRepository;
    private final FlashcardRepository cardRepository;

    public KoreanDictionaryLearningService(KoreanDictionaryClient dictionaryClient,
                                           FlashcardDeckRepository deckRepository,
                                           FlashcardRepository cardRepository) {
        this.dictionaryClient = dictionaryClient;
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
    }

    public LookupResult lookup(String rawWord) {
        String word = normalizeWord(rawWord);
        Optional<DictionaryEntry> found = dictionaryClient.isConfigured()
                ? dictionaryClient.lookupVietnamese(word) : Optional.empty();
        if (found.isEmpty()) return new LookupResult(false, dictionaryClient.isConfigured(), word, null, null, null, null);
        DictionaryEntry value = found.get();
        return new LookupResult(true, true, value.word(), value.pronunciation(),
                value.meaningVi(), value.partOfSpeech(), value.dictionaryUrl());
    }

    @Transactional(readOnly = true)
    public List<DeckOption> decks(Long userId) {
        return deckRepository.findByOwnerIdOrderByUpdatedAtDesc(userId).stream()
                .map(deck -> new DeckOption(deck.getId(), deck.getTitle(), cardRepository.countByDeckId(deck.getId())))
                .toList();
    }

    @Transactional
    public SaveResult save(Long userId, SaveRequest request) {
        if (request == null) throw new IllegalArgumentException("Hãy chọn bộ thẻ muốn lưu.");
        String word = normalizeWord(request.word());
        String meaning = conciseMeaning(normalizeRequired(
                request.meaningVi(), "Nhập nghĩa tiếng Việt trước khi lưu."));
        boolean createDeck = request.deckId() == null
                && request.newDeckTitle() != null
                && !request.newDeckTitle().isBlank();
        if (request.deckId() != null && request.newDeckTitle() != null
                && !request.newDeckTitle().isBlank()) {
            throw new IllegalArgumentException("Chỉ chọn một bộ thẻ hoặc tạo một bộ mới.");
        }
        FlashcardDeck deck;
        if (createDeck) {
            String title = normalizeDeckTitle(request.newDeckTitle());
            deck = deckRepository.save(new FlashcardDeck(userId, title, null));
        } else {
            if (request.deckId() == null) {
                throw new IllegalArgumentException("Hãy chọn bộ thẻ hoặc nhập tên bộ thẻ mới.");
            }
            deck = deckRepository.findById(request.deckId())
                    .filter(value -> value.getOwnerId().equals(userId))
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Không tìm thấy bộ thẻ thuộc tài khoản của bạn."));
        }
        Optional<Flashcard> existing = cardRepository.findFirstByDeckIdAndFrontText(deck.getId(), word);
        if (existing.isPresent()) return result(deck, existing.get(), true, false);
        int sortOrder = Math.toIntExact(cardRepository.countByDeckId(deck.getId()));
        // Keep the learning surface clean: front = Korean, back = Vietnamese meaning only.
        Flashcard card = cardRepository.save(new Flashcard(deck.getId(), word, meaning, sortOrder));
        return result(deck, card, false, createDeck);
    }

    public static String normalizeWord(String value) {
        String word = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        word = EDGE.matcher(word).replaceAll("");
        if (word.isBlank() || !HANGUL.matcher(word).find()) {
            throw new IllegalArgumentException("Hãy chọn một từ hoặc cụm từ tiếng Hàn.");
        }
        if (word.length() > 120) throw new IllegalArgumentException("Cụm từ đã chọn quá dài.");
        return word;
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private static String conciseMeaning(String value) {
        int separator = value.indexOf(" — ");
        return separator > 0 ? value.substring(0, separator).trim() : value;
    }

    private static String normalizeDeckTitle(String value) {
        String title = normalizeRequired(value, "Nhập tên bộ thẻ mới.");
        if (title.length() > 300) {
            throw new IllegalArgumentException("Tên bộ thẻ tối đa 300 ký tự.");
        }
        return title;
    }

    private static SaveResult result(FlashcardDeck deck, Flashcard card,
                                     boolean duplicate, boolean deckCreated) {
        return new SaveResult(deck.getId(), card.getId(), deck.getTitle(),
                "/my/flashcards/" + deck.getId(), duplicate, deckCreated);
    }
}
