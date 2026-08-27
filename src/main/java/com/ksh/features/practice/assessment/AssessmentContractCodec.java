package com.ksh.features.practice.assessment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

@Component
public class AssessmentContractCodec {

    private static final Set<String> TFNG_VALUES = Set.of("TRUE", "FALSE", "NOT_GIVEN");
    private static final Set<String> QUESTION_CONTENT_FIELDS = Set.of(
            "schemaVersion",
            "options",
            "blanks",
            "imageReference",
            "audioReference",
            "speakingDelivery",
            "writingResponse",
            "languageTag");
    private static final Set<String> OPTION_FIELDS =
            Set.of("id", "text", "imageReference");
    private static final Set<String> BLANK_FIELDS =
            Set.of("id", "prompt", "ordinal", "blankId", "context");
    private static final Set<String> SPEAKING_DELIVERY_FIELDS = Set.of(
            "inputType",
            "deliveryMode",
            "promptAudioReference",
            "audioOrigin",
            "promptPlayLimit",
            "preparationSeconds",
            "responseSeconds");
    private static final Set<String> WRITING_RESPONSE_FIELDS = Set.of(
            "responseSchemaVersion", "responseMode", "taskType", "blanks");
    private static final Set<String> WRITING_BLANK_DEFINITION_FIELDS = Set.of(
            "blankId", "ordinal", "context");
    private static final Set<String> ANSWER_SPEC_FIELDS = Set.of(
            "schemaVersion", "questionType", "correctOptionIds",
            "correctValue", "blanks", "scoringPolicyCode",
            "writingBlankAuthority", "evaluationMode");
    private static final Set<String> ANSWER_BLANK_FIELDS = Set.of(
            "blankId", "acceptedValues", "id", "ordinal", "acceptedAnswers");
    private static final Set<String> WRITING_AUTHORITY_FIELDS = Set.of(
            "contractVersion", "taskType", "normalization",
            "whitespacePolicy", "blanks");
    private static final Set<String> WRITING_BLANK_AUTHORITY_FIELDS = Set.of(
            "blankId", "ordinal", "acceptedAnswers");
    private static final Set<String> WRITING_ACCEPTED_ANSWER_FIELDS = Set.of(
            "text", "equivalence", "reason", "evidenceIds");

    private final ObjectMapper objectMapper;
    private final QuestionTypeResolver typeResolver;

    public AssessmentContractCodec(ObjectMapper objectMapper, QuestionTypeResolver typeResolver) {
        this.objectMapper = objectMapper;
        this.typeResolver = typeResolver;
    }

    public String writeQuestionContent(QuestionContent content, CanonicalQuestionType type) {
        validateQuestionContent(content, type);
        return write(content, "question content");
    }

    public QuestionContent readQuestionContent(String json, CanonicalQuestionType type) {
        validateQuestionContentJsonShape(json);
        QuestionContent content = read(json, QuestionContent.class, "question content");
        validateQuestionContent(content, type);
        return content;
    }

    public String writeAnswerSpec(AnswerSpec answerSpec, QuestionContent content) {
        validateAnswerSpec(answerSpec, content);
        return write(answerSpec, "answer spec");
    }

    public AnswerSpec readAnswerSpec(String json, QuestionContent content) {
        validateAnswerSpecJsonShape(json);
        AnswerSpec answerSpec = read(json, AnswerSpec.class, "answer spec");
        validateAnswerSpec(answerSpec, content);
        return answerSpec;
    }

    public String writeLearnerAnswer(LearnerAnswer learnerAnswer) {
        validateLearnerAnswer(learnerAnswer);
        return write(learnerAnswer, "learner answer");
    }

    public LearnerAnswer readLearnerAnswer(String json) {
        LearnerAnswer learnerAnswer = read(json, LearnerAnswer.class, "learner answer");
        validateLearnerAnswer(learnerAnswer);
        return learnerAnswer;
    }

    public QuestionContent adaptLegacyContent(String optionsJson, String rawQuestionType) {
        CanonicalQuestionType type = resolveLegacyType(rawQuestionType);
        requireTypedContract(type);
        List<QuestionContent.Option> options = readLegacyOptions(optionsJson);
        List<QuestionContent.Blank> blanks = type == CanonicalQuestionType.FILL_BLANK
                ? List.of(new QuestionContent.Blank("blank_1", ""))
                : List.of();
        QuestionContent content = new QuestionContent(
                QuestionContent.SCHEMA_VERSION,
                options,
                blanks
        );
        validateQuestionContent(content, type);
        return content;
    }

    public AnswerSpec adaptLegacyAnswerSpec(String rawQuestionType,
                                            String answerKey,
                                            QuestionContent content) {
        CanonicalQuestionType type = resolveLegacyType(rawQuestionType);
        requireTypedContract(type);
        String normalizedKey = normalizeValue(answerKey);
        AnswerSpec answerSpec = switch (type) {
            case SINGLE_CHOICE -> new AnswerSpec(
                    AnswerSpec.SCHEMA_VERSION, type,
                    List.of(resolveLegacyOptionId(answerKey, content)), null,
                    List.of(), ScoringPolicyCode.ALL_OR_NOTHING);
            case TRUE_FALSE_NOT_GIVEN -> new AnswerSpec(
                    AnswerSpec.SCHEMA_VERSION, type, List.of(), normalizedKey,
                    List.of(), ScoringPolicyCode.ALL_OR_NOTHING);
            case FILL_BLANK -> new AnswerSpec(
                    AnswerSpec.SCHEMA_VERSION, type, List.of(), null,
                    List.of(new AnswerSpec.BlankAnswer("blank_1", List.of(required(answerKey, "answer key")))),
                    ScoringPolicyCode.NORMALIZED_EXACT);
            case MULTIPLE_ANSWER, MATCHING -> throw new IllegalArgumentException(
                    "Question type " + type + " requires a typed assessment contract");
            case ESSAY, SPEAKING -> new AnswerSpec(
                    AnswerSpec.SCHEMA_VERSION, type, List.of(), null,
                    List.of(), ScoringPolicyCode.PROFILE_BASED);
        };
        validateAnswerSpec(answerSpec, content);
        return answerSpec;
    }

    public LearnerAnswer adaptLegacyLearnerAnswer(String rawQuestionType,
                                                  String rawAnswer,
                                                  QuestionContent content) {
        CanonicalQuestionType type = resolveLegacyType(rawQuestionType);
        requireTypedContract(type);
        LearnerAnswer learnerAnswer = switch (type) {
            case SINGLE_CHOICE -> new LearnerAnswer(
                    LearnerAnswer.SCHEMA_VERSION, type,
                    blank(rawAnswer) ? List.of() : List.of(resolveLegacyOptionId(rawAnswer, content)),
                    null, java.util.Map.of(), null);
            case TRUE_FALSE_NOT_GIVEN -> new LearnerAnswer(
                    LearnerAnswer.SCHEMA_VERSION, type, List.of(),
                    blank(rawAnswer) ? null : normalizeValue(rawAnswer), java.util.Map.of(), null);
            case FILL_BLANK -> new LearnerAnswer(
                    LearnerAnswer.SCHEMA_VERSION, type, List.of(), null,
                    blank(rawAnswer) ? java.util.Map.of() : java.util.Map.of("blank_1", rawAnswer), null);
            case MULTIPLE_ANSWER, MATCHING -> throw new IllegalArgumentException(
                    "Question type " + type + " requires a typed assessment contract");
            case ESSAY, SPEAKING -> new LearnerAnswer(
                    LearnerAnswer.SCHEMA_VERSION, type, List.of(), null,
                    java.util.Map.of(), rawAnswer);
        };
        validateLearnerAnswer(learnerAnswer);
        return learnerAnswer;
    }

    public void validateQuestionContent(QuestionContent content, CanonicalQuestionType type) {
        require(content, "question content");
        require(type, "question type");
        requireVersion(
                content.schemaVersion(),
                Set.of(
                        QuestionContent.SCHEMA_VERSION_V1,
                        QuestionContent.SCHEMA_VERSION_V2,
                        QuestionContent.SCHEMA_VERSION_V3),
                "question content");
        validateLanguageTag(content);
        uniqueIds(content.options(), QuestionContent.Option::id, "option");
        uniqueIds(content.blanks(), QuestionContent.Blank::id, "blank");

        if (type != CanonicalQuestionType.SPEAKING && content.speakingDelivery() != null) {
            throw new IllegalArgumentException("Speaking delivery is only valid for SPEAKING questions");
        }
        if (type != CanonicalQuestionType.ESSAY
                && content.writingResponse() != null) {
            throw new IllegalArgumentException(
                    "Writing structured response is only valid for ESSAY-family Writing questions");
        }
        if (content.writingResponse() != null) {
            if (!content.blanks().isEmpty()) {
                throw new IllegalArgumentException(
                        "Writing structured blanks cannot reuse objective content blanks");
            }
            WritingBlankContractVerifier.verifyQuestion(
                    content.writingResponse());
        }
        if (content.speakingDelivery() != null) {
            validateSpeakingDelivery(content.schemaVersion(), content.speakingDelivery());
        }
        if (QuestionContent.SCHEMA_VERSION_V2.equals(content.schemaVersion())) {
            if (type != CanonicalQuestionType.SPEAKING) {
                throw new IllegalArgumentException(
                        "question-content-v2 is only valid for SPEAKING questions");
            }
            require(content.speakingDelivery(), "v2 speaking delivery");
        }
        if (QuestionContent.SCHEMA_VERSION_V3.equals(content.schemaVersion())
                && type == CanonicalQuestionType.SPEAKING) {
            require(content.speakingDelivery(), "v3 speaking delivery");
        }

        switch (type) {
            case SINGLE_CHOICE, MULTIPLE_ANSWER -> requireNotEmpty(content.options(), "options");
            case FILL_BLANK -> requireNotEmpty(content.blanks(), "blanks");
            case MATCHING -> {
                requireNotEmpty(content.options(), "matching candidates");
                requireNotEmpty(content.blanks(), "matching targets");
            }
            case TRUE_FALSE_NOT_GIVEN, ESSAY, SPEAKING -> {
                // No additional content fields are mandatory.
            }
        }
    }

    private static void validateLanguageTag(QuestionContent content) {
        String languageTag = content.languageTag();
        if (QuestionContent.SCHEMA_VERSION_V3.equals(content.schemaVersion())) {
            if (!Set.of("ko", "vi").contains(languageTag)) {
                throw new IllegalArgumentException(
                        "question-content-v3 languageTag must be ko or vi");
            }
            return;
        }
        if (languageTag != null) {
            throw new IllegalArgumentException(
                    "Language region metadata requires question-content-v3");
        }
    }

    private static void validateSpeakingDelivery(
            String schemaVersion,
            QuestionContent.SpeakingDelivery delivery) {
        requireRange(delivery.preparationSeconds(), 0, 600, "speaking preparation seconds");
        requireRange(delivery.responseSeconds(), 1, 1800, "speaking response seconds");
        if (QuestionContent.SCHEMA_VERSION_V1.equals(schemaVersion)) {
            requireRange(delivery.promptPlayLimit(), 1, 10, "speaking prompt play limit");
            if (delivery.inputType() != null
                    || delivery.deliveryMode() != null
                    || delivery.audioOrigin() != null) {
                throw new IllegalArgumentException(
                        "question-content-v1 cannot contain v2 speaking mode identity");
            }
            return;
        }

        QuestionContent.SpeakingPromptInputType inputType =
                require(delivery.inputType(), "speaking prompt input type");
        QuestionContent.SpeakingDeliveryMode deliveryMode =
                require(delivery.deliveryMode(), "speaking delivery mode");
        QuestionContent.SpeakingAudioOrigin audioOrigin =
                require(delivery.audioOrigin(), "speaking audio origin");
        boolean hasPromptAudio = !blank(delivery.promptAudioReference());

        boolean uploadedAudio = inputType == QuestionContent.SpeakingPromptInputType.AUDIO_UPLOAD
                && deliveryMode == QuestionContent.SpeakingDeliveryMode.AUDIO_ONLY
                && audioOrigin == QuestionContent.SpeakingAudioOrigin.TEACHER_UPLOAD
                && hasPromptAudio;
        boolean manualTextOnly = inputType == QuestionContent.SpeakingPromptInputType.MANUAL_TEXT
                && deliveryMode == QuestionContent.SpeakingDeliveryMode.TEXT_ONLY
                && audioOrigin == QuestionContent.SpeakingAudioOrigin.NONE
                && !hasPromptAudio
                && delivery.promptPlayLimit() == null;
        boolean manualTextWithTts = inputType == QuestionContent.SpeakingPromptInputType.MANUAL_TEXT
                && deliveryMode == QuestionContent.SpeakingDeliveryMode.TEXT_AND_AUDIO
                && audioOrigin == QuestionContent.SpeakingAudioOrigin.AI_TTS
                && hasPromptAudio
                && validAudioPlayLimit(delivery.promptPlayLimit());
        uploadedAudio = uploadedAudio && validAudioPlayLimit(delivery.promptPlayLimit());
        if (!uploadedAudio && !manualTextOnly && !manualTextWithTts) {
            throw new IllegalArgumentException(
                    "Unsupported question-content-v2 speaking mode combination");
        }
    }

    private static boolean validAudioPlayLimit(Integer value) {
        return value != null && value >= 1 && value <= 10;
    }

    public void validateAnswerSpec(AnswerSpec spec, QuestionContent content) {
        require(spec, "answer spec");
        require(content, "question content");
        if (!Set.of(AnswerSpec.SCHEMA_VERSION_V1, AnswerSpec.SCHEMA_VERSION_V2)
                .contains(spec.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported answer spec schema version: "
                            + spec.schemaVersion());
        }
        CanonicalQuestionType type = require(spec.questionType(), "answer spec question type");
        validateQuestionContent(content, type);
        ScoringPolicyCode policy = require(spec.scoringPolicyCode(), "scoring policy");
        rejectDuplicates(spec.correctOptionIds(), "correct option ID");

        switch (type) {
            case SINGLE_CHOICE -> {
                requireSize(spec.correctOptionIds(), 1, "single-choice correct option IDs");
                requirePolicy(policy, Set.of(ScoringPolicyCode.ALL_OR_NOTHING), type);
                requireReferences(spec.correctOptionIds(), ids(content.options(), QuestionContent.Option::id), "option");
            }
            case MULTIPLE_ANSWER -> {
                if (spec.correctOptionIds().size() < 2) {
                    throw new IllegalArgumentException(
                            "Multiple-answer questions require at least two correct option IDs");
                }
                requirePolicy(policy, Set.of(ScoringPolicyCode.ALL_OR_NOTHING), type);
                requireReferences(spec.correctOptionIds(),
                        ids(content.options(), QuestionContent.Option::id), "option");
            }
            case TRUE_FALSE_NOT_GIVEN -> {
                if (!TFNG_VALUES.contains(normalizeValue(spec.correctValue()))) {
                    throw new IllegalArgumentException("TFNG correct value must be TRUE, FALSE, or NOT_GIVEN");
                }
                requirePolicy(policy, Set.of(ScoringPolicyCode.ALL_OR_NOTHING), type);
            }
            case FILL_BLANK -> {
                requirePolicy(policy, Set.of(ScoringPolicyCode.NORMALIZED_EXACT), type);
                requireNotEmpty(spec.blanks(), "blank answer specs");
                uniqueIds(spec.blanks(), AnswerSpec.BlankAnswer::blankId, "blank answer");
                Set<String> contentBlankIds = ids(content.blanks(), QuestionContent.Blank::id);
                requireReferences(spec.blanks().stream().map(AnswerSpec.BlankAnswer::blankId).toList(),
                        contentBlankIds, "blank");
                if (!contentBlankIds.equals(ids(spec.blanks(), AnswerSpec.BlankAnswer::blankId))) {
                    throw new IllegalArgumentException("Every blank must have an answer spec");
                }
                for (AnswerSpec.BlankAnswer blankAnswer : spec.blanks()) {
                    requireNotEmpty(blankAnswer.acceptedValues(), "accepted values for " + blankAnswer.blankId());
                    rejectDuplicates(blankAnswer.acceptedValues().stream().map(AssessmentContractCodec::normalizeValue).toList(),
                            "normalized accepted value");
                }
            }
            case MATCHING -> {
                requirePolicy(policy, Set.of(ScoringPolicyCode.NORMALIZED_EXACT), type);
                requireNotEmpty(spec.blanks(), "matching answer specs");
                uniqueIds(spec.blanks(), AnswerSpec.BlankAnswer::blankId, "matching answer");
                Set<String> targetIds = ids(content.blanks(), QuestionContent.Blank::id);
                requireReferences(spec.blanks().stream().map(AnswerSpec.BlankAnswer::blankId).toList(),
                        targetIds, "matching target");
                if (!targetIds.equals(ids(spec.blanks(), AnswerSpec.BlankAnswer::blankId))) {
                    throw new IllegalArgumentException("Every matching target must have an answer spec");
                }
                Set<String> candidateIds = ids(content.options(), QuestionContent.Option::id);
                for (AnswerSpec.BlankAnswer matchingAnswer : spec.blanks()) {
                    requireSize(matchingAnswer.acceptedValues(), 1,
                            "matching candidate for " + matchingAnswer.blankId());
                    requireReferences(matchingAnswer.acceptedValues(), candidateIds,
                            "matching candidate option");
                }
            }
            case ESSAY -> {
                requirePolicy(
                        policy,
                        Set.of(ScoringPolicyCode.PROFILE_BASED),
                        type);
                if (content.writingResponse() == null) {
                    if (spec.writingBlankAuthority() != null) {
                        throw new IllegalArgumentException(
                                "Writing blank authority requires a structured Writing response");
                    }
                } else {
                    if (!spec.blanks().isEmpty()
                            || !spec.correctOptionIds().isEmpty()
                            || !blank(spec.correctValue())) {
                        throw new IllegalArgumentException(
                                "Structured Writing answers cannot reuse objective answer fields");
                    }
                    WritingBlankContractVerifier.verifyAuthority(
                            content.writingResponse(),
                            require(
                                    spec.writingBlankAuthority(),
                                    "Writing blank answer authority"));
                }
            }
            case SPEAKING -> {
                requirePolicy(
                        policy,
                        Set.of(ScoringPolicyCode.PROFILE_BASED),
                        type);
                if (spec.writingBlankAuthority() != null) {
                    throw new IllegalArgumentException(
                            "Writing blank authority is invalid for Speaking");
                }
            }
        }
        if (type != CanonicalQuestionType.ESSAY
                && spec.evaluationMode() != null) {
            throw new IllegalArgumentException(
                    "Evaluation mode is only supported for Writing ESSAY questions");
        }
        if ((spec.evaluationMode() == null)
                != AnswerSpec.SCHEMA_VERSION_V1.equals(
                spec.schemaVersion())) {
            throw new IllegalArgumentException(
                    "answer-spec-v2 requires an explicit Writing evaluation mode");
        }
    }

    public void validateLearnerAnswer(LearnerAnswer answer) {
        require(answer, "learner answer");
        requireVersion(answer.schemaVersion(), LearnerAnswer.SCHEMA_VERSION, "learner answer");
        CanonicalQuestionType type = require(answer.questionType(), "learner answer question type");
        rejectDuplicates(answer.selectedOptionIds(), "selected option ID");
        answer.blankAnswers().keySet().forEach(id -> required(id, "blank answer ID"));
        if (type == CanonicalQuestionType.TRUE_FALSE_NOT_GIVEN
                && !blank(answer.selectedValue())
                && !TFNG_VALUES.contains(normalizeValue(answer.selectedValue()))) {
            throw new IllegalArgumentException("TFNG learner value must be TRUE, FALSE, or NOT_GIVEN");
        }
        switch (type) {
            case SINGLE_CHOICE -> {
                if (answer.selectedOptionIds().size() > 1) {
                    throw new IllegalArgumentException(
                            "Single-choice learner answer cannot select more than one option");
                }
            }
            case MULTIPLE_ANSWER -> {
                if (!answer.blankAnswers().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Multiple-answer learner answer cannot contain matching targets");
                }
            }
            case MATCHING -> {
                if (!answer.selectedOptionIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Matching learner answer must use target answers");
                }
            }
            case TRUE_FALSE_NOT_GIVEN, FILL_BLANK, ESSAY, SPEAKING -> {
                // Historical fields remain readable for their owning contracts.
            }
        }
    }

    private static void requireTypedContract(CanonicalQuestionType type) {
        if (type == CanonicalQuestionType.MULTIPLE_ANSWER
                || type == CanonicalQuestionType.MATCHING) {
            throw new IllegalArgumentException(
                    "Question type " + type + " requires a typed assessment contract");
        }
    }

    private CanonicalQuestionType resolveLegacyType(String rawQuestionType) {
        String normalized = rawQuestionType == null ? "" : rawQuestionType.trim();
        if (normalized.equalsIgnoreCase("MCQ")) {
            return CanonicalQuestionType.SINGLE_CHOICE;
        }
        if (normalized.equalsIgnoreCase("MCQ_MULTIPLE")) {
            return CanonicalQuestionType.MULTIPLE_ANSWER;
        }
        if (normalized.equalsIgnoreCase("MATCHING_INFORMATION")) {
            return CanonicalQuestionType.MATCHING;
        }
        return typeResolver.resolve(rawQuestionType);
    }

    private List<QuestionContent.Option> readLegacyOptions(String optionsJson) {
        if (blank(optionsJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(optionsJson);
            if (!root.isArray()) {
                throw new IllegalArgumentException("Legacy options must be a JSON array");
            }
            List<QuestionContent.Option> options = new ArrayList<>();
            for (int index = 0; index < root.size(); index++) {
                JsonNode node = root.get(index);
                String fallbackId = "opt_" + (index + 1);
                String id = node.isObject() ? node.path("id").asText(fallbackId) : fallbackId;
                String text = node.isObject() ? node.path("text").asText("") : node.asText("");
                String imageReference = node.isObject()
                        ? node.path("imageReference").asText(node.path("imageUrl").asText(null))
                        : null;
                options.add(new QuestionContent.Option(id, text, imageReference));
            }
            return options;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid legacy options JSON", exception);
        }
    }

    private void validateQuestionContentJsonShape(String json) {
        if (blank(json)) {
            throw new IllegalArgumentException("Missing question content JSON");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            requireObject(root, "question content");
            rejectUnknownFields(root, QUESTION_CONTENT_FIELDS, "question content");

            JsonNode options = root.get("options");
            if (options != null && !options.isNull()) {
                if (!options.isArray()) {
                    throw new IllegalArgumentException("Question content options must be an array");
                }
                for (JsonNode option : options) {
                    requireObject(option, "question content option");
                    rejectUnknownFields(option, OPTION_FIELDS, "question content option");
                }
            }

            JsonNode blanks = root.get("blanks");
            if (blanks != null && !blanks.isNull()) {
                if (!blanks.isArray()) {
                    throw new IllegalArgumentException("Question content blanks must be an array");
                }
                for (JsonNode contentBlank : blanks) {
                    requireObject(contentBlank, "question content blank");
                    rejectUnknownFields(contentBlank, BLANK_FIELDS, "question content blank");
                }
            }

            JsonNode delivery = root.get("speakingDelivery");
            if (delivery != null && !delivery.isNull()) {
                requireObject(delivery, "speaking delivery");
                rejectUnknownFields(
                        delivery, SPEAKING_DELIVERY_FIELDS, "speaking delivery");
            }
            JsonNode writingResponse = root.get("writingResponse");
            if (writingResponse != null && !writingResponse.isNull()) {
                requireObject(
                        writingResponse, "Writing response contract");
                rejectUnknownFields(
                        writingResponse,
                        WRITING_RESPONSE_FIELDS,
                        "Writing response contract");
                JsonNode definitions = writingResponse.get("blanks");
                if (definitions == null || !definitions.isArray()) {
                    throw new IllegalArgumentException(
                            "Writing response blanks must be an array");
                }
                for (JsonNode definition : definitions) {
                    requireObject(
                            definition, "Writing blank definition");
                    rejectUnknownFields(
                            definition,
                            WRITING_BLANK_DEFINITION_FIELDS,
                            "Writing blank definition");
                }
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid question content JSON", exception);
        }
    }

    private void validateAnswerSpecJsonShape(String json) {
        if (blank(json)) {
            throw new IllegalArgumentException(
                    "Missing answer spec JSON");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            requireObject(root, "answer spec");
            rejectUnknownFields(root, ANSWER_SPEC_FIELDS, "answer spec");
            JsonNode blanks = root.get("blanks");
            if (blanks != null && !blanks.isNull()) {
                if (!blanks.isArray()) {
                    throw new IllegalArgumentException(
                            "Answer spec blanks must be an array");
                }
                for (JsonNode blank : blanks) {
                    requireObject(blank, "answer spec blank");
                    rejectUnknownFields(
                            blank, ANSWER_BLANK_FIELDS,
                            "answer spec blank");
                }
            }
            JsonNode authority = root.get("writingBlankAuthority");
            if (authority == null || authority.isNull()) {
                return;
            }
            requireObject(authority, "Writing blank authority");
            rejectUnknownFields(
                    authority, WRITING_AUTHORITY_FIELDS,
                    "Writing blank authority");
            JsonNode authorityBlanks = authority.get("blanks");
            if (authorityBlanks == null || !authorityBlanks.isArray()) {
                throw new IllegalArgumentException(
                        "Writing blank authority blanks must be an array");
            }
            for (JsonNode authorityBlank : authorityBlanks) {
                requireObject(
                        authorityBlank, "Writing blank authority item");
                rejectUnknownFields(
                        authorityBlank,
                        WRITING_BLANK_AUTHORITY_FIELDS,
                        "Writing blank authority item");
                JsonNode accepted = authorityBlank.get(
                        "acceptedAnswers");
                if (accepted == null || !accepted.isArray()) {
                    throw new IllegalArgumentException(
                            "Writing accepted answers must be an array");
                }
                for (JsonNode answer : accepted) {
                    requireObject(answer, "Writing accepted answer");
                    rejectUnknownFields(
                            answer,
                            WRITING_ACCEPTED_ANSWER_FIELDS,
                            "Writing accepted answer");
                }
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Invalid answer spec JSON", exception);
        }
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
    }

    private static void rejectUnknownFields(
            JsonNode object,
            Set<String> allowedFields,
            String label) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException(
                        "Unsupported " + label + " field: " + field);
            }
        });
    }

    private String resolveLegacyOptionId(String legacyValue, QuestionContent content) {
        String value = required(legacyValue, "legacy option answer");
        for (QuestionContent.Option option : content.options()) {
            if (option.id().equals(value) || normalizeValue(option.text()).equals(normalizeValue(value))) {
                return option.id();
            }
        }
        int index = legacyOptionIndex(value);
        if (index >= 0 && index < content.options().size()) {
            return content.options().get(index).id();
        }
        throw new IllegalArgumentException("Legacy option answer does not reference a known option: " + value);
    }

    private static int legacyOptionIndex(String value) {
        String normalized = normalizeValue(value);
        try {
            return Integer.parseInt(normalized) - 1;
        } catch (NumberFormatException ignored) {
            return normalized.length() == 1 && normalized.charAt(0) >= 'A' && normalized.charAt(0) <= 'Z'
                    ? normalized.charAt(0) - 'A'
                    : -1;
        }
    }

    private String write(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize " + label, exception);
        }
    }

    private <T> T read(String json, Class<T> type, String label) {
        if (blank(json)) {
            throw new IllegalArgumentException("Missing " + label + " JSON");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid " + label + " JSON", exception);
        }
    }

    private static void requireVersion(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Unsupported " + label + " schema version: " + actual);
        }
    }

    private static void requireVersion(String actual, Set<String> expected, String label) {
        if (!expected.contains(actual)) {
            throw new IllegalArgumentException("Unsupported " + label + " schema version: " + actual);
        }
    }

    private static <T> void uniqueIds(List<T> values, Function<T, String> idExtractor, String label) {
        List<String> ids = values.stream().map(idExtractor).map(id -> required(id, label + " ID")).toList();
        rejectDuplicates(ids, label + " ID");
    }

    private static void rejectDuplicates(List<String> values, String label) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("Duplicate " + label);
        }
    }

    private static <T> Set<String> ids(List<T> values, Function<T, String> extractor) {
        Set<String> result = new LinkedHashSet<>();
        for (T value : values) {
            result.add(extractor.apply(value));
        }
        return result;
    }

    private static void requireReferences(Iterable<String> references, Set<String> validIds, String label) {
        for (String reference : references) {
            if (!validIds.contains(reference)) {
                throw new IllegalArgumentException("Unknown " + label + " ID: " + reference);
            }
        }
    }

    private static void requirePolicy(ScoringPolicyCode actual,
                                      Set<ScoringPolicyCode> allowed,
                                      CanonicalQuestionType type) {
        if (!allowed.contains(actual)) {
            throw new IllegalArgumentException("Scoring policy " + actual + " is not valid for " + type);
        }
    }

    private static void requireSize(List<?> values, int expected, String label) {
        if (values.size() != expected) {
            throw new IllegalArgumentException(label + " must contain exactly " + expected + " value(s)");
        }
    }

    private static void requireNotEmpty(Iterable<?> values, String label) {
        if (!values.iterator().hasNext()) {
            throw new IllegalArgumentException("Missing " + label);
        }
    }

    private static void requireRange(Integer value, int minimum, int maximum, String label) {
        if (value == null || value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + label);
        }
        return value;
    }

    private static String required(String value, String label) {
        if (blank(value)) {
            throw new IllegalArgumentException("Missing " + label);
        }
        return value.trim();
    }

    static String normalizeValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
