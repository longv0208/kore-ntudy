package com.ksh.features.practice.assessment;

import com.ksh.entities.WritingTaskType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict referential verifier shared by authoring, learner delivery and
 * evaluation boundaries for Writing Q51/Q52.
 */
public final class WritingBlankContractVerifier {

    private WritingBlankContractVerifier() {
    }

    public static void verifyQuestion(
            WritingBlankContract.QuestionResponse response) {
        require(response, "Writing blank response contract");
        requireEquals(
                WritingBlankContract.RESPONSE_SCHEMA_VERSION,
                response.responseSchemaVersion(),
                "Writing blank response schema");
        requireEquals(
                WritingBlankContract.RESPONSE_MODE,
                response.responseMode(),
                "Writing response mode");
        requireStructuredTask(response.taskType());
        verifyDefinitions(response.taskType(), response.blanks());
    }

    public static void verifyAuthority(
            WritingBlankContract.QuestionResponse response,
            WritingBlankContract.AnswerAuthority authority) {
        verifyQuestion(response);
        require(authority, "Writing blank answer authority");
        requireEquals(
                WritingBlankContract.AUTHORITY_SCHEMA_VERSION,
                authority.contractVersion(),
                "Writing blank authority schema");
        requireEquals(
                response.taskType(),
                authority.taskType(),
                "Writing blank authority task");
        requireEquals(
                WritingBlankContract.NORMALIZATION,
                authority.normalization(),
                "Writing blank normalization");
        requireEquals(
                WritingBlankContract.WHITESPACE_POLICY,
                authority.whitespacePolicy(),
                "Writing blank whitespace policy");
        requireSameOrderedBlankIdentity(
                response.blanks(),
                authority.blanks().stream()
                        .map(blank -> new WritingBlankContract.BlankDefinition(
                                blank.blankId(), blank.ordinal(), null))
                        .toList(),
                "Writing blank answer authority");

        for (WritingBlankContract.BlankAuthority blank : authority.blanks()) {
            if (blank.acceptedAnswers().isEmpty()) {
                throw invalid(
                        "Writing blank acceptedAnswers must not be empty: "
                                + blank.blankId());
            }
            Set<String> canonical = new LinkedHashSet<>();
            for (WritingBlankContract.AcceptedAnswer accepted
                    : blank.acceptedAnswers()) {
                require(accepted, "Writing accepted answer");
                String displayText = requireText(
                        accepted.text(), "Writing accepted answer text");
                String canonicalText = canonicalAnswer(displayText);
                if (!canonical.add(canonicalText)) {
                    throw invalid(
                            "Duplicate canonical Writing accepted answer: "
                                    + blank.blankId());
                }
                require(accepted.equivalence(),
                        "Writing accepted answer equivalence");
                if (accepted.equivalence()
                        == WritingBlankContract.Equivalence
                        .SEMANTIC_EQUIVALENT
                        && (blank(accepted.reason())
                        || accepted.evidenceIds().isEmpty())) {
                    throw invalid(
                            "Semantic-equivalent Writing answer requires "
                                    + "reason and evidence IDs");
                }
                rejectBlankOrDuplicateIds(
                        accepted.evidenceIds(),
                        "Writing accepted-answer evidence ID");
            }
        }
    }

    public static void verifyLearnerResponse(
            WritingBlankContract.QuestionResponse question,
            WritingBlankContract.LearnerResponse response) {
        verifyQuestion(question);
        require(response, "Writing learner response");
        requireEquals(
                WritingBlankContract.LEARNER_SCHEMA_VERSION,
                response.contractVersion(),
                "Writing learner response schema");
        requireEquals(
                WritingBlankContract.RESPONSE_MODE,
                response.responseMode(),
                "Writing learner response mode");
        requireEquals(
                question.taskType(),
                response.taskType(),
                "Writing learner response task");
        if (response.answers().size() != question.blanks().size()) {
            throw invalid(
                    "Writing learner response must contain every blank once");
        }
        List<WritingBlankContract.BlankDefinition> actual =
                new ArrayList<>();
        for (int index = 0; index < response.answers().size(); index++) {
            WritingBlankContract.LearnerBlankAnswer answer =
                    require(response.answers().get(index),
                            "Writing learner blank answer");
            actual.add(new WritingBlankContract.BlankDefinition(
                    answer.blankId(), index + 1, null));
            if (answer.text() == null) {
                throw invalid(
                        "Writing learner blank answer text must be present");
            }
            requireNfc(answer.text(), "Writing learner blank answer");
        }
        requireSameOrderedBlankIdentity(
                question.blanks(), actual, "Writing learner response");
    }

    public static void verifyEvaluation(
            WritingBlankContract.QuestionResponse question,
            WritingBlankContract.Evaluation evaluation) {
        verifyQuestion(question);
        require(evaluation, "Writing blank evaluation");
        requireEquals(
                WritingBlankContract.EVALUATION_SCHEMA_VERSION,
                evaluation.contractVersion(),
                "Writing blank evaluation schema");
        requireEquals(
                question.taskType(),
                evaluation.taskType(),
                "Writing blank evaluation task");
        requireSameOrderedBlankIdentity(
                question.blanks(),
                evaluation.blanks().stream()
                        .map(blank -> new WritingBlankContract.BlankDefinition(
                                blank.blankId(), blank.ordinal(), null))
                        .toList(),
                "Writing blank evaluation");
        for (WritingBlankContract.BlankEvaluation blank
                : evaluation.blanks()) {
            require(blank.verdict(), "Writing blank verdict");
            rejectBlankOrDuplicateIds(
                    blank.evidenceIds(), "Writing blank evidence ID");
            rejectBlankOrDuplicateIds(
                    blank.findingIds(), "Writing blank finding ID");
            if ((blank.verdict()
                    == WritingBlankContract.Verdict.CORRECT
                    || blank.verdict()
                    == WritingBlankContract.Verdict.EMPTY)
                    && !blank(blank.correction())) {
                throw invalid(
                        "Correct/empty Writing blank cannot carry correction");
            }
            if ((blank.verdict()
                    == WritingBlankContract.Verdict.PARTIAL
                    || blank.verdict()
                    == WritingBlankContract.Verdict.INCORRECT)
                    && (blank(blank.correction())
                    || blank.evidenceIds().isEmpty()
                    || blank.findingIds().isEmpty())) {
                throw invalid(
                        "Deducted Writing blank requires correction, evidence "
                                + "and finding identity");
            }
        }
    }

    public static String canonicalAnswer(String value) {
        String nfc = Normalizer.normalize(
                value == null ? "" : value,
                Normalizer.Form.NFC);
        return nfc.strip().replaceAll("\\s+", " ");
    }

    public static Map<String, String> orderedAnswers(
            WritingBlankContract.QuestionResponse question,
            WritingBlankContract.LearnerResponse response) {
        verifyLearnerResponse(question, response);
        Map<String, String> result = new LinkedHashMap<>();
        for (WritingBlankContract.LearnerBlankAnswer answer
                : response.answers()) {
            result.put(answer.blankId(), answer.text());
        }
        return Map.copyOf(result);
    }

    private static void verifyDefinitions(
            WritingTaskType taskType,
            List<WritingBlankContract.BlankDefinition> blanks) {
        if (blanks == null || blanks.size() != 2) {
            throw invalid(
                    "Writing " + taskType + " requires exactly two blanks");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < blanks.size(); index++) {
            WritingBlankContract.BlankDefinition blank =
                    require(blanks.get(index), "Writing blank definition");
            String expectedId = taskType.name().toLowerCase(Locale.ROOT)
                    + "-b" + (index + 1);
            if (blank.blankId() == null
                    || (!expectedId.equalsIgnoreCase(blank.blankId())
                    && !blank.blankId().toLowerCase(Locale.ROOT).endsWith(expectedId))) {
                requireEquals(expectedId, blank.blankId(),
                        "Writing blank ID");
            }
            requireEquals(index + 1, blank.ordinal(),
                    "Writing blank ordinal");
            requireText(blank.context(), "Writing blank context");
            if (!ids.add(blank.blankId())) {
                throw invalid("Duplicate Writing blank ID");
            }
        }
    }

    private static void requireSameOrderedBlankIdentity(
            List<WritingBlankContract.BlankDefinition> expected,
            List<WritingBlankContract.BlankDefinition> actual,
            String label) {
        if (actual == null || actual.size() != expected.size()) {
            throw invalid(label + " blank count mismatch");
        }
        for (int index = 0; index < expected.size(); index++) {
            WritingBlankContract.BlankDefinition left =
                    expected.get(index);
            WritingBlankContract.BlankDefinition right =
                    actual.get(index);
            if (!left.blankId().equals(right.blankId())
                    || !left.ordinal().equals(right.ordinal())) {
                throw invalid(
                        label + " contains unknown, swapped or missing blank");
            }
        }
    }

    private static void requireStructuredTask(WritingTaskType taskType) {
        if (taskType != WritingTaskType.Q51
                && taskType != WritingTaskType.Q52) {
            throw invalid(
                    "Structured Writing blanks are only valid for Q51/Q52");
        }
    }

    private static void rejectBlankOrDuplicateIds(
            List<String> ids, String label) {
        Set<String> unique = new LinkedHashSet<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            String value = requireText(id, label);
            if (!unique.add(value)) {
                throw invalid("Duplicate " + label);
            }
        }
    }

    private static void requireNfc(String value, String label) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw invalid(label + " must be Unicode NFC");
        }
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw invalid("Missing " + label);
        }
        return value;
    }

    private static String requireText(String value, String label) {
        if (blank(value)) {
            throw invalid("Missing " + label);
        }
        requireNfc(value, label);
        return value;
    }

    private static void requireEquals(
            Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw invalid(label + " mismatch");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
