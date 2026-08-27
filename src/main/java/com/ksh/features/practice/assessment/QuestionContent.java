package com.ksh.features.practice.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record QuestionContent(
        String schemaVersion,
        List<Option> options,
        List<Blank> blanks,
        String imageReference,
        String audioReference,
        SpeakingDelivery speakingDelivery,
        WritingBlankContract.QuestionResponse writingResponse,
        String languageTag
) {
    public static final String SCHEMA_VERSION_V1 = "question-content-v1";
    public static final String SCHEMA_VERSION_V2 = "question-content-v2";
    public static final String SCHEMA_VERSION_V3 = "question-content-v3";

    /**
     * Existing assessment writers remain on v1 until their owning 13C3 slices
     * explicitly move them. Keeping this alias stable prevents an accidental
     * rewrite of published v1 questions or attempts.
     */
    public static final String SCHEMA_VERSION = SCHEMA_VERSION_V1;

    public QuestionContent {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        options = immutable(options);
        blanks = immutable(blanks);
        languageTag = languageTag == null || languageTag.isBlank()
                ? null
                : languageTag.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public QuestionContent(String schemaVersion,
                           List<Option> options,
                           List<Blank> blanks,
                           String imageReference,
                           String audioReference,
                           SpeakingDelivery speakingDelivery,
                           WritingBlankContract.QuestionResponse writingResponse) {
        this(schemaVersion, options, blanks, imageReference, audioReference,
                speakingDelivery, writingResponse, null);
    }

    public QuestionContent(String schemaVersion,
                           List<Option> options,
                           List<Blank> blanks,
                           String imageReference,
                           String audioReference,
                           SpeakingDelivery speakingDelivery) {
        this(schemaVersion, options, blanks, imageReference, audioReference,
                speakingDelivery, null, null);
    }

    public QuestionContent(String schemaVersion,
                           List<Option> options,
                           List<Blank> blanks,
                           String imageReference,
                           String audioReference) {
        this(schemaVersion, options, blanks, imageReference, audioReference,
                null, null, null);
    }

    public QuestionContent(String schemaVersion,
                           List<Option> options,
                           List<Blank> blanks) {
        this(schemaVersion, options, blanks, null, null, null);
    }

    public static QuestionContent empty() {
        return new QuestionContent(SCHEMA_VERSION, List.of(), List.of());
    }

    public static QuestionContent speakingV2(SpeakingDelivery speakingDelivery) {
        return new QuestionContent(
                SCHEMA_VERSION_V2,
                List.of(),
                List.of(),
                null,
                null,
                speakingDelivery,
                null,
                null);
    }

    public static boolean supportsTypedSpeakingDelivery(String schemaVersion) {
        return SCHEMA_VERSION_V2.equals(schemaVersion)
                || SCHEMA_VERSION_V3.equals(schemaVersion);
    }

    public QuestionContent withLanguageTag(String nextLanguageTag) {
        return new QuestionContent(
                SCHEMA_VERSION_V3,
                options,
                blanks,
                imageReference,
                audioReference,
                speakingDelivery,
                writingResponse,
                nextLanguageTag);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(String id, String text, String imageReference) {
        public Option(String id, String text) {
            this(id, text, null);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Blank(String id, String prompt) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record SpeakingDelivery(
            SpeakingPromptInputType inputType,
            SpeakingDeliveryMode deliveryMode,
            String promptAudioReference,
            SpeakingAudioOrigin audioOrigin,
            Integer promptPlayLimit,
            Integer preparationSeconds,
            Integer responseSeconds
    ) {
        /** Historical v1 shape retained for immutable dual-read and v1 writers. */
        public SpeakingDelivery(String promptAudioReference,
                                Integer promptPlayLimit,
                                Integer preparationSeconds,
                                Integer responseSeconds) {
            this(null, null, promptAudioReference, null,
                    promptPlayLimit, preparationSeconds, responseSeconds);
        }
    }

    public enum SpeakingPromptInputType implements JsonCode {
        AUDIO_UPLOAD("audio_upload"),
        MANUAL_TEXT("manual_text");

        private final String code;

        SpeakingPromptInputType(String code) {
            this.code = code;
        }

        @Override
        @JsonValue
        public String code() {
            return code;
        }

        @JsonCreator
        public static SpeakingPromptInputType fromJson(String value) {
            return parse(SpeakingPromptInputType.class, value);
        }
    }

    public enum SpeakingDeliveryMode implements JsonCode {
        AUDIO_ONLY("audio_only"),
        TEXT_ONLY("text_only"),
        TEXT_AND_AUDIO("text_and_audio");

        private final String code;

        SpeakingDeliveryMode(String code) {
            this.code = code;
        }

        @Override
        @JsonValue
        public String code() {
            return code;
        }

        @JsonCreator
        public static SpeakingDeliveryMode fromJson(String value) {
            return parse(SpeakingDeliveryMode.class, value);
        }
    }

    public enum SpeakingAudioOrigin implements JsonCode {
        TEACHER_UPLOAD("teacher_upload"),
        AI_TTS("ai_tts"),
        NONE("none");

        private final String code;

        SpeakingAudioOrigin(String code) {
            this.code = code;
        }

        @Override
        @JsonValue
        public String code() {
            return code;
        }

        @JsonCreator
        public static SpeakingAudioOrigin fromJson(String value) {
            return parse(SpeakingAudioOrigin.class, value);
        }
    }

    private interface JsonCode {
        String code();
    }

    private static <T extends Enum<T> & JsonCode> T parse(Class<T> type, String value) {
        if (value != null) {
            for (T candidate : type.getEnumConstants()) {
                if (candidate.code().equals(value)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value);
    }
}
