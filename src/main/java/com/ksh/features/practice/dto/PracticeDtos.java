package com.ksh.features.practice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ksh.features.practice.assessment.WritingBlankContract;

public final class PracticeDtos {
    private PracticeDtos() {
    }

    public static String getSkillLabel(String skill) {
        if (skill == null) return "Chưa xác định";
        return switch (skill.toUpperCase()) {
            case "READING" -> "Đọc";
            case "LISTENING" -> "Nghe";
            case "WRITING" -> "Viết";
            case "SPEAKING" -> "Nói";
            case "MIXED" -> "Tổng hợp";
            default -> skill;
        };
    }

    public record PracticeSetRow(Long id, String title, String description,
                                 String skill, String skillLabel,
                                 String metadataJson,
                                 String creationMethod) {
    }

    public record PracticeCatalogQuery(String search, String skill,
                                       String writingTask, Long classId, int batch) {
    }

    public record PracticeCatalogSkill(String code, String label) {
    }

    public record PracticeCatalogClassOption(Long id, String name) {
    }

    public record PracticeGlobalResume(
            Long attemptId,
            Long setId,
            Long testId,
            Long sectionId,
            String setTitle,
            String testTitle,
            String primarySkill,
            String skillLabel,
            LocalDateTime activityAt
    ) {
    }

    public record PracticeCatalogCard(
            Long id,
            String title,
            String description,
            String primarySkill,
            List<PracticeCatalogSkill> skills,
            int testCount,
            int completedTests,
            String visibilityLabel,
            String state,
            String stateLabel,
            Long resumeAttemptId
    ) {
        public boolean hasSkill(String code) {
            if (code == null) return false;
            if (skills != null && skills.stream()
                    .anyMatch(skill -> code.equalsIgnoreCase(skill.code()))) {
                return true;
            }
            return (skills == null || skills.isEmpty())
                    && code.equalsIgnoreCase(primarySkill);
        }

        public boolean multiSkill() {
            return skills != null && skills.size() > 1;
        }

        public String coverSkill() {
            return multiSkill() ? "MIXED" : primarySkill;
        }

        public String coverLabel() {
            if (skills == null || skills.isEmpty()) return "LUYỆN TẬP";
            if (skills.size() == 2) return "2 KỸ NĂNG";
            if (skills.size() > 2) return "TỔNG HỢP";
            return switch (skills.get(0).code()) {
                case "LISTENING" -> "NGHE";
                case "READING" -> "ĐỌC";
                case "WRITING" -> "VIẾT";
                case "SPEAKING" -> "NÓI";
                default -> "LUYỆN TẬP";
            };
        }

        public String skillSummary() {
            if (skills == null || skills.isEmpty()) return "Chưa xác định";
            return String.join(", ", skills.stream()
                    .map(PracticeCatalogSkill::label)
                    .toList());
        }

        public String skillCodes() {
            if (skills == null || skills.isEmpty()) {
                return primarySkill == null ? "" : primarySkill;
            }
            return String.join(",", skills.stream()
                    .map(PracticeCatalogSkill::code)
                    .toList());
        }

        public int progressPercent() {
            if (testCount <= 0) return 0;
            return Math.min(100, Math.max(0,
                    (int) Math.round(completedTests * 100.0 / testCount)));
        }
    }

    public record PracticeCatalogBatch(
            List<PracticeCatalogCard> items,
            PracticeGlobalResume globalResume,
            List<PracticeCatalogClassOption> classes,
            String search,
            String skill,
            String writingTask,
            Long classId,
            int batch,
            int batchSize,
            long totalElements,
            boolean hasMore
    ) {
        public boolean hasPrevious() {
            return batch > 0;
        }

        public int previousBatch() {
            return Math.max(0, batch - 1);
        }

        public int nextBatch() {
            return batch + 1;
        }

        public long firstItemNumber() {
            if (items == null || items.isEmpty()) return 0;
            return (long) batch * batchSize + 1;
        }

        public long lastItemNumber() {
            if (items == null || items.isEmpty()) return 0;
            return Math.min(totalElements, firstItemNumber() + items.size() - 1);
        }
    }


    public record ExampleBox(
        String label,
        String content,
        List<String> choices,
        Integer answer
    ) {
    }

    public record PracticeQuestionOptionRow(String id, String text, String imageReference) {
    }

    public record PracticeQuestionBlankRow(String id, String prompt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PracticeQuestionRow(Long id, Integer questionNo,
                                      String questionType, String prompt,
                                      List<String> options,
                                      String answerKey,
                                      String explanation,
                                      String groupLabel,
                                      String imageReference,
                                      String audioReference,
                                      List<PracticeQuestionOptionRow> optionRows,
                                      List<PracticeQuestionBlankRow> blankRows,
                                      WritingBlankContract.QuestionResponse
                                              writingResponse,
                                      String languageTag) {
        public PracticeQuestionRow(Long id, Integer questionNo,
                                   String questionType, String prompt,
                                   List<String> options,
                                   String answerKey,
                                   String explanation,
                                   String groupLabel,
                                   String imageReference,
                                   String audioReference,
                                   List<PracticeQuestionOptionRow> optionRows,
                                   List<PracticeQuestionBlankRow> blankRows,
                                   WritingBlankContract.QuestionResponse
                                           writingResponse) {
            this(id, questionNo, questionType, prompt, options, answerKey,
                    explanation, groupLabel, imageReference, audioReference,
                    optionRows, blankRows, writingResponse, "ko");
        }

        public PracticeQuestionRow(Long id, Integer questionNo,
                                   String questionType, String prompt,
                                   List<String> options,
                                   String answerKey,
                                   String explanation,
                                   String groupLabel,
                                   String imageReference,
                                   String audioReference,
                                   List<PracticeQuestionOptionRow> optionRows,
                                   List<PracticeQuestionBlankRow> blankRows) {
            this(id, questionNo, questionType, prompt, options, answerKey,
                    explanation, groupLabel, imageReference, audioReference,
                    optionRows, blankRows, null, "ko");
        }

        public PracticeQuestionRow(Long id, Integer questionNo,
                                   String questionType, String prompt,
                                   List<String> options,
                                   String answerKey,
                                   String explanation,
                                   String groupLabel,
                                   String imageReference,
                                   String audioReference,
                                   List<PracticeQuestionOptionRow> optionRows) {
            this(id, questionNo, questionType, prompt, options, answerKey, explanation,
                    groupLabel, imageReference, audioReference, optionRows,
                    null, null, "ko");
        }

        public PracticeQuestionRow(Long id, Integer questionNo,
                                   String questionType, String prompt,
                                   List<String> options,
                                   String answerKey,
                                   String explanation,
                                   String groupLabel) {
            this(id, questionNo, questionType, prompt, options, answerKey, explanation,
                    groupLabel, null, null, null, null, null, "ko");
        }

        public PracticeQuestionRow {
            options = options == null ? List.of() : List.copyOf(options);
            optionRows = optionRows == null || optionRows.isEmpty()
                    ? fallbackOptionRows(options)
                    : List.copyOf(optionRows);
            blankRows = blankRows == null ? List.of() : List.copyOf(blankRows);
            languageTag = "ko".equals(languageTag) || "vi".equals(languageTag)
                    ? languageTag
                    : "ko";
        }

        private static List<PracticeQuestionOptionRow> fallbackOptionRows(List<String> options) {
            if (options == null || options.isEmpty()) {
                return List.of();
            }
            return java.util.stream.IntStream.range(0, options.size())
                    .mapToObj(index -> new PracticeQuestionOptionRow(
                            "opt_" + (index + 1), options.get(index), null))
                    .toList();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PracticeQuestionGroupRow(
        Long id,
        Long sectionId,
        String groupLabel,
        Integer questionFrom,
        Integer questionTo,
        String instruction,
        String stimulusType,
        String passageText,
        String transcriptText,
        String imageUrl,
        String stimulusProvenanceJson,
        String audioUrl,
        ExampleBox exampleBox,
        List<PracticeQuestionRow> questions,
        String stimulusLanguageTag,
        String instructionLanguageTag
    ) {
        public PracticeQuestionGroupRow {
            stimulusLanguageTag = "ko".equals(stimulusLanguageTag)
                    || "vi".equals(stimulusLanguageTag)
                    ? stimulusLanguageTag
                    : "ko";
            instructionLanguageTag = "ko".equals(instructionLanguageTag)
                    || "vi".equals(instructionLanguageTag)
                    ? instructionLanguageTag
                    : "vi";
        }

        public PracticeQuestionGroupRow(Long id,
                                        Long sectionId,
                                        String groupLabel,
                                        Integer questionFrom,
                                        Integer questionTo,
                                        String instruction,
                                        String stimulusType,
                                        String passageText,
                                        String transcriptText,
                                        String imageUrl,
                                        String stimulusProvenanceJson,
                                        String audioUrl,
                                        ExampleBox exampleBox,
                                        List<PracticeQuestionRow> questions,
                                        String stimulusLanguageTag) {
            this(id, sectionId, groupLabel, questionFrom, questionTo, instruction,
                    stimulusType, passageText, transcriptText, imageUrl,
                    stimulusProvenanceJson, audioUrl, exampleBox, questions,
                    stimulusLanguageTag, "vi");
        }

        public PracticeQuestionGroupRow(Long id,
                                        Long sectionId,
                                        String groupLabel,
                                        Integer questionFrom,
                                        Integer questionTo,
                                        String instruction,
                                        String stimulusType,
                                        String passageText,
                                        String transcriptText,
                                        String imageUrl,
                                        String stimulusProvenanceJson,
                                        String audioUrl,
                                        ExampleBox exampleBox,
                                        List<PracticeQuestionRow> questions) {
            this(id, sectionId, groupLabel, questionFrom, questionTo, instruction,
                    stimulusType, passageText, transcriptText, imageUrl,
                    stimulusProvenanceJson, audioUrl, exampleBox, questions, "ko", "vi");
        }

        public PracticeQuestionGroupRow(Long id,
                                        Long sectionId,
                                        String groupLabel,
                                        Integer questionFrom,
                                        Integer questionTo,
                                        String instruction,
                                        String audioUrl,
                                        ExampleBox exampleBox,
                                        List<PracticeQuestionRow> questions) {
            this(id, sectionId, groupLabel, questionFrom, questionTo, instruction,
                    null, null, null, null, null, audioUrl, exampleBox, questions, "ko", "vi");
        }
    }

    public record PracticeTestRow(Long id,
                                  Long setId,
                                  String title,
                                  String description,
                                  Integer displayOrder,
                                  Integer estimatedMinutes) {
    }

    public record PracticeSetTestCard(
            Long id,
            String title,
            String description,
            Integer displayOrder,
            Integer estimatedMinutes,
            List<PracticeCatalogSkill> skills,
            int completedSkillCount,
            int totalSkillCount,
            String state,
            String stateLabel,
            Long resumeAttemptId
    ) {
        public boolean hasSkill(String code) {
            return code != null && skills != null && skills.stream()
                    .anyMatch(skill -> code.equalsIgnoreCase(skill.code()));
        }

        public int progressPercent() {
            if (totalSkillCount <= 0) return 0;
            return Math.min(100, Math.max(0,
                    (int) Math.round(completedSkillCount * 100.0 / totalSkillCount)));
        }

        public String actionLabel() {
            return switch (state) {
                case "IN_PROGRESS" -> "Tiếp tục";
                case "COMPLETED" -> "Xem bài";
                case "PARTIAL" -> "Tiếp tục luyện";
                default -> "Bắt đầu";
            };
        }
    }

    public record PracticeAttemptCard(
            Long id,
            int attemptNumber,
            String scoreLabel,
            String status,
            String state,
            String statusLabel,
            LocalDateTime activityAt,
            boolean resultEligible,
            boolean initiallyVisible
    ) {
    }

    public record PracticeSkillAttemptCard(
            Long sectionId,
            String title,
            String skill,
            String skillLabel,
            Integer durationMinutes,
            BigDecimal totalPoints,
            Long inProgressAttemptId,
            List<PracticeAttemptCard> completedAttempts,
            String state,
            String stateLabel,
            String latestScoreLabel,
            String bestScoreLabel
    ) {
        public boolean hasInProgressAttempt() {
            return inProgressAttemptId != null;
        }

        public boolean hasCompletedAttempts() {
            return completedAttempts != null && !completedAttempts.isEmpty();
        }

        public int completedAttemptCount() {
            return completedAttempts == null ? 0 : completedAttempts.size();
        }

        public int hiddenAttemptCount() {
            if (completedAttempts == null) return 0;
            return (int) completedAttempts.stream()
                    .filter(attempt -> !attempt.initiallyVisible())
                    .count();
        }

        public Long latestCompletedAttemptId() {
            if (!hasCompletedAttempts()) return null;
            return completedAttempts.get(0).id();
        }

        public String actionLabel() {
            if (hasInProgressAttempt()) return "Tiếp tục";
            return hasCompletedAttempts() ? "Làm lại" : "Bắt đầu";
        }
    }

    public record PracticeSetView(PracticeSetRow set,
                                  List<PracticeQuestionGroupRow> groups,
                                  List<SectionView> sections,
                                  List<PracticeTestRow> tests) {
        // Convenience constructor for code that only supplies groups (backward-compat)
        public PracticeSetView(PracticeSetRow set, List<PracticeQuestionGroupRow> groups) {
            this(set, groups, List.of(), List.of());
        }

        public PracticeSetView(PracticeSetRow set, List<PracticeQuestionGroupRow> groups, List<SectionView> sections) {
            this(set, groups, sections, List.of());
        }

        public boolean writing() {
            return sections.stream().anyMatch(s -> "WRITING".equals(s.skill()));
        }

        public boolean listening() {
            return sections.stream().anyMatch(s -> "LISTENING".equals(s.skill()));
        }

        public boolean reading() {
            return sections.stream().anyMatch(s -> "READING".equals(s.skill()));
        }

        public boolean speaking() {
            return sections.stream().anyMatch(s -> "SPEAKING".equals(s.skill()));
        }

        public int totalQuestions() {
            return groups.stream()
                    .mapToInt(g -> g.questions().size())
                    .sum();
        }
    }

    /**
     * A single section visible during the exam player — its skill, duration,
     * title, and ordered groups.
     */
    public record SectionView(
            Long id,
            String title,
            String skill,
            int durationMinutes,
            List<PracticeQuestionGroupRow> groups
    ) {
        public int totalQuestions() {
            return groups.stream().mapToInt(g -> g.questions().size()).sum();
        }
    }

    public record SpeakingFeedbackView(
            BigDecimal percentage,
            String summary,
            String summaryVi,
            List<SpeakingRubricScoreView> rubricScores,
            List<SpeakingFindingView> strengths,
            List<SpeakingFindingView> needsImprovement,
            String sampleAnswer,
            String correctedVersion,
            String engine,
            String source,
            String evaluationStatus,
            boolean scoreAvailable,
            String levelLabel,
            String overallSummary,
            String taskAchievementSummary,
            List<String> majorStrengths,
            List<String> majorNeedsImprovement,
            List<SpeakingActionPlanView> actionPlan,
            List<SpeakingCriterionFeedbackView> criterionFeedback,
            List<SpeakingTranscriptAnnotationView> transcriptAnnotations,
            List<SpeakingTranscriptAnnotationView> annotations,
            String transcript,
            String normalizedTranscript,
            String actuallyHeardTranscript,
            String interpretedIntent,
            BigDecimal transcriptConfidence,
            String confidenceNotes,
            String listenerBurden,
            List<String> pronunciationAdvisory,
            List<String> fluencyObservations,
            String errorCategory,
            boolean retryable,
            Long audioMediaId,
            Long mediaVersion,
            String promptVersion,
            String rubricVersion,
            String schemaVersion,
            String model,
            String transcriptionModel,
            String evaluatorCapability,
            String evidenceMode,
            String evidenceContractVersion,
            String contractTrust,
            boolean profileAvailable,
            boolean holisticScoreAvailable
    ) {
        public SpeakingFeedbackView(
                BigDecimal percentage,
                String summary,
                String summaryVi,
                List<SpeakingRubricScoreView> rubricScores,
                List<SpeakingFindingView> strengths,
                List<SpeakingFindingView> needsImprovement,
                String sampleAnswer,
                String correctedVersion,
                String engine,
                String source
        ) {
            this(
                    percentage, summary, summaryVi, rubricScores, strengths, needsImprovement,
                    sampleAnswer, correctedVersion, engine, source,
                    null, percentage != null, null, summaryVi == null ? summary : summaryVi, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, null, List.of(), List.of(),
                    null, false, null, null, null, null, null, engine, null,
                    "LEGACY_UNKNOWN", "UNKNOWN", null, "LEGACY_UNVERIFIED", false, false);
        }

        public SpeakingFeedbackView {
            rubricScores = rubricScores == null ? List.of() : List.copyOf(rubricScores);
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            needsImprovement = needsImprovement == null ? List.of() : List.copyOf(needsImprovement);
            majorStrengths = majorStrengths == null ? List.of() : List.copyOf(majorStrengths);
            majorNeedsImprovement = majorNeedsImprovement == null ? List.of() : List.copyOf(majorNeedsImprovement);
            actionPlan = actionPlan == null ? List.of() : List.copyOf(actionPlan);
            criterionFeedback = criterionFeedback == null ? List.of() : List.copyOf(criterionFeedback);
            transcriptAnnotations = transcriptAnnotations == null ? List.of() : List.copyOf(transcriptAnnotations);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            pronunciationAdvisory = pronunciationAdvisory == null ? List.of() : List.copyOf(pronunciationAdvisory);
            fluencyObservations = fluencyObservations == null ? List.of() : List.copyOf(fluencyObservations);
            if (!holisticScoreAvailable) {
                percentage = null;
                scoreAvailable = false;
                levelLabel = null;
                listenerBurden = null;
                pronunciationAdvisory = List.of();
                fluencyObservations = List.of();
            }
        }
    }

    public record SpeakingRubricScoreView(
            String name,
            BigDecimal percentage,
            String feedback,
            String criterionId,
            BigDecimal score,
            BigDecimal maxScore,
            String availability
    ) {
        public SpeakingRubricScoreView {
            availability = speakingAvailability(availability, score);
            if ("SCORED".equals(availability) && (score == null || maxScore == null)) {
                availability = "UNAVAILABLE";
            }
            if (!"SCORED".equals(availability)) {
                percentage = null;
                score = null;
                maxScore = null;
            }
        }

        public SpeakingRubricScoreView(
                String name,
                BigDecimal percentage,
                String feedback,
                String criterionId,
                BigDecimal score,
                BigDecimal maxScore
        ) {
            this(name, percentage, feedback, criterionId, score, maxScore,
                    score == null ? "UNAVAILABLE" : "SCORED");
        }

        public SpeakingRubricScoreView(String name, BigDecimal percentage, String feedback) {
            this(name, percentage, feedback, null, null, null, "UNAVAILABLE");
        }
    }

    public record SpeakingCriterionFeedbackView(
            String criterionId,
            String name,
            BigDecimal score,
            BigDecimal maxScore,
            String levelLabel,
            String summary,
            List<String> strengths,
            List<String> needsImprovement,
            List<SpeakingSubcriterionFeedbackView> subcriteria
    ) {
        public SpeakingCriterionFeedbackView {
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            needsImprovement = needsImprovement == null ? List.of() : List.copyOf(needsImprovement);
            subcriteria = subcriteria == null ? List.of() : List.copyOf(subcriteria);
        }
    }

    public record SpeakingSubcriterionFeedbackView(
            String subcriterionId,
            String name,
            String levelLabel,
            String summary,
            List<String> strengths,
            List<String> needsImprovement
    ) {
        public SpeakingSubcriterionFeedbackView {
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            needsImprovement = needsImprovement == null ? List.of() : List.copyOf(needsImprovement);
        }
    }

    public record SpeakingActionPlanView(
            String criterionId,
            String subcriterionId,
            String findingId,
            String evidenceId,
            String titleVi,
            String instructionVi,
            String reasonVi,
            String priority
    ) {
        public SpeakingActionPlanView(
                String criterionId,
                String subcriterionId,
                String titleVi,
                String instructionVi,
                String reasonVi,
                String priority
        ) {
            this(
                    criterionId,
                    subcriterionId,
                    null,
                    null,
                    titleVi,
                    instructionVi,
                    reasonVi,
                    priority);
        }

        public SpeakingActionPlanView {
            if ((findingId == null) != (evidenceId == null)) {
                throw new IllegalArgumentException(
                        "Speaking action plan linkage is incomplete");
            }
        }

        public String criterionLabel() {
            return switch (criterionId == null ? "" : criterionId) {
                case "S_CONTENT_TASK_FULFILLMENT" -> "Nội dung và hoàn thành yêu cầu";
                case "S_GRAMMAR_SENTENCE_CONTROL" -> "Ngữ pháp và kiểm soát câu";
                case "S_VOCABULARY_EXPRESSIONS" -> "Từ vựng và biểu đạt";
                case "S_COHERENCE_ORGANIZATION" -> "Mạch lạc và tổ chức ý";
                default -> null;
            };
        }
    }

    public record SpeakingOverviewFindingView(
            Long questionId,
            String findingId,
            String evidenceId,
            String criterionId,
            String subcriterionId,
            String polarity,
            String exactText,
            Integer startOffset,
            Integer endOffset,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String normalization,
            String sourceHash,
            String explanationVi,
            String correctionKo
    ) {
        public SpeakingOverviewFindingView {
            if (questionId == null
                    || findingId == null || findingId.isBlank()
                    || evidenceId == null || evidenceId.isBlank()
                    || criterionId == null || criterionId.isBlank()
                    || subcriterionId == null || subcriterionId.isBlank()
                    || !Set.of(
                            "STRENGTH",
                            "NEEDS_IMPROVEMENT").contains(polarity)
                    || exactText == null || exactText.isBlank()
                    || startOffset == null || startOffset < 0
                    || endOffset == null || endOffset <= startOffset
                    || occurrenceIndex == null || occurrenceIndex < 1
                    || occurrenceCount == null
                    || occurrenceCount < occurrenceIndex
                    || normalization == null || normalization.isBlank()
                    || sourceHash == null || sourceHash.isBlank()
                    || explanationVi == null || explanationVi.isBlank()
                    || ("STRENGTH".equals(polarity)
                    && correctionKo != null
                    && !correctionKo.isBlank())
                    || ("NEEDS_IMPROVEMENT".equals(polarity)
                    && (correctionKo == null || correctionKo.isBlank()))) {
                throw new IllegalArgumentException(
                        "Speaking overview finding is incomplete");
            }
        }
    }

    public record SpeakingTranscriptAnnotationView(
            String findingId,
            String evidenceId,
            String criterionId,
            String subcriterionId,
            String evidenceScope,
            String evidence,
            String evidenceSource,
            Integer startOffset,
            Integer endOffset,
            Integer start,
            Integer end,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String normalization,
            String sourceHash,
            String operation,
            String annotationType,
            String kind,
            String category,
            String explanationVi,
            String suggestionKo,
            String correction,
            String severity
    ) {}

    public record SpeakingFindingView(
            String findingId,
            String evidenceId,
            String criterionId,
            String subcriterionId,
            String evidenceScope,
            String evidence,
            String evidenceSource,
            Integer startOffset,
            Integer endOffset,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String normalization,
            String sourceHash,
            String operation,
            String category,
            String explanationVi,
            String correction
    ) {
        public SpeakingFindingView(String criterionId, String explanationVi, String correction) {
            this(null, null, criterionId, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    explanationVi, correction);
        }
    }

    public record WritingFeedbackView(
            @JsonProperty("raw_score") BigDecimal rawScore,
            @JsonProperty("raw_score_max") BigDecimal rawScoreMax,
            BigDecimal score,
            String summary,
            @JsonProperty("summary_vi") String summaryVi,
            @JsonProperty("rubric_scores") List<WritingRubricScoreView> rubricScores,
            List<WritingFindingView> strengths,
            @JsonProperty("needs_improvement") List<WritingFindingView> needsImprovement,
            List<WritingAnnotationView> annotations,
            @JsonProperty("upgraded_answer") String upgradedAnswer,
            @JsonProperty("sentence_rewrites") List<WritingSentenceRewriteView> sentenceRewrites,
            @JsonProperty("sample_answer") String sampleAnswer,
            @JsonProperty("evaluation_status") String evaluationStatus,
            @JsonProperty("evaluation_source") String evaluationSource,
            @JsonProperty("evaluation_reason") String evaluationReason,
            @JsonProperty("evaluation_retryable") Boolean evaluationRetryable,
            @JsonProperty("score_available") Boolean scoreAvailable
    ) {
        public WritingFeedbackView {
            rubricScores = rubricScores == null ? List.of() : List.copyOf(rubricScores);
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            needsImprovement = needsImprovement == null ? List.of() : List.copyOf(needsImprovement);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            sentenceRewrites = sentenceRewrites == null ? List.of() : List.copyOf(sentenceRewrites);
        }

        public boolean scoreAvailableFlag() {
            return scoreAvailable == null || scoreAvailable;
        }
    }

    public record WritingRubricScoreView(
            String name,
            BigDecimal score,
            String feedback
    ) {}

    public record WritingFindingView(
            String category,
            String vietnameseLabel,
            String uiLabel,
            String criterionId,
            String evidenceScope,
            String evidence,
            String explanationVi,
            String correction,
            String severity,
            String errorType,
            String whyItIsGood,
            String topikTip,
            String subtype,
            String scoringCriterionId,
            String impact,
            Integer frequency,
            BigDecimal confidence,
            String observability
    ) {}

    public record WritingAnnotationView(
            String id,
            String findingId,
            String evidenceId,
            String kind,
            String criterionId,
            String category,
            Integer start,
            Integer end,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String sourceHash,
            String operation,
            String severity,
            String displayType,
            Integer index,
            String explanationVi,
            String correction,
            String evidence
    ) {}

    public record WritingSentenceRewriteView(
            List<String> findingIds,
            String evidenceId,
            String original,
            String upgraded,
            String reason
    ) {
        public WritingSentenceRewriteView {
            findingIds = findingIds == null
                    ? List.of()
                    : List.copyOf(findingIds);
        }
    }


    public record PracticeResultSummary(Long id, String title, String skill,
                                        BigDecimal score, BigDecimal totalPoints,
                                        LocalDateTime submittedAt,
                                        LocalDateTime activityAt,
                                        String status,
                                        String state,
                                        boolean resumable,
                                        boolean resultEligible,
                                        Long setId,
                                        Long testId,
                                        Long sectionId,
                                        Long publishedVersionId,
                                        Long setVersionId,
                                        Long testVersionId,
                                        Long sectionVersionId,
                                        ProgressAvailability identityAvailability,
                                        ProgressExclusionReason identityReason,
                                        ProgressNumericFact scoreFact) {
    }

    public record SpeakingMediaUploadResponse(
            Long mediaId,
            Long attemptId,
            Long questionId,
            String status,
            Boolean active,
            Long byteSize,
            Long durationMs,
            String mimeType,
            String playbackPath,
            Long lockVersion
    ) {
    }

    public record SpeakingMediaDeleteResponse(
            Long mediaId,
            Long attemptId,
            Long questionId,
            String status,
            Boolean active,
            Boolean pendingCleanup
    ) {
    }

    public record SpeakingMediaView(
            Long mediaId,
            Long questionId,
            String status,
            Long byteSize,
            Long durationMs,
            String mimeType,
            String playbackPath,
            Long lockVersion
    ) {
    }

    public record SpeakingMediaErrorResponse(
            String code,
            String message
    ) {
    }

    // =========================================================================
    // Canonical read-only progress contract
    // =========================================================================

    public enum ProgressAvailability {
        AVAILABLE,
        PARTIAL,
        UNAVAILABLE,
        NOT_SCORABLE,
        DEFERRED
    }

    public enum ProgressExclusionReason {
        NO_ACTIVITY,
        FILTER_NO_DATA,
        CHART_ENHANCEMENT_UNAVAILABLE,
        NO_ELIGIBLE_SCORE,
        INCOMPLETE_VERSION_LOCK,
        LEGACY_UNVERIFIED,
        MISSING_OR_INVALID_DURATION,
        DURATION_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY,
        SCORE_NOT_APPLICABLE_FOR_INCOMPLETE_ACTIVITY,
        UNSUPPORTED_SCORE_PROFILE,
        MALFORMED_OBJECTIVE_EVIDENCE,
        WRITING_SKILL_AGGREGATION_REQUIRES_TASK_COHORT,
        WRITING_TASK_IDENTITY_MISSING,
        WRITING_TASK_IDENTITY_MISMATCH,
        WRITING_SCORE_EVIDENCE_MISSING,
        WRITING_SCORE_EVIDENCE_MALFORMED,
        WRITING_EVALUATION_NOT_SCORE_BEARING,
        WRITING_LEGACY_SCORE_EVIDENCE,
        WRITING_SCORING_PROFILE_UNSUPPORTED,
        WRITING_MAXIMUM_MISMATCH,
        SPEAKING_NUMERIC_AGGREGATION_NOT_SUPPORTED,
        COMPARISON_SAMPLE_UNAVAILABLE,
        PAGE_DATA_UNAVAILABLE,
        SERIALIZATION_UNAVAILABLE
    }

    public enum ProgressSkillFilter {
        ALL("Tất cả kỹ năng", "전체 기능"),
        READING("Đọc", "읽기"),
        LISTENING("Nghe", "듣기"),
        WRITING("Viết", "쓰기"),
        SPEAKING("Nói", "말하기");

        private final String labelVi;
        private final String labelKo;

        ProgressSkillFilter(String labelVi, String labelKo) {
            this.labelVi = labelVi;
            this.labelKo = labelKo;
        }

        public String labelVi() {
            return labelVi;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    public enum ProgressWritingTaskFilter {
        ALL("Tất cả tác vụ Viết", "쓰기 전체"),
        Q51("Câu 51", "51번"),
        Q52("Câu 52", "52번"),
        Q53("Câu 53", "53번"),
        Q54("Câu 54", "54번");

        private final String labelVi;
        private final String labelKo;

        ProgressWritingTaskFilter(String labelVi, String labelKo) {
            this.labelVi = labelVi;
            this.labelKo = labelKo;
        }

        public String labelVi() {
            return labelVi;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    public record ProgressFilterOption(
            String id,
            String labelVi,
            String labelKo
    ) {}

    public record ProgressFilterState(
            String tab,
            ProgressSkillFilter skill,
            ProgressWritingTaskFilter writingTask,
            String profileId,
            List<ProgressFilterOption> profileOptions,
            ProgressAvailability availability,
            ProgressExclusionReason reason
    ) {
        public boolean active() {
            return skill != ProgressSkillFilter.ALL
                    || writingTask != ProgressWritingTaskFilter.ALL
                    || (profileId != null && !"ALL".equals(profileId));
        }

        public boolean writingFilterActive() {
            return skill == ProgressSkillFilter.WRITING;
        }

        public List<ProgressSkillFilter> skillOptions() {
            return List.of(ProgressSkillFilter.values());
        }

        public List<ProgressWritingTaskFilter> writingTaskOptions() {
            return List.of(ProgressWritingTaskFilter.values());
        }
    }

    public record ProgressExclusion(
            ProgressExclusionReason reason,
            long activityCount
    ) {}

    public record ProgressCoverage(
            long activityCount,
            long eligibleCount,
            long excludedCount,
            List<ProgressExclusion> exclusions
    ) {}

    public record ProgressObservationWindow(
            String code,
            String label,
            boolean bounded,
            Integer limit,
            long returnedCount,
            boolean truncated,
            LocalDateTime observedFrom,
            LocalDateTime observedTo,
            LocalDateTime asOf,
            LocalDateTime lastObservedAt
    ) {}

    public record ProgressNumericFact(
            ProgressAvailability availability,
            BigDecimal value,
            BigDecimal numerator,
            BigDecimal denominator,
            String unit,
            String profileId,
            long sampleSize,
            long activityCount,
            ProgressObservationWindow observationWindow,
            ProgressCoverage coverage
    ) {
        public boolean renderableValue() {
            return value != null
                    && (availability == ProgressAvailability.AVAILABLE
                    || availability == ProgressAvailability.PARTIAL);
        }

        public boolean partialCoverage() {
            return value != null && availability == ProgressAvailability.PARTIAL;
        }
    }

    public record ProgressLevelFact(
            ProgressAvailability availability,
            String value,
            String profileId,
            ProgressObservationWindow observationWindow,
            ProgressCoverage coverage
    ) {}

    public record ProgressAttemptCounts(
            long total,
            long completed,
            long inProgress,
            long other
    ) {}

    public record ProgressPageState(
            ProgressAvailability availability,
            ProgressExclusionReason reason,
            String retryHint
    ) {}

    public record WritingTaskScoreCohort(
            String cohortId,
            String taskType,
            String scoringProfileId,
            String policyBundleId,
            BigDecimal maximum,
            ProgressNumericFact scoreFact
    ) {}

    public record WritingTaskProgressSeam(
            String taskType,
            String label,
            ProgressAvailability availability,
            List<WritingTaskScoreCohort> cohorts,
            ProgressObservationWindow observationWindow,
            ProgressCoverage coverage
    ) {}

    public record SkillMetric(
            String skill,
            String skillLabel,
            Double normalizedScore,
            long attemptCount,
            Double deltaFromLastPeriod,
            ProgressAttemptCounts attemptCounts,
            ProgressNumericFact scoreFact,
            ProgressNumericFact deltaFact,
            ProgressObservationWindow observationWindow,
            ProgressCoverage coverage
    ) {}

    public record HeatmapCell(
            String date,
            int attemptCount,
            Long totalMinutes,
            ProgressCoverage durationCoverage
    ) {}

    public record ScoreTrendPoint(
            String date,
            String skill,
            Double normalizedScore,
            String title,
            Long attemptId,
            ProgressNumericFact scoreFact
    ) {}

    public record PerformanceHighlight(
            String type,
            String label,
            String skillOrType,
            long attempts,
            Double score,
            boolean hasData,
            ProgressNumericFact scoreFact
    ) {}

    public record QuestionTypePerf(
            String skill,
            String questionType,
            String questionTypeLabel,
            long totalAttempts,
            Double averageScore,
            Double bestScore,
            String lastPracticedAt,
            ProgressNumericFact scoreFact
    ) {}

    public record LearningProgressOverview(
            String studentName,
            String avatarUrl,
            String currentLevel,
            long totalAttempts,
            long totalCompletedTests,
            Long totalPracticeMinutes,
            Double recentAverageScore,
            List<SkillMetric> skillMetrics,
            List<HeatmapCell> heatmap,
            List<PracticeResultSummary> recentHistory,
            ProgressAttemptCounts attemptCounts,
            ProgressLevelFact levelFact,
            ProgressNumericFact durationFact,
            ProgressNumericFact recentScoreFact,
            ProgressObservationWindow allTimeWindow,
            ProgressObservationWindow recentDetailWindow,
            ProgressCoverage coverage
    ) {}

    public record PracticeAnalytics(
            List<SkillMetric> weeklySkillMetrics,
            List<ScoreTrendPoint> scoreTrend,
            List<QuestionTypePerf> questionTypePerf,
            List<PerformanceHighlight> highlights,
            List<PracticeResultSummary> history,
            List<WritingTaskProgressSeam> writingTaskSeams,
            ProgressCoverage writingAttemptCoverage,
            ProgressObservationWindow recentDetailWindow,
            ProgressPageState state
    ) {
        public boolean hasWeeklySkill(String skill) {
            return skill != null
                    && weeklySkillMetrics != null
                    && weeklySkillMetrics.stream()
                            .anyMatch(metric -> skill.equals(metric.skill()));
        }
    }

    public record PracticeProgressPageData(
            LearningProgressOverview overview,
            PracticeAnalytics analytics,
            ProgressPageState state
    ) {
        public static PracticeProgressPageData unavailable(
                String studentName,
                String avatarUrl,
                ProgressExclusionReason reason
        ) {
            ProgressPageState pageState =
                    new ProgressPageState(ProgressAvailability.UNAVAILABLE, reason, "RELOAD");
            ProgressCoverage coverage = new ProgressCoverage(
                    0, 0, 0, List.of(new ProgressExclusion(reason, 0)));
            ProgressObservationWindow window = new ProgressObservationWindow(
                    "UNAVAILABLE", "Dữ liệu tiến độ chưa khả dụng", true, 0,
                    0, false, null, null, null, null);
            ProgressNumericFact unavailableFact = new ProgressNumericFact(
                    ProgressAvailability.UNAVAILABLE, null, null, null,
                    null, null, 0, 0, window, coverage);
            LearningProgressOverview overview = new LearningProgressOverview(
                    studentName, avatarUrl, null, 0, 0, null, null,
                    List.of(), List.of(), List.of(),
                    new ProgressAttemptCounts(0, 0, 0, 0),
                    new ProgressLevelFact(
                            ProgressAvailability.UNAVAILABLE, null, null, window, coverage),
                    unavailableFact, unavailableFact, window, window, coverage);
            PracticeAnalytics analytics = new PracticeAnalytics(
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), coverage, window, pageState);
            return new PracticeProgressPageData(overview, analytics, pageState);
        }
    }

    public static String getOptionLabelMode(String title, String metadataJson) {
        if (metadataJson != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(metadataJson);
                if (root.has("optionLabelMode") && !root.path("optionLabelMode").asText().isEmpty()) {
                    return root.path("optionLabelMode").asText();
                }
                if (root.has("document")) {
                    com.fasterxml.jackson.databind.JsonNode doc = root.path("document");
                    if (doc.has("detectedCategory")) {
                        String cat = doc.path("detectedCategory").asText();
                        if (cat.startsWith("TOPIK_")) {
                            return "NUMERIC";
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (title != null && title.toUpperCase().contains("TOPIK")) {
            return "NUMERIC";
        }
        return "ALPHA";
    }

    // =========================================================================
    //  Canonical immutable-attempt Result Detail contract
    // =========================================================================

    public enum ResultDetailScreenKind {
        OBJECTIVE_DETAIL,
        WRITING_DETAIL,
        SPEAKING_DETAIL
    }

    public enum ResultDetailPolarity {
        STRENGTH,
        NEEDS_IMPROVEMENT
    }

    public record PracticeResultDetailView(
            String schemaVersion,
            String descriptorPolicyId,
            ResultAttemptIdentity identity,
            ResultState state,
            ResultDetailPayload payload
    ) {
        public static final String SCHEMA_VERSION = "practice-result-detail-v1";
        public static final String DESCRIPTOR_POLICY_ID = "ksh-korean-detail-descriptors-v1";

        public PracticeResultDetailView(
                ResultAttemptIdentity identity,
                ResultState state,
                ResultDetailPayload payload
        ) {
            this(SCHEMA_VERSION, DESCRIPTOR_POLICY_ID, identity, state, payload);
        }

        public PracticeResultDetailView {
            if (identity == null || state == null || payload == null) {
                throw new IllegalArgumentException("Practice Result Detail envelope is incomplete");
            }
            schemaVersion = SCHEMA_VERSION;
            descriptorPolicyId = DESCRIPTOR_POLICY_ID;
            ResultDetailScreenKind expected = switch (identity.skill()) {
                case "READING", "LISTENING" -> ResultDetailScreenKind.OBJECTIVE_DETAIL;
                case "WRITING" -> ResultDetailScreenKind.WRITING_DETAIL;
                case "SPEAKING" -> ResultDetailScreenKind.SPEAKING_DETAIL;
                default -> throw new IllegalArgumentException(
                        "Unsupported skill for Practice Result Detail: " + identity.skill());
            };
            if (payload.screenKind() != expected) {
                throw new IllegalArgumentException(
                        "Practice Result Detail payload does not match immutable attempt skill");
            }
        }

        public ResultDetailScreenKind screenKind() {
            return payload.screenKind();
        }
    }

    public sealed interface ResultDetailPayload
            permits ObjectiveDetailPayload, WritingDetailPayload, SpeakingDetailPayload {
        ResultDetailScreenKind screenKind();
    }

    public record ObjectiveDetailPayload(
            ResultScoreSummary score,
            ResultAnswerDistribution answers,
            ResultFeedbackAvailability feedback,
            ObjectiveResultPayload summary,
            List<ObjectiveResultGroup> groups,
            List<ObjectiveDetailCapability> capabilities,
            String constructRegistryState,
            String constructRegistryNote
    ) implements ResultDetailPayload {
        public ObjectiveDetailPayload {
            if (score == null || answers == null || feedback == null || summary == null
                    || constructRegistryState == null || constructRegistryState.isBlank()
                    || constructRegistryNote == null || constructRegistryNote.isBlank()) {
                throw new IllegalArgumentException("Objective Result Detail payload is incomplete");
            }
            groups = immutableResultList(groups);
            if (groups.isEmpty()) {
                throw new IllegalArgumentException(
                        "Objective Result Detail requires immutable group ownership");
            }
            capabilities = immutableResultList(capabilities);
            Set<ObjectiveDetailCapabilityCode> capabilityCodes = capabilities.stream()
                    .map(ObjectiveDetailCapability::code)
                    .collect(java.util.stream.Collectors.toCollection(
                            () -> java.util.EnumSet.noneOf(
                                    ObjectiveDetailCapabilityCode.class)));
            if (capabilities.size() != ObjectiveDetailCapabilityCode.values().length
                    || capabilityCodes.size() != capabilities.size()
                    || !capabilityCodes.equals(java.util.EnumSet.allOf(
                            ObjectiveDetailCapabilityCode.class))) {
                throw new IllegalArgumentException(
                        "Objective Result Detail capability catalogue must be exhaustive");
            }
            Set<String> sourceIds = new LinkedHashSet<>();
            Set<Long> groupVersionIds = new LinkedHashSet<>();
            Set<Long> questionVersionIds = new LinkedHashSet<>();
            for (ObjectiveResultGroup group : groups) {
                ObjectiveSourceGroup source = group.source();
                if (!sourceIds.add(source.sourceId())
                        || (group.groupVersionId() != null
                        && !groupVersionIds.add(group.groupVersionId()))) {
                    throw new IllegalArgumentException(
                            "Objective Result Detail immutable group navigation must be unique");
                }
                Set<Long> groupQuestionVersionIds = new LinkedHashSet<>();
                for (ObjectiveQuestionDetail question : group.questions()) {
                    if (!questionVersionIds.add(question.core().questionVersionId())
                            || !groupQuestionVersionIds.add(
                            question.core().questionVersionId())) {
                        throw new IllegalArgumentException(
                                "Objective Result Detail must contain one item per immutable question");
                    }
                    if (!source.sourceId().equals(question.core().sourceId())
                            || !java.util.Objects.equals(
                            group.groupVersionId(),
                            question.core().groupVersionId())
                            || !java.util.Objects.equals(
                            group.groupId(), question.core().groupId())
                            || !group.groupOrder().equals(
                            question.core().groupOrder())
                            || !group.displayLabel().equals(
                            question.core().groupLabel())) {
                        throw new IllegalArgumentException(
                                "Objective Result Detail question escaped immutable group ownership");
                    }
                }
                if (!groupQuestionVersionIds.equals(
                        new LinkedHashSet<>(source.questionVersionIds()))) {
                    throw new IllegalArgumentException(
                            "Objective Result Detail group navigation must match immutable questions");
                }
            }
        }

        /**
         * Compatibility-only flattened view. The authoritative render/read
         * contract is {@link #groups()}.
         */
        @JsonIgnore
        public List<ObjectiveSourceGroup> sourceGroups() {
            return groups.stream().map(ObjectiveResultGroup::source).toList();
        }

        /**
         * Compatibility-only flattened view. New result consumers must retain
         * immutable group ownership through {@link #groups()}.
         */
        @JsonIgnore
        public List<ObjectiveQuestionDetail> questions() {
            return groups.stream()
                    .flatMap(group -> group.questions().stream())
                    .toList();
        }

        @Override
        public ResultDetailScreenKind screenKind() {
            return ResultDetailScreenKind.OBJECTIVE_DETAIL;
        }
    }

    public enum ObjectiveQuestionType {
        SINGLE_CHOICE,
        MULTIPLE_ANSWER,
        MATCHING,
        FILL_BLANK,
        TRUE_FALSE_NOT_GIVEN
    }

    public enum ObjectiveDetailCapabilityCode {
        MULTIPLE_ANSWER,
        MATCHING,
        PINNED_SHARED_MATERIAL,
        LOCAL_HELPER_DRAWER
    }

    public enum ObjectiveDetailCapabilityState {
        AVAILABLE,
        NOT_AVAILABLE
    }

    public record ObjectiveDetailCapability(
            ObjectiveDetailCapabilityCode code,
            ObjectiveDetailCapabilityState state,
            String reasonVi
    ) {
        public ObjectiveDetailCapability {
            if (code == null || state == null) {
                throw new IllegalArgumentException(
                        "Objective Result Detail capability identity is incomplete");
            }
            reasonVi = blankResultText(reasonVi);
            if (state == ObjectiveDetailCapabilityState.NOT_AVAILABLE
                    && reasonVi.isBlank()) {
                throw new IllegalArgumentException(
                        "Unavailable Objective Result Detail capability requires a reason");
            }
        }

        public static List<ObjectiveDetailCapability> availableCatalogue() {
            return List.of(
                    available(
                            ObjectiveDetailCapabilityCode.MULTIPLE_ANSWER,
                            ""),
                    available(
                            ObjectiveDetailCapabilityCode.MATCHING,
                            ""),
                    available(
                            ObjectiveDetailCapabilityCode.PINNED_SHARED_MATERIAL,
                            ""),
                    available(
                            ObjectiveDetailCapabilityCode.LOCAL_HELPER_DRAWER,
                            ""));
        }

        private static ObjectiveDetailCapability available(
                ObjectiveDetailCapabilityCode code,
                String reasonVi) {
            return new ObjectiveDetailCapability(
                    code,
                    ObjectiveDetailCapabilityState.AVAILABLE,
                    reasonVi);
        }
    }

    public enum ObjectiveEvidenceKind {
        TEXT_SPAN,
        TRANSCRIPT_SPAN,
        IMAGE_REGION
    }

    public enum ObjectiveOptionState {
        USER_SELECTED_PENDING,
        UNSELECTED_PENDING,
        CORRECT,
        SELECTED_INCORRECT,
        UNSELECTED_INCORRECT
    }

    public record ObjectiveResultGroup(
            String groupKey,
            Long groupVersionId,
            Long groupId,
            Integer groupOrder,
            String displayLabel,
            ObjectiveSourceGroup source,
            List<ObjectiveQuestionDetail> questions
    ) {
        public ObjectiveResultGroup {
            if (groupKey == null || groupKey.isBlank()
                    || groupOrder == null || groupOrder < 0
                    || displayLabel == null || displayLabel.isBlank()
                    || source == null) {
                throw new IllegalArgumentException(
                        "Objective result group identity is incomplete");
            }
            questions = immutableResultList(questions);
            if (questions.isEmpty()
                    || !java.util.Objects.equals(
                    groupVersionId, source.groupVersionId())
                    || !java.util.Objects.equals(groupId, source.groupId())
                    || !groupOrder.equals(source.groupOrder())
                    || !displayLabel.equals(source.label())) {
                throw new IllegalArgumentException(
                        "Objective result group does not match immutable source ownership");
            }
            if (groupVersionId == null || groupId == null) {
                throw new IllegalArgumentException(
                        "Objective result canonical group identity is required");
            }
        }
    }

    public record ObjectiveSourceGroup(
            String sourceId,
            Long groupVersionId,
            Long groupId,
            Integer groupOrder,
            String label,
            String sourceKind,
            String instruction,
            String passageText,
            String transcriptText,
            String imageUrl,
            String audioUrl,
            String provenance,
            String transcriptEvidenceScope,
            List<Long> questionVersionIds,
            String languageTag,
            String instructionLanguageTag
    ) {
        public ObjectiveSourceGroup {
            if (sourceId == null || sourceId.isBlank()
                    || groupOrder == null || groupOrder < 0
                    || label == null || label.isBlank()
                    || sourceKind == null || sourceKind.isBlank()
                    || provenance == null || provenance.isBlank()
                    || transcriptEvidenceScope == null || transcriptEvidenceScope.isBlank()) {
                throw new IllegalArgumentException("Objective source group is incomplete");
            }
            instruction = blankResultText(instruction);
            passageText = blankResultText(passageText);
            transcriptText = blankResultText(transcriptText);
            imageUrl = blankResultText(imageUrl);
            audioUrl = blankResultText(audioUrl);
            languageTag = "ko".equals(languageTag) || "vi".equals(languageTag)
                    ? languageTag
                    : "ko";
            instructionLanguageTag = "ko".equals(instructionLanguageTag)
                    || "vi".equals(instructionLanguageTag)
                    ? instructionLanguageTag
                    : "vi";
            questionVersionIds = immutableResultList(questionVersionIds);
            if (questionVersionIds.isEmpty()
                    || new LinkedHashSet<>(questionVersionIds).size() != questionVersionIds.size()
                    || questionVersionIds.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Objective source group question navigation is invalid");
            }
            if (groupVersionId == null || groupId == null) {
                throw new IllegalArgumentException(
                        "Objective source canonical group identity is required");
            }
        }

        public boolean hasPassage() {
            return !passageText.isBlank();
        }

        public String provenanceLabelVi() {
            return "PUBLISHED_IMMUTABLE_SNAPSHOT".equals(provenance)
                    ? "Nguồn đề đã xuất bản và khóa"
                    : "Nguồn đề đã khóa";
        }

        public String provenanceLabelKo() {
            return "PUBLISHED_IMMUTABLE_SNAPSHOT".equals(provenance)
                    ? "게시 후 잠긴 출제 자료"
                    : "잠긴 출제 자료";
        }

        public boolean hasApprovedTranscript() {
            return !transcriptText.isBlank();
        }

        public boolean hasImage() {
            return !imageUrl.isBlank();
        }

        public boolean hasAudio() {
            return !audioUrl.isBlank();
        }
    }

    public record ObjectiveQuestionCore(
            Long questionVersionId,
            Long questionId,
            Integer questionNo,
            Integer stableOrder,
            Long groupVersionId,
            Long groupId,
            Integer groupOrder,
            Integer questionOrder,
            String groupLabel,
            String sourceId,
            String anchorId,
            String prompt,
            String scoreState,
            BigDecimal earnedPoints,
            BigDecimal possiblePoints,
            String learnerAnswerProvenance,
            String officialKeyProvenance,
            String teacherExplanation,
            String teacherExplanationProvenance,
            String languageTag
    ) {
        public ObjectiveQuestionCore {
            if (questionVersionId == null || questionId == null || questionNo == null
                    || stableOrder == null || stableOrder <= 0
                    || groupOrder == null || groupOrder < 0
                    || questionOrder == null || questionOrder < 0
                    || groupLabel == null || groupLabel.isBlank()
                    || sourceId == null || sourceId.isBlank()
                    || anchorId == null || anchorId.isBlank()
                    || prompt == null || scoreState == null || scoreState.isBlank()
                    || possiblePoints == null
                    || learnerAnswerProvenance == null || learnerAnswerProvenance.isBlank()
                    || officialKeyProvenance == null || officialKeyProvenance.isBlank()
                    || teacherExplanationProvenance == null
                    || teacherExplanationProvenance.isBlank()) {
                throw new IllegalArgumentException("Objective question identity is incomplete");
            }
            teacherExplanation = sanitizeTeacherExplanation(teacherExplanation);
            languageTag = "ko".equals(languageTag) || "vi".equals(languageTag)
                    ? languageTag
                    : "ko";
        }

        public String pointsDisplay() {
            return earnedPoints == null
                    ? null
                    : compactResultNumber(earnedPoints) + "/" + compactResultNumber(possiblePoints);
        }
    }

    public sealed interface ObjectiveQuestionDetail
            permits ObjectiveSingleChoiceDetail, ObjectiveMultipleAnswerDetail,
            ObjectiveMatchingDetail, ObjectiveFillBlankDetail, ObjectiveTfngDetail {
        ObjectiveQuestionType questionType();
        ObjectiveQuestionCore core();
        ObjectiveExplanation explanation();
    }

    public record ObjectiveMultipleAnswerDetail(
            ObjectiveQuestionCore core,
            List<ObjectiveOptionResult> options,
            ObjectiveExplanation explanation
    ) implements ObjectiveQuestionDetail {
        public ObjectiveMultipleAnswerDetail {
            if (core == null || explanation == null) {
                throw new IllegalArgumentException("Multiple-answer detail is incomplete");
            }
            options = immutableResultList(options);
            if (options.isEmpty()
                    || options.stream().filter(ObjectiveOptionResult::correct).count() < 2
                    || new LinkedHashSet<>(options.stream()
                    .map(ObjectiveOptionResult::optionId).toList()).size() != options.size()) {
                throw new IllegalArgumentException(
                        "Multiple-answer detail does not match immutable option authority");
            }
        }

        @Override
        public ObjectiveQuestionType questionType() {
            return ObjectiveQuestionType.MULTIPLE_ANSWER;
        }

        public boolean answered() {
            return options.stream().anyMatch(ObjectiveOptionResult::learnerSelected);
        }

        public boolean unanswered() {
            return !answered();
        }
    }

    public record ObjectiveMatchingDetail(
            ObjectiveQuestionCore core,
            List<ObjectiveMatchingResult> matches,
            ObjectiveExplanation explanation
    ) implements ObjectiveQuestionDetail {
        public ObjectiveMatchingDetail {
            if (core == null || explanation == null) {
                throw new IllegalArgumentException("Matching detail is incomplete");
            }
            matches = immutableResultList(matches);
            if (matches.isEmpty()
                    || new LinkedHashSet<>(matches.stream()
                    .map(ObjectiveMatchingResult::targetId).toList()).size() != matches.size()) {
                throw new IllegalArgumentException(
                        "Matching detail does not match immutable target authority");
            }
        }

        @Override
        public ObjectiveQuestionType questionType() {
            return ObjectiveQuestionType.MATCHING;
        }
    }

    public record ObjectiveMatchingResult(
            String targetId,
            String targetKo,
            String learnerCandidateId,
            String learnerCandidateLabel,
            String learnerCandidateText,
            String officialCandidateId,
            String officialCandidateLabel,
            String officialCandidateText,
            boolean correct,
            String reasonVi,
            List<String> evidenceIds
    ) {
        public ObjectiveMatchingResult {
            if (targetId == null || targetId.isBlank()
                    || officialCandidateId == null || officialCandidateId.isBlank()
                    || officialCandidateLabel == null || officialCandidateLabel.isBlank()) {
                throw new IllegalArgumentException("Matching result authority is incomplete");
            }
            targetKo = blankResultText(targetKo);
            learnerCandidateId = blankResultText(learnerCandidateId);
            learnerCandidateLabel = blankResultText(learnerCandidateLabel);
            learnerCandidateText = blankResultText(learnerCandidateText);
            officialCandidateText = blankResultText(officialCandidateText);
            reasonVi = blankResultText(reasonVi);
            evidenceIds = immutableResultList(evidenceIds);
        }

        public boolean answered() {
            return !learnerCandidateId.isBlank();
        }
    }

    public record ObjectiveSingleChoiceDetail(
            ObjectiveQuestionCore core,
            List<ObjectiveOptionResult> options,
            ObjectiveExplanation explanation
    ) implements ObjectiveQuestionDetail {
        public ObjectiveSingleChoiceDetail {
            if (core == null || explanation == null) {
                throw new IllegalArgumentException("Single-choice detail is incomplete");
            }
            options = immutableResultList(options);
            if (options.isEmpty()
                    || options.stream().filter(ObjectiveOptionResult::correct).count() != 1
                    || options.stream().filter(ObjectiveOptionResult::learnerSelected).count() > 1
                    || new LinkedHashSet<>(options.stream()
                            .map(ObjectiveOptionResult::optionId).toList()).size() != options.size()) {
                throw new IllegalArgumentException(
                        "Single-choice detail does not match immutable option authority");
            }
            Set<String> evidenceIds = explanation.evidenceRefs().stream()
                    .map(ObjectiveEvidenceRef::evidenceId)
                    .collect(java.util.stream.Collectors.toSet());
            if (options.stream().anyMatch(option ->
                    !evidenceIds.containsAll(option.evidenceIds()))) {
                throw new IllegalArgumentException(
                        "Single-choice option rationale references foreign evidence");
            }
        }

        @Override
        public ObjectiveQuestionType questionType() {
            return ObjectiveQuestionType.SINGLE_CHOICE;
        }

        public boolean answered() {
            return options.stream().anyMatch(ObjectiveOptionResult::learnerSelected);
        }

        public boolean unanswered() {
            return !answered();
        }
    }

    public record ObjectiveOptionResult(
            String optionId,
            String visibleLabel,
            String text,
            String imageReference,
            boolean learnerSelected,
            boolean correct,
            ObjectiveOptionState state,
            String rationaleVi,
            String rationaleProvenance,
            List<String> evidenceIds
    ) {
        public ObjectiveOptionResult {
            if (optionId == null || optionId.isBlank()
                    || visibleLabel == null || visibleLabel.isBlank()
                    || state == null
                    || rationaleVi == null
                    || rationaleProvenance == null || rationaleProvenance.isBlank()) {
                throw new IllegalArgumentException("Objective option row is incomplete");
            }
            if ((state == ObjectiveOptionState.USER_SELECTED_PENDING
                    || state == ObjectiveOptionState.SELECTED_INCORRECT)
                    && !learnerSelected
                    || (state == ObjectiveOptionState.UNSELECTED_PENDING
                    || state == ObjectiveOptionState.UNSELECTED_INCORRECT)
                    && learnerSelected) {
                throw new IllegalArgumentException(
                        "Objective option selected state contradicts learner answer");
            }
            if (state == ObjectiveOptionState.CORRECT && !correct
                    || state == ObjectiveOptionState.SELECTED_INCORRECT && correct
                    || state == ObjectiveOptionState.UNSELECTED_INCORRECT && correct) {
                throw new IllegalArgumentException(
                        "Objective option reveal state contradicts official answer");
            }
            text = blankResultText(text);
            imageReference = blankResultText(imageReference);
            rationaleVi = blankResultText(rationaleVi);
            evidenceIds = immutableResultList(evidenceIds);
        }

        public boolean hasStatusLabel() {
            return state != ObjectiveOptionState.UNSELECTED_PENDING
                    && state != ObjectiveOptionState.UNSELECTED_INCORRECT;
        }

        public String statusLabelVi() {
            return switch (state) {
                case USER_SELECTED_PENDING -> "Bạn đã chọn";
                case CORRECT -> learnerSelected
                        ? "Bạn đã chọn · Đúng"
                        : "Đáp án đúng";
                case SELECTED_INCORRECT -> "Bạn đã chọn · Sai";
                case UNSELECTED_PENDING, UNSELECTED_INCORRECT -> "";
            };
        }

        public int evidenceCount() {
            return evidenceIds.size();
        }

        public boolean hasRationale() {
            return !rationaleVi.isBlank();
        }
    }

    public record ObjectiveFillBlankDetail(
            ObjectiveQuestionCore core,
            List<ObjectiveBlankResult> blanks,
            ObjectiveExplanation explanation
    ) implements ObjectiveQuestionDetail {
        public ObjectiveFillBlankDetail {
            if (core == null || explanation == null) {
                throw new IllegalArgumentException("Fill-blank detail is incomplete");
            }
            blanks = immutableResultList(blanks);
            if (blanks.isEmpty()
                    || new LinkedHashSet<>(blanks.stream()
                            .map(ObjectiveBlankResult::blankId).toList()).size() != blanks.size()) {
                throw new IllegalArgumentException(
                        "Fill-blank detail does not match immutable blank authority");
            }
            Set<String> evidenceIds = explanation.evidenceRefs().stream()
                    .map(ObjectiveEvidenceRef::evidenceId)
                    .collect(java.util.stream.Collectors.toSet());
            if (blanks.stream().anyMatch(blank ->
                    !evidenceIds.containsAll(blank.evidenceIds()))) {
                throw new IllegalArgumentException(
                        "Fill-blank explanation references foreign evidence");
            }
        }

        @Override
        public ObjectiveQuestionType questionType() {
            return ObjectiveQuestionType.FILL_BLANK;
        }
    }

    public record ObjectiveBlankResult(
            String blankId,
            String contextKo,
            String learnerValue,
            List<String> acceptedValues,
            String normalizationPolicy,
            boolean correct,
            String contextExplanationVi,
            String semanticConstraintVi,
            String grammarConstraintVi,
            String registerConstraintVi,
            String explanationProvenance,
            List<String> evidenceIds
    ) {
        public ObjectiveBlankResult {
            if (blankId == null || blankId.isBlank()
                    || acceptedValues == null || acceptedValues.isEmpty()
                    || normalizationPolicy == null || normalizationPolicy.isBlank()
                    || explanationProvenance == null || explanationProvenance.isBlank()) {
                throw new IllegalArgumentException("Objective blank row is incomplete");
            }
            contextKo = blankResultText(contextKo);
            learnerValue = blankResultText(learnerValue);
            acceptedValues = immutableResultList(acceptedValues);
            if (acceptedValues.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "Objective blank accepted values must be authoritative text");
            }
            contextExplanationVi = blankResultText(contextExplanationVi);
            semanticConstraintVi = blankResultText(semanticConstraintVi);
            grammarConstraintVi = blankResultText(grammarConstraintVi);
            registerConstraintVi = blankResultText(registerConstraintVi);
            evidenceIds = immutableResultList(evidenceIds);
        }
    }

    public record ObjectiveTfngDetail(
            ObjectiveQuestionCore core,
            String claimKo,
            String learnerValue,
            String officialValue,
            String relation,
            String relationExplanationVi,
            String missingInformationVi,
            List<ObjectiveTfngAlternative> alternatives,
            ObjectiveExplanation explanation
    ) implements ObjectiveQuestionDetail {
        public ObjectiveTfngDetail {
            if (core == null || officialValue == null || officialValue.isBlank()
                    || relation == null || relation.isBlank() || explanation == null) {
                throw new IllegalArgumentException("TFNG detail is incomplete");
            }
            claimKo = blankResultText(claimKo);
            learnerValue = blankResultText(learnerValue);
            relationExplanationVi = blankResultText(relationExplanationVi);
            missingInformationVi = blankResultText(missingInformationVi);
            alternatives = immutableResultList(alternatives);
            Set<String> tfngLabels = Set.of("TRUE", "FALSE", "NOT_GIVEN");
            String expectedRelation = switch (officialValue) {
                case "TRUE" -> "ENTAILED";
                case "FALSE" -> "CONTRADICTED";
                case "NOT_GIVEN" -> "NOT_STATED";
                default -> throw new IllegalArgumentException(
                        "TFNG official value is not canonical");
            };
            Set<String> expectedAlternatives = new LinkedHashSet<>(tfngLabels);
            expectedAlternatives.remove(officialValue);
            if (alternatives.size() != 2
                    || alternatives.stream().anyMatch(alternative ->
                            officialValue.equals(alternative.label()))
                    || new LinkedHashSet<>(alternatives.stream()
                            .map(ObjectiveTfngAlternative::label).toList()).size() != 2
                    || !expectedAlternatives.equals(alternatives.stream()
                            .map(ObjectiveTfngAlternative::label)
                            .collect(java.util.stream.Collectors.toSet()))
                    || !expectedRelation.equals(relation)
                    || alternatives.stream().anyMatch(alternative ->
                            !tfngRelationForResult(alternative.label())
                                    .equals(alternative.relation()))
                    || ("NOT_GIVEN".equals(officialValue)
                            && explanation.ready()
                            && missingInformationVi.isBlank())) {
                throw new IllegalArgumentException(
                        "TFNG detail must explain exactly the two non-authoritative labels");
            }
        }

        @Override
        public ObjectiveQuestionType questionType() {
            return ObjectiveQuestionType.TRUE_FALSE_NOT_GIVEN;
        }

        public String learnerValueLabelVi() {
            return learnerValue.isBlank() ? "Chưa trả lời" : tfngLabelVi(learnerValue);
        }

        public String learnerValueLabelKo() {
            return learnerValue.isBlank() ? "미응답" : tfngLabelKo(learnerValue);
        }

        public String officialValueLabelVi() {
            return tfngLabelVi(officialValue);
        }

        public String officialValueLabelKo() {
            return tfngLabelKo(officialValue);
        }

        public String relationLabelVi() {
            return tfngRelationLabelVi(relation);
        }

        public String relationLabelKo() {
            return tfngRelationLabelKo(relation);
        }
    }

    public record ObjectiveTfngAlternative(
            String label,
            String relation,
            String reasonVi,
            String provenance
    ) {
        public ObjectiveTfngAlternative {
            if (label == null || label.isBlank()
                    || relation == null || relation.isBlank()
                    || reasonVi == null || reasonVi.isBlank()
                    || provenance == null || provenance.isBlank()) {
                throw new IllegalArgumentException("TFNG alternative explanation is incomplete");
            }
        }

        public String labelVi() {
            return tfngLabelVi(label);
        }

        public String labelKo() {
            return tfngLabelKo(label);
        }

        public String relationLabelVi() {
            return tfngRelationLabelVi(relation);
        }

        public String relationLabelKo() {
            return tfngRelationLabelKo(relation);
        }
    }

    public record ObjectiveExplanation(
            String state,
            String stateLabel,
            String artifactSchemaVersion,
            String strategyRegistryVersion,
            String strategyCode,
            String strategyVersion,
            String strategyCategoryVi,
            String strategyLabelVi,
            String strategyDescriptionVi,
            String strategyRendererCode,
            String aiMeaningVi,
            String correctReasonVi,
            String aiArtifactProvenance,
            List<ObjectiveExplanationClaim> claims,
            List<ObjectiveEvidenceRef> evidenceRefs,
            List<ObjectiveEvidenceTranslation> evidenceTranslations,
            List<ObjectiveConstructDescriptor> constructDescriptors,
            String constructRegistryState
    ) {
        public ObjectiveExplanation {
            if (state == null || state.isBlank()
                    || stateLabel == null || stateLabel.isBlank()
                    || aiArtifactProvenance == null || aiArtifactProvenance.isBlank()
                    || constructRegistryState == null || constructRegistryState.isBlank()) {
                throw new IllegalArgumentException("Objective explanation state is incomplete");
            }
            artifactSchemaVersion = blankResultText(artifactSchemaVersion);
            strategyRegistryVersion =
                    blankResultText(strategyRegistryVersion);
            strategyCode = blankResultText(strategyCode);
            strategyVersion = blankResultText(strategyVersion);
            strategyCategoryVi = blankResultText(strategyCategoryVi);
            strategyLabelVi = blankResultText(strategyLabelVi);
            strategyDescriptionVi = blankResultText(strategyDescriptionVi);
            strategyRendererCode = blankResultText(strategyRendererCode);
            aiMeaningVi = blankResultText(aiMeaningVi);
            correctReasonVi = blankResultText(correctReasonVi);
            claims = immutableResultList(claims);
            evidenceRefs = immutableResultList(evidenceRefs);
            evidenceTranslations = immutableResultList(evidenceTranslations);
            constructDescriptors = immutableResultList(constructDescriptors);
            Set<String> evidenceIds = new LinkedHashSet<>();
            for (ObjectiveEvidenceRef evidence : evidenceRefs) {
                if (!evidenceIds.add(evidence.evidenceId())) {
                    throw new IllegalArgumentException(
                            "Objective explanation evidence IDs must be unique");
                }
            }
            Set<String> translatedEvidenceIds = new LinkedHashSet<>();
            for (ObjectiveEvidenceTranslation translation : evidenceTranslations) {
                if (!evidenceIds.contains(translation.evidenceId())
                        || !translatedEvidenceIds.add(translation.evidenceId())) {
                    throw new IllegalArgumentException(
                            "Evidence translation references foreign or duplicate evidence");
                }
            }
            Set<String> claimIds = new LinkedHashSet<>();
            for (ObjectiveExplanationClaim claim : claims) {
                if (!claimIds.add(claim.claimId())
                        || !evidenceIds.containsAll(claim.evidenceIds())) {
                    throw new IllegalArgumentException(
                            "Objective explanation claim references foreign evidence");
                }
            }
            if ("v4".equals(artifactSchemaVersion)
                    && ("READY".equals(state)
                    && (strategyRegistryVersion.isBlank()
                    || strategyCode.isBlank()
                    || strategyVersion.isBlank()
                    || strategyCategoryVi.isBlank()
                    || strategyLabelVi.isBlank()
                    || strategyDescriptionVi.isBlank()
                    || strategyRendererCode.isBlank()
                    || claims.isEmpty()))) {
                throw new IllegalArgumentException(
                        "v4 objective explanation requires strategy-linked claims");
            }
            if (!"READY".equals(state)
                    && (!claims.isEmpty()
                            || !evidenceRefs.isEmpty()
                            || !evidenceTranslations.isEmpty()
                            || !constructDescriptors.isEmpty())) {
                throw new IllegalArgumentException(
                        "Unavailable objective explanation cannot expose artifact evidence");
            }
        }

        public boolean ready() {
            return "READY".equals(state);
        }
    }

    public record ObjectiveExplanationClaim(
            String claimId,
            String textVi,
            List<String> evidenceIds
    ) {
        public ObjectiveExplanationClaim {
            if (claimId == null || claimId.isBlank()
                    || textVi == null || textVi.isBlank()) {
                throw new IllegalArgumentException(
                        "Objective explanation claim is incomplete");
            }
            evidenceIds = immutableResultList(evidenceIds);
            if (evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Objective explanation claim must reference evidence");
            }
        }
    }

    public sealed interface ObjectiveEvidenceRef
            permits ObjectiveTextEvidenceRef, ObjectiveImageEvidenceRef {
        String evidenceId();
        ObjectiveEvidenceKind kind();
        String purpose();
        String sourceRole();
    }

    public record ObjectiveTextEvidenceRef(
            String evidenceId,
            ObjectiveEvidenceKind kind,
            String purpose,
            String sourceRole,
            String exactQuoteKo,
            int startOffset,
            int endOffset
    ) implements ObjectiveEvidenceRef {
        public ObjectiveTextEvidenceRef {
            if (evidenceId == null || evidenceId.isBlank()
                    || (kind != ObjectiveEvidenceKind.TEXT_SPAN
                            && kind != ObjectiveEvidenceKind.TRANSCRIPT_SPAN)
                    || purpose == null || purpose.isBlank()
                    || sourceRole == null || sourceRole.isBlank()
                    || exactQuoteKo == null || exactQuoteKo.isBlank()
                    || startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("Objective text evidence is invalid");
            }
        }
    }

    public record ObjectiveImageEvidenceRef(
            String evidenceId,
            ObjectiveEvidenceKind kind,
            String purpose,
            String sourceRole,
            String assetDigest,
            int imageIndex,
            String regionMode,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height
    ) implements ObjectiveEvidenceRef {
        public ObjectiveImageEvidenceRef {
            if (evidenceId == null || evidenceId.isBlank()
                    || kind != ObjectiveEvidenceKind.IMAGE_REGION
                    || purpose == null || purpose.isBlank()
                    || sourceRole == null || sourceRole.isBlank()
                    || assetDigest == null || !assetDigest.matches("(?i)[0-9a-f]{64}")
                    || imageIndex < 0
                    || (!"WHOLE_IMAGE".equals(regionMode)
                            && !"RECTANGLE".equals(regionMode))) {
                throw new IllegalArgumentException("Objective image evidence is invalid");
            }
            if ("RECTANGLE".equals(regionMode)
                    && (x == null || y == null || width == null || height == null
                            || x.signum() < 0 || y.signum() < 0
                            || width.signum() <= 0 || height.signum() <= 0)) {
                throw new IllegalArgumentException(
                        "Objective image evidence rectangle is incomplete");
            }
            if ("WHOLE_IMAGE".equals(regionMode)) {
                if (x != null || y != null || width != null || height != null) {
                    throw new IllegalArgumentException(
                            "WHOLE_IMAGE evidence must not expose rectangle coordinates");
                }
            }
        }
    }

    public record ObjectiveEvidenceTranslation(
            String evidenceId,
            String label,
            String translationVi,
            String provenance
    ) {
        public ObjectiveEvidenceTranslation {
            if (evidenceId == null || evidenceId.isBlank()
                    || !"Dịch đoạn liên quan".equals(label)
                    || translationVi == null || translationVi.isBlank()
                    || provenance == null || provenance.isBlank()) {
                throw new IllegalArgumentException(
                        "Objective evidence translation contract is incomplete");
            }
        }
    }

    public record ObjectiveConstructDescriptor(
            String code,
            String labelVi,
            String labelKo,
            String registryVersion
    ) {
        public ObjectiveConstructDescriptor {
            if (code == null || code.isBlank()
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()
                    || registryVersion == null || registryVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "Objective construct descriptor is incomplete");
            }
        }
    }

    /**
     * Stable backend-owned membership between one exact source span and one
     * diagnostic chip.  The UI may renumber an occurrence only by consuming
     * {@code scopedDisplayNumber}; it must never rediscover the span from text.
     */
    public record ResultDetailSpanMembership(
            String findingId,
            String evidenceId,
            String descriptorId,
            String featureId,
            ResultDetailPolarity polarity,
            Integer startOffset,
            Integer endOffset,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String operation,
            int scopedDisplayNumber,
            String evidence,
            String explanationVi,
            String correctionKo
    ) {
        public ResultDetailSpanMembership {
            if (findingId == null || findingId.isBlank()
                    || evidenceId == null || evidenceId.isBlank()
                    || descriptorId == null || descriptorId.isBlank()
                    || featureId == null || featureId.isBlank()
                    || polarity == null
                    || startOffset == null || startOffset < 0
                    || endOffset == null || endOffset <= startOffset
                    || occurrenceIndex == null || occurrenceIndex < 1
                    || occurrenceCount == null
                    || occurrenceCount < occurrenceIndex
                    || operation == null
                    || !Set.of("KEEP", "MISSING", "REPLACE", "REDUNDANT")
                    .contains(operation)
                    || scopedDisplayNumber < 1
                    || evidence == null || evidence.isBlank()
                    || explanationVi == null || explanationVi.isBlank()
                    || (polarity == ResultDetailPolarity.STRENGTH
                    && !"KEEP".equals(operation))
                    || (polarity == ResultDetailPolarity.NEEDS_IMPROVEMENT
                    && "KEEP".equals(operation))) {
                throw new IllegalArgumentException(
                        "Result Detail span membership is incomplete");
            }
        }
    }

    public record WritingTextSegment(
            String text,
            boolean annotated,
            String annotationId,
            int annotationNumber,
            String kind,
            String categoryCode,
            String criterionId,
            String explanationVi,
            String correctionKo,
            String featureId,
            List<ResultDetailSpanMembership> memberships
    ) {
        public WritingTextSegment {
            memberships = memberships == null ? List.of() : List.copyOf(memberships);
            if (text == null) {
                throw new IllegalArgumentException(
                        "Writing learner-answer segment text is required");
            }
            if (annotated) {
                if (text.isEmpty()
                        || annotationId == null || annotationId.isBlank()
                        || annotationNumber < 1
                        || kind == null
                        || !Set.of("STRENGTH", "NEEDS_IMPROVEMENT").contains(kind)
                        || categoryCode == null || categoryCode.isBlank()
                        || criterionId == null || criterionId.isBlank()
                        || explanationVi == null || explanationVi.isBlank()
                        || featureId == null || featureId.isBlank()
                        || memberships.isEmpty()
                        || memberships.stream().noneMatch(membership ->
                        annotationId.equals(membership.findingId())
                                && featureId.equals(membership.descriptorId()))) {
                    throw new IllegalArgumentException(
                            "Annotated Writing learner-answer segment is incomplete");
                }
            } else if (annotationId != null
                    || annotationNumber != 0
                    || kind != null
                    || categoryCode != null
                    || criterionId != null
                    || explanationVi != null
                    || correctionKo != null
                    || featureId != null
                    || !memberships.isEmpty()) {
                throw new IllegalArgumentException(
                        "Plain Writing learner-answer segment cannot carry annotation metadata");
            }
        }

        public WritingTextSegment(
                String text,
                boolean annotated,
                String annotationId,
                int annotationNumber,
                String kind,
                String categoryCode,
                String criterionId,
                String explanationVi,
                String correctionKo,
                String featureId
        ) {
            this(text, annotated, annotationId, annotationNumber, kind,
                    categoryCode, criterionId, explanationVi, correctionKo,
                    featureId, List.of());
        }

        public String featureIds() {
            return memberships.stream()
                    .map(ResultDetailSpanMembership::descriptorId)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        public String findingIds() {
            return memberships.stream()
                    .map(ResultDetailSpanMembership::findingId)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        public static WritingTextSegment plain(String text) {
            return new WritingTextSegment(
                    text == null ? "" : text,
                    false,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of());
        }
    }

    public record WritingBlankAnswerView(
            String blankId,
            int ordinal,
            String text,
            List<WritingTextSegment> segments
    ) {
        public WritingBlankAnswerView {
            if (blankId == null || blankId.isBlank()
                    || ordinal < 1 || text == null) {
                throw new IllegalArgumentException(
                        "Writing structured blank answer is incomplete");
            }
            segments = immutableResultList(segments);
            if (segments.isEmpty()
                    || !text.equals(segments.stream()
                    .map(WritingTextSegment::text)
                    .collect(java.util.stream.Collectors.joining()))) {
                throw new IllegalArgumentException(
                        "Writing structured blank segments must preserve the answer");
            }
        }
    }

    public record WritingTeacherSampleView(
            String content,
            String availability,
            String source,
            String authorRole,
            String fixtureId
    ) {
        public WritingTeacherSampleView {
            content = content == null ? "" : content;
            if (!Set.of("AVAILABLE", "UNAVAILABLE").contains(availability)) {
                throw new IllegalArgumentException(
                        "Writing teacher sample availability is invalid");
            }
            if ("AVAILABLE".equals(availability)) {
                if (content.isBlank()
                        || !"TEACHER_AUTHORED".equals(source)
                        || !"LECTURER".equals(authorRole)
                        || fixtureId == null || fixtureId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Available Writing teacher sample requires authored provenance");
                }
            } else if (!content.isEmpty()
                    || !"NOT_AVAILABLE".equals(source)
                    || authorRole != null
                    || fixtureId != null) {
                throw new IllegalArgumentException(
                        "Unavailable Writing teacher sample cannot expose content or authorship");
            }
        }

        public boolean available() {
            return "AVAILABLE".equals(availability);
        }

        public static WritingTeacherSampleView unavailable() {
            return new WritingTeacherSampleView(
                    "", "UNAVAILABLE", "NOT_AVAILABLE", null, null);
        }
    }

    public record WritingDetailPayload(
            ResultFeedbackAvailability feedback,
            List<WritingTaskResult> tasks,
            Long activeQuestionId,
            List<WritingTextSegment> learnerAnswerSegments,
            List<ResultDetailScoreCriterion> scoreCriteria,
            List<WritingTaskCoverageView> taskCoverage,
            @JsonIgnore String scoreProfileId,
            String diagnosticSeamId,
            String diagnosticSeamState,
            String diagnosticScopeNoteVi,
            String diagnosticScopeNoteKo,
            String diagnosticAvailability,
            String diagnosticAvailabilityNoteVi,
            String diagnosticAvailabilityNoteKo,
            List<WritingDiagnosticGroup> diagnosticGroups,
            WritingUpgradeView upgrade,
            List<WritingBlankAnswerView> structuredBlankAnswers,
            WritingTeacherSampleView teacherSample
    ) implements ResultDetailPayload {
        public WritingDetailPayload {
            if (feedback == null
                    || !"KSH_INTERNAL_TASK_NATIVE_V1".equals(scoreProfileId)
                    || !"ksh-writing-detail-diagnostics-seam-v1".equals(diagnosticSeamId)
                    || !"BOUNDED_CURRENT_EVIDENCE".equals(diagnosticSeamState)
                    || diagnosticScopeNoteVi == null || diagnosticScopeNoteVi.isBlank()
                    || diagnosticScopeNoteKo == null || diagnosticScopeNoteKo.isBlank()
                    || diagnosticAvailability == null
                    || !Set.of(
                            "AVAILABLE",
                            "NO_VALIDATED_EVIDENCE",
                            "BLANK_IDENTITY_UNAVAILABLE",
                            "FEEDBACK_UNAVAILABLE",
                            "CURRENT_EVIDENCE_CONTRACT_UNAVAILABLE",
                            "TASK_IDENTITY_UNAVAILABLE",
                            "NO_DETAIL_TASK").contains(diagnosticAvailability)
                    || diagnosticAvailabilityNoteVi == null || diagnosticAvailabilityNoteVi.isBlank()
                    || diagnosticAvailabilityNoteKo == null || diagnosticAvailabilityNoteKo.isBlank()) {
                throw new IllegalArgumentException("Writing Result Detail contract is incomplete");
            }
            tasks = immutableResultList(tasks);
            learnerAnswerSegments = immutableResultList(learnerAnswerSegments);
            scoreCriteria = immutableResultList(scoreCriteria);
            taskCoverage = immutableResultList(taskCoverage);
            diagnosticGroups = immutableResultList(diagnosticGroups);
            structuredBlankAnswers =
                    immutableResultList(structuredBlankAnswers);
            if (teacherSample == null) {
                throw new IllegalArgumentException(
                        "Writing teacher sample state is required");
            }
            List<WritingTaskResult> immutableTasks = tasks;
            if (activeQuestionId != null && immutableTasks.stream().noneMatch(task ->
                    activeQuestionId.equals(task.questionId()) && task.detailAvailable())) {
                throw new IllegalArgumentException(
                        "Writing Result Detail question selection is outside the immutable attempt");
            }
            if (activeQuestionId == null) {
                if (!learnerAnswerSegments.isEmpty()
                        || !structuredBlankAnswers.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Writing learner-answer segments require a selected task");
                }
            } else {
                WritingTaskResult selected = immutableTasks.stream()
                        .filter(task -> activeQuestionId.equals(task.questionId()))
                        .findFirst()
                        .orElseThrow();
                String learnerAnswer = Normalizer.normalize(
                        selected.learnerAnswer() == null
                                ? ""
                                : selected.learnerAnswer(),
                        Normalizer.Form.NFC);
                String reconstructedAnswer = learnerAnswerSegments.stream()
                        .map(WritingTextSegment::text)
                        .collect(java.util.stream.Collectors.joining());
                boolean hasAnnotatedSegment = learnerAnswerSegments.stream()
                        .anyMatch(WritingTextSegment::annotated);
                if (learnerAnswerSegments.isEmpty()
                        || !learnerAnswer.equals(reconstructedAnswer)
                        || (learnerAnswerSegments.size() > 1
                        && learnerAnswerSegments.stream()
                                .anyMatch(segment -> segment.text().isEmpty()))
                        || (!hasAnnotatedSegment && learnerAnswerSegments.size() != 1)
                        || (selected.clozeTask() && hasAnnotatedSegment)) {
                    throw new IllegalArgumentException(
                            "Writing learner-answer segments must exactly preserve the selected answer");
                }
                Set<String> structuredBlankIds = new LinkedHashSet<>();
                Set<Integer> structuredBlankOrdinals =
                        new LinkedHashSet<>();
                if (!structuredBlankAnswers.isEmpty()
                        && (!selected.clozeTask()
                        || structuredBlankAnswers.size() != 2
                        || structuredBlankAnswers.stream().anyMatch(blank ->
                        !structuredBlankIds.add(blank.blankId())
                                || !structuredBlankOrdinals.add(
                                blank.ordinal()))
                        || !structuredBlankOrdinals.equals(
                        Set.of(1, 2)))) {
                    throw new IllegalArgumentException(
                            "Writing structured blanks must preserve two authoritative identities");
                }
            }
            if (scoreCriteria.stream().anyMatch(criterion ->
                    criterion.questionId() == null || immutableTasks.stream().noneMatch(task ->
                            criterion.questionId().equals(task.questionId())
                                    && task.detailAvailable()))) {
                throw new IllegalArgumentException(
                        "Writing score criteria must belong to a detail-capable immutable task");
            }
            Set<String> coverageRequirementIds = new LinkedHashSet<>();
            if (taskCoverage.stream().anyMatch(row ->
                    activeQuestionId == null
                            || row.questionId() == null
                            || !activeQuestionId.equals(row.questionId())
                            || !coverageRequirementIds.add(row.requirementId()))) {
                throw new IllegalArgumentException(
                        "Writing task coverage must belong only to the selected immutable task");
            }
            if (diagnosticGroups.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            group.strengths().stream(),
                            group.needsImprovement().stream()))
                    .anyMatch(finding ->
                    activeQuestionId == null
                            || finding.questionId() == null
                            || !activeQuestionId.equals(finding.questionId()))) {
                throw new IllegalArgumentException(
                        "Writing diagnostics must belong only to the selected immutable task");
            }
            if (activeQuestionId == null && upgrade != null) {
                throw new IllegalArgumentException(
                        "Writing upgrade provenance cannot exist without a selected task");
            }
            if (activeQuestionId != null
                    && (upgrade == null || !activeQuestionId.equals(upgrade.questionId()))) {
                throw new IllegalArgumentException(
                        "Writing upgrade provenance must belong to the selected immutable task");
            }
            if (upgrade != null) {
                WritingTaskResult selected = immutableTasks.stream()
                        .filter(task -> activeQuestionId.equals(task.questionId()))
                        .findFirst()
                        .orElseThrow();
                String learnerAnswer = Normalizer.normalize(
                        selected.learnerAnswer() == null
                                ? ""
                                : selected.learnerAnswer(),
                        Normalizer.Form.NFC);
                if (upgrade.significantRewrites().stream().anyMatch(rewrite ->
                        rewrite.original() == null
                                || rewrite.original().isBlank()
                                || !learnerAnswer.contains(rewrite.original()))) {
                    throw new IllegalArgumentException(
                            "Writing rewrites must preserve an exact selected learner span");
                }
            }
        }

        public WritingDetailPayload(
                ResultFeedbackAvailability feedback,
                List<WritingTaskResult> tasks,
                Long activeQuestionId,
                List<WritingTextSegment> learnerAnswerSegments,
                List<ResultDetailScoreCriterion> scoreCriteria,
                List<WritingTaskCoverageView> taskCoverage,
                String scoreProfileId,
                String diagnosticSeamId,
                String diagnosticSeamState,
                String diagnosticScopeNoteVi,
                String diagnosticScopeNoteKo,
                String diagnosticAvailability,
                String diagnosticAvailabilityNoteVi,
                String diagnosticAvailabilityNoteKo,
                List<WritingDiagnosticGroup> diagnosticGroups,
                WritingUpgradeView upgrade
        ) {
            this(
                    feedback,
                    tasks,
                    activeQuestionId,
                    learnerAnswerSegments,
                    scoreCriteria,
                    taskCoverage,
                    scoreProfileId,
                    diagnosticSeamId,
                    diagnosticSeamState,
                    diagnosticScopeNoteVi,
                    diagnosticScopeNoteKo,
                    diagnosticAvailability,
                    diagnosticAvailabilityNoteVi,
                    diagnosticAvailabilityNoteKo,
                    diagnosticGroups,
                    upgrade,
                    List.of(),
                    WritingTeacherSampleView.unavailable());
        }

        public List<WritingDiagnosticFinding> diagnosticFindings() {
            return diagnosticGroups.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            group.strengths().stream(),
                            group.needsImprovement().stream()))
                    .toList();
        }

        public List<WritingDiagnosticChip> filterChips() {
            return diagnosticGroups.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            group.strengthChips().stream(),
                            group.needsImprovementChips().stream()))
                    .toList();
        }

        public boolean hasStrengthFindings() {
            return diagnosticGroups.stream()
                    .anyMatch(WritingDiagnosticGroup::hasStrengths);
        }

        public boolean hasNeedsImprovementFindings() {
            return diagnosticGroups.stream()
                    .anyMatch(WritingDiagnosticGroup::hasNeedsImprovement);
        }

        public boolean hasUpgradeForDescriptor(String descriptorId) {
            if (descriptorId == null || descriptorId.isBlank()
                    || upgrade == null) {
                return false;
            }
            Set<String> rewriteFindingIds = upgrade.significantRewrites()
                    .stream()
                    .flatMap(rewrite -> rewrite.findingIds().stream())
                    .collect(java.util.stream.Collectors.toSet());
            return diagnosticFindings().stream().anyMatch(finding ->
                    descriptorId.equals(finding.descriptorId())
                            && rewriteFindingIds.contains(
                            finding.findingId()));
        }

        public boolean hasUpgradeForGroup(String categoryCode) {
            if (categoryCode == null || categoryCode.isBlank()) {
                return false;
            }
            return diagnosticGroups.stream()
                    .filter(group -> categoryCode.equals(
                            group.categoryCode()))
                    .flatMap(group -> group.needsImprovementChips()
                            .stream())
                    .anyMatch(chip -> hasUpgradeForDescriptor(chip.id()));
        }

        @Override
        public ResultDetailScreenKind screenKind() {
            return ResultDetailScreenKind.WRITING_DETAIL;
        }
    }

    public record WritingTaskCoverageView(
            Long questionId,
            String requirementId,
            String labelVi,
            String status,
            List<String> evidenceIds
    ) {
        public WritingTaskCoverageView {
            if (questionId == null
                    || requirementId == null || requirementId.isBlank()
                    || labelVi == null || labelVi.isBlank()
                    || status == null
                    || !Set.of(
                            "MET", "PARTIAL",
                            "NOT_MET", "NOT_APPLICABLE").contains(status)) {
                throw new IllegalArgumentException(
                        "Writing task coverage view is incomplete");
            }
            evidenceIds = immutableResultList(evidenceIds);
            if (evidenceIds.stream().anyMatch(
                    id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException(
                        "Writing task coverage evidence ID is invalid");
            }
        }

        public String statusLabelVi() {
            return switch (status) {
                case "MET" -> "Đã đáp ứng";
                case "PARTIAL" -> "Đáp ứng một phần";
                case "NOT_MET" -> "Chưa đáp ứng";
                case "NOT_APPLICABLE" -> "Không áp dụng";
                default -> throw new IllegalStateException(
                        "Unknown Writing coverage status");
            };
        }

        public String stateCssClass() {
            return switch (status) {
                case "MET" -> "is-met";
                case "PARTIAL" -> "is-partial";
                case "NOT_MET" -> "is-not-met";
                case "NOT_APPLICABLE" -> "is-not-applicable";
                default -> throw new IllegalStateException(
                        "Unknown Writing coverage status");
            };
        }

        public int evidenceCount() {
            return evidenceIds.size();
        }
    }

    public enum WritingDiagnosticTargetKind {
        WHOLE_ANSWER,
        TEXT_SPAN,
        BLANK
    }

    public record WritingDiagnosticTarget(
            WritingDiagnosticTargetKind kind,
            String blankId,
            Integer blankIndex
    ) {
        public WritingDiagnosticTarget {
            if (kind == null) {
                throw new IllegalArgumentException("Writing diagnostic target kind is required");
            }
            if ((kind == WritingDiagnosticTargetKind.WHOLE_ANSWER
                    || kind == WritingDiagnosticTargetKind.TEXT_SPAN)
                    && (blankId != null || blankIndex != null)) {
                throw new IllegalArgumentException(
                        "Non-blank diagnostics cannot fabricate a blank target");
            }
            if (kind == WritingDiagnosticTargetKind.BLANK
                    && (blankId == null || blankId.isBlank()
                    || blankIndex == null || blankIndex < 1)) {
                throw new IllegalArgumentException(
                        "Blank diagnostics require an authoritative blank identity");
            }
        }
    }

    public record WritingDiagnosticFinding(
            Long questionId,
            String findingId,
            int displayNumber,
            String evidenceId,
            Integer startOffset,
            Integer endOffset,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String operation,
            String errorCategory,
            List<String> requirementIds,
            String categoryCode,
            String categoryLabelVi,
            String categoryLabelKo,
            int categoryOrder,
            String featureCode,
            String subtype,
            String featureLabelVi,
            String featureLabelKo,
            int featureOrder,
            ResultDetailPolarity polarity,
            String parentCriterionId,
            String scoreEffect,
            String applicability,
            WritingDiagnosticTarget target,
            String evidenceAvailability,
            String evidenceScope,
            String evidence,
            String explanationVi,
            String correctionKo,
            String impact,
            Integer frequency,
            BigDecimal confidence,
            String observability
    ) {
        public WritingDiagnosticFinding {
            if (questionId == null
                    || findingId == null || findingId.isBlank()
                    || displayNumber < 1
                    || operation == null
                    || !Set.of(
                            "KEEP", "MISSING",
                            "REPLACE", "REDUNDANT").contains(operation)
                    || errorCategory == null || errorCategory.isBlank()
                    || categoryCode == null
                    || !Set.of(
                            "TASK_CONTENT",
                            "DISCOURSE",
                            "MORPHOSYNTAX",
                            "LEXICO_SEMANTIC",
                            "SOCIOLINGUISTIC_PRAGMATIC",
                            "ORTHOGRAPHY",
                            "LENGTH_FORMAT").contains(categoryCode)
                    || categoryLabelVi == null || categoryLabelVi.isBlank()
                    || categoryLabelKo == null || categoryLabelKo.isBlank()
                    || categoryOrder <= 0
                    || featureCode == null || featureCode.isBlank()
                    || subtype == null || subtype.isBlank()
                    || featureLabelVi == null || featureLabelVi.isBlank()
                    || featureLabelKo == null || featureLabelKo.isBlank()
                    || featureOrder <= 0 || polarity == null
                    || scoreEffect == null || scoreEffect.isBlank()
                    || applicability == null || applicability.isBlank()
                    || target == null
                    || evidenceAvailability == null
                    || !Set.of(
                            "EXACT_TEXT_AVAILABLE",
                            "WHOLE_ANSWER_AVAILABLE").contains(evidenceAvailability)
                    || evidenceScope == null
                    || !Set.of("TEXT_SPAN", "WHOLE_ANSWER").contains(evidenceScope)
                    || explanationVi == null || explanationVi.isBlank()
                    || impact == null
                    || !Set.of(
                            "MINOR", "MODERATE",
                            "MAJOR", "BLOCKING").contains(impact)
                    || frequency == null || frequency < 1
                    || confidence == null
                    || confidence.compareTo(BigDecimal.ZERO) < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0
                    || observability == null
                    || !Set.of(
                            "DIRECT", "INFERRED_BOUNDED",
                            "NOT_OBSERVABLE").contains(observability)) {
                throw new IllegalArgumentException(
                        "Writing diagnostic finding is incomplete");
            }
            requirementIds = requirementIds == null
                    ? List.of()
                    : List.copyOf(requirementIds);
            if ("PARENT_LINKED".equals(scoreEffect)
                    && (parentCriterionId == null || parentCriterionId.isBlank())) {
                throw new IllegalArgumentException(
                        "Parent-linked Writing diagnostics require score authority");
            }
            if ("DIAGNOSTIC_ONLY".equals(scoreEffect)
                    && parentCriterionId != null) {
                throw new IllegalArgumentException(
                        "Diagnostic-only Writing findings cannot own a score parent");
            }
            if (!"PARENT_LINKED".equals(scoreEffect)
                    && !"DIAGNOSTIC_ONLY".equals(scoreEffect)) {
                throw new IllegalArgumentException(
                        "Unknown Writing diagnostic score effect");
            }
            if ("TEXT_SPAN".equals(evidenceScope)
                    && (evidence == null || evidence.isBlank()
                    || evidenceId == null || evidenceId.isBlank()
                    || startOffset == null || startOffset < 0
                    || endOffset == null || endOffset <= startOffset
                    || occurrenceIndex == null || occurrenceIndex < 1
                    || occurrenceCount == null
                    || occurrenceCount < occurrenceIndex)) {
                throw new IllegalArgumentException(
                        "Text-span Writing diagnostics require exact evidence");
            }
            if ("WHOLE_ANSWER".equals(evidenceScope)
                    && (evidence != null && !evidence.isEmpty()
                    || evidenceId != null
                    || startOffset != null
                    || endOffset != null
                    || occurrenceIndex != null
                    || occurrenceCount != null)) {
                throw new IllegalArgumentException(
                        "Whole-answer Writing diagnostics cannot fake a highlight");
            }
            if (("TEXT_SPAN".equals(evidenceScope)
                    && !"EXACT_TEXT_AVAILABLE".equals(evidenceAvailability))
                    || ("WHOLE_ANSWER".equals(evidenceScope)
                    && !"WHOLE_ANSWER_AVAILABLE".equals(evidenceAvailability))) {
                throw new IllegalArgumentException(
                        "Writing evidence scope and availability are inconsistent");
            }
            if (("TEXT_SPAN".equals(evidenceScope)
                    && !"DIRECT".equals(observability))
                    || ("WHOLE_ANSWER".equals(evidenceScope)
                    && !"DIRECT".equals(observability)
                    && !"INFERRED_BOUNDED".equals(observability))) {
                throw new IllegalArgumentException(
                        "Writing observability and evidence scope are inconsistent");
            }
        }

        public String descriptorId() {
            String id = featureCode + "_" + applicability;
            if (target.kind() == WritingDiagnosticTargetKind.BLANK) {
                id += "_BLANK_" + target.blankIndex();
            }
            return id;
        }

        public WritingDiagnosticFinding withDisplayNumber(int number) {
            return new WritingDiagnosticFinding(
                    questionId, findingId, number, evidenceId, startOffset,
                    endOffset, occurrenceIndex, occurrenceCount, operation,
                    errorCategory, requirementIds, categoryCode,
                    categoryLabelVi, categoryLabelKo, categoryOrder,
                    featureCode, subtype, featureLabelVi, featureLabelKo,
                    featureOrder, polarity, parentCriterionId, scoreEffect,
                    applicability, target, evidenceAvailability, evidenceScope,
                    evidence, explanationVi, correctionKo, impact, frequency,
                    confidence, observability);
        }

        public ResultDetailSpanMembership spanMembership() {
            if (!"TEXT_SPAN".equals(evidenceScope)) {
                return null;
            }
            return new ResultDetailSpanMembership(
                    findingId, evidenceId, descriptorId(), featureCode, polarity,
                    startOffset, endOffset, occurrenceIndex, occurrenceCount,
                    operation, displayNumber, evidence, explanationVi,
                    correctionKo);
        }
    }

    public record WritingDiagnosticChip(
            String id,
            String labelVi,
            String labelKo,
            ResultDetailPolarity polarity,
            String parentCriterionId,
            String scoreEffect,
            String applicability,
            int stableOrder,
            int count,
            boolean countedSeparately,
            String evidenceAvailability
    ) {
        public WritingDiagnosticChip {
            if (id == null || id.isBlank()
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()
                    || polarity == null
                    || scoreEffect == null || scoreEffect.isBlank()
                    || applicability == null || applicability.isBlank()
                    || stableOrder <= 0 || count < 0
                    || evidenceAvailability == null || evidenceAvailability.isBlank()) {
                throw new IllegalArgumentException(
                        "Writing diagnostic chip is incomplete");
            }
            if (countedSeparately) {
                throw new IllegalArgumentException(
                        "Writing diagnostic chip counts are non-additive navigation metadata");
            }
            if (scoreEffect == null
                    || !Set.of("PARENT_LINKED", "DIAGNOSTIC_ONLY").contains(scoreEffect)) {
                throw new IllegalArgumentException(
                        "Unknown Writing diagnostic chip score effect");
            }
            if (evidenceAvailability == null
                    || !Set.of(
                    "EXACT_TEXT_AVAILABLE",
                    "WHOLE_ANSWER_AVAILABLE",
                    "MIXED_EVIDENCE_AVAILABLE",
                    "NO_FINDING").contains(evidenceAvailability)) {
                throw new IllegalArgumentException(
                        "Unknown Writing diagnostic chip evidence availability");
            }
            if ("PARENT_LINKED".equals(scoreEffect)
                    && (parentCriterionId == null || parentCriterionId.isBlank())) {
                throw new IllegalArgumentException(
                        "Parent-linked Writing chips require score authority");
            }
            if ("DIAGNOSTIC_ONLY".equals(scoreEffect) && parentCriterionId != null) {
                throw new IllegalArgumentException(
                        "Diagnostic-only Writing chips cannot own a score parent");
            }
        }
    }

    public record WritingDiagnosticGroup(
            String categoryCode,
            String labelVi,
            String labelKo,
            int stableOrder,
            List<WritingDiagnosticFinding> strengths,
            List<WritingDiagnosticFinding> needsImprovement,
            List<WritingDiagnosticChip> strengthChips,
            List<WritingDiagnosticChip> needsImprovementChips
    ) {
        public WritingDiagnosticGroup {
            if (categoryCode == null
                    || !Set.of(
                    "TASK_CONTENT",
                    "DISCOURSE",
                    "MORPHOSYNTAX",
                    "LEXICO_SEMANTIC",
                    "SOCIOLINGUISTIC_PRAGMATIC",
                    "ORTHOGRAPHY",
                    "LENGTH_FORMAT").contains(categoryCode)
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()
                    || stableOrder <= 0) {
                throw new IllegalArgumentException(
                        "Writing diagnostic group is incomplete");
            }
            strengths = immutableResultList(strengths);
            needsImprovement = immutableResultList(needsImprovement);
            strengthChips = immutableResultList(strengthChips);
            needsImprovementChips = immutableResultList(needsImprovementChips);
            if (strengths.stream().anyMatch(finding ->
                    finding.polarity() != ResultDetailPolarity.STRENGTH
                            || !categoryCode.equals(finding.categoryCode()))
                    || needsImprovement.stream().anyMatch(finding ->
                    finding.polarity() != ResultDetailPolarity.NEEDS_IMPROVEMENT
                            || !categoryCode.equals(finding.categoryCode()))
                    || strengthChips.stream().anyMatch(chip ->
                    chip.polarity() != ResultDetailPolarity.STRENGTH)
                    || needsImprovementChips.stream().anyMatch(chip ->
                    chip.polarity() != ResultDetailPolarity.NEEDS_IMPROVEMENT)) {
                throw new IllegalArgumentException(
                        "Writing diagnostic group polarity/category is inconsistent");
            }
        }

        public boolean hasStrengths() {
            return !strengths.isEmpty();
        }

        public boolean hasNeedsImprovement() {
            return !needsImprovement.isEmpty();
        }
    }

    public record WritingAnswerArtifact(
            String content,
            String availability,
            String provenance,
            String labelVi,
            String labelKo
    ) {
        public WritingAnswerArtifact {
            content = content == null ? "" : content;
            if (availability == null
                    || !Set.of(
                    "AVAILABLE",
                    "UNAVAILABLE").contains(availability)
                    || provenance == null
                    || !Set.of(
                    "LEARNER_SUBMISSION_DERIVED_EVALUATOR_OUTPUT",
                    "EVALUATOR_GENERATED_NOT_TEACHER_REFERENCE",
                    "NOT_PROVIDED_BY_CURRENT_EVALUATOR").contains(provenance)
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()) {
                throw new IllegalArgumentException(
                        "Writing answer provenance is incomplete");
            }
            if ("AVAILABLE".equals(availability) && content.isBlank()) {
                throw new IllegalArgumentException(
                        "Available Writing answer provenance requires content");
            }
            if (!"AVAILABLE".equals(availability) && !content.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unavailable Writing answer provenance cannot expose content");
            }
        }

        public boolean available() {
            return "AVAILABLE".equals(availability);
        }
    }

    public record WritingUpgradeView(
            Long questionId,
            WritingAnswerArtifact learnerDerivedUpgrade,
            List<WritingSentenceRewriteView> significantRewrites,
            WritingAnswerArtifact evaluatorSample
    ) {
        public WritingUpgradeView {
            if (questionId == null || learnerDerivedUpgrade == null
                    || evaluatorSample == null) {
                throw new IllegalArgumentException(
                        "Writing upgrade provenance is incomplete");
            }
            significantRewrites = immutableResultList(significantRewrites);
            if (significantRewrites.stream().anyMatch(rewrite ->
                    rewrite.original() == null || rewrite.original().isBlank()
                            || rewrite.upgraded() == null || rewrite.upgraded().isBlank()
                            || rewrite.reason() == null || rewrite.reason().isBlank())) {
                throw new IllegalArgumentException(
                        "Writing significant rewrite is incomplete");
            }
        }
    }

    public record SpeakingTextSegment(
            String text,
            boolean annotated,
            String kind,
            String findingId,
            String descriptorId,
            String featureId,
            String explanationVi,
            String correctionKo,
            List<ResultDetailSpanMembership> memberships
    ) {
        public SpeakingTextSegment {
            memberships = memberships == null ? List.of() : List.copyOf(memberships);
            if (text == null) {
                throw new IllegalArgumentException(
                        "Speaking transcript segment text is required");
            }
            if (annotated) {
                if (text.isEmpty()
                        || kind == null
                        || !Set.of("STRENGTH", "NEEDS_IMPROVEMENT").contains(kind)
                        || findingId == null || findingId.isBlank()
                        || descriptorId == null || descriptorId.isBlank()
                        || featureId == null || featureId.isBlank()
                        || explanationVi == null || explanationVi.isBlank()
                        || memberships.isEmpty()
                        || memberships.stream().noneMatch(membership ->
                        findingId.equals(membership.findingId())
                                && descriptorId.equals(membership.descriptorId()))
                        || ("STRENGTH".equals(kind) && correctionKo != null)
                        || ("NEEDS_IMPROVEMENT".equals(kind)
                        && (correctionKo == null || correctionKo.isBlank()))) {
                    throw new IllegalArgumentException(
                            "Annotated Speaking transcript segment is incomplete");
                }
            } else if (kind != null
                    || findingId != null
                    || descriptorId != null
                    || featureId != null
                    || explanationVi != null
                    || correctionKo != null
                    || !memberships.isEmpty()) {
                throw new IllegalArgumentException(
                        "Plain Speaking transcript segment cannot carry annotation metadata");
            }
        }

        public SpeakingTextSegment(
                String text,
                boolean annotated,
                String kind,
                String findingId,
                String descriptorId,
                String featureId,
                String explanationVi,
                String correctionKo
        ) {
            this(text, annotated, kind, findingId, descriptorId, featureId,
                    explanationVi, correctionKo, List.of());
        }

        public String featureIds() {
            return memberships.stream()
                    .map(ResultDetailSpanMembership::descriptorId)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        public String findingIds() {
            return memberships.stream()
                    .map(ResultDetailSpanMembership::findingId)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        public static SpeakingTextSegment plain(String text) {
            return new SpeakingTextSegment(
                    text == null ? "" : text,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of());
        }
    }

    public record SpeakingTeacherSampleView(
            String content,
            String availability,
            String source,
            String authorRole,
            String fixtureId
    ) {
        public SpeakingTeacherSampleView {
            content = content == null ? "" : content;
            if (!Set.of("AVAILABLE", "UNAVAILABLE").contains(availability)) {
                throw new IllegalArgumentException(
                        "Speaking teacher sample availability is invalid");
            }
            if ("AVAILABLE".equals(availability)) {
                if (content.isBlank()
                        || !"TEACHER_AUTHORED".equals(source)
                        || !"LECTURER".equals(authorRole)
                        || fixtureId == null || fixtureId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Available Speaking teacher sample requires authored provenance");
                }
            } else if (!content.isEmpty()
                    || !"NOT_AVAILABLE".equals(source)
                    || authorRole != null
                    || fixtureId != null) {
                throw new IllegalArgumentException(
                        "Unavailable Speaking teacher sample cannot expose content or authorship");
            }
        }

        public boolean available() {
            return "AVAILABLE".equals(availability);
        }

        public static SpeakingTeacherSampleView unavailable() {
            return new SpeakingTeacherSampleView(
                    "", "UNAVAILABLE", "NOT_AVAILABLE", null, null);
        }
    }

    public record SpeakingDetailPayload(
            ResultFeedbackAvailability feedback,
            List<SpeakingTaskDetail> tasks,
            Long activeQuestionId,
            String scoreProfileId,
            String profileState,
            String evidenceMode,
            String evaluatorCapability,
            String evidenceNote,
            String taskScoreState,
            List<ResultDetailScoreCriterion> scoreCriteria,
            SpeakingEvidenceView evidence,
            List<SpeakingTextSegment> transcriptSegments,
            String diagnosticAvailability,
            String diagnosticScopeNoteVi,
            String diagnosticScopeNoteKo,
            String diagnosticAvailabilityNoteVi,
            String diagnosticAvailabilityNoteKo,
            List<SpeakingDiagnosticGroup> diagnosticGroups,
            SpeakingUpgradeView upgrade,
            SpeakingTeacherSampleView teacherSample
    ) implements ResultDetailPayload {
        public SpeakingDetailPayload {
            if (feedback == null
                    || scoreProfileId == null || scoreProfileId.isBlank()
                    || profileState == null || profileState.isBlank()
                    || evidenceMode == null || evidenceMode.isBlank()
                    || evaluatorCapability == null || evaluatorCapability.isBlank()
                    || evidenceNote == null || evidenceNote.isBlank()
                    || taskScoreState == null || taskScoreState.isBlank()
                    || diagnosticAvailability == null || diagnosticAvailability.isBlank()
                    || diagnosticScopeNoteVi == null || diagnosticScopeNoteVi.isBlank()
                    || diagnosticScopeNoteKo == null || diagnosticScopeNoteKo.isBlank()
                    || diagnosticAvailabilityNoteVi == null
                    || diagnosticAvailabilityNoteVi.isBlank()
                    || diagnosticAvailabilityNoteKo == null
                    || diagnosticAvailabilityNoteKo.isBlank()) {
                throw new IllegalArgumentException(
                        "Speaking Result Detail contract is incomplete");
            }
            tasks = immutableResultList(tasks);
            scoreCriteria = immutableResultList(scoreCriteria);
            transcriptSegments = immutableResultList(transcriptSegments);
            diagnosticGroups = immutableResultList(diagnosticGroups);
            if (teacherSample == null) {
                throw new IllegalArgumentException(
                        "Speaking teacher sample state is required");
            }
            List<ResultDetailDiagnosticFinding> allDiagnosticFindings =
                    diagnosticGroups.stream()
                            .flatMap(group -> java.util.stream.Stream.concat(
                                    group.strengths().stream(),
                                    group.needsImprovement().stream()))
                            .toList();
            List<ResultDetailFilterChip> allFilterChips = diagnosticGroups.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            group.strengthChips().stream(),
                            group.needsImprovementChips().stream()))
                    .toList();
            if (new LinkedHashSet<>(tasks.stream()
                    .map(SpeakingTaskDetail::questionId).toList()).size()
                    != tasks.size()
                    || new LinkedHashSet<>(tasks.stream()
                    .map(SpeakingTaskDetail::questionVersionId).toList()).size()
                    != tasks.size()) {
                throw new IllegalArgumentException(
                        "Speaking immutable task navigation must be unique");
            }
            if (activeQuestionId == null) {
                if (!scoreCriteria.isEmpty() || evidence != null || upgrade != null
                        || !transcriptSegments.isEmpty() || !diagnosticGroups.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Speaking detail artifacts require a selected immutable task");
                }
            }
            if (activeQuestionId != null && tasks.stream().noneMatch(task ->
                    activeQuestionId.equals(task.questionId()) && task.detailAvailable())) {
                throw new IllegalArgumentException(
                        "Speaking Result Detail question selection is outside the immutable attempt");
            }
            if (activeQuestionId != null && tasks.stream().anyMatch(task ->
                    !activeQuestionId.equals(task.questionId())
                            && (!task.prompt().isBlank()
                            || task.hasLearnerSubmissionText()
                            || !task.summary().isBlank()
                            || !"NAVIGATION_ONLY".equals(task.submissionState())
                            || !"NAVIGATION_ONLY".equals(task.evaluationState())))) {
                throw new IllegalArgumentException(
                        "Non-selected Speaking tasks may expose navigation identity only");
            }
            if (activeQuestionId != null && scoreCriteria.size() != 6) {
                throw new IllegalArgumentException(
                        "Speaking Result Detail must preserve all six criterion states");
            }
            List<String> expected = List.of(
                    "S_CONTENT_TASK_FULFILLMENT",
                    "S_GRAMMAR_SENTENCE_CONTROL",
                    "S_VOCABULARY_EXPRESSIONS",
                    "S_COHERENCE_ORGANIZATION",
                    "S_FLUENCY",
                    "S_PRONUNCIATION_DELIVERY");
            if (activeQuestionId != null
                    && !scoreCriteria.stream()
                    .map(ResultDetailScoreCriterion::criterionId).toList()
                    .equals(expected)) {
                throw new IllegalArgumentException(
                        "Speaking Result Detail criterion order is not canonical");
            }
            if (activeQuestionId != null && scoreCriteria.stream().anyMatch(criterion ->
                    !activeQuestionId.equals(criterion.questionId()))) {
                throw new IllegalArgumentException(
                        "Speaking score criteria must belong to the selected immutable task");
            }
            if (activeQuestionId != null
                    && (evidence == null || !activeQuestionId.equals(evidence.questionId())
                    || upgrade == null || !activeQuestionId.equals(upgrade.questionId()))) {
                throw new IllegalArgumentException(
                        "Speaking evidence and upgrade must belong to the selected immutable task");
            }
            if (activeQuestionId != null) {
                String transcript = evidence.transcriptText() == null
                        ? ""
                        : evidence.transcriptText();
                String reconstructedTranscript = transcriptSegments.stream()
                        .map(SpeakingTextSegment::text)
                        .collect(java.util.stream.Collectors.joining());
                boolean hasAnnotatedSegment = transcriptSegments.stream()
                        .anyMatch(SpeakingTextSegment::annotated);
                if (transcriptSegments.isEmpty()
                        || !transcript.equals(reconstructedTranscript)
                        || (transcriptSegments.size() > 1
                        && transcriptSegments.stream()
                                .anyMatch(segment -> segment.text().isEmpty()))
                        || (!hasAnnotatedSegment && transcriptSegments.size() != 1)
                        || (hasAnnotatedSegment
                        && (!evidence.transcriptAvailable()
                        || !"TRANSCRIPT_ONLY".equals(evidenceMode)
                        || !"TRANSCRIPT_GROUNDED_LANGUAGE_EVALUATION"
                                .equals(evaluatorCapability)))) {
                    throw new IllegalArgumentException(
                            "Speaking transcript segments must exactly preserve current trusted evidence");
                }
            }
            if (activeQuestionId != null
                    && !"DIRECT_AUDIO_AND_TRANSCRIPT".equals(evidenceMode)) {
                if (!"NOT_SCORABLE".equals(
                        evidence.acousticEvidenceAvailability())) {
                    throw new IllegalArgumentException(
                            "Speaking without direct-audio scoring must keep acoustic evidence NOT_SCORABLE");
                }
                for (int index = 4; index < 6; index++) {
                    ResultDetailScoreCriterion acoustic = scoreCriteria.get(index);
                    if (!Set.of("NOT_SCORABLE", "LEGACY_UNVERIFIED", "UNAVAILABLE")
                            .contains(acoustic.availability())
                            || acoustic.score() != null || acoustic.maxScore() != null) {
                        throw new IllegalArgumentException(
                                "Speaking acoustic criteria require governed direct-audio evidence");
                    }
                }
            }
            if (activeQuestionId != null && "TRANSCRIPT_ONLY".equals(evidenceMode)) {
                for (int index = 4; index < 6; index++) {
                    if (!"NOT_SCORABLE".equals(scoreCriteria.get(index).availability())) {
                        throw new IllegalArgumentException(
                                "Transcript-only Speaking acoustic criteria must be NOT_SCORABLE");
                    }
                }
            }
            if (activeQuestionId != null
                    && allDiagnosticFindings.stream().anyMatch(finding ->
                    !activeQuestionId.equals(finding.questionId()))) {
                throw new IllegalArgumentException(
                        "Speaking diagnostics must belong only to the selected immutable task");
            }
            if (activeQuestionId != null
                    && !"DIRECT_AUDIO_AND_TRANSCRIPT".equals(evidenceMode)
                    && allFilterChips.stream().anyMatch(chip ->
                    chip.parentCriterionId().equals("S_FLUENCY")
                            || chip.parentCriterionId()
                            .equals("S_PRONUNCIATION_DELIVERY"))) {
                throw new IllegalArgumentException(
                        "Speaking without direct-audio scoring cannot expose acoustic diagnostic chips");
            }
            if (activeQuestionId != null
                    && upgrade.significantRewrites().stream().anyMatch(rewrite ->
                    evidence.transcriptText().isBlank()
                            || !evidence.transcriptText().contains(rewrite.original()))) {
                throw new IllegalArgumentException(
                        "Speaking rewrites must preserve an exact authoritative transcript span");
            }
            if (activeQuestionId != null
                    && !evidence.transcriptAvailable()
                    && (upgrade.learnerDerivedUpgrade().available()
                    || upgrade.evaluatorSample().available())) {
                throw new IllegalArgumentException(
                        "Speaking upgrade artifacts require an authoritative transcript");
            }
        }

        public SpeakingDetailPayload(
                ResultFeedbackAvailability feedback,
                List<SpeakingTaskDetail> tasks,
                Long activeQuestionId,
                String scoreProfileId,
                String profileState,
                String evidenceMode,
                String evaluatorCapability,
                String evidenceNote,
                String taskScoreState,
                List<ResultDetailScoreCriterion> scoreCriteria,
                SpeakingEvidenceView evidence,
                List<SpeakingTextSegment> transcriptSegments,
                String diagnosticAvailability,
                String diagnosticScopeNoteVi,
                String diagnosticScopeNoteKo,
                String diagnosticAvailabilityNoteVi,
                String diagnosticAvailabilityNoteKo,
                List<SpeakingDiagnosticGroup> diagnosticGroups,
                SpeakingUpgradeView upgrade
        ) {
            this(
                    feedback,
                    tasks,
                    activeQuestionId,
                    scoreProfileId,
                    profileState,
                    evidenceMode,
                    evaluatorCapability,
                    evidenceNote,
                    taskScoreState,
                    scoreCriteria,
                    evidence,
                    transcriptSegments,
                    diagnosticAvailability,
                    diagnosticScopeNoteVi,
                    diagnosticScopeNoteKo,
                    diagnosticAvailabilityNoteVi,
                    diagnosticAvailabilityNoteKo,
                    diagnosticGroups,
                    upgrade,
                    SpeakingTeacherSampleView.unavailable());
        }

        public List<ResultDetailDiagnosticFinding> diagnosticFindings() {
            return diagnosticGroups.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            group.strengths().stream(),
                            group.needsImprovement().stream()))
                    .toList();
        }

        public List<ResultDetailFilterChip> filterChips() {
            return diagnosticGroups.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            group.strengthChips().stream(),
                            group.needsImprovementChips().stream()))
                    .toList();
        }

        public boolean hasStrengthFindings() {
            return diagnosticGroups.stream().anyMatch(SpeakingDiagnosticGroup::hasStrengths);
        }

        public boolean hasNeedsImprovementFindings() {
            return diagnosticGroups.stream()
                    .anyMatch(SpeakingDiagnosticGroup::hasNeedsImprovement);
        }

        public boolean hasUpgradeForDescriptor(String descriptorId) {
            if (descriptorId == null || descriptorId.isBlank()
                    || upgrade == null) {
                return false;
            }
            Set<String> rewriteFindingIds = upgrade.significantRewrites()
                    .stream()
                    .map(SpeakingPhraseRewriteView::findingId)
                    .collect(java.util.stream.Collectors.toSet());
            return diagnosticFindings().stream().anyMatch(finding ->
                    descriptorId.equals(finding.descriptorId())
                            && rewriteFindingIds.contains(
                            finding.findingId()));
        }

        public boolean hasUpgradeForGroup(String categoryCode) {
            if (categoryCode == null || categoryCode.isBlank()) {
                return false;
            }
            return diagnosticGroups.stream()
                    .filter(group -> categoryCode.equals(
                            group.categoryCode()))
                    .flatMap(group -> group.needsImprovementChips()
                            .stream())
                    .anyMatch(chip -> hasUpgradeForDescriptor(chip.id()));
        }

        public String profileStateLabelVi() {
            return switch (profileState) {
                case "READY" -> "Hồ sơ đã sẵn sàng";
                case "LOW_CONFIDENCE" -> "Bản chép lời có độ tin cậy thấp";
                case "PENDING" -> "Bằng chứng đang được xử lý";
                case "LEGACY_UNVERIFIED" -> "Dữ liệu tương thích cũ chưa được xác minh";
                case "FAILED" -> "Chưa thể tạo hồ sơ";
                default -> "Chưa có hồ sơ khả dụng";
            };
        }

        public String profileStateLabelKo() {
            return switch (profileState) {
                case "READY" -> "프로필 준비 완료";
                case "LOW_CONFIDENCE" -> "전사 신뢰도 낮음";
                case "PENDING" -> "근거 처리 중";
                case "LEGACY_UNVERIFIED" -> "이전 호환 데이터 미검증";
                case "FAILED" -> "프로필 생성 불가";
                default -> "사용 가능한 프로필 없음";
            };
        }

        public String evidenceModeLabelVi() {
            return switch (evidenceMode) {
                case "TRANSCRIPT_ONLY" -> "Chỉ dựa trên bản chép lời";
                case "RECORDING_SOURCE_ONLY" -> "Chỉ xác nhận nguồn bản ghi";
                case "DIRECT_AUDIO_AND_TRANSCRIPT" ->
                        "Âm thanh trực tiếp và bản chép lời";
                default -> "Chưa xác định được nguồn bằng chứng";
            };
        }

        public String evidenceModeLabelKo() {
            return switch (evidenceMode) {
                case "TRANSCRIPT_ONLY" -> "전사문 기반 평가";
                case "RECORDING_SOURCE_ONLY" -> "녹음 출처만 확인";
                case "DIRECT_AUDIO_AND_TRANSCRIPT" -> "직접 음성과 전사문";
                default -> "근거 출처 미확인";
            };
        }

        public String taskScoreStateLabelVi() {
            return switch (taskScoreState) {
                case "LANGUAGE_CRITERIA_AVAILABLE_NO_TASK_TOTAL" ->
                        "Có điểm theo tiêu chí ngôn ngữ; không tạo điểm tổng nhiệm vụ";
                case "PENDING" -> "Chưa có điểm khi bằng chứng đang xử lý";
                case "LEGACY_UNVERIFIED" -> "Điểm cũ không đủ điều kiện hiển thị";
                default -> "Chưa có điểm nhiệm vụ khả dụng";
            };
        }

        public String taskScoreStateLabelKo() {
            return switch (taskScoreState) {
                case "LANGUAGE_CRITERIA_AVAILABLE_NO_TASK_TOTAL" ->
                        "언어 기준별 점수만 제공하며 과제 총점은 산출하지 않음";
                case "PENDING" -> "근거 처리 중에는 점수를 제공하지 않음";
                case "LEGACY_UNVERIFIED" -> "이전 점수는 표시 조건을 충족하지 않음";
                default -> "사용 가능한 과제 점수 없음";
            };
        }

        public String evaluatorCapabilityLabelVi() {
            return "TRANSCRIPT_GROUNDED_LANGUAGE_EVALUATION".equals(evaluatorCapability)
                    ? "Bộ đánh giá chỉ nhận văn bản chép lời, không nghe bản ghi"
                    : "Chưa có bộ đánh giá âm thanh trực tiếp đã được phê duyệt";
        }

        public String evaluatorCapabilityLabelKo() {
            return "TRANSCRIPT_GROUNDED_LANGUAGE_EVALUATION".equals(evaluatorCapability)
                    ? "평가기는 전사문만 사용하며 녹음을 직접 듣지 않음"
                    : "승인된 직접 음성 평가기 없음";
        }

        @Override
        public ResultDetailScreenKind screenKind() {
            return ResultDetailScreenKind.SPEAKING_DETAIL;
        }
    }

    public record SpeakingTaskDetail(
            Long questionId,
            Long questionVersionId,
            Integer questionNo,
            String questionType,
            String compatibilityMode,
            String prompt,
            String learnerSubmissionText,
            String submissionState,
            String evaluationState,
            String summary,
            String languageTag
    ) {
        public SpeakingTaskDetail {
            learnerSubmissionText = blankResultText(learnerSubmissionText);
            summary = blankResultText(summary);
            languageTag = "ko".equals(languageTag) || "vi".equals(languageTag)
                    ? languageTag
                    : "ko";
            if (questionId == null || questionVersionId == null || questionNo == null
                    || questionType == null
                    || !"SPEAKING".equals(questionType)
                    || compatibilityMode == null
                    || !"CANONICAL_SPEAKING".equals(compatibilityMode)
                    || prompt == null
                    || submissionState == null || submissionState.isBlank()
                    || evaluationState == null || evaluationState.isBlank()
                    || "AUDIO_SUBMITTED".equalsIgnoreCase(learnerSubmissionText.trim())) {
                throw new IllegalArgumentException(
                        "Speaking immutable task identity/provenance is incomplete");
            }
        }

        public SpeakingTaskDetail(
                Long questionId,
                Long questionVersionId,
                Integer questionNo,
                String questionType,
                String compatibilityMode,
                String prompt,
                String learnerSubmissionText,
                String submissionState,
                String evaluationState,
                String summary
        ) {
            this(questionId, questionVersionId, questionNo, questionType,
                    compatibilityMode, prompt, learnerSubmissionText,
                    submissionState, evaluationState, summary, "ko");
        }

        public boolean detailAvailable() {
            return true;
        }

        public boolean canonicalSpeaking() {
            return "CANONICAL_SPEAKING".equals(compatibilityMode);
        }

        public boolean hasLearnerSubmissionText() {
            return !learnerSubmissionText.isBlank();
        }

        public String taskLabelVi() {
            return (canonicalSpeaking() ? "Câu Nói " : "Câu Nói cũ ")
                    + questionNo;
        }

        public String taskLabelKo() {
            return (canonicalSpeaking() ? "말하기 " : "이전 말하기 ")
                    + questionNo + "번";
        }

        public String compatibilityLabelVi() {
            return canonicalSpeaking()
                    ? "Nhiệm vụ Nói chuẩn hiện hành"
                    : "Dữ liệu Nói cũ lưu theo dạng bài tự luận";
        }

        public String compatibilityLabelKo() {
            return canonicalSpeaking()
                    ? "현재 표준 말하기 과제"
                    : "서술형으로 저장된 이전 말하기 데이터";
        }

        public String submissionStateLabelVi() {
            return switch (submissionState) {
                case "AUDIO_SOURCE_WITH_AUTHORITATIVE_TRANSCRIPT" ->
                        "Đã nộp bản ghi và có bản chép lời đủ thẩm quyền";
                case "AUDIO_SOURCE_TRANSCRIPT_UNAVAILABLE" ->
                        "Đã nộp bản ghi; chưa có bản chép lời đủ thẩm quyền";
                case "TEXT_COMPATIBILITY" ->
                        "Nội dung văn bản tương thích, không phải bằng chứng âm thanh";
                default -> "Chưa có câu trả lời đã nộp";
            };
        }

        public String submissionStateLabelKo() {
            return switch (submissionState) {
                case "AUDIO_SOURCE_WITH_AUTHORITATIVE_TRANSCRIPT" ->
                        "녹음 제출 완료 · 권한 있는 전사문 있음";
                case "AUDIO_SOURCE_TRANSCRIPT_UNAVAILABLE" ->
                        "녹음 제출 완료 · 권한 있는 전사문 없음";
                case "TEXT_COMPATIBILITY" ->
                        "텍스트 호환 내용 · 음성 근거 아님";
                default -> "제출된 답변 없음";
            };
        }

        public String evaluationStateLabelVi() {
            return switch (evaluationState) {
                case "READY" -> "Phản hồi đã sẵn sàng";
                case "LOW_CONFIDENCE" -> "Bản chép lời có độ tin cậy thấp";
                case "PENDING" -> "Đang xử lý bằng chứng";
                case "LEGACY_UNVERIFIED" -> "Dữ liệu cũ chưa được xác minh";
                case "FAILED" -> "Xử lý phản hồi không thành công";
                default -> "Chưa có phản hồi";
            };
        }

        public String evaluationStateLabelKo() {
            return switch (evaluationState) {
                case "READY" -> "피드백 준비 완료";
                case "LOW_CONFIDENCE" -> "전사 신뢰도 낮음";
                case "PENDING" -> "근거 처리 중";
                case "LEGACY_UNVERIFIED" -> "이전 데이터 미검증";
                case "FAILED" -> "피드백 처리 실패";
                default -> "피드백 없음";
            };
        }
    }

    public record SpeakingAudioTokenAlignmentView(
            String tokenId,
            String evidenceId,
            String exactText,
            Integer startOffset,
            Integer endOffset,
            Long audioStartMs,
            Long audioEndMs,
            String learnerClipPath,
            String referenceClipPath,
            String phonemeEvidence,
            String stressEvidence
    ) {
        public SpeakingAudioTokenAlignmentView {
            if (tokenId == null || tokenId.isBlank()
                    || evidenceId == null || evidenceId.isBlank()
                    || exactText == null || exactText.isBlank()
                    || startOffset == null || startOffset < 0
                    || endOffset == null || endOffset <= startOffset
                    || audioStartMs == null || audioStartMs < 0
                    || audioEndMs == null || audioEndMs <= audioStartMs
                    || learnerClipPath == null || learnerClipPath.isBlank()) {
                throw new IllegalArgumentException(
                        "Speaking token alignment is incomplete");
            }
        }
    }

    public record SpeakingAudioAlignmentView(
            String availability,
            String reasonCode,
            List<SpeakingAudioTokenAlignmentView> tokens
    ) {
        public SpeakingAudioAlignmentView {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
            if (!Set.of("AVAILABLE", "NOT_AVAILABLE").contains(availability)
                    || reasonCode == null || reasonCode.isBlank()
                    || ("AVAILABLE".equals(availability) && tokens.isEmpty())
                    || ("NOT_AVAILABLE".equals(availability) && !tokens.isEmpty())) {
                throw new IllegalArgumentException(
                        "Speaking audio alignment availability is inconsistent");
            }
        }

        public static SpeakingAudioAlignmentView unavailable(String reasonCode) {
            return new SpeakingAudioAlignmentView(
                    "NOT_AVAILABLE", reasonCode, List.of());
        }

        public boolean available() {
            return "AVAILABLE".equals(availability);
        }
    }

    public record SpeakingEvidenceView(
            Long questionId,
            String transcriptText,
            String transcriptAvailability,
            String transcriptSource,
            String transcriptMediaBinding,
            String recordingState,
            Long mediaId,
            Long durationMs,
            Long byteSize,
            String mimeType,
            String playbackPath,
            boolean playbackAvailable,
            String acousticEvidenceAvailability,
            SpeakingAudioAlignmentView audioAlignment
    ) {
        public SpeakingEvidenceView {
            transcriptText = blankResultText(transcriptText);
            playbackPath = blankResultText(playbackPath);
            mimeType = blankResultText(mimeType);
            if (questionId == null
                    || transcriptAvailability == null
                    || !Set.of("AVAILABLE", "UNAVAILABLE")
                    .contains(transcriptAvailability)
                    || transcriptSource == null
                    || !Set.of(
                    "CURRENT_AUTHORITATIVE_TRANSCRIPT",
                    "UNAVAILABLE").contains(transcriptSource)
                    || transcriptMediaBinding == null
                    || !Set.of(
                    "MATCHED_CURRENT_EVALUATION",
                    "UNVERIFIED",
                    "NOT_APPLICABLE").contains(transcriptMediaBinding)
                    || recordingState == null
                    || !Set.of(
                    "READY_OWNER_BOUND_RECORDING",
                    "SUBMISSION_MARKER_ONLY",
                    "UNAVAILABLE").contains(recordingState)
                    || acousticEvidenceAvailability == null
                    || !Set.of(
                    "NOT_SCORABLE",
                    "AVAILABLE_GOVERNED_DIRECT_AUDIO").contains(
                    acousticEvidenceAvailability)
                    || audioAlignment == null
                    || "AUDIO_SUBMITTED".equalsIgnoreCase(transcriptText.trim())) {
                throw new IllegalArgumentException(
                        "Speaking evidence provenance is incomplete");
            }
            if ("AVAILABLE".equals(transcriptAvailability)
                    != !transcriptText.isBlank()
                    || ("AVAILABLE".equals(transcriptAvailability)
                    && !"CURRENT_AUTHORITATIVE_TRANSCRIPT".equals(transcriptSource))
                    || ("UNAVAILABLE".equals(transcriptAvailability)
                    && !"UNAVAILABLE".equals(transcriptSource))) {
                throw new IllegalArgumentException(
                        "Speaking transcript availability/source is inconsistent");
            }
            if (("AVAILABLE".equals(transcriptAvailability)
                    && "NOT_APPLICABLE".equals(transcriptMediaBinding))
                    || ("UNAVAILABLE".equals(transcriptAvailability)
                    && !"NOT_APPLICABLE".equals(transcriptMediaBinding))) {
                throw new IllegalArgumentException(
                        "Speaking transcript/media binding state is inconsistent");
            }
            if ("READY_OWNER_BOUND_RECORDING".equals(recordingState)
                    != (mediaId != null)) {
                throw new IllegalArgumentException(
                        "Speaking recording state requires owner-bound media identity");
            }
            if (playbackAvailable && (mediaId == null || playbackPath.isBlank())) {
                throw new IllegalArgumentException(
                        "Speaking playback requires an owner-bound recording path");
            }
            if (!playbackAvailable && !playbackPath.isBlank()) {
                throw new IllegalArgumentException(
                        "Disabled Speaking playback cannot expose a media path");
            }
            if (!"AVAILABLE_GOVERNED_DIRECT_AUDIO".equals(
                    acousticEvidenceAvailability)
                    && audioAlignment.available()) {
                throw new IllegalArgumentException(
                        "Speaking audio alignment requires governed direct audio");
            }
        }

        public SpeakingEvidenceView(
                Long questionId,
                String transcriptText,
                String transcriptAvailability,
                String transcriptSource,
                String transcriptMediaBinding,
                String recordingState,
                Long mediaId,
                Long durationMs,
                Long byteSize,
                String mimeType,
                String playbackPath,
                boolean playbackAvailable,
                String acousticEvidenceAvailability
        ) {
            this(questionId, transcriptText, transcriptAvailability,
                    transcriptSource, transcriptMediaBinding, recordingState,
                    mediaId, durationMs, byteSize, mimeType, playbackPath,
                    playbackAvailable, acousticEvidenceAvailability,
                    SpeakingAudioAlignmentView.unavailable(
                            "AUTHORITATIVE_TOKEN_ALIGNMENT_UNAVAILABLE"));
        }

        public boolean transcriptAvailable() {
            return "AVAILABLE".equals(transcriptAvailability);
        }

        public boolean recordingAvailable() {
            return "READY_OWNER_BOUND_RECORDING".equals(recordingState);
        }

        public String transcriptSourceLabelVi() {
            return transcriptAvailable()
                    ? "Bản chép lời thuộc hợp đồng bằng chứng hiện tại"
                    : "Chưa có bản chép lời đủ thẩm quyền";
        }

        public String transcriptSourceLabelKo() {
            return transcriptAvailable()
                    ? "현재 근거 계약에 속한 전사문"
                    : "권한 있는 전사문 없음";
        }

        public String recordingStateLabelVi() {
            return switch (recordingState) {
                case "READY_OWNER_BOUND_RECORDING" ->
                        "Có bản ghi thuộc bài làm của người học";
                case "SUBMISSION_MARKER_ONLY" ->
                        "Chỉ có trạng thái đã nộp âm thanh; chưa có bản ghi phát lại";
                default -> "Không có bản ghi khả dụng";
            };
        }

        public String recordingStateLabelKo() {
            return switch (recordingState) {
                case "READY_OWNER_BOUND_RECORDING" ->
                        "학습자 제출에 귀속된 녹음 있음";
                case "SUBMISSION_MARKER_ONLY" ->
                        "음성 제출 상태만 있으며 재생 가능한 녹음 없음";
                default -> "사용 가능한 녹음 없음";
            };
        }

        public String transcriptMediaBindingLabelVi() {
            return switch (transcriptMediaBinding) {
                case "MATCHED_CURRENT_EVALUATION" ->
                        "Bản ghi khớp định danh media của lần tạo bản chép lời hiện tại";
                case "UNVERIFIED" ->
                        "Chưa chứng minh bản ghi phát lại là đúng media đã tạo bản chép lời";
                default -> "Chưa có bản chép lời để đối chiếu media";
            };
        }

        public String transcriptMediaBindingLabelKo() {
            return switch (transcriptMediaBinding) {
                case "MATCHED_CURRENT_EVALUATION" ->
                        "녹음이 현재 전사 생성의 미디어 식별자와 일치함";
                case "UNVERIFIED" ->
                        "재생 녹음과 전사 생성 미디어의 동일성이 확인되지 않음";
                default -> "미디어와 대조할 전사문 없음";
            };
        }

        public String acousticAvailabilityLabelVi() {
            return "AVAILABLE_GOVERNED_DIRECT_AUDIO".equals(acousticEvidenceAvailability)
                    ? "Có bằng chứng âm thanh trực tiếp đã được phê duyệt"
                    : "Chưa thể chấm âm học từ năng lực hiện tại";
        }

        public String acousticAvailabilityLabelKo() {
            return "AVAILABLE_GOVERNED_DIRECT_AUDIO".equals(acousticEvidenceAvailability)
                    ? "승인된 직접 음성 근거 있음"
                    : "현재 평가 역량으로 음향 채점 불가";
        }

        public String durationDisplay() {
            if (durationMs == null || durationMs < 0) {
                return null;
            }
            long seconds = durationMs / 1_000;
            return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }

        public String byteSizeDisplay() {
            if (byteSize == null || byteSize < 0) {
                return null;
            }
            if (byteSize < 1_024) {
                return byteSize + " B";
            }
            return compactResultNumber(
                    BigDecimal.valueOf(byteSize)
                            .divide(BigDecimal.valueOf(1_024), 1, java.math.RoundingMode.HALF_UP))
                    + " KB";
        }
    }

    public record SpeakingDiagnosticGroup(
            String categoryCode,
            String labelVi,
            String labelKo,
            int stableOrder,
            List<ResultDetailDiagnosticFinding> strengths,
            List<ResultDetailDiagnosticFinding> needsImprovement,
            List<ResultDetailFilterChip> strengthChips,
            List<ResultDetailFilterChip> needsImprovementChips
    ) {
        public SpeakingDiagnosticGroup {
            if (categoryCode == null
                    || !Set.of(
                    "TASK_RESPONSE_RELEVANCE",
                    "DISCOURSE_ORGANIZATION",
                    "MORPHOSYNTAX",
                    "LEXICON_COLLOCATION",
                    "SOCIOLINGUISTIC_REGISTER_PRAGMATICS",
                    "FLUENCY_RHYTHM",
                    "PRONUNCIATION_ACOUSTICS").contains(categoryCode)
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()
                    || stableOrder <= 0) {
                throw new IllegalArgumentException(
                        "Speaking diagnostic group is incomplete");
            }
            strengths = immutableResultList(strengths);
            needsImprovement = immutableResultList(needsImprovement);
            strengthChips = immutableResultList(strengthChips);
            needsImprovementChips = immutableResultList(needsImprovementChips);
            if (strengths.stream().anyMatch(finding ->
                    finding.polarity() != ResultDetailPolarity.STRENGTH)
                    || needsImprovement.stream().anyMatch(finding ->
                    finding.polarity() != ResultDetailPolarity.NEEDS_IMPROVEMENT)
                    || strengthChips.stream().anyMatch(chip ->
                    chip.polarity() != ResultDetailPolarity.STRENGTH)
                    || needsImprovementChips.stream().anyMatch(chip ->
                    chip.polarity() != ResultDetailPolarity.NEEDS_IMPROVEMENT)) {
                throw new IllegalArgumentException(
                        "Speaking diagnostic group polarity is inconsistent");
            }
            requireSpeakingChipCoverage(strengths, strengthChips);
            requireSpeakingChipCoverage(needsImprovement, needsImprovementChips);
        }

        public boolean hasStrengths() {
            return !strengths.isEmpty();
        }

        public boolean hasNeedsImprovement() {
            return !needsImprovement.isEmpty();
        }

        private static void requireSpeakingChipCoverage(
                List<ResultDetailDiagnosticFinding> findings,
                List<ResultDetailFilterChip> chips
        ) {
            Map<String, Long> expectedCounts = findings.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            ResultDetailDiagnosticFinding::descriptorId,
                            LinkedHashMap::new,
                            java.util.stream.Collectors.counting()));
            if (new LinkedHashSet<>(chips.stream()
                    .map(ResultDetailFilterChip::id).toList()).size() != chips.size()
                    || !chips.stream()
                    .map(ResultDetailFilterChip::id)
                    .collect(java.util.stream.Collectors.toSet())
                    .containsAll(expectedCounts.keySet())
                    || chips.stream().anyMatch(chip ->
                            expectedCounts.getOrDefault(chip.id(), 0L) != chip.count()
                            || findings.stream()
                            .filter(finding ->
                                    chip.id().equals(finding.descriptorId()))
                            .anyMatch(finding ->
                                    !chip.parentCriterionId().equals(
                                            finding.parentCriterionId())
                                            || !chip.applicability().equals(
                                            finding.applicability())))) {
                throw new IllegalArgumentException(
                        "Speaking diagnostic chips must exactly count rendered findings");
            }
        }
    }

    public record SpeakingAnswerArtifact(
            String content,
            String availability,
            String provenance,
            String labelVi,
            String labelKo
    ) {
        public SpeakingAnswerArtifact {
            content = blankResultText(content);
            if (availability == null
                    || !Set.of("AVAILABLE", "UNAVAILABLE").contains(availability)
                    || provenance == null
                    || !Set.of(
                    "LEARNER_TRANSCRIPT_DERIVED_EVALUATOR_OUTPUT",
                    "EVALUATOR_GENERATED_NOT_TEACHER_REFERENCE").contains(provenance)
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()) {
                throw new IllegalArgumentException(
                        "Speaking answer artifact provenance is incomplete");
            }
            if ("AVAILABLE".equals(availability) != !content.isBlank()) {
                throw new IllegalArgumentException(
                        "Speaking answer artifact availability/content is inconsistent");
            }
        }

        public boolean available() {
            return "AVAILABLE".equals(availability);
        }
    }

    public record SpeakingPhraseRewriteView(
            String findingId,
            String descriptorId,
            String original,
            String upgraded,
            String reason
    ) {
        public SpeakingPhraseRewriteView {
            if (findingId == null || findingId.isBlank()
                    || descriptorId == null || descriptorId.isBlank()
                    || original == null || original.isBlank()
                    || upgraded == null || upgraded.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "Speaking phrase rewrite is incomplete");
            }
        }
    }

    public record SpeakingUpgradeView(
            Long questionId,
            SpeakingAnswerArtifact learnerDerivedUpgrade,
            List<SpeakingPhraseRewriteView> significantRewrites,
            SpeakingAnswerArtifact evaluatorSample
    ) {
        public SpeakingUpgradeView {
            if (questionId == null || learnerDerivedUpgrade == null
                    || evaluatorSample == null) {
                throw new IllegalArgumentException(
                        "Speaking upgrade provenance is incomplete");
            }
            significantRewrites = immutableResultList(significantRewrites);
        }
    }

    public record ResultDetailScoreCriterion(
            Long questionId,
            String criterionId,
            String labelVi,
            String labelKo,
            BigDecimal score,
            BigDecimal maxScore,
            String availability,
            int stableOrder,
            ResultPerformanceLevel performanceLevel,
            String feedbackVi
    ) {
        public ResultDetailScoreCriterion {
            feedbackVi = feedbackVi == null || feedbackVi.isBlank()
                    ? null : feedbackVi.trim();
            if (criterionId == null || criterionId.isBlank()
                    || labelVi == null || labelVi.isBlank()
                    || availability == null || availability.isBlank()
                    || stableOrder <= 0) {
                throw new IllegalArgumentException("Result Detail score criterion is incomplete");
            }
            if (!"SCORED".equals(availability)) {
                score = null;
                maxScore = null;
                performanceLevel = "NOT_SCORABLE".equals(availability)
                        ? ResultPerformanceLevel.notScorableView()
                        : ResultPerformanceLevel.unavailableView();
            } else if (performanceLevel != null
                    && !performanceLevel.scored()) {
                throw new IllegalArgumentException(
                        "A scored Result Detail criterion requires a scored performance level");
            }
        }

        public ResultDetailScoreCriterion(
                Long questionId,
                String criterionId,
                String labelVi,
                String labelKo,
                BigDecimal score,
                BigDecimal maxScore,
                String availability,
                int stableOrder,
                ResultPerformanceLevel performanceLevel
        ) {
            this(questionId, criterionId, labelVi, labelKo, score, maxScore,
                    availability, stableOrder, performanceLevel, null);
        }

        public ResultDetailScoreCriterion(
                Long questionId,
                String criterionId,
                String labelVi,
                String labelKo,
                BigDecimal score,
                BigDecimal maxScore,
                String availability,
                int stableOrder
        ) {
            this(questionId, criterionId, labelVi, labelKo, score, maxScore,
                    availability, stableOrder, null, null);
        }

        public String scoreDisplay() {
            return score == null || maxScore == null
                    ? null
                    : compactResultNumber(score) + "/" + compactResultNumber(maxScore);
        }
    }

    public record ResultDetailDiagnosticFinding(
            Long questionId,
            String findingId,
            String evidenceId,
            String descriptorId,
            String featureId,
            ResultDetailPolarity polarity,
            String parentCriterionId,
            String applicability,
            String evidenceAvailability,
            String evidenceScope,
            String evidence,
            Integer startOffset,
            Integer endOffset,
            Integer occurrenceIndex,
            Integer occurrenceCount,
            String normalization,
            String sourceHash,
            String operation,
            int scopedDisplayNumber,
            String explanationVi,
            String correctionKo
    ) {
        public ResultDetailDiagnosticFinding {
            correctionKo = correctionKo == null || correctionKo.isBlank()
                    ? null : correctionKo;
            if (findingId == null || findingId.isBlank()
                    || descriptorId == null || descriptorId.isBlank()
                    || featureId == null || featureId.isBlank() || polarity == null
                    || parentCriterionId == null || parentCriterionId.isBlank()
                    || applicability == null || applicability.isBlank()
                    || evidenceAvailability == null || evidenceAvailability.isBlank()
                    || evidenceScope == null || evidenceScope.isBlank()
                    || operation == null
                    || !Set.of("KEEP", "MISSING", "REPLACE", "REDUNDANT")
                    .contains(operation)
                    || scopedDisplayNumber < 1
                    || explanationVi == null || explanationVi.isBlank()) {
                throw new IllegalArgumentException("Result Detail diagnostic finding is incomplete");
            }
            if ((polarity == ResultDetailPolarity.STRENGTH
                    && !"KEEP".equals(operation))
                    || (polarity == ResultDetailPolarity.NEEDS_IMPROVEMENT
                    && "KEEP".equals(operation))) {
                throw new IllegalArgumentException(
                        "Result Detail diagnostic operation/polarity is inconsistent");
            }
            if ("TEXT_SPAN".equals(evidenceScope)
                    && (evidenceId == null || evidenceId.isBlank()
                    || evidence == null || evidence.isBlank()
                    || startOffset == null || startOffset < 0
                    || endOffset == null || endOffset <= startOffset
                    || occurrenceIndex == null || occurrenceIndex < 1
                    || occurrenceCount == null
                    || occurrenceCount < occurrenceIndex
                    || normalization == null || normalization.isBlank()
                    || sourceHash == null || sourceHash.isBlank())) {
                throw new IllegalArgumentException(
                        "Result Detail text-span finding requires exact authority");
            }
            if ("WHOLE_ANSWER".equals(evidenceScope)
                    && (evidenceId != null || evidence != null
                    || startOffset != null || endOffset != null
                    || occurrenceIndex != null || occurrenceCount != null
                    || normalization != null || sourceHash != null)) {
                throw new IllegalArgumentException(
                        "Result Detail whole-answer finding cannot fabricate a span");
            }
        }

        public ResultDetailDiagnosticFinding withScopedDisplayNumber(int number) {
            return new ResultDetailDiagnosticFinding(
                    questionId, findingId, evidenceId, descriptorId, featureId, polarity,
                    parentCriterionId, applicability, evidenceAvailability,
                    evidenceScope, evidence, startOffset, endOffset,
                    occurrenceIndex, occurrenceCount, normalization, sourceHash,
                    operation, number, explanationVi, correctionKo);
        }

        public ResultDetailSpanMembership spanMembership() {
            if (!"TEXT_SPAN".equals(evidenceScope)) {
                return null;
            }
            return new ResultDetailSpanMembership(
                    findingId, evidenceId, descriptorId, featureId, polarity,
                    startOffset, endOffset, occurrenceIndex, occurrenceCount,
                    operation, scopedDisplayNumber, evidence, explanationVi,
                    correctionKo);
        }
    }

    public record ResultDetailFilterChip(
            String id,
            String labelVi,
            String labelKo,
            ResultDetailPolarity polarity,
            String parentCriterionId,
            String applicability,
            int stableOrder,
            int count,
            boolean countedSeparately,
            String evidenceAvailability
    ) {
        public ResultDetailFilterChip {
            if (id == null || id.isBlank() || labelVi == null || labelVi.isBlank()
                    || polarity == null || parentCriterionId == null || parentCriterionId.isBlank()
                    || applicability == null || applicability.isBlank()
                    || stableOrder <= 0 || count < 0
                    || evidenceAvailability == null || evidenceAvailability.isBlank()) {
                throw new IllegalArgumentException("Result Detail filter chip is incomplete");
            }
            if (countedSeparately) {
                throw new IllegalArgumentException(
                        "Diagnostic chip counts are navigation metadata, not separate scores");
            }
        }
    }

    // =========================================================================
    //  Canonical immutable-attempt result contract
    // =========================================================================

    public record PracticeAttemptResultView(
            ResultAttemptIdentity identity,
            ResultState state,
            ResultScoreSummary score,
            ResultAnswerDistribution answers,
            ResultFeedbackAvailability feedback,
            LocalDateTime startedAt,
            LocalDateTime submittedAt,
            Long elapsedSeconds,
            ResultSkillPayload payload
    ) {
        public PracticeAttemptResultView {
            if (identity == null || state == null || score == null || answers == null
                    || feedback == null || payload == null) {
                throw new IllegalArgumentException("Practice result envelope is incomplete");
            }
        }

        public String elapsedDisplay() {
            if (elapsedSeconds == null) {
                return null;
            }
            long minutes = elapsedSeconds / 60;
            long seconds = elapsedSeconds % 60;
            return minutes + " phút " + seconds + " giây";
        }
    }

    public record ResultAttemptIdentity(
            Long attemptId,
            Long publishedVersionId,
            Long setVersionId,
            Long testVersionId,
            Long sectionVersionId,
            Long setId,
            String setTitle,
            Long testId,
            String testTitle,
            Long sectionId,
            String sectionTitle,
            String skill,
            String skillLabel
    ) {
    }

    public record ResultState(String code, String label) {
    }

    /**
     * Backend-authoritative localized performance projection.
     *
     * <p>Scored levels are derived from accepted Writing score anchors.
     * Evidence availability states remain explicit and are never collapsed
     * into the lowest scored level.</p>
     */
    public record ResultPerformanceLevel(
            String code,
            String labelVi,
            String labelKo
    ) {
        private static final Set<String> SCORED_CODES = Set.of(
                "LIMITED", "MODEST", "GOOD", "EXCELLENT");
        private static final Set<String> AVAILABILITY_CODES = Set.of(
                "NOT_SCORABLE", "UNAVAILABLE");

        public ResultPerformanceLevel {
            if (code == null
                    || (!SCORED_CODES.contains(code)
                    && !AVAILABILITY_CODES.contains(code))
                    || labelVi == null || labelVi.isBlank()
                    || labelKo == null || labelKo.isBlank()) {
                throw new IllegalArgumentException(
                        "Result performance level is incomplete");
            }
        }

        public boolean scored() {
            return SCORED_CODES.contains(code);
        }

        public boolean unavailable() {
            return AVAILABILITY_CODES.contains(code);
        }

        public static ResultPerformanceLevel unavailableView() {
            return new ResultPerformanceLevel(
                    "UNAVAILABLE", "Chưa khả dụng", "평가 불가");
        }

        public static ResultPerformanceLevel notScorableView() {
            return new ResultPerformanceLevel(
                    "NOT_SCORABLE", "Chưa thể chấm", "채점 불가");
        }
    }

    public record ResultScoreSummary(
            BigDecimal value,
            BigDecimal earnedPoints,
            BigDecimal possiblePoints,
            BigDecimal percentage,
            String unit,
            String scaleLabel,
            String levelLabel
    ) {
        public boolean available() {
            return value != null || percentage != null || earnedPoints != null;
        }

        public String primaryDisplay() {
            BigDecimal display;
            if ("PERCENTAGE".equals(unit)) {
                display = percentage != null ? percentage : value;
            } else if ("EARNED_POINTS".equals(unit)) {
                display = earnedPoints != null ? earnedPoints : value;
            } else {
                display = value != null ? value : percentage;
            }
            return display == null ? null : compactResultNumber(display);
        }

        public String pointsDisplay() {
            if (earnedPoints == null || possiblePoints == null) {
                return null;
            }
            return compactResultNumber(earnedPoints) + "/" + compactResultNumber(possiblePoints);
        }

        public ResultScoreSummary unavailableView() {
            return new ResultScoreSummary(
                    null,
                    null,
                    null,
                    null,
                    unit,
                    scaleLabel,
                    null);
        }
    }

    public record ResultAnswerDistribution(
            int correct,
            int partial,
            int incorrect,
            int notAnswered,
            int pending,
            int unscorable,
            int total,
            int scoredDenominator
    ) {
        public String scoredLabel() {
            return scoredDenominator + "/" + total + " câu đã chấm";
        }
    }

    public record ResultFeedbackAvailability(
            String state,
            String label,
            int readyCount,
            int totalCount
    ) {
        public boolean ready() {
            return "READY".equals(state);
        }

        public String progressLabel(String noun) {
            return readyCount + "/" + totalCount + " " + noun;
        }

        public String stateLabelKo() {
            return switch (state == null ? "" : state) {
                case "READY" -> "평가 완료";
                case "PENDING" -> "평가 대기 중";
                case "FAILED" -> "평가 실패";
                case "PARTIAL" -> "일부 평가 가능";
                case "LOW_CONFIDENCE" -> "신뢰도 낮음";
                case "NOT_SCORABLE" -> "채점 불가";
                case "LEGACY_UNVERIFIED" -> "이전 데이터 확인 불가";
                case "UNAVAILABLE" -> "평가 불가";
                default -> "상태 확인 불가";
            };
        }
    }

    public sealed interface ResultSkillPayload
            permits ObjectiveResultPayload, WritingResultPayload, SpeakingResultPayload {
        String kind();
    }

    public record ObjectiveResultPayload(
            String kind,
            List<ObjectiveResultTypeBreakdown> breakdown,
            List<ObjectiveOverviewGroup> groups
    ) implements ResultSkillPayload {
        public ObjectiveResultPayload {
            kind = "OBJECTIVE";
            breakdown = immutableResultList(breakdown);
            groups = immutableResultList(groups);
        }

        public ObjectiveResultPayload(List<ObjectiveResultTypeBreakdown> breakdown) {
            this("OBJECTIVE", breakdown, List.of());
        }

        public ObjectiveResultPayload(
                List<ObjectiveResultTypeBreakdown> breakdown,
                List<ObjectiveOverviewGroup> groups
        ) {
            this("OBJECTIVE", breakdown, groups);
        }
    }

    public record ObjectiveOverviewGroup(
            String displayLabel,
            String sourceLabel,
            Long firstQuestionId,
            List<String> questionTypeLabels,
            ResultAnswerDistribution answers,
            BigDecimal earnedPoints,
            BigDecimal possiblePoints,
            BigDecimal scoreRatePercentage
    ) {
        public ObjectiveOverviewGroup {
            if (displayLabel == null || displayLabel.isBlank()
                    || sourceLabel == null || sourceLabel.isBlank()
                    || firstQuestionId == null || answers == null) {
                throw new IllegalArgumentException(
                        "Objective Overview group is incomplete");
            }
            questionTypeLabels = immutableResultList(questionTypeLabels);
        }

        public String pointsDisplay() {
            if (earnedPoints == null || possiblePoints == null) {
                return null;
            }
            return compactResultNumber(earnedPoints) + "/"
                    + compactResultNumber(possiblePoints);
        }

        public String scoreRateDisplay() {
            return scoreRatePercentage == null
                    ? null
                    : compactResultNumber(scoreRatePercentage) + "%";
        }
    }

    public record ObjectiveResultTypeBreakdown(
            String questionType,
            String label,
            ResultAnswerDistribution answers,
            BigDecimal earnedPoints,
            BigDecimal possiblePoints,
            BigDecimal scoreRatePercentage
    ) {
        public String pointsDisplay() {
            if (earnedPoints == null || possiblePoints == null) {
                return null;
            }
            return compactResultNumber(earnedPoints) + "/" + compactResultNumber(possiblePoints);
        }

        public String scoreRateDisplay() {
            return scoreRatePercentage == null
                    ? null
                    : compactResultNumber(scoreRatePercentage) + "%";
        }
    }

    public record WritingResultPayload(
            String kind,
            List<WritingTaskResult> tasks
    ) implements ResultSkillPayload {
        public WritingResultPayload {
            kind = "WRITING";
            tasks = immutableResultList(tasks);
        }

        public WritingResultPayload(List<WritingTaskResult> tasks) {
            this("WRITING", tasks);
        }
    }

    public record WritingTaskResult(
            Long questionId,
            Long questionVersionId,
            Integer questionNo,
            String taskType,
            String taskLabel,
            String prompt,
            String learnerAnswer,
            ResultScoreSummary score,
            ResultFeedbackAvailability feedback,
            String summary,
            List<ResultRubricCriterion> officialCriteria,
            List<WritingAnalysisLens> analysisLenses,
            boolean detailAvailable,
            String languageTag,
            ResultPerformanceLevel performanceLevel
    ) {
        public WritingTaskResult {
            officialCriteria = immutableResultList(officialCriteria);
            analysisLenses = immutableResultList(analysisLenses);
            languageTag = "ko".equals(languageTag) || "vi".equals(languageTag)
                    ? languageTag
                    : "ko";
        }

        public WritingTaskResult(
                Long questionId,
                Long questionVersionId,
                Integer questionNo,
                String taskType,
                String taskLabel,
                String prompt,
                String learnerAnswer,
                ResultScoreSummary score,
                ResultFeedbackAvailability feedback,
                String summary,
                List<ResultRubricCriterion> officialCriteria,
                List<WritingAnalysisLens> analysisLenses,
                boolean detailAvailable
        ) {
            this(questionId, questionVersionId, questionNo, taskType, taskLabel,
                    prompt, learnerAnswer, score, feedback, summary, officialCriteria,
                    analysisLenses, detailAvailable, "ko", null);
        }

        public WritingTaskResult(
                Long questionId,
                Long questionVersionId,
                Integer questionNo,
                String taskType,
                String taskLabel,
                String prompt,
                String learnerAnswer,
                ResultScoreSummary score,
                ResultFeedbackAvailability feedback,
                String summary,
                List<ResultRubricCriterion> officialCriteria,
                List<WritingAnalysisLens> analysisLenses,
                boolean detailAvailable,
                String languageTag
        ) {
            this(questionId, questionVersionId, questionNo, taskType, taskLabel,
                    prompt, learnerAnswer, score, feedback, summary, officialCriteria,
                    analysisLenses, detailAvailable, languageTag, null);
        }

        public boolean answered() {
            return learnerAnswer != null && !learnerAnswer.isBlank();
        }

        public boolean evaluated() {
            return feedback != null && feedback.ready() && score != null && score.available();
        }

        public boolean clozeTask() {
            return "Q51".equals(taskType) || "Q52".equals(taskType);
        }

        public List<WritingCriterionGroup> criterionGroups() {
            if (!clozeTask()) {
                return officialCriteria.stream()
                        .map(criterion -> new WritingCriterionGroup(
                                criterion.criterionId(),
                                criterion.label(),
                                List.of(criterion)))
                        .toList();
            }
            return List.of(
                    clozeGroup("CONTEXT", "Nội dung và ngữ cảnh"),
                    clozeGroup("GRAMMAR", "Ngữ pháp và cấu trúc"),
                    clozeGroup("EXPRESSION", "Biểu đạt và độ tự nhiên"))
                    .stream()
                    .filter(group -> !group.criteria().isEmpty())
                    .toList();
        }

        private WritingCriterionGroup clozeGroup(
                String code,
                String label) {
            return new WritingCriterionGroup(
                    code,
                    label,
                    officialCriteria.stream()
                            .filter(criterion -> criterion.criterionId() != null
                                    && criterion.criterionId()
                                    .endsWith("_" + code))
                            .toList());
        }
    }

    public record WritingAnalysisLens(
            String code,
            String label,
            String sourceCriterionId,
            List<String> evidence
    ) {
        public WritingAnalysisLens {
            evidence = immutableResultList(evidence);
        }
    }

    public enum ResultOverviewCapabilityAvailability {
        AVAILABLE,
        NOT_SCORABLE,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public record ResultOverviewCapability(
            String code,
            ResultOverviewCapabilityAvailability availability,
            String reasonVi
    ) {
        public ResultOverviewCapability {
            if (code == null || code.isBlank()
                    || availability == null
                    || reasonVi == null || reasonVi.isBlank()) {
                throw new IllegalArgumentException(
                        "Result Overview capability is incomplete");
            }
        }

        public boolean available() {
            return availability == ResultOverviewCapabilityAvailability.AVAILABLE;
        }
    }

    public record SpeakingRadarAxis(
            String criterionId,
            String label,
            BigDecimal earned,
            BigDecimal possible,
            BigDecimal percentage,
            ResultPerformanceLevel performanceLevel,
            String availability
    ) {
        public SpeakingRadarAxis {
            if (criterionId == null || criterionId.isBlank()
                    || label == null || label.isBlank()
                    || availability == null
                    || !Set.of("SCORED", "NOT_SCORABLE", "UNAVAILABLE")
                    .contains(availability)) {
                throw new IllegalArgumentException(
                        "Speaking radar axis is incomplete");
            }
            if ("SCORED".equals(availability)) {
                if (earned == null || possible == null
                        || possible.signum() <= 0
                        || percentage == null
                        || percentage.compareTo(BigDecimal.ZERO) < 0
                        || percentage.compareTo(BigDecimal.valueOf(100)) > 0
                        || performanceLevel == null
                        || !performanceLevel.scored()) {
                    throw new IllegalArgumentException(
                            "Scored Speaking radar axis requires an anchored score");
                }
            } else if (earned != null || possible != null || percentage != null
                    || performanceLevel == null
                    || !performanceLevel.unavailable()) {
                throw new IllegalArgumentException(
                        "Unavailable Speaking radar axis cannot carry a score");
            }
        }

        public boolean scored() {
            return "SCORED".equals(availability);
        }

        public String scoreDisplay() {
            return scored()
                    ? compactResultNumber(earned) + "/" + compactResultNumber(possible)
                    : "—";
        }

        public String levelCssClass() {
            return "is-" + performanceLevel.code()
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }
    }

    public record SpeakingSubmetricPerformance(
            String subcriterionId,
            String labelVi,
            String parentCriterionId,
            String parentCriterionLabelVi,
            ResultPerformanceLevel anchorLevel,
            int strengthFindings,
            int improvementFindings,
            List<Long> questionIds
    ) {
        public SpeakingSubmetricPerformance {
            questionIds = immutableResultList(questionIds);
            if (subcriterionId == null || subcriterionId.isBlank()
                    || labelVi == null || labelVi.isBlank()
                    || parentCriterionId == null || parentCriterionId.isBlank()
                    || parentCriterionLabelVi == null
                    || parentCriterionLabelVi.isBlank()
                    || anchorLevel == null
                    || !anchorLevel.scored()
                    || strengthFindings < 0 || improvementFindings < 0
                    || strengthFindings + improvementFindings < 1
                    || questionIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Speaking submetric performance is incomplete");
            }
        }

        public int findingCount() {
            return strengthFindings + improvementFindings;
        }

        public String levelCssClass() {
            return "is-" + anchorLevel.code()
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }
    }

    public record SpeakingQuestionPerformance(
            Long questionId,
            Integer questionNo,
            String questionLabel,
            String groupLabel,
            String availability,
            List<SpeakingCriterionResult> criteria
    ) {
        public SpeakingQuestionPerformance {
            criteria = immutableResultList(criteria);
            if (questionId == null || questionNo == null
                    || questionLabel == null || questionLabel.isBlank()
                    || groupLabel == null || groupLabel.isBlank()
                    || availability == null
                    || !Set.of("READY", "LOW_CONFIDENCE", "NOT_ANSWERED",
                    "LEGACY_UNVERIFIED", "UNAVAILABLE").contains(availability)
                    || criteria.size() != 6) {
                throw new IllegalArgumentException(
                        "Speaking question performance is incomplete");
            }
        }

        public List<SpeakingCriterionResult> languageCriteria() {
            return criteria.stream()
                    .filter(criterion -> !criterion.requiresDirectAudioEvidence())
                    .toList();
        }

        public boolean ready() {
            return "READY".equals(availability);
        }

        public String availabilityLabel() {
            return switch (availability) {
                case "READY" -> "Đã chấm";
                case "LOW_CONFIDENCE" -> "Bản chép lời chưa đủ tin cậy";
                case "NOT_ANSWERED" -> "Chưa trả lời";
                case "LEGACY_UNVERIFIED" -> "Kết quả cũ chưa xác minh";
                default -> "Chưa khả dụng";
            };
        }

        public String stateCssClass() {
            return "is-" + availability.toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }
    }

    public record SpeakingResultPayload(
            String kind,
            ResultScoreSummary holisticScore,
            int coveredSegments,
            int totalSegments,
            String profileState,
            String evidenceMode,
            String evidenceNote,
            List<String> overallSummaries,
            List<SpeakingOverviewFindingView> strengths,
            List<SpeakingOverviewFindingView> needsImprovement,
            List<SpeakingActionPlanView> actionPlan,
            List<SpeakingCriterionResult> criteria,
            List<SpeakingSubmetricPerformance> submetricPerformance,
            List<SpeakingQuestionPerformance> questionPerformance,
            String evaluatorCapability,
            String evidenceContractVersion,
            String policyBundleId,
            String policyBundleFingerprint,
            String contractTrust,
            boolean holisticScoreAvailable,
            int legacyUnverifiedSegments
    ) implements ResultSkillPayload {
        public SpeakingResultPayload {
            kind = "SPEAKING";
            overallSummaries = immutableResultList(overallSummaries);
            strengths = immutableResultList(strengths);
            needsImprovement = immutableResultList(needsImprovement);
            actionPlan = immutableResultList(actionPlan);
            criteria = immutableResultList(criteria);
            submetricPerformance = immutableResultList(submetricPerformance);
            questionPerformance = immutableResultList(questionPerformance);
            profileState = switch (profileState == null ? "" : profileState) {
                case "READY", "PARTIAL", "PENDING", "FAILED", "UNAVAILABLE",
                        "LOW_CONFIDENCE", "LEGACY_UNVERIFIED" -> profileState;
                default -> "UNAVAILABLE";
            };
            boolean transcriptProfile = ("CURRENT_VERIFIED".equals(contractTrust)
                    || "MIXED_WITH_LEGACY_UNVERIFIED".equals(contractTrust))
                    && "TRANSCRIPT_GROUNDED_LANGUAGE_EVALUATION".equals(evaluatorCapability)
                    && "TRANSCRIPT_ONLY".equals(evidenceMode)
                    && com.ksh.features.practice.ai.speaking.SpeakingPromptRules
                            .EVIDENCE_CONTRACT_VERSION.equals(evidenceContractVersion)
                    && com.ksh.features.practice.ai.speaking
                            .SpeakingAssessmentPolicyBundle.POLICY_BUNDLE_ID
                            .equals(policyBundleId)
                    && com.ksh.features.practice.ai.speaking
                            .SpeakingAssessmentPolicyBundle.fingerprint()
                            .equals(policyBundleFingerprint);
            boolean governedFutureHolisticProfile = "CURRENT_VERIFIED".equals(contractTrust)
                    && holisticScoreAvailable
                    && holisticScore != null
                    && holisticScore.available()
                    && "DIRECT_AUDIO_AND_TRANSCRIPT".equals(evidenceMode)
                    && evaluatorCapability != null
                    && !"AUDIO_DIRECT_FULL_RESERVED".equals(evaluatorCapability)
                    && !"LEGACY_UNKNOWN".equals(evaluatorCapability)
                    && evidenceContractVersion != null
                    && !evidenceContractVersion.isBlank()
                    && policyBundleId != null
                    && !policyBundleId.isBlank()
                    && policyBundleFingerprint != null
                    && !policyBundleFingerprint.isBlank();
            if (!transcriptProfile && !governedFutureHolisticProfile) {
                contractTrust = "LEGACY_UNVERIFIED";
                evaluatorCapability = "LEGACY_UNKNOWN";
                evidenceMode = "UNKNOWN";
                evidenceContractVersion = null;
                policyBundleId = null;
                policyBundleFingerprint = null;
                holisticScoreAvailable = false;
                actionPlan = List.of();
                String unavailableCriterionState = legacyUnverifiedSegments > 0
                        ? "LEGACY_UNVERIFIED" : "UNAVAILABLE";
                criteria = criteria.stream()
                        .map(criterion -> criterion.unavailableView(unavailableCriterionState))
                        .toList();
                submetricPerformance = List.of();
                // A per-question performance row is still a score-bearing
                // projection. If the aggregate contract is not current and
                // verified, omit that projection instead of serializing
                // legacy identifiers with unavailable-looking values.
                questionPerformance = List.of();
                if (legacyUnverifiedSegments > 0) {
                    profileState = "LEGACY_UNVERIFIED";
                } else if (!"PENDING".equals(profileState)
                        && !"FAILED".equals(profileState)
                        && !"UNAVAILABLE".equals(profileState)) {
                    profileState = "UNAVAILABLE";
                }
            } else if (transcriptProfile) {
                // The current transcript capability cannot produce an overall
                // Speaking score, even if a stale caller supplies one.
                holisticScoreAvailable = false;
                actionPlan = actionPlan.stream()
                        .filter(item -> item.findingId() != null
                                && !item.findingId().isBlank()
                                && item.evidenceId() != null
                                && !item.evidenceId().isBlank())
                        .toList();
            }
            if (!holisticScoreAvailable && holisticScore != null) {
                holisticScore = holisticScore.unavailableView();
            }
        }

        public SpeakingResultPayload(
                ResultScoreSummary holisticScore,
                int coveredSegments,
                int totalSegments,
                String evidenceMode,
                String evidenceNote,
                List<String> overallSummaries,
                List<SpeakingOverviewFindingView> strengths,
                List<SpeakingOverviewFindingView> needsImprovement,
                List<SpeakingActionPlanView> actionPlan,
                List<SpeakingCriterionResult> criteria) {
            this("SPEAKING", holisticScore, coveredSegments, totalSegments,
                    defaultSpeakingProfileState(coveredSegments, totalSegments), evidenceMode,
                    evidenceNote, overallSummaries, strengths, needsImprovement, actionPlan, criteria,
                    List.of(), List.of(),
                    "LEGACY_UNKNOWN", null, null, null,
                    "LEGACY_UNVERIFIED", false, 0);
        }

        public SpeakingResultPayload(
                ResultScoreSummary holisticScore,
                int coveredSegments,
                int totalSegments,
                String profileState,
                String evidenceMode,
                String evidenceNote,
                List<String> overallSummaries,
                List<SpeakingOverviewFindingView> strengths,
                List<SpeakingOverviewFindingView> needsImprovement,
                List<SpeakingActionPlanView> actionPlan,
                List<SpeakingCriterionResult> criteria,
                List<SpeakingSubmetricPerformance> submetricPerformance,
                List<SpeakingQuestionPerformance> questionPerformance,
                String evaluatorCapability,
                String evidenceContractVersion,
                String policyBundleId,
                String policyBundleFingerprint,
                String contractTrust,
                boolean holisticScoreAvailable,
                int legacyUnverifiedSegments) {
            this("SPEAKING", holisticScore, coveredSegments, totalSegments, profileState, evidenceMode,
                    evidenceNote, overallSummaries, strengths, needsImprovement, actionPlan, criteria,
                    submetricPerformance, questionPerformance,
                    evaluatorCapability, evidenceContractVersion,
                    policyBundleId, policyBundleFingerprint,
                    contractTrust,
                    holisticScoreAvailable, legacyUnverifiedSegments);
        }

        public SpeakingResultPayload(
                ResultScoreSummary holisticScore,
                int coveredSegments,
                int totalSegments,
                String profileState,
                String evidenceMode,
                String evidenceNote,
                List<String> overallSummaries,
                List<SpeakingOverviewFindingView> strengths,
                List<SpeakingOverviewFindingView> needsImprovement,
                List<SpeakingActionPlanView> actionPlan,
                List<SpeakingCriterionResult> criteria,
                String evaluatorCapability,
                String evidenceContractVersion,
                String policyBundleId,
                String policyBundleFingerprint,
                String contractTrust,
                boolean holisticScoreAvailable,
                int legacyUnverifiedSegments) {
            this(holisticScore, coveredSegments, totalSegments, profileState,
                    evidenceMode, evidenceNote, overallSummaries, strengths,
                    needsImprovement, actionPlan, criteria, List.of(), List.of(),
                    evaluatorCapability, evidenceContractVersion, policyBundleId,
                    policyBundleFingerprint, contractTrust,
                    holisticScoreAvailable, legacyUnverifiedSegments);
        }

        public SpeakingResultPayload(
                ResultScoreSummary holisticScore,
                int coveredSegments,
                int totalSegments,
                String evidenceMode,
                String evidenceNote,
                List<String> overallSummaries,
                List<SpeakingOverviewFindingView> strengths,
                List<SpeakingOverviewFindingView> needsImprovement,
                List<SpeakingActionPlanView> actionPlan,
                List<SpeakingCriterionResult> criteria,
                String evaluatorCapability,
                String evidenceContractVersion,
                String policyBundleId,
                String policyBundleFingerprint,
                String contractTrust,
                boolean holisticScoreAvailable,
                int legacyUnverifiedSegments) {
            this(holisticScore, coveredSegments, totalSegments,
                    legacyUnverifiedSegments > 0 && coveredSegments == 0
                            ? "LEGACY_UNVERIFIED"
                            : defaultSpeakingProfileState(coveredSegments, totalSegments),
                    evidenceMode, evidenceNote, overallSummaries, strengths, needsImprovement,
                    actionPlan, criteria, List.of(), List.of(), evaluatorCapability,
                    evidenceContractVersion,
                    policyBundleId, policyBundleFingerprint,
                    contractTrust, holisticScoreAvailable, legacyUnverifiedSegments);
        }

        public boolean transcriptGroundedProfile() {
            return ("CURRENT_VERIFIED".equals(contractTrust)
                    || "MIXED_WITH_LEGACY_UNVERIFIED".equals(contractTrust))
                    && "TRANSCRIPT_GROUNDED_LANGUAGE_EVALUATION".equals(evaluatorCapability)
                    && "TRANSCRIPT_ONLY".equals(evidenceMode)
                    && com.ksh.features.practice.ai.speaking
                            .SpeakingAssessmentPolicyBundle.POLICY_BUNDLE_ID
                            .equals(policyBundleId)
                    && com.ksh.features.practice.ai.speaking
                            .SpeakingAssessmentPolicyBundle.fingerprint()
                            .equals(policyBundleFingerprint);
        }

        /**
         * Typed, fail-closed authority for PREP-like aggregate visuals.  These
         * states are intentionally separate from CSS and the seed route: a
         * client may only render a numeric aggregate when its capability is
         * AVAILABLE. Values are normalized and anchored on the backend; the
         * browser never invents a missing axis, level or question aggregate.
         */
        public List<ResultOverviewCapability> overviewCapabilities() {
            ResultOverviewCapabilityAvailability holisticAvailability =
                    holisticScoreAvailable
                            && holisticScore != null
                            && holisticScore.available()
                            ? ResultOverviewCapabilityAvailability.AVAILABLE
                            : transcriptGroundedProfile()
                            ? ResultOverviewCapabilityAvailability.NOT_SCORABLE
                            : ResultOverviewCapabilityAvailability.UNAVAILABLE;
            boolean completeLanguageProfile = radarAxes().size() >= 3;
            ResultOverviewCapabilityAvailability radarAvailability =
                    completeLanguageProfile
                            ? ResultOverviewCapabilityAvailability.AVAILABLE
                            : ResultOverviewCapabilityAvailability.UNAVAILABLE;
            return List.of(
                    new ResultOverviewCapability(
                            "HOLISTIC_SCORE",
                            holisticAvailability,
                            holisticAvailability
                                    == ResultOverviewCapabilityAvailability.AVAILABLE
                                    ? "Điểm Nói tổng hợp có authority đã xác minh."
                                    : holisticAvailability
                                    == ResultOverviewCapabilityAvailability.NOT_SCORABLE
                                    ? "Bản chép lời không đủ authority để tạo điểm Nói tổng hợp."
                                    : "Chưa có hồ sơ đủ điều kiện cho điểm Nói tổng hợp."),
                    new ResultOverviewCapability(
                            "CRITERION_RADAR",
                            radarAvailability,
                            radarAvailability
                                    == ResultOverviewCapabilityAvailability.AVAILABLE
                                    ? "Các trục ngôn ngữ được chuẩn hóa độc lập từ điểm đạt được trên điểm tối đa."
                                    : "Chưa có đủ tiêu chí ngôn ngữ đã chấm để dựng hồ sơ radar."),
                    new ResultOverviewCapability(
                            "PART_PERFORMANCE",
                            questionPerformance.stream().anyMatch(
                                    SpeakingQuestionPerformance::ready)
                                    ? ResultOverviewCapabilityAvailability.AVAILABLE
                                    : ResultOverviewCapabilityAvailability.UNAVAILABLE,
                            questionPerformance.stream().anyMatch(
                                    SpeakingQuestionPerformance::ready)
                                    ? "Hiệu suất theo câu và nhóm đã publish có authority."
                                    : "Chưa có câu đã đánh giá để hiển thị hiệu suất theo nhóm."),
                    new ResultOverviewCapability(
                            "NAMED_CRITERION_SUBMETRICS",
                            submetricPerformance.isEmpty()
                                    ? ResultOverviewCapabilityAvailability.UNAVAILABLE
                                    : ResultOverviewCapabilityAvailability.AVAILABLE,
                            submetricPerformance.isEmpty()
                                    ? "Chưa có submetric với bằng chứng đã xác minh."
                                    : "Submetric dùng level anchor KSH của tiêu chí mẹ và bằng chứng đã xác minh."));
        }

        public List<SpeakingRadarAxis> radarAxes() {
            return criteria.stream()
                    .filter(criterion -> !criterion.requiresDirectAudioEvidence())
                    .filter(SpeakingCriterionResult::scored)
                    .map(criterion -> new SpeakingRadarAxis(
                            criterion.criterionId(), criterion.label(),
                            criterion.score(), criterion.weight(),
                            criterion.percentage(), criterion.performanceLevel(),
                            criterion.availability()))
                    .toList();
        }

        public List<SpeakingRadarAxis> unavailableAcousticAxes() {
            return criteria.stream()
                    .filter(SpeakingCriterionResult::requiresDirectAudioEvidence)
                    .map(criterion -> new SpeakingRadarAxis(
                            criterion.criterionId(), criterion.label(),
                            null, null, null, criterion.performanceLevel(),
                            criterion.notScorable() ? "NOT_SCORABLE" : "UNAVAILABLE"))
                    .toList();
        }

        public List<SpeakingSubmetricPerformance> submetricsFor(
                String criterionId) {
            if (criterionId == null || criterionId.isBlank()) {
                return List.of();
            }
            return submetricPerformance.stream()
                    .filter(metric -> criterionId.equals(
                            metric.parentCriterionId()))
                    .toList();
        }

        public String radarPolygonPoints() {
            List<SpeakingRadarAxis> axes = radarAxes();
            if (axes.size() != 4) {
                return null;
            }
            double[][] units = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
            List<String> points = new java.util.ArrayList<>();
            for (int index = 0; index < axes.size(); index++) {
                double ratio = axes.get(index).percentage()
                        .divide(BigDecimal.valueOf(100), 6,
                                java.math.RoundingMode.HALF_UP)
                        .doubleValue();
                double x = 100 + units[index][0] * 78 * ratio;
                double y = 100 + units[index][1] * 78 * ratio;
                points.add(String.format(java.util.Locale.ROOT,
                        "%.2f,%.2f", x, y));
            }
            return String.join(" ", points);
        }

        public ResultOverviewCapability overviewCapability(String code) {
            return overviewCapabilities().stream()
                    .filter(capability -> capability.code().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown Result Overview capability: " + code));
        }

        public String profileTitle() {
            if (holisticScoreAvailable) {
                return "Kết quả Nói tổng hợp";
            }
            if (transcriptGroundedProfile()
                    && ("READY".equals(profileState) || "PARTIAL".equals(profileState))) {
                return "Hồ sơ ngôn ngữ dựa trên bản chép lời";
            }
            if (transcriptGroundedProfile() && "PENDING".equals(profileState)) {
                return "Hồ sơ ngôn ngữ đang được xử lý";
            }
            if (transcriptGroundedProfile() && "LOW_CONFIDENCE".equals(profileState)) {
                return "Bản chép lời có độ tin cậy thấp";
            }
            return "Hồ sơ đánh giá chưa khả dụng";
        }

        public String profileStateLabel() {
            return switch (profileState) {
                case "READY" -> "Hồ sơ đã sẵn sàng";
                case "PARTIAL" -> "Hồ sơ mới có một phần";
                case "PENDING" -> "Đang xử lý bằng chứng";
                case "LOW_CONFIDENCE" -> "Bản chép lời có độ tin cậy thấp";
                case "FAILED" -> "Chưa thể tạo hồ sơ";
                case "LEGACY_UNVERIFIED" -> "Kết quả cũ chưa được xác minh";
                default -> "Chưa có hồ sơ khả dụng";
            };
        }

        public String profileStateDescription() {
            return switch (profileState) {
                case "READY" -> coveredSegments + "/" + totalSegments
                        + " phần trả lời có bằng chứng đủ điều kiện.";
                case "PARTIAL" -> coveredSegments + "/" + totalSegments
                        + " phần trả lời có bằng chứng đủ điều kiện; phần còn lại không được tính là 0 điểm.";
                case "PENDING" -> "Bằng chứng chưa xử lý xong; chưa có điểm nào được suy đoán trong thời gian chờ.";
                case "LOW_CONFIDENCE" -> "Bản chép lời hiện tại không đủ tin cậy để chấm tiêu chí; trạng thái này không được quy đổi thành 0 điểm.";
                case "FAILED" -> "Không có điểm nào được tạo khi xử lý bằng chứng không thành công.";
                case "LEGACY_UNVERIFIED" -> "Dữ liệu lưu trước đây không đủ thông tin để xác minh; mọi số điểm cũ đều được ẩn.";
                default -> "Chưa có bằng chứng đủ điều kiện và trạng thái này không có nghĩa là 0 điểm.";
            };
        }

        public String evidenceSourceLabel() {
            if (transcriptGroundedProfile()
                    && ("READY".equals(profileState) || "PARTIAL".equals(profileState))) {
                return "Bản chép lời của bài làm";
            }
            if (transcriptGroundedProfile() && "PENDING".equals(profileState)) {
                return "Bản chép lời đang được xử lý";
            }
            if (transcriptGroundedProfile() && "LOW_CONFIDENCE".equals(profileState)) {
                return "Bản chép lời đã xác minh nguồn nhưng có độ tin cậy thấp";
            }
            if (transcriptGroundedProfile() && "FAILED".equals(profileState)) {
                return "Bản chép lời chưa đủ điều kiện đánh giá";
            }
            if (holisticScoreAvailable && "DIRECT_AUDIO_AND_TRANSCRIPT".equals(evidenceMode)) {
                return "Âm thanh trực tiếp và bản chép lời đã xác minh";
            }
            return "Chưa có nguồn bằng chứng đủ điều kiện";
        }

        public String scopeLabel() {
            if (transcriptGroundedProfile() && "LOW_CONFIDENCE".equals(profileState)) {
                return "Chỉ ghi nhận nguồn bằng chứng; chưa đánh giá tiêu chí";
            }
            if (transcriptGroundedProfile()) {
                return "4 tiêu chí ngôn ngữ; 2 tiêu chí cần âm thanh trực tiếp";
            }
            return holisticScoreAvailable
                    ? "Hồ sơ Nói tổng hợp đã qua kiểm soát"
                    : "Không suy đoán tiêu chí hoặc điểm tổng hợp";
        }

        public String trustLabel() {
            return switch (contractTrust) {
                case "CURRENT_VERIFIED" -> "Đã xác minh theo quy tắc đánh giá hiện tại";
                case "MIXED_WITH_LEGACY_UNVERIFIED" -> "Chỉ phần bằng chứng hiện tại được dùng để chấm";
                default -> "Chưa thể xác minh theo quy tắc đánh giá hiện tại";
            };
        }
    }

    public record SpeakingCriterionResult(
            String criterionId,
            String label,
            BigDecimal weight,
            BigDecimal score,
            BigDecimal percentage,
            int coveredSegments,
            int totalSegments,
            ResultEvaluationBand band,
            String summary,
            boolean advisoryOnly,
            String availability,
            boolean requiresDirectAudioEvidence
    ) {
        public SpeakingCriterionResult {
            availability = speakingAvailability(availability, score);
            if ("NOT_SCORABLE".equals(availability) && !requiresDirectAudioEvidence) {
                availability = "UNAVAILABLE";
            }
            if (!"SCORED".equals(availability)) {
                weight = null;
                score = null;
                percentage = null;
                summary = null;
                advisoryOnly = false;
                band = ResultEvaluationBand.UNAVAILABLE;
            }
            band = band == null ? ResultEvaluationBand.UNAVAILABLE : band;
        }

        public SpeakingCriterionResult(
                String criterionId,
                String label,
                BigDecimal weight,
                BigDecimal score,
                BigDecimal percentage,
                int coveredSegments,
                int totalSegments,
                ResultEvaluationBand band,
                String summary,
                boolean advisoryOnly
        ) {
            this(criterionId, label, weight, score, percentage, coveredSegments,
                    totalSegments, band, summary, advisoryOnly,
                    score == null ? "UNAVAILABLE" : "SCORED",
                    speakingCriterionRequiresDirectAudio(criterionId));
        }

        public SpeakingCriterionResult(
                String criterionId,
                String label,
                BigDecimal weight,
                BigDecimal score,
                BigDecimal percentage,
                int coveredSegments,
                int totalSegments,
                ResultEvaluationBand band,
                String summary,
                boolean advisoryOnly,
                String availability
        ) {
            this(criterionId, label, weight, score, percentage, coveredSegments,
                    totalSegments, band, summary, advisoryOnly, availability,
                    speakingCriterionRequiresDirectAudio(criterionId));
        }

        public String coverageLabel() {
            if ("NOT_SCORABLE".equals(availability)) {
                return "Bộ đánh giá chưa nhận âm thanh trực tiếp của người học";
            }
            if ("LEGACY_UNVERIFIED".equals(availability)) {
                return "Kết quả lưu trước đây chưa đủ thông tin xác minh";
            }
            if ("UNAVAILABLE".equals(availability)) {
                return "Chưa có bằng chứng đủ điều kiện để chấm";
            }
            return coveredSegments + "/" + totalSegments
                    + " phần trả lời có bằng chứng bản chép lời";
        }

        public String scoreDisplay() {
            if (!"SCORED".equals(availability) || score == null || weight == null) {
                return null;
            }
            return compactResultNumber(score) + "/" + compactResultNumber(weight);
        }

        public boolean scored() {
            return "SCORED".equals(availability) && scoreDisplay() != null;
        }

        public boolean notScorable() {
            return "NOT_SCORABLE".equals(availability);
        }

        public String availabilityLabel() {
            return switch (availability) {
                case "SCORED" -> "Đã chấm từ bản chép lời";
                case "NOT_SCORABLE" -> "Chưa thể chấm";
                case "LEGACY_UNVERIFIED" -> "Kết quả cũ không khả dụng";
                default -> "Chưa có dữ liệu chấm";
            };
        }

        public String stateCssClass() {
            return switch (availability) {
                case "SCORED" -> "scored";
                case "NOT_SCORABLE" -> "not-scorable";
                case "LEGACY_UNVERIFIED" -> "legacy-unverified";
                default -> "unavailable";
            };
        }

        public String performanceCssClass() {
            return scored() ? band.cssClass() : "unavailable";
        }

        public String performanceLabel() {
            return scored() ? band.label() : availabilityLabel();
        }

        public ResultPerformanceLevel performanceLevel() {
            if (notScorable()) {
                return ResultPerformanceLevel.notScorableView();
            }
            if (!scored() || percentage == null) {
                return ResultPerformanceLevel.unavailableView();
            }
            if (percentage.compareTo(BigDecimal.valueOf(40)) < 0) {
                return new ResultPerformanceLevel(
                        "LIMITED", "Hạn chế", "제한적");
            }
            if (percentage.compareTo(BigDecimal.valueOf(60)) < 0) {
                return new ResultPerformanceLevel(
                        "MODEST", "Khiêm tốn", "보통");
            }
            if (percentage.compareTo(BigDecimal.valueOf(80)) < 0) {
                return new ResultPerformanceLevel(
                        "GOOD", "Tốt", "우수");
            }
            return new ResultPerformanceLevel(
                    "EXCELLENT", "Xuất sắc", "탁월");
        }

        public String performanceLevelCssClass() {
            return "is-" + performanceLevel().code()
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }

        public SpeakingCriterionResult unavailableView(String unavailableState) {
            return new SpeakingCriterionResult(
                    criterionId, label, null, null, null, coveredSegments, totalSegments,
                    ResultEvaluationBand.UNAVAILABLE, null, false, unavailableState,
                    requiresDirectAudioEvidence);
        }
    }

    public record ResultRubricCriterion(
            String criterionId,
            String label,
            BigDecimal score,
            BigDecimal maxScore,
            String feedback,
            String performanceLevel,
            String performanceLabel,
            String performanceLabelKo
    ) {
        public ResultRubricCriterion(
                String criterionId,
                String label,
                BigDecimal score,
                BigDecimal maxScore,
                String feedback,
                String performanceLevel,
                String performanceLabel
        ) {
            this(criterionId, label, score, maxScore, feedback,
                    performanceLevel, performanceLabel, null);
        }

        public String scoreDisplay() {
            if (score == null || maxScore == null) {
                return null;
            }
            return compactResultNumber(score) + "/" + compactResultNumber(maxScore);
        }

        public String performanceCssClass() {
            return switch (performanceLevel == null ? "" : performanceLevel) {
                case "LIMITED" -> "is-limited";
                case "MODEST" -> "is-modest";
                case "GOOD" -> "is-good";
                case "EXCELLENT" -> "is-excellent";
                default -> "is-unavailable";
            };
        }

        public String clozeItemLabel() {
            if (criterionId == null) {
                return null;
            }
            if (criterionId.contains("_BLANK_1_")) {
                return "Ô 1";
            }
            if (criterionId.contains("_BLANK_2_")) {
                return "Ô 2";
            }
            return null;
        }
    }

    public record WritingCriterionGroup(
            String code,
            String label,
            List<ResultRubricCriterion> criteria
    ) {
        public WritingCriterionGroup {
            criteria = immutableResultList(criteria);
        }
    }

    public enum ResultEvaluationBand {
        LIMITED("limited", "Cần cải thiện"),
        DEVELOPING("developing", "Đang phát triển"),
        GOOD("good", "Tốt"),
        VERY_GOOD("very-good", "Rất tốt"),
        UNAVAILABLE("unavailable", "Chưa có dữ liệu");

        private final String cssClass;
        private final String label;

        ResultEvaluationBand(String cssClass, String label) {
            this.cssClass = cssClass;
            this.label = label;
        }

        public String cssClass() {
            return cssClass;
        }

        public String label() {
            return label;
        }

        public static ResultEvaluationBand fromPercentage(BigDecimal percentage) {
            if (percentage == null) {
                return UNAVAILABLE;
            }
            if (percentage.compareTo(BigDecimal.valueOf(40)) < 0) {
                return LIMITED;
            }
            if (percentage.compareTo(BigDecimal.valueOf(60)) < 0) {
                return DEVELOPING;
            }
            if (percentage.compareTo(BigDecimal.valueOf(80)) < 0) {
                return GOOD;
            }
            return VERY_GOOD;
        }
    }

    private static <T> List<T> immutableResultList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String blankResultText(String value) {
        return value == null ? "" : value;
    }

    private static String sanitizeTeacherExplanation(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.contains("provenance:")
                || trimmed.contains("questionArtifactId")
                || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            return "";
        }
        return trimmed;
    }

    private static String tfngRelationForResult(String value) {
        return switch (value) {
            case "TRUE" -> "ENTAILED";
            case "FALSE" -> "CONTRADICTED";
            case "NOT_GIVEN" -> "NOT_STATED";
            default -> "";
        };
    }

    private static String tfngLabelVi(String value) {
        return switch (value) {
            case "TRUE" -> "Đúng";
            case "FALSE" -> "Sai";
            case "NOT_GIVEN" -> "Không có thông tin";
            default -> "Chưa xác định";
        };
    }

    private static String tfngLabelKo(String value) {
        return switch (value) {
            case "TRUE" -> "맞음";
            case "FALSE" -> "틀림";
            case "NOT_GIVEN" -> "정보 없음";
            default -> "확인 불가";
        };
    }

    private static String tfngRelationLabelVi(String value) {
        return switch (value) {
            case "ENTAILED" -> "Được nguồn xác nhận";
            case "CONTRADICTED" -> "Trái với nguồn";
            case "NOT_STATED" -> "Nguồn không nêu";
            default -> "Chưa xác định";
        };
    }

    private static String tfngRelationLabelKo(String value) {
        return switch (value) {
            case "ENTAILED" -> "근거에서 확인됨";
            case "CONTRADICTED" -> "근거와 모순됨";
            case "NOT_STATED" -> "근거에 제시되지 않음";
            default -> "확인 불가";
        };
    }

    private static String speakingAvailability(String availability, BigDecimal score) {
        String resolved = availability == null || availability.isBlank()
                ? (score == null ? "UNAVAILABLE" : "SCORED")
                : availability;
        return switch (resolved) {
            case "SCORED", "NOT_SCORABLE", "UNAVAILABLE", "LEGACY_UNVERIFIED" -> resolved;
            default -> "UNAVAILABLE";
        };
    }

    private static String defaultSpeakingProfileState(int coveredSegments, int totalSegments) {
        if (totalSegments <= 0) {
            return "UNAVAILABLE";
        }
        if (coveredSegments >= totalSegments) {
            return "READY";
        }
        return coveredSegments > 0 ? "PARTIAL" : "UNAVAILABLE";
    }

    private static boolean speakingCriterionRequiresDirectAudio(String criterionId) {
        return "S_FLUENCY".equals(criterionId)
                || "S_PRONUNCIATION_DELIVERY".equals(criterionId);
    }

    private static String compactResultNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
