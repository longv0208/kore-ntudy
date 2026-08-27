package com.ksh.features.practice.assessment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AnswerSpec(
        String schemaVersion,
        CanonicalQuestionType questionType,
        List<String> correctOptionIds,
        String correctValue,
        List<BlankAnswer> blanks,
        ScoringPolicyCode scoringPolicyCode,
        WritingBlankContract.AnswerAuthority writingBlankAuthority,
        EvaluationMode evaluationMode
) {
    public static final String SCHEMA_VERSION_V1 = "answer-spec-v1";
    public static final String SCHEMA_VERSION_V2 = "answer-spec-v2";
    public static final String SCHEMA_VERSION = SCHEMA_VERSION_V1;

    public AnswerSpec {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        correctOptionIds = correctOptionIds == null ? List.of() : List.copyOf(correctOptionIds);
        blanks = blanks == null ? List.of() : List.copyOf(blanks);
    }

    public AnswerSpec(String schemaVersion,
                      CanonicalQuestionType questionType,
                      List<String> correctOptionIds,
                      String correctValue,
                      List<BlankAnswer> blanks,
                      ScoringPolicyCode scoringPolicyCode) {
        this(schemaVersion, questionType, correctOptionIds, correctValue,
                blanks, scoringPolicyCode, null, null);
    }

    public AnswerSpec(String schemaVersion,
                      CanonicalQuestionType questionType,
                      List<String> correctOptionIds,
                      String correctValue,
                      List<BlankAnswer> blanks,
                      ScoringPolicyCode scoringPolicyCode,
                      WritingBlankContract.AnswerAuthority writingBlankAuthority) {
        this(schemaVersion, questionType, correctOptionIds, correctValue,
                blanks, scoringPolicyCode, writingBlankAuthority, null);
    }

    public EvaluationMode effectiveEvaluationMode() {
        return evaluationMode == null
                ? EvaluationMode.AUTOMATED_SCORE_ALLOWED
                : evaluationMode;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record BlankAnswer(String blankId, List<String> acceptedValues) {
        public BlankAnswer {
            acceptedValues = acceptedValues == null ? List.of() : List.copyOf(acceptedValues);
        }
    }

    public enum EvaluationMode {
        AUTOMATED_SCORE_ALLOWED,
        MANUAL_OR_EXPERIMENTAL_UNSCORED
    }
}
