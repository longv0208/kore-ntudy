package com.ksh.features.dictionary;

import java.util.List;

public final class KoreanDictionaryDtos {
    private KoreanDictionaryDtos() {}

    public record LookupResult(boolean found, boolean configured, String word,
                               String pronunciation, String meaningVi,
                               String partOfSpeech, String dictionaryUrl) {}

    public record DeckOption(Long id, String title, long cardCount) {}

    public record SaveRequest(Long deckId, String newDeckTitle,
                              String word, String meaningVi,
                              String pronunciation, String partOfSpeech,
                              String dictionaryUrl) {
        public SaveRequest(Long deckId, String word, String meaningVi,
                           String pronunciation, String partOfSpeech,
                           String dictionaryUrl) {
            this(deckId, null, word, meaningVi, pronunciation, partOfSpeech, dictionaryUrl);
        }
    }

    public record SaveResult(Long deckId, Long cardId, String deckTitle,
                             String deckUrl, boolean alreadySaved,
                             boolean deckCreated) {}

    public record DeckOptions(List<DeckOption> decks) {}
}
