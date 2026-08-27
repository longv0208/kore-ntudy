package com.ksh.features.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.WritingTaskType;
import com.ksh.features.practice.assessment.WritingBlankContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PracticeAttemptAnswerCodecTest {

    private final PracticeAttemptAnswerCodec codec =
            new PracticeAttemptAnswerCodec(new ObjectMapper());

    @Test
    void canonicalWriterRetainsEmptyTextSoReloadAndSubmitRemainReadable() {
        PracticeAttemptAnswerCodec.DecodedAnswers answers =
                new PracticeAttemptAnswerCodec.DecodedAnswers(
                        Map.of("4", ""),
                        Map.of(),
                        false,
                        false);

        String encoded = codec.write(answers);

        assertThat(encoded)
                .contains("\"responseMode\":\"TEXT\"")
                .contains("\"text\":\"\"");
        assertThat(codec.read(encoded, Map.of()).textAnswers())
                .containsEntry("4", "");
    }

    @Test
    void readerRecoversPreviouslyOmittedEmptyTextWithoutAcceptingExtraShape() {
        String omittedEmptyText = """
                {
                  "schemaVersion":"practice-attempt-answers.v2",
                  "responses":{"4":{"responseMode":"TEXT"}}
                }
                """;

        PracticeAttemptAnswerCodec.DecodedAnswers decoded =
                codec.read(omittedEmptyText, Map.of());

        assertThat(decoded.textAnswers()).containsEntry("4", "");
        assertThat(codec.write(decoded)).contains("\"text\":\"\"");
    }

    @Test
    void readerTreatsNullResponsesAsAnEmptyUnansweredSubmission() {
        String historical = """
                {"schemaVersion":"practice-attempt-answers.v2","responses":null}
                """;

        PracticeAttemptAnswerCodec.DecodedAnswers decoded =
                codec.read(historical, Map.of());

        assertThat(decoded.textAnswers()).isEmpty();
        assertThat(decoded.writingBlankAnswers()).isEmpty();
        assertThat(codec.write(decoded)).doesNotContain("\"responses\":null");
    }

    @Test
    void structuredWriterAndReaderRetainEveryIntentionallyEmptyBlank() {
        WritingBlankContract.QuestionResponse authority =
                structuredAuthority();
        WritingBlankContract.LearnerResponse response =
                structuredResponse("", "둘째 답");
        PracticeAttemptAnswerCodec.DecodedAnswers answers =
                new PracticeAttemptAnswerCodec.DecodedAnswers(
                        Map.of(), Map.of("14352", response), false, false);

        String encoded = codec.write(answers);

        assertThat(encoded)
                .contains("\"blankId\":\"q52-b1\",\"text\":\"\"")
                .contains("\"blankId\":\"q52-b2\",\"text\":\"둘째 답\"");
        assertThat(codec.read(encoded, Map.of(14352L, authority))
                .writingBlankAnswers().get("14352").answers())
                .extracting(WritingBlankContract.LearnerBlankAnswer::text)
                .containsExactly("", "둘째 답");
    }

    @Test
    void readerRecoversHistoricalStructuredBlankWhoseEmptyTextWasOmitted() {
        String historical = """
                {
                  "schemaVersion":"practice-attempt-answers.v2",
                  "responses":{"14352":{
                    "responseMode":"STRUCTURED_BLANKS",
                    "writingBlanks":{
                      "contractVersion":"writing-blank-response.v1",
                      "taskType":"Q52",
                      "responseMode":"STRUCTURED_BLANKS",
                      "answers":[
                        {"blankId":"q52-b1"},
                        {"blankId":"q52-b2","text":"둘째 답"}
                      ]
                    }
                  }}
                }
                """;

        PracticeAttemptAnswerCodec.DecodedAnswers decoded = codec.read(
                historical, Map.of(14352L, structuredAuthority()));

        assertThat(decoded.writingBlankAnswers().get("14352").answers())
                .extracting(WritingBlankContract.LearnerBlankAnswer::text)
                .containsExactly("", "둘째 답");
        assertThat(codec.write(decoded))
                .contains("\"blankId\":\"q52-b1\",\"text\":\"\"");
    }

    private static WritingBlankContract.QuestionResponse
            structuredAuthority() {
        return new WritingBlankContract.QuestionResponse(
                WritingBlankContract.RESPONSE_SCHEMA_VERSION,
                WritingBlankContract.RESPONSE_MODE,
                WritingTaskType.Q52,
                List.of(
                        new WritingBlankContract.BlankDefinition(
                                "q52-b1", 1, "첫째"),
                        new WritingBlankContract.BlankDefinition(
                                "q52-b2", 2, "둘째")));
    }

    private static WritingBlankContract.LearnerResponse structuredResponse(
            String first,
            String second) {
        return new WritingBlankContract.LearnerResponse(
                WritingBlankContract.LEARNER_SCHEMA_VERSION,
                WritingTaskType.Q52,
                WritingBlankContract.RESPONSE_MODE,
                List.of(
                        new WritingBlankContract.LearnerBlankAnswer(
                                "q52-b1", first),
                        new WritingBlankContract.LearnerBlankAnswer(
                                "q52-b2", second)));
    }
}
