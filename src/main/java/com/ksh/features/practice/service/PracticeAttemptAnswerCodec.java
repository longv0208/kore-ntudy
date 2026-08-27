package com.ksh.features.practice.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.assessment.WritingBlankContract;
import com.ksh.features.practice.assessment.WritingBlankContractVerifier;
import com.ksh.features.practice.web.PracticeFormFields;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dual reader for immutable historical flat answers and the current typed
 * attempt-answer document.
 */
@Component
public class PracticeAttemptAnswerCodec {

    public static final String DOCUMENT_SCHEMA_VERSION =
            "practice-attempt-answers.v2";
    public static final String TEXT_RESPONSE_MODE = "TEXT";

    private final ObjectMapper objectMapper;

    public PracticeAttemptAnswerCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecodedAnswers read(
            String json,
            Map<Long, WritingBlankContract.QuestionResponse>
                    writingAuthorities) {
        Map<Long, WritingBlankContract.QuestionResponse> authorities =
                writingAuthorities == null
                        ? Map.of()
                        : Map.copyOf(writingAuthorities);
        if (json == null || json.isBlank()) {
            return new DecodedAnswers(
                    Map.of(), Map.of(), false, false);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalid(
                        "Practice attempt answers must be a JSON object");
            }
            if (root.has("schemaVersion")) {
                return readCurrent(root, authorities);
            }
            return readLegacy(root);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Invalid Practice attempt answers JSON", exception);
        }
    }

    public String write(DecodedAnswers answers) {
        try {
            Map<String, AnswerEntry> responses = new LinkedHashMap<>();
            answers.textAnswers().forEach((key, value) ->
                    responses.put(key, AnswerEntry.text(value)));
            answers.writingBlankAnswers().forEach((key, value) ->
                    responses.put(key, AnswerEntry.writing(value)));
            AttemptAnswerDocument document = new AttemptAnswerDocument(
                    DOCUMENT_SCHEMA_VERSION, responses);
            return objectMapper.writeValueAsString(document);
        } catch (Exception exception) {
            throw invalid(
                    "Could not serialize Practice attempt answers",
                    exception);
        }
    }

    public DecodedAnswers mergeForm(
            DecodedAnswers existing,
            Map<String, String> form,
            Set<Long> allowedQuestionIds,
            Map<Long, WritingBlankContract.QuestionResponse>
                    writingAuthorities) {
        Map<String, String> text = new LinkedHashMap<>(
                existing.textAnswers());
        Map<String, WritingBlankContract.LearnerResponse> writing =
                new LinkedHashMap<>(existing.writingBlankAnswers());
        Set<String> allowed = new LinkedHashSet<>();
        for (Long questionId : allowedQuestionIds) {
            allowed.add(String.valueOf(questionId));
        }
        text.keySet().removeIf(key -> !allowed.contains(key));
        writing.keySet().removeIf(key -> !allowed.contains(key));

        for (Long questionId : allowedQuestionIds) {
            WritingBlankContract.QuestionResponse authority =
                    writingAuthorities.get(questionId);
            if (authority == null) {
                String formKey =
                        PracticeFormFields.answerKey(questionId);
                if (form.containsKey(formKey)) {
                    text.put(
                            String.valueOf(questionId),
                            cleanText(form.get(formKey)));
                    writing.remove(String.valueOf(questionId));
                }
                continue;
            }
            WritingBlankContractVerifier.verifyQuestion(authority);
            WritingBlankContract.LearnerResponse previous =
                    writing.get(String.valueOf(questionId));
            Map<String, String> values = previous == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(
                            WritingBlankContractVerifier.orderedAnswers(
                                    authority, previous));
            boolean supplied = false;
            for (WritingBlankContract.BlankDefinition blank
                    : authority.blanks()) {
                String formKey = PracticeFormFields
                        .writingBlankAnswerKey(
                                questionId, blank.blankId());
                if (form.containsKey(formKey)) {
                    values.put(
                            blank.blankId(),
                            cleanText(form.get(formKey)));
                    supplied = true;
                }
            }
            if (!supplied && previous != null) {
                continue;
            }
            List<WritingBlankContract.LearnerBlankAnswer> blankAnswers =
                    authority.blanks().stream()
                            .map(blank ->
                                    new WritingBlankContract
                                            .LearnerBlankAnswer(
                                            blank.blankId(),
                                            values.getOrDefault(
                                                    blank.blankId(), "")))
                            .toList();
            WritingBlankContract.LearnerResponse response =
                    new WritingBlankContract.LearnerResponse(
                            WritingBlankContract.LEARNER_SCHEMA_VERSION,
                            authority.taskType(),
                            WritingBlankContract.RESPONSE_MODE,
                            blankAnswers);
            WritingBlankContractVerifier.verifyLearnerResponse(
                    authority, response);
            writing.put(String.valueOf(questionId), response);
            text.remove(String.valueOf(questionId));
        }
        return new DecodedAnswers(
                Map.copyOf(text),
                Map.copyOf(writing),
                false,
                existing.legacyEssayShape());
    }

    public Map<String, String> compatibilityTextAnswers(
            DecodedAnswers decoded) {
        Map<String, String> result =
                new LinkedHashMap<>(decoded.textAnswers());
        decoded.writingBlankAnswers().forEach((questionId, response) -> {
            try {
                result.put(
                        questionId,
                        objectMapper.writeValueAsString(response));
            } catch (Exception exception) {
                throw invalid(
                        "Could not serialize structured Writing answer",
                        exception);
            }
        });
        return Map.copyOf(result);
    }

    private DecodedAnswers readCurrent(
            JsonNode root,
            Map<Long, WritingBlankContract.QuestionResponse> authorities)
            throws Exception {
        Set<String> allowedRoot = Set.of(
                "schemaVersion", "responses");
        rejectUnknown(root, allowedRoot, "attempt answers");
        if (!DOCUMENT_SCHEMA_VERSION.equals(
                root.path("schemaVersion").asText())) {
            throw invalid(
                    "Unsupported Practice attempt answer schema");
        }
        JsonNode responses = root.get("responses");
        if (responses == null || responses.isNull()) {
            return new DecodedAnswers(Map.of(), Map.of(), false, false);
        }
        if (!responses.isObject()) {
            throw invalid(
                    "Practice attempt responses must be an object");
        }
        Map<String, String> text = new LinkedHashMap<>();
        Map<String, WritingBlankContract.LearnerResponse> writing =
                new LinkedHashMap<>();
        var fields = responses.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Long questionId = parseQuestionId(field.getKey());
            JsonNode node = field.getValue();
            if (!node.isObject()) {
                throw invalid(
                        "Practice attempt response must be an object");
            }
            rejectUnknown(
                    node,
                    Set.of(
                            "responseMode",
                            "text",
                            "writingBlanks"),
                    "attempt response");
            String mode = node.path("responseMode").asText("");
            if (TEXT_RESPONSE_MODE.equals(mode)) {
                JsonNode textNode = node.get("text");
                if ((textNode != null && !textNode.isTextual())
                        || !node.path("writingBlanks").isMissingNode()) {
                    throw invalid("Invalid text response shape");
                }
                // Versions that used NON_EMPTY for AnswerEntry accidentally
                // omitted an intentionally empty text value.  Treat that one
                // exact historical shape as an empty response so an autosave
                // cannot brick reload or submission; the writer below always
                // emits the canonical `text: ""` field from now on.
                text.put(
                        field.getKey(),
                        textNode == null ? "" : textNode.asText());
                continue;
            }
            if (!WritingBlankContract.RESPONSE_MODE.equals(mode)
                    || node.path("writingBlanks").isMissingNode()) {
                throw invalid(
                        "Unsupported Practice attempt response mode");
            }
            WritingBlankContract.QuestionResponse authority =
                    authorities.get(questionId);
            if (authority == null) {
                throw invalid(
                        "Structured Writing answer has no immutable question authority");
            }
            WritingBlankContract.LearnerResponse response =
                    objectMapper.treeToValue(
                            node.get("writingBlanks"),
                            WritingBlankContract.LearnerResponse.class);
            response = recoverOmittedEmptyBlankTexts(
                    node.get("writingBlanks"), response);
            WritingBlankContractVerifier.verifyLearnerResponse(
                    authority, response);
            writing.put(field.getKey(), response);
        }
        return new DecodedAnswers(
                Map.copyOf(text),
                Map.copyOf(writing),
                false,
                false);
    }

    private DecodedAnswers readLegacy(JsonNode root) {
        Map<String, String> text = new LinkedHashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            parseQuestionId(field.getKey());
            if (!field.getValue().isTextual()
                    && !field.getValue().isNull()) {
                throw invalid(
                        "Historical Practice answers must be text values");
            }
            text.put(
                    field.getKey(),
                    field.getValue().isNull()
                            ? ""
                            : field.getValue().asText());
        }
        return new DecodedAnswers(
                Map.copyOf(text), Map.of(), true, true);
    }

    private WritingBlankContract.LearnerResponse
            recoverOmittedEmptyBlankTexts(
                    JsonNode source,
                    WritingBlankContract.LearnerResponse response) {
        if (source == null || response == null
                || response.answers() == null) {
            return response;
        }
        JsonNode answerNodes = source.get("answers");
        if (answerNodes == null || !answerNodes.isArray()
                || answerNodes.size() != response.answers().size()) {
            return response;
        }
        List<WritingBlankContract.LearnerBlankAnswer> recovered =
                new java.util.ArrayList<>(response.answers().size());
        boolean changed = false;
        for (int index = 0; index < response.answers().size(); index++) {
            JsonNode answerNode = answerNodes.get(index);
            WritingBlankContract.LearnerBlankAnswer answer =
                    response.answers().get(index);
            if (answerNode == null || !answerNode.isObject()) {
                recovered.add(answer);
                continue;
            }
            rejectUnknown(
                    answerNode,
                    Set.of("blankId", "text"),
                    "Writing learner blank answer");
            JsonNode textNode = answerNode.get("text");
            if (textNode != null && !textNode.isTextual()) {
                throw invalid(
                        "Writing learner blank answer text must be textual");
            }
            if (textNode == null) {
                recovered.add(new WritingBlankContract.LearnerBlankAnswer(
                        answer.blankId(), ""));
                changed = true;
            } else {
                recovered.add(answer);
            }
        }
        if (!changed) {
            return response;
        }
        return new WritingBlankContract.LearnerResponse(
                response.contractVersion(),
                response.taskType(),
                response.responseMode(),
                recovered);
    }

    private static String cleanText(String value) {
        return Normalizer.normalize(
                value == null ? "" : value,
                Normalizer.Form.NFC);
    }

    private static Long parseQuestionId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw invalid(
                    "Practice answer key is not a positive question ID");
        }
    }

    private static void rejectUnknown(
            JsonNode node, Set<String> allowed, String label) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw invalid(
                        "Unsupported " + label + " field: " + field);
            }
        });
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(
            String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record AttemptAnswerDocument(
            String schemaVersion,
            Map<String, AnswerEntry> responses
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnswerEntry(
            String responseMode,
            String text,
            WritingBlankContract.LearnerResponse writingBlanks
    ) {
        static AnswerEntry text(String value) {
            return new AnswerEntry(
                    TEXT_RESPONSE_MODE, value, null);
        }

        static AnswerEntry writing(
                WritingBlankContract.LearnerResponse value) {
            return new AnswerEntry(
                    WritingBlankContract.RESPONSE_MODE,
                    null,
                    value);
        }
    }

    public record DecodedAnswers(
            Map<String, String> textAnswers,
            Map<String, WritingBlankContract.LearnerResponse>
                    writingBlankAnswers,
            boolean historicalDocument,
            boolean legacyEssayShape
    ) {
        public DecodedAnswers {
            textAnswers = textAnswers == null
                    ? Map.of()
                    : Map.copyOf(textAnswers);
            writingBlankAnswers = writingBlankAnswers == null
                    ? Map.of()
                    : Map.copyOf(writingBlankAnswers);
        }
    }
}
