package com.ksh.features.practice.ai.readinglistening;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.LecturerAsset;
import com.ksh.entities.PracticeQuestionGroupVersion;
import com.ksh.entities.PracticeQuestionVersion;
import com.ksh.entities.PracticeSectionVersion;
import com.ksh.entities.PracticeSetVersion;
import com.ksh.features.practice.ai.media.AiQuestionImageResolver;
import com.ksh.features.practice.assessment.AnswerSpec;
import com.ksh.features.practice.assessment.AssessmentContractCodec;
import com.ksh.features.practice.assessment.AssessmentSkill;
import com.ksh.features.practice.assessment.AssessmentStimulus;
import com.ksh.features.practice.assessment.CanonicalQuestionType;
import com.ksh.features.practice.assessment.ExplanationContext;
import com.ksh.features.practice.assessment.QuestionContent;
import com.ksh.features.practice.assessment.ObjectiveExplanationStrategyRegistry;
import com.ksh.features.practice.dto.PracticeDtos;
import com.ksh.features.practice.repository.LecturerAssetRepository;
import com.ksh.features.practice.repository.PracticeSetVersionRepository;
import com.ksh.features.practice.assessment.QuestionTypeResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExplanationInputFactory {

    public static final String ISSUE_EVIDENCE_UNAVAILABLE = "EVIDENCE_UNAVAILABLE";
    public static final String ISSUE_MEDIA_DIGEST_UNAVAILABLE = "MEDIA_DIGEST_UNAVAILABLE";
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)");
    private static final Pattern INTERNAL_MATERIAL_REFERENCE_PATTERN =
            Pattern.compile("/practice/materials/[1-9][0-9]*/content");

    private final AssessmentContractCodec contractCodec;
    private final QuestionTypeResolver typeResolver;
    private final LecturerAssetRepository assetRepository;
    private final PracticeSetVersionRepository setVersionRepository;
    private final ObjectMapper objectMapper;
    private final ExplanationFingerprintBuilder fingerprintBuilder;

    public ExplanationInputFactory(
            AssessmentContractCodec contractCodec,
            QuestionTypeResolver typeResolver,
            LecturerAssetRepository assetRepository,
            PracticeSetVersionRepository setVersionRepository,
            ObjectMapper objectMapper,
            ExplanationFingerprintBuilder fingerprintBuilder) {
        this.contractCodec = contractCodec;
        this.typeResolver = typeResolver;
        this.assetRepository = assetRepository;
        this.setVersionRepository = setVersionRepository;
        this.objectMapper = objectMapper;
        this.fingerprintBuilder = fingerprintBuilder;
    }

    public PreparedExplanation prepare(
            PracticeQuestionVersion question,
            PracticeQuestionGroupVersion group,
            PracticeSectionVersion section) {
        AssessmentSkill skill = AssessmentSkill.valueOf(section.getSkill().toUpperCase(Locale.ROOT));
        if (skill != AssessmentSkill.READING && skill != AssessmentSkill.LISTENING) {
            throw new IllegalArgumentException("Only Reading/Listening questions have shared explanations");
        }
        CanonicalQuestionType type = typeResolver.resolve(question.getQuestionType());
        if (type == CanonicalQuestionType.ESSAY || type == CanonicalQuestionType.SPEAKING) {
            throw new IllegalArgumentException("Subjective questions do not use answer explanation artifacts");
        }
        QuestionContent content = blank(question.getQuestionContentJson())
                ? contractCodec.adaptLegacyContent(question.getOptionsJson(), question.getQuestionType())
                : contractCodec.readQuestionContent(question.getQuestionContentJson(), type);
        AnswerSpec answerSpec = blank(question.getAnswerSpecJson())
                ? contractCodec.adaptLegacyAnswerSpec(
                        question.getQuestionType(), question.getAnswerKey(), content)
                : contractCodec.readAnswerSpec(question.getAnswerSpecJson(), content);

        PracticeSetVersion setVersion = setVersionRepository
                .findByPublishedVersionId(question.getPublishedVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Published explanation input has no immutable set version"));
        String optionLabelMode = PracticeDtos.getOptionLabelMode(
                setVersion.getTitle(), setVersion.getMetadataJson());
        String instruction = sanitizeEvidenceText(firstNonBlank(
                group == null ? null : group.getInstruction(),
                section.getInstructions()));

        String provenance = provenance(group);
        String immutablePrompt = sanitizeEvidenceText(question.getPrompt());
        AssessmentStimulus stimulus;
        if (skill == AssessmentSkill.READING
                && group != null
                && !blank(group.getPassageText())) {
            stimulus = AssessmentStimulus.readingPassage(
                    sanitizeEvidenceText(group.getPassageText()),
                    provenance);
        } else if (skill == AssessmentSkill.LISTENING
                && group != null
                && !blank(group.getTranscriptText())) {
            stimulus = AssessmentStimulus.listeningAudio(
                    null,
                    sanitizeEvidenceText(group.getTranscriptText()),
                    provenance,
                    transcriptApproved(group));
        } else {
            stimulus = AssessmentStimulus.standalonePrompt(
                    immutablePrompt,
                    provenance);
        }

        List<RuntimeMedia> runtimeMedia = collectMedia(content, question, group, section);
        List<ExplanationArtifactInput.MediaDescriptor> descriptors = new ArrayList<>();
        String readinessIssue = null;
        for (RuntimeMedia media : runtimeMedia) {
            Long assetId = AiQuestionImageResolver.internalAssetId(media.reference());
            LecturerAsset asset = assetId == null ? null : assetRepository.findById(assetId).orElse(null);
            if (!validAssetDigest(asset)) {
                readinessIssue = ISSUE_MEDIA_DIGEST_UNAVAILABLE;
                descriptors.add(new ExplanationArtifactInput.MediaDescriptor(
                        media.role(), media.kind(), "UNAVAILABLE", "", null));
                continue;
            }
            descriptors.add(new ExplanationArtifactInput.MediaDescriptor(
                    media.role(),
                    media.kind(),
                    asset.getSha256().toLowerCase(Locale.ROOT),
                    normalize(asset.getMimeType()),
                    asset.getFileSize()));
        }
        descriptors.sort(Comparator.comparing(ExplanationArtifactInput.MediaDescriptor::role)
                .thenComparing(ExplanationArtifactInput.MediaDescriptor::kind));

        boolean hasImage = runtimeMedia.stream().anyMatch(media -> "IMAGE".equals(media.kind()));
        if (readinessIssue == null && !stimulus.hasUsableEvidence() && !hasImage) {
            readinessIssue = ISSUE_EVIDENCE_UNAVAILABLE;
        }
        SanitizedAssessment sanitizedAssessment = sanitizeAssessment(content, answerSpec);
        QuestionContent sanitizedContent = sanitizedAssessment.content();
        AnswerSpec sanitizedAnswerSpec = sanitizedAssessment.answerSpec();
        AssessmentStimulus sanitizedStimulus = new AssessmentStimulus(
                stimulus.schemaVersion(), stimulus.type(), stimulus.passageText(),
                stimulus.transcriptText(), null, stimulus.provenance(), stimulus.approved());
        ObjectiveExplanationStrategyRegistry.Selection explanationStrategy =
                blank(question.getExplanationStrategyCode())
                        ? defaultStrategy(type, sanitizedAnswerSpec)
                        : ObjectiveExplanationStrategyRegistry.requireSelection(
                                type,
                                question.getExplanationStrategyRegistryVersion(),
                                question.getExplanationStrategyCode(),
                                question.getExplanationStrategyVersion());
        ExplanationArtifactInput input = new ExplanationArtifactInput(
                ExplanationArtifactInput.SCHEMA_VERSION,
                skill,
                type,
                immutablePrompt,
                instruction,
                sanitizedContent,
                sanitizedAnswerSpec,
                sanitizedStimulus,
                sanitizeEvidenceText(question.getExplanation()),
                optionLabelMode,
                ReadingListeningExplanationClient.EXPLANATION_LANGUAGE,
                explanationStrategy,
                descriptors,
                readinessIssue);
        ExplanationFingerprint fingerprint = fingerprintBuilder.build(input);
        ExplanationContext context = readinessIssue == null
                ? new ExplanationContext(
                        ExplanationContext.SCHEMA_VERSION,
                        question.getQuestionId(),
                        question.getId(),
                        question.getQuestionNo(),
                        skill,
                        type,
                        immutablePrompt,
                        instruction,
                        sanitizedContent,
                        sanitizedAnswerSpec,
                        null,
                        sanitizedStimulus,
                        sanitizeEvidenceText(question.getExplanation()),
                        input.explanationLanguage(),
                        optionLabelMode,
                        explanationStrategy)
                : null;
        return new PreparedExplanation(input, fingerprint, context, runtimeMedia);
    }

    private List<RuntimeMedia> collectMedia(
            QuestionContent content,
            PracticeQuestionVersion question,
            PracticeQuestionGroupVersion group,
            PracticeSectionVersion section) {
        List<RuntimeMedia> media = new ArrayList<>();
        add(media, "question.image", "IMAGE", content.imageReference());
        if (blank(content.imageReference())) {
            add(media, "question.image", "IMAGE",
                    firstMarkdownImageReference(question.getPrompt()));
        }
        add(media, "question.audio", "AUDIO", content.audioReference());
        for (int index = 0; index < content.options().size(); index++) {
            QuestionContent.Option option = content.options().get(index);
            add(media, "option." + (index + 1) + ".image", "IMAGE", option.imageReference());
        }
        if (group != null) {
            add(media, "group.image", "IMAGE", group.getImageUrl());
            if (blank(group.getImageUrl())) {
                add(media, "group.image", "IMAGE", firstNonBlank(
                        firstMarkdownImageReference(group.getPassageText()),
                        firstNonBlank(
                                firstMarkdownImageReference(group.getTranscriptText()),
                                firstMarkdownImageReference(group.getInstruction()))));
            }
            add(media, "group.audio", "AUDIO", group.getAudioUrl());
        }
        if ((group == null || blank(group.getInstruction()))
                && (group == null || blank(group.getImageUrl()))) {
            add(media, "section.image", "IMAGE",
                    firstMarkdownImageReference(section.getInstructions()));
        }
        media.sort(Comparator.comparing(RuntimeMedia::role).thenComparing(RuntimeMedia::kind));
        return List.copyOf(media);
    }

    private static SanitizedAssessment sanitizeAssessment(
            QuestionContent content,
            AnswerSpec answerSpec) {
        Map<String, String> optionIds = new LinkedHashMap<>();
        List<QuestionContent.Option> options = new ArrayList<>();
        for (int index = 0; index < content.options().size(); index++) {
            QuestionContent.Option option = content.options().get(index);
            String canonicalId = "option_" + (index + 1);
            optionIds.put(option.id(), canonicalId);
            options.add(new QuestionContent.Option(
                    canonicalId, sanitizeEvidenceText(option.text()), null));
        }

        Map<String, String> blankIds = new LinkedHashMap<>();
        List<QuestionContent.Blank> blanks = new ArrayList<>();
        for (int index = 0; index < content.blanks().size(); index++) {
            QuestionContent.Blank blank = content.blanks().get(index);
            String canonicalId = "blank_" + (index + 1);
            blankIds.put(blank.id(), canonicalId);
            blanks.add(new QuestionContent.Blank(
                    canonicalId, sanitizeEvidenceText(blank.prompt())));
        }

        QuestionContent sanitizedContent = new QuestionContent(
                content.schemaVersion(), options, blanks, null, null, null,
                null, content.languageTag());
        AnswerSpec sanitizedAnswerSpec = new AnswerSpec(
                answerSpec.schemaVersion(),
                answerSpec.questionType(),
                answerSpec.correctOptionIds().stream()
                        .map(optionId -> requireCanonicalId(optionIds, optionId, "option"))
                        .toList(),
                answerSpec.correctValue(),
                answerSpec.blanks().stream()
                        .map(blank -> new AnswerSpec.BlankAnswer(
                                requireCanonicalId(blankIds, blank.blankId(), "blank"),
                                blank.acceptedValues()))
                        .toList(),
                answerSpec.scoringPolicyCode());
        return new SanitizedAssessment(sanitizedContent, sanitizedAnswerSpec);
    }

    private static String requireCanonicalId(
            Map<String, String> canonicalIds,
            String sourceId,
            String kind) {
        String canonicalId = canonicalIds.get(sourceId);
        if (canonicalId == null) {
            throw new IllegalArgumentException(
                    "Published answer spec references an unknown " + kind + " ID");
        }
        return canonicalId;
    }

    private static void add(List<RuntimeMedia> media, String role, String kind, String reference) {
        if (!blank(reference)) {
            media.add(new RuntimeMedia(role, kind, reference.trim()));
        }
    }

    private static boolean validAssetDigest(LecturerAsset asset) {
        return asset != null
                && asset.isContentVerified()
                && asset.getDeletedAt() == null
                && ("ACTIVE".equals(asset.getStatus()) || "ARCHIVED".equals(asset.getStatus()))
                && asset.getSha256() != null
                && asset.getSha256().matches("(?i)[0-9a-f]{64}");
    }

    private String provenance(PracticeQuestionGroupVersion group) {
        if (group == null || blank(group.getStimulusProvenanceJson())) {
            return "PUBLISHED_SNAPSHOT";
        }
        try {
            String source = objectMapper.readTree(group.getStimulusProvenanceJson())
                    .path("source").asText("").trim();
            return source.isBlank() ? "PUBLISHED_SNAPSHOT" : source;
        } catch (Exception exception) {
            return "PUBLISHED_SNAPSHOT";
        }
    }

    private boolean transcriptApproved(PracticeQuestionGroupVersion group) {
        if (group == null || blank(group.getTranscriptText())) {
            return false;
        }
        if (blank(group.getStimulusProvenanceJson())) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(group.getStimulusProvenanceJson());
            return root.path("approved").asBoolean(false);
        } catch (Exception exception) {
            return false;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    private static String firstMarkdownImageReference(String value) {
        if (blank(value)) {
            return null;
        }
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(value);
        while (matcher.find()) {
            String reference = matcher.group(1).trim();
            if (AiQuestionImageResolver.isInternalMaterialReference(reference)) {
                return reference;
            }
        }
        return null;
    }

    static String sanitizeEvidenceText(String value) {
        if (blank(value)) {
            return value;
        }
        String withoutMarkdownImages = MARKDOWN_IMAGE_PATTERN.matcher(value).replaceAll(" ");
        return INTERNAL_MATERIAL_REFERENCE_PATTERN.matcher(withoutMarkdownImages)
                .replaceAll(" ")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static ObjectiveExplanationStrategyRegistry.Selection defaultStrategy(
            CanonicalQuestionType type, AnswerSpec answerSpec) {
        ObjectiveExplanationStrategyRegistry.Code code = switch (type) {
            case SINGLE_CHOICE, MULTIPLE_ANSWER -> ObjectiveExplanationStrategyRegistry.Code.MCQ_OPTION_ELIMINATION;
            case MATCHING -> ObjectiveExplanationStrategyRegistry.Code.MATCHING_MATRIX;
            case TRUE_FALSE_NOT_GIVEN -> (answerSpec != null && "NOT_GIVEN".equalsIgnoreCase(answerSpec.correctValue()))
                    ? ObjectiveExplanationStrategyRegistry.Code.NOT_GIVEN_BOUNDARY
                    : ObjectiveExplanationStrategyRegistry.Code.TFNG_CONTRADICTION_TABLE;
            case FILL_BLANK -> ObjectiveExplanationStrategyRegistry.Code.FILL_SLOT_GRAMMAR_ANALYSIS;
            case ESSAY, SPEAKING -> ObjectiveExplanationStrategyRegistry.Code.EXACT_EVIDENCE_ONLY;
        };
        return new ObjectiveExplanationStrategyRegistry.Selection(
                ObjectiveExplanationStrategyRegistry.CURRENT_REGISTRY_VERSION,
                code.name(),
                ObjectiveExplanationStrategyRegistry.STRATEGY_VERSION);
    }

    public record PreparedExplanation(
            ExplanationArtifactInput input,
            ExplanationFingerprint fingerprint,
            ExplanationContext context,
            List<RuntimeMedia> runtimeMedia
    ) {
    }

    public record RuntimeMedia(String role, String kind, String reference) {
    }

    private record SanitizedAssessment(
            QuestionContent content,
            AnswerSpec answerSpec) {
    }
}
