package com.ksh.features.library.service;

import com.ksh.common.HtmlSanitizer;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.entities.Lesson;
import com.ksh.entities.LessonActivity;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.LessonTemplate;
import com.ksh.entities.LessonTemplateAttachment;
import com.ksh.entities.LibraryAsset;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.lessons.service.LessonActivityWriter;
import com.ksh.features.lessons.service.LessonsReorderService;
import com.ksh.features.lessons.service.SectionsService;
import com.ksh.features.lessons.support.VimeoEmbedUrl;
import com.ksh.features.lessons.support.YouTubeEmbedUrl;
import com.ksh.features.classes.dto.ClassesDtos.ClassRow;
import com.ksh.features.library.dto.LibraryDtos.AttachTargetClassRow;
import com.ksh.features.library.dto.LibraryDtos.LessonCloneResult;
import com.ksh.features.library.dto.LibraryDtos.MaterialOption;
import com.ksh.features.library.dto.LibraryDtos.LessonTemplatePageView;
import com.ksh.features.library.dto.LibraryDtos.LessonTemplateRow;
import com.ksh.features.library.dto.LibraryDtos.LessonResourceRow;
import com.ksh.features.library.dto.LibraryDtos.SubjectContext;
import com.ksh.features.library.dto.LibraryDtos.ChapterView;
import com.ksh.features.library.dto.LibraryDtos.SubjectLibraryStats;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.features.library.repository.LessonTemplateAttachmentRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;

import static com.ksh.common.IConstant.CONTENT_TYPE_PDF;
import static com.ksh.common.IConstant.CONTENT_TYPE_RICHTEXT;
import static com.ksh.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ksh.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ksh.common.IConstant.MAX_LIBRARY_PAGE_SIZE;
import static com.ksh.common.IConstant.MSG_TEMPLATE_BODY_INCOMPLETE;
import static com.ksh.common.IConstant.MSG_TEMPLATE_NOT_FOUND;
import static com.ksh.common.IConstant.VIDEO_PROVIDER_UPLOAD;
import static com.ksh.common.IConstant.VIDEO_PROVIDER_VIMEO;
import static com.ksh.common.IConstant.VIDEO_PROVIDER_YOUTUBE;
import static com.ksh.entities.LibraryAsset.KIND_DOCUMENT;
import static com.ksh.entities.LibraryAsset.KIND_VIDEO;
import static com.ksh.entities.LessonAttachment.ORIGIN_CANONICAL_TEMPLATE;
import static com.ksh.common.IConstant.MSG_STORAGE_UPLOAD_FAILED;

/**
 * Canonical subject lessons authored in Library and distributed as published
 * snapshots to one or more classes with the same subject code.
 */
@Service
public class LessonTemplateService {

    private final LessonTemplateRepository templateRepository;
    private final LessonTemplateAttachmentRepository templateAttachmentRepository;
    private final LibraryAssetRepository assetRepository;
    private final LibraryService libraryService;
    private final LessonRepository lessonRepository;
    private final LessonAttachmentRepository attachmentRepository;
    private final SectionRepository sectionRepository;
    private final ClassRepository classRepository;
    private final LessonsReorderService reorderService;
    private final SectionsService sectionsService;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;
    private final LibrarySubjectResolver subjectResolver;
    private final UserRepository userRepository;

    public LessonTemplateService(LessonTemplateRepository templateRepository,
                                 LessonTemplateAttachmentRepository templateAttachmentRepository,
                                 LibraryAssetRepository assetRepository,
                                 LibraryService libraryService,
                                 LessonRepository lessonRepository,
                                 LessonAttachmentRepository attachmentRepository,
                                 SectionRepository sectionRepository,
                                 ClassRepository classRepository,
                                 LessonsReorderService reorderService,
                                 SectionsService sectionsService,
                                 ClassesService classesService,
                                 LessonActivityWriter activityWriter,
                                 LibrarySubjectResolver subjectResolver,
                                 UserRepository userRepository) {
        this.templateRepository = templateRepository;
        this.templateAttachmentRepository = templateAttachmentRepository;
        this.assetRepository = assetRepository;
        this.libraryService = libraryService;
        this.lessonRepository = lessonRepository;
        this.attachmentRepository = attachmentRepository;
        this.sectionRepository = sectionRepository;
        this.classRepository = classRepository;
        this.reorderService = reorderService;
        this.sectionsService = sectionsService;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
        this.subjectResolver = subjectResolver;
        this.userRepository = userRepository;
    }

    /** Dropdown options: every class owned by the lecturer (capped). */
    private List<AttachTargetClassRow> listOwnedClassOptions(Long lecturerId, Role role,
                                                             Long subjectId) {
        Page<ClassRow> owned = classesService.listForUser(
                lecturerId, role, PageRequest.of(0, MAX_LIBRARY_PAGE_SIZE));
        List<AttachTargetClassRow> options = new ArrayList<>(owned.getNumberOfElements());
        for (ClassRow row : owned.getContent()) {
            classRepository.findById(row.id())
                    .filter(clazz -> subjectId.equals(clazz.getSubjectId()))
                    .filter(clazz -> ClassEntity.STATUS_ACTIVE.equals(clazz.getStatus()))
                    .ifPresent(clazz -> options.add(new AttachTargetClassRow(
                            row.id(), row.name(), row.code(),
                            hasDistributedSubjectSnapshot(row.id(), subjectId))));
        }
        return options;
    }

    /**
     * The legacy snapshot schema has no source-template FK. A matching
     * chapter/title pair is therefore the durable signal that this class has
     * already received at least part of the subject package. Re-distribution
     * is disabled in the UI because the transactional distributor would
     * reject that duplicate anyway.
     */
    private boolean hasDistributedSubjectSnapshot(Long classId, Long subjectId) {
        List<LessonTemplate> templates = templateRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subjectId);
        if (templates.isEmpty()) return false;
        Map<String, Long> sectionIds = sectionRepository
                .findByClassIdOrderByDisplayOrderAsc(classId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        section -> section.getTitle().toLowerCase(java.util.Locale.ROOT),
                        Section::getId,
                        (first, ignored) -> first));
        return templates.stream().anyMatch(template -> {
            Long sectionId = sectionIds.get(template.getChapterTitle()
                    .toLowerCase(java.util.Locale.ROOT));
            return sectionId != null && lessonRepository
                    .findFirstBySectionIdAndTitleIgnoreCase(sectionId, template.getTitle())
                    .isPresent();
        });
    }

    /** Saved templates only (secondary list / management). */
    @Transactional(readOnly = true)
    public LessonTemplatePageView list(Long ownerId, Role role, Long subjectId,
                                       String q, int page, int size) {
        Department subject = subjectResolver.require(ownerId, role, subjectId);
        PageRequest pr = pageRequest(page, size);
        String qNorm = normalizeQ(q);
        Page<LessonTemplate> result = templateRepository.searchSubject(
                subject.getId(), qNorm, pr);
        Map<Long, String> uploaderNames = uploaderNames(result.getContent());
        boolean authoringOpen = !subject.isLibraryLocked();
        Page<LessonTemplateRow> rows = result.map(t -> toRow(t, subject.getCode(),
                authoringOpen && ownerId.equals(t.getOwnerId()), uploaderNames));
        long templateCount = templateRepository.countBySubjectId(subject.getId());
        Map<Integer, List<LessonTemplateRow>> byChapter = new LinkedHashMap<>();
        rows.getContent().forEach(row -> byChapter
                .computeIfAbsent(row.chapterNumber(), ignored -> new ArrayList<>()).add(row));
        List<ChapterView> chapters = byChapter.entrySet().stream()
                .map(entry -> new ChapterView(entry.getKey(),
                        entry.getValue().get(0).chapterTitle(), List.copyOf(entry.getValue()),
                        entry.getValue().stream().allMatch(LessonTemplateRow::canManage)))
                .toList();
        return new LessonTemplatePageView(
                rows,
                qNorm == null ? "" : qNorm,
                subject.getId(),
                subject.getCode(),
                subject.getName(),
                subject.getDescription(),
                subjectOptions(ownerId, role),
                listOwnedClassOptions(ownerId, role, subject.getId()),
                chapters,
                templateCount,
                subject.isLibraryLocked(),
                role == Role.LEADER && ownerId.equals(subject.getLeaderUserId()));
    }

    @Transactional(readOnly = true)
    public LessonTemplateForm loadForm(Long ownerId, Role role, Long templateId,
                                       Long requestedSubjectId) {
        return loadForm(ownerId, role, templateId, requestedSubjectId, false);
    }

    @Transactional(readOnly = true)
    public LessonTemplateForm loadForm(Long ownerId, Role role, Long templateId,
                                       Long requestedSubjectId, boolean startNewChapter) {
        return loadForm(ownerId, role, templateId, requestedSubjectId, startNewChapter, null);
    }

    @Transactional(readOnly = true)
    public LessonTemplateForm loadForm(Long ownerId, Role role, Long templateId,
                                       Long requestedSubjectId, boolean startNewChapter,
                                       Integer requestedChapterNumber) {
        LessonTemplateForm form = new LessonTemplateForm();
        if (templateId == null) {
            Department subject = requireAuthoringOpen(ownerId, role, requestedSubjectId);
            form.setSubjectId(subject.getId());
            List<LessonTemplate> existing = templateRepository
                    .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId());
            if (existing.isEmpty()) {
                form.setChapterNumber(1);
                form.setLessonNumber(1);
                form.setChapterTitle("Chương mới");
            } else if (requestedChapterNumber != null) {
                LessonTemplate lastInChapter = existing.stream()
                        .filter(row -> row.getChapterOrder() == requestedChapterNumber)
                        .max(java.util.Comparator.comparingInt(LessonTemplate::getDisplayOrder))
                        .orElseThrow(() -> new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND));
                form.setChapterNumber(lastInChapter.getChapterOrder());
                form.setChapterTitle(stripChapterPrefix(lastInChapter.getChapterTitle()));
                form.setLessonNumber(lastInChapter.getDisplayOrder() + 1);
            } else if (startNewChapter) {
                LessonTemplate last = existing.get(existing.size() - 1);
                form.setChapterNumber(last.getChapterOrder() + 1);
                form.setChapterTitle("Chương mới");
                form.setLessonNumber(existing.stream()
                        .mapToInt(LessonTemplate::getDisplayOrder).max().orElse(0) + 1);
            } else {
                LessonTemplate last = existing.get(existing.size() - 1);
                form.setChapterNumber(last.getChapterOrder());
                form.setChapterTitle(stripChapterPrefix(last.getChapterTitle()));
                form.setLessonNumber(existing.stream()
                        .filter(row -> row.getChapterOrder() == last.getChapterOrder())
                        .mapToInt(LessonTemplate::getDisplayOrder).max().orElse(0) + 1);
            }
            form.setTitle("Bài học mới");
            form.setContentType(CONTENT_TYPE_RICHTEXT);
            return form;
        }
        LessonTemplate template = getOwned(ownerId, templateId);
        requireAuthoringOpen(ownerId, role, template.getSubjectId());
        form.setId(template.getId());
        form.setSubjectId(template.getSubjectId());
        form.setChapterNumber(template.getChapterOrder());
        form.setChapterTitle(stripChapterPrefix(template.getChapterTitle()));
        form.setLessonNumber(template.getDisplayOrder());
        form.setTitle(stripLessonPrefix(template.getTitle()));
        form.setContentType(template.getContentType());
        form.setContentRichtext(template.getContentRichtext() == null ? "" : template.getContentRichtext());
        form.setPdfLibraryAssetId(template.getPdfLibraryAssetId());
        form.setVideoProvider(template.getVideoProvider());
        // An uploaded video's durable identity is its owner-scoped LibraryAsset id.
        // Never post the internal storage key back through the external URL field.
        form.setVideoUrl(VIDEO_PROVIDER_UPLOAD.equals(template.getVideoProvider())
                ? "" : template.getVideoUrl());
        form.setVideoSummary(template.getVideoSummary());
        form.setVideoLibraryAssetId(template.getVideoLibraryAssetId());
        LinkedHashSet<Long> retainedAssets = templateAttachmentRepository
                .findByTemplateIdOrderByDisplayOrderAsc(templateId).stream()
                .map(LessonTemplateAttachment::getLibraryAssetId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // Primary PDF/video fields are not supplementary attachments. Keeping
        // them separate avoids duplicating a storage-backed VIDEO into the
        // generic material list on every edit.
        form.setMaterialAssetIds(new ArrayList<>(retainedAssets));
        return form;
    }

    @Transactional
    public void renameChapter(Long ownerId, Role role, Long subjectId,
                              int chapterNumber, String title) {
        Department subject = requireAuthoringOpen(ownerId, role, subjectId);
        String chapterTitle = canonicalChapter(chapterNumber,
                requireText(stripChapterPrefix(title), "Tên chương không được để trống"));
        List<LessonTemplate> rows = templateRepository
                .findByOwnerIdAndSubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(
                        ownerId, subject.getId());
        List<LessonTemplate> chapterRows = rows.stream()
                .filter(row -> row.getChapterOrder() == chapterNumber).toList();
        if (chapterRows.isEmpty()) throw new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND);
        chapterRows.forEach(row -> row.updateSequence(
                row.getChapterOrder(), chapterTitle, row.getDisplayOrder()));
        templateRepository.saveAll(chapterRows);
    }

    @Transactional
    public void renameLesson(Long ownerId, Role role, Long templateId, String title) {
        LessonTemplate template = getOwned(ownerId, templateId);
        requireAuthoringOpen(ownerId, role, template.getSubjectId());
        String description = requireText(stripLessonPrefix(title),
                "Tên bài học không được để trống");
        template.rename(canonicalLesson(template.getDisplayOrder(), description));
        templateRepository.saveAndFlush(template);
        // Existing class lessons are immutable distribution snapshots. The
        // renamed canonical lesson reaches a class only through distribute().
    }

    @Transactional
    public void reorderChapters(Long ownerId, Role role, Long subjectId,
                                List<Integer> chapterNumbers) {
        Department subject = requireAuthoringOpen(ownerId, role, subjectId);
        List<LessonTemplate> rows = new ArrayList<>(templateRepository
                .findByOwnerIdAndSubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(
                        ownerId, subject.getId()));
        List<Integer> existing = rows.stream().map(LessonTemplate::getChapterOrder)
                .distinct().sorted().toList();
        List<Integer> requested = chapterNumbers == null ? List.of()
                : chapterNumbers.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (requested.size() != existing.size()
                || !new java.util.HashSet<>(requested).equals(new java.util.HashSet<>(existing))) {
            throw new IllegalArgumentException("Thứ tự chương không hợp lệ");
        }
        // Snapshot the original groups before mutating any managed entity. The
        // previous two-phase +10_000 algorithm changed titles and positions on
        // the same rows before grouping them again, which could make a chapter
        // appear empty after refresh. There is no unique order constraint, so a
        // single transaction and a single flush is safer and sufficient.
        Map<Integer, List<LessonTemplate>> originalChapters = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        LessonTemplate::getChapterOrder,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toCollection(ArrayList::new)));

        int nextLesson = 1;
        for (int index = 0; index < requested.size(); index++) {
            int oldChapter = requested.get(index);
            int newChapter = index + 1;
            List<LessonTemplate> chapterRows = originalChapters.get(oldChapter);
            if (chapterRows == null || chapterRows.isEmpty()) {
                throw new IllegalArgumentException("Thứ tự chương không hợp lệ");
            }
            chapterRows.sort(java.util.Comparator.comparingInt(LessonTemplate::getDisplayOrder));
            String description = stripChapterPrefix(chapterRows.get(0).getChapterTitle());
            String chapterTitle = canonicalChapter(newChapter, description);
            for (LessonTemplate row : chapterRows) {
                row.updateSequence(newChapter, chapterTitle, nextLesson++);
            }
        }
        templateRepository.saveAllAndFlush(rows);
    }

    @Transactional(readOnly = true)
    public List<MaterialOption> materialOptions(Long ownerId) {
        return assetRepository.findByOwnerIdOrderByTitleAsc(ownerId).stream()
                .map(asset -> new MaterialOption(
                        asset.getId(), asset.getTitle(), asset.getKind(), asset.getMimeType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubjectContext subjectContext(Long ownerId, Role role, Long subjectId) {
        Department subject = subjectResolver.require(ownerId, role, subjectId);
        return new SubjectContext(subject.getId(), subject.getCode(), subject.getName(),
                subject.getDescription());
    }

    @Transactional(readOnly = true)
    public List<SubjectContext> subjectOptions(Long ownerId, Role role) {
        return subjectResolver.allowed(ownerId, role).stream()
                .map(subject -> new SubjectContext(subject.getId(), subject.getCode(),
                        subject.getName(), subject.getDescription()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<com.ksh.features.library.dto.LibraryDtos.SubjectLibraryStats> getLibrarySubjectStats(Long ownerId, Role role) {
        return subjectResolver.allowed(ownerId, role).stream()
                .map(subject -> {
                    List<LessonTemplate> templates = templateRepository
                            .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId());
                    
                    int chapterCount = (int) templates.stream()
                            .map(LessonTemplate::getChapterOrder)
                            .distinct()
                            .count();
                    
                    int lessonCount = templates.size();
                    
                    return new com.ksh.features.library.dto.LibraryDtos.SubjectLibraryStats(
                            subject.getId(),
                            subject.getCode(),
                            subject.getName(),
                            subject.getDescription(),
                            chapterCount,
                            lessonCount
                    );
                })
                .toList();
    }

    @Transactional
    public LessonTemplateRow saveForm(Long ownerId, Role role, LessonTemplateForm form) {
        Department subject = requireAuthoringOpen(ownerId, role, form.getSubjectId());
        int chapterNumber = requirePositive(form.getChapterNumber(), "Số chương phải từ 1 trở lên");
        String chapterDescription = requireText(form.getChapterTitle(),
                "Nội dung tên chương không được để trống");
        String lessonDescription = requireText(form.getTitle(),
                "Nội dung tên bài học không được để trống");
        String type = form.getContentType();
        Lesson.validateContentType(type);
        ingestInlineUploads(ownerId, form);
        lockFormAssets(ownerId, form);

        List<LessonTemplate> ordered = new ArrayList<>(templateRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId()));
        LessonTemplate template;
        if (form.getId() == null) {
            String chapter = existingChapterTitle(ordered, chapterNumber);
            if (chapter == null) chapter = canonicalChapter(chapterNumber, chapterDescription);
            int lessonNumber = insertionOrder(ordered, chapterNumber);
            shiftFrom(ordered, lessonNumber, 1);
            form.setLessonNumber(lessonNumber);
            String title = canonicalLesson(lessonNumber, lessonDescription);
            template = new LessonTemplate(ownerId, subject.getId(), chapterNumber,
                    chapter, lessonNumber, title, type);
        } else {
            template = getOwned(ownerId, form.getId());
            requireTemplateSubject(template, subject.getId());
            int oldChapter = template.getChapterOrder();
            int oldOrder = template.getDisplayOrder();
            List<LessonTemplate> withoutCurrent = ordered.stream()
                    .filter(row -> !row.getId().equals(template.getId()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            int lessonNumber = oldOrder;
            String chapter;
            if (oldChapter == chapterNumber) {
                String sameChapterTitle = canonicalChapter(chapterNumber, chapterDescription);
                chapter = sameChapterTitle;
                ordered.stream().filter(row -> row.getChapterOrder() == chapterNumber)
                        .forEach(row -> row.updateSequence(
                                chapterNumber, sameChapterTitle, row.getDisplayOrder()));
            } else {
                shiftAfter(withoutCurrent, oldOrder, -1);
                chapter = existingChapterTitle(withoutCurrent, chapterNumber);
                if (chapter == null) chapter = canonicalChapter(chapterNumber, chapterDescription);
                lessonNumber = insertionOrder(withoutCurrent, chapterNumber);
                shiftFrom(withoutCurrent, lessonNumber, 1);
            }
            form.setLessonNumber(lessonNumber);
            String title = canonicalLesson(lessonNumber, lessonDescription);
            template.updateAuthoring(chapterNumber, chapter, lessonNumber, title, type);
        }
        templateRepository.saveAll(ordered.stream()
                .filter(row -> template.getId() == null || !row.getId().equals(template.getId()))
                .toList());
        applyFormBody(template, form, ownerId);
        LessonTemplate saved = templateRepository.saveAndFlush(template);

        templateAttachmentRepository.deleteByTemplateId(saved.getId());
        int order = 0;
        List<Long> selectedMaterials = form.getMaterialAssetIds() == null
                ? List.of() : form.getMaterialAssetIds();
        for (Long assetId : new LinkedHashSet<>(selectedMaterials)) {
            if (assetId == null) continue;
            LibraryAsset asset = libraryService.getOwnedAssetForUpdate(ownerId, assetId);
            if (!KIND_DOCUMENT.equals(asset.getKind()) && !KIND_VIDEO.equals(asset.getKind())) {
                throw new IllegalArgumentException("Tệp đính kèm không được hỗ trợ");
            }
            templateAttachmentRepository.save(new LessonTemplateAttachment(
                    saved.getId(), asset.getId(), asset.getOriginalFilename(),
                    asset.getMimeType(), asset.getSizeBytes(), order++));
        }
        templateRepository.flush();
        // Distributed lessons are immutable class snapshots. Updating the
        // canonical Library row must not mutate content learners are already
        // consuming; an explicit distribute action refreshes that snapshot.
        return toRow(saved, subject.getCode(), true, uploaderNames(List.of(saved)));
    }

    /** Detaches a reusable asset from one lesson without deleting its R2/local object. */
    @Transactional
    public void detachResource(Long ownerId, Role role, Long templateId, Long assetId) {
        LessonTemplate template = getOwned(ownerId, templateId);
        requireAuthoringOpen(ownerId, role, template.getSubjectId());
        LessonTemplateAttachment attachment = templateAttachmentRepository
                .findByTemplateIdAndLibraryAssetId(templateId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("Tài nguyên không còn gắn với bài học"));
        templateAttachmentRepository.delete(attachment);
        template.touch();
        templateRepository.saveAndFlush(template);
        // Keep existing class snapshots unchanged until explicit redistribution.
    }

    private void refreshSnapshot(Lesson lesson, LessonTemplate template, Long actorId) {
        lesson.switchContentTypeTo(CONTENT_TYPE_RICHTEXT);
        lesson.updateContent("");
        lessonRepository.saveAndFlush(lesson);
        // Replace only material owned by the canonical snapshot. Explicit
        // CLASS_PRIVATE shares into this class must survive template refresh.
        attachmentRepository.deleteByLessonIdAndOriginScope(
                lesson.getId(), ORIGIN_CANONICAL_TEMPLATE);
        lesson.rename(template.getTitle());
        applyTemplateBodyToLesson(lesson, template, template.getOwnerId(), actorId);
        lesson.publish();
        Lesson saved = lessonRepository.saveAndFlush(lesson);
        cloneSupplementaryAttachments(template, saved, actorId);
        activityWriter.write(saved.getId(), LessonActivity.TYPE_PUBLISHED,
                "Cập nhật từ Library: " + saved.getTitle(), actorId);
    }

    /** Locks every form-selected asset in a deterministic order before mutation. */
    private void lockFormAssets(Long ownerId, LessonTemplateForm form) {
        List<Long> assetIds = new ArrayList<>();
        assetIds.add(form.getPdfLibraryAssetId());
        assetIds.add(form.getVideoLibraryAssetId());
        if (form.getMaterialAssetIds() != null) {
            assetIds.addAll(form.getMaterialAssetIds());
        }
        lockAssets(ownerId, assetIds);
    }

    /** Locks all canonical assets before refreshing any existing lesson row. */
    private void lockTemplateAssets(LessonTemplate template) {
        List<Long> assetIds = new ArrayList<>();
        assetIds.add(template.getPdfLibraryAssetId());
        assetIds.add(template.getVideoLibraryAssetId());
        templateAttachmentRepository
                .findByTemplateIdOrderByDisplayOrderAsc(template.getId()).stream()
                .map(LessonTemplateAttachment::getLibraryAssetId)
                .forEach(assetIds::add);
        lockAssets(template.getOwnerId(), assetIds);
    }

    private void lockAssets(Long ownerId, List<Long> assetIds) {
        assetIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(assetId -> libraryService.getOwnedAssetForUpdate(ownerId, assetId));
    }

    private void ingestInlineUploads(Long ownerId, LessonTemplateForm form) {
        try {
            if (form.getPdfUpload() != null && !form.getPdfUpload().isEmpty()) {
                form.setPdfLibraryAssetId(
                        libraryService.upload(ownerId, form.getPdfUpload(), KIND_DOCUMENT).id());
            }
            if (form.getVideoUpload() != null && !form.getVideoUpload().isEmpty()) {
                form.setVideoLibraryAssetId(
                        libraryService.upload(ownerId, form.getVideoUpload(), KIND_VIDEO).id());
                form.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
            }
            LinkedHashSet<Long> materialIds = new LinkedHashSet<>(
                    form.getMaterialAssetIds() == null ? List.of() : form.getMaterialAssetIds());
            if (form.getMaterialUploads() != null) {
                for (var upload : form.getMaterialUploads()) {
                    if (upload != null && !upload.isEmpty()) {
                        materialIds.add(libraryService.upload(ownerId, upload, null).id());
                    }
                }
            }
            form.setMaterialAssetIds(new ArrayList<>(materialIds));
        } catch (IOException exception) {
            throw new IllegalStateException(MSG_STORAGE_UPLOAD_FAILED, exception);
        }
    }

    @Transactional
    public List<LessonCloneResult> distribute(Long templateId, List<Long> classIds,
                                               Long userId, Role role) {
        if (classIds == null || classIds.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một lớp");
        }
        List<Long> distinctClassIds = classIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        // Under MySQL REPEATABLE READ, any ordinary select performed before
        // waiting on the class mutex would pin a stale transaction snapshot.
        // Lock every target class first (in deterministic id order), then read
        // the template/sections/lessons. A concurrent distribution that waited
        // here will therefore observe the snapshot committed by the winner.
        Map<Long, ClassEntity> lockedClasses = new LinkedHashMap<>();
        for (Long classId : distinctClassIds) {
            lockedClasses.put(classId,
                    classesService.getEditableForUpdate(classId, userId, role));
        }

        LessonTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND));
        Department subject = subjectResolver.require(userId, role, template.getSubjectId());
        List<LessonCloneResult> results = new ArrayList<>();
        for (Long classId : distinctClassIds) {
            ClassEntity clazz = lockedClasses.get(classId);
            if (!subject.getId().equals(clazz.getSubjectId())
                    || !ClassEntity.STATUS_ACTIVE.equals(clazz.getStatus())) {
                throw new IllegalArgumentException("Chỉ được phân phối tới lớp cùng mã môn đang sử dụng");
            }
            Section section = sectionRepository.findByClassIdOrderByDisplayOrderAsc(classId).stream()
                    .filter(row -> row.getTitle().equalsIgnoreCase(template.getChapterTitle()))
                    .findFirst()
                    .orElseGet(() -> {
                        Long createdId = sectionsService.create(classId, template.getChapterTitle(),
                                userId, role).id();
                        return sectionRepository.findByIdAndClassId(createdId, classId)
                                .orElseThrow(() -> new EntityNotFoundException("Chương không tồn tại"));
                    });
            // The section is the mutex for exact provenance and title checks;
            // both checks and the append now happen under the same row lock.
            reorderService.lockSectionForUpdate(section.getId(), classId);
            List<Lesson> existingSnapshots = lessonRepository
                    .findBySourceLessonTemplateIdOrderByIdAsc(template.getId()).stream()
                    .filter(row -> sectionRepository.findById(row.getSectionId())
                            .map(existingSection -> classId.equals(existingSection.getClassId()))
                            .orElse(false))
                    .toList();
            if (!existingSnapshots.isEmpty()) {
                Lesson existing = existingSnapshots.get(0);
                refreshSnapshot(existing, template, userId);
                results.add(new LessonCloneResult(
                        existing.getId(), classId, existing.getSectionId(), existing.getTitle()));
                continue;
            }
            if (lessonRepository.findFirstBySectionIdAndTitleIgnoreCase(
                    section.getId(), template.getTitle()).isPresent()) {
                throw new IllegalArgumentException(
                        "Lớp " + clazz.getName() + " đã có bài học cùng tên trong chương này");
            }
            LessonCloneResult result = snapshotTemplateToSection(
                    template, classId, section.getId(), userId);
            Lesson distributed = lessonRepository.findById(result.lessonId())
                    .orElseThrow(() -> new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND));
            distributed.publish();
            lessonRepository.save(distributed);
            activityWriter.write(distributed.getId(), LessonActivity.TYPE_PUBLISHED,
                    "Phân phối từ Library: " + distributed.getTitle(), userId);
            results.add(result);
        }
        return results;
    }

    /**
     * Distributes the complete canonical subject tree in one transaction:
     * every chapter, lesson body and attached material is snapshotted to each
     * selected ACTIVE class of the same subject.
     */
    @Transactional
    public List<LessonCloneResult> distributeSubject(Long subjectId, List<Long> classIds,
                                                      Long userId, Role role) {
        Department subject = subjectResolver.require(userId, role, subjectId);
        List<LessonTemplate> templates = templateRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId());
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("Mã môn chưa có bài học để phân phối");
        }
        List<LessonCloneResult> results = new ArrayList<>();
        for (LessonTemplate template : templates) {
            results.addAll(distribute(template.getId(), classIds, userId, role));
        }
        return results;
    }

    /** Soft-deletes an owned template (attachment rows stay for FK integrity). */
    @Transactional
    public void softDelete(Long ownerId, Role role, Long templateId) {
        LessonTemplate template = getOwned(ownerId, templateId);
        requireAuthoringOpen(ownerId, role, template.getSubjectId());
        int removedOrder = template.getDisplayOrder();
        template.markDeleted();
        templateRepository.save(template);
        List<LessonTemplate> remaining = new ArrayList<>(templateRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(
                        template.getSubjectId()));
        shiftAfter(remaining, removedOrder, -1);
        templateRepository.saveAll(remaining);
    }

    /** Removes a complete owned chapter and closes both chapter and lesson numbering gaps. */
    @Transactional
    public void softDeleteChapter(Long ownerId, Role role, Long subjectId, int chapterNumber) {
        Department subject = requireAuthoringOpen(ownerId, role, subjectId);
        List<LessonTemplate> rows = new ArrayList<>(templateRepository
                .findBySubjectIdOrderByChapterOrderAscDisplayOrderAscTitleAsc(subject.getId()));
        List<LessonTemplate> target = rows.stream()
                .filter(row -> row.getChapterOrder() == chapterNumber).toList();
        if (target.isEmpty() || target.stream().anyMatch(row -> !ownerId.equals(row.getOwnerId()))) {
            throw new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND);
        }
        target.forEach(LessonTemplate::markDeleted);
        templateRepository.saveAllAndFlush(target);
        List<LessonTemplate> remaining = rows.stream()
                .filter(row -> row.getChapterOrder() != chapterNumber)
                .sorted(java.util.Comparator.comparingInt(LessonTemplate::getChapterOrder)
                        .thenComparingInt(LessonTemplate::getDisplayOrder))
                .toList();
        Map<Integer, Integer> chapterMap = new LinkedHashMap<>();
        int nextLesson = 1;
        for (LessonTemplate row : remaining) {
            int newChapter = chapterMap.computeIfAbsent(row.getChapterOrder(),
                    ignored -> chapterMap.size() + 1);
            row.updateSequence(newChapter,
                    canonicalChapter(newChapter, stripChapterPrefix(row.getChapterTitle())),
                    nextLesson++);
        }
        templateRepository.saveAll(remaining);
    }

    /** Materializes one canonical Library lesson as a class-owned snapshot. */
    private LessonCloneResult snapshotTemplateToSection(LessonTemplate template, Long classId,
                                                        Long sectionId, Long userId) {
        Lesson lesson = materializeDraft(sectionId, template.getTitle(),
                template.getContentType(), userId);
        lesson.setSourceLessonTemplateId(template.getId());
        applyTemplateBodyToLesson(lesson, template, template.getOwnerId(), userId);
        Lesson saved = lessonRepository.saveAndFlush(lesson);

        cloneSupplementaryAttachments(template, saved, userId);

        activityWriter.write(saved.getId(), LessonActivity.TYPE_CREATED,
                "Tạo bài giảng (clone từ mẫu): " + saved.getTitle(), userId);
        return new LessonCloneResult(saved.getId(), classId, sectionId, saved.getTitle());
    }

    // ── Body mapping ────────────────────────────────────────────────────

    private void cloneSupplementaryAttachments(LessonTemplate template, Lesson lesson, Long userId) {
        for (LessonTemplateAttachment extra : templateAttachmentRepository
                .findByTemplateIdOrderByDisplayOrderAsc(template.getId())) {
            LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                    template.getOwnerId(), extra.getLibraryAssetId());
            if (attachmentRepository.existsByLessonIdAndLibraryAssetId(
                    lesson.getId(), asset.getId())) {
                continue;
            }
            attachmentRepository.save(new LessonAttachment(
                    lesson.getId(), asset.getOriginalFilename(), asset.getStoredPath(),
                    asset.getMimeType(), asset.getSizeBytes(), userId, asset.getId(),
                    ORIGIN_CANONICAL_TEMPLATE));
        }
    }

    private void applyFormBody(LessonTemplate template, LessonTemplateForm form, Long ownerId) {
        String type = template.getContentType();
        if (CONTENT_TYPE_RICHTEXT.equals(type)) {
            String html = form.getContentRichtext() == null ? "" : form.getContentRichtext();
            template.setContentRichtext(HtmlSanitizer.sanitize(html));
            if (form.getVideoLibraryAssetId() != null) {
                LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                        ownerId, form.getVideoLibraryAssetId());
                if (!KIND_VIDEO.equals(asset.getKind())) {
                    throw new IllegalArgumentException("Video đã chọn không hợp lệ");
                }
                template.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
                template.setVideoLibraryAssetId(asset.getId());
                template.setVideoUrl(asset.getStoredPath());
                template.setVideoSummary(normalizeVideoSummary(form.getVideoSummary()));
                return;
            }
            String videoUrl = form.getVideoUrl() == null ? "" : form.getVideoUrl().trim();
            if (videoUrl.isEmpty()) {
                template.setVideoProvider(null);
                template.setVideoUrl(null);
                template.setVideoSummary(null);
                template.setVideoLibraryAssetId(null);
            } else if (YouTubeEmbedUrl.matches(videoUrl)) {
                template.setVideoProvider(VIDEO_PROVIDER_YOUTUBE);
                template.setVideoUrl(videoUrl);
                template.setVideoSummary(normalizeVideoSummary(form.getVideoSummary()));
                template.setVideoLibraryAssetId(null);
            } else if (VimeoEmbedUrl.matches(videoUrl)) {
                template.setVideoProvider(VIDEO_PROVIDER_VIMEO);
                template.setVideoUrl(videoUrl);
                template.setVideoSummary(normalizeVideoSummary(form.getVideoSummary()));
                template.setVideoLibraryAssetId(null);
            } else {
                throw new IllegalArgumentException("Link video phải là URL YouTube hoặc Vimeo hợp lệ");
            }
            return;
        }
        if (CONTENT_TYPE_PDF.equals(type)) {
            if (form.getPdfLibraryAssetId() == null) {
                throw new IllegalArgumentException("Vui lòng chọn PDF chính");
            }
            LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                    ownerId, form.getPdfLibraryAssetId());
            if (!KIND_DOCUMENT.equals(asset.getKind())
                    || !"application/pdf".equalsIgnoreCase(asset.getMimeType())) {
                throw new IllegalArgumentException("PDF chính không hợp lệ");
            }
            template.setPdfLibraryAssetId(asset.getId());
            template.setVideoSummary(null);
            return;
        }
        if (CONTENT_TYPE_VIDEO.equals(type)) {
            String provider = form.getVideoProvider() == null
                    ? "" : form.getVideoProvider().trim().toUpperCase();
            if (VIDEO_PROVIDER_UPLOAD.equals(provider)) {
                if (form.getVideoLibraryAssetId() == null) {
                    throw new IllegalArgumentException("Vui lòng chọn video trong Library");
                }
                LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                        ownerId, form.getVideoLibraryAssetId());
                if (!KIND_VIDEO.equals(asset.getKind())) {
                    throw new IllegalArgumentException("Video đã chọn không hợp lệ");
                }
                template.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
                template.setVideoLibraryAssetId(asset.getId());
                template.setVideoUrl(asset.getStoredPath());
                template.setVideoSummary(normalizeVideoSummary(form.getVideoSummary()));
                return;
            }
            String videoUrl = form.getVideoUrl() == null ? "" : form.getVideoUrl().trim();
            boolean validExternalUrl = VIDEO_PROVIDER_YOUTUBE.equals(provider)
                    ? YouTubeEmbedUrl.matches(videoUrl)
                    : VIDEO_PROVIDER_VIMEO.equals(provider) && VimeoEmbedUrl.matches(videoUrl);
            if (validExternalUrl) {
                template.setVideoProvider(provider);
                template.setVideoUrl(videoUrl);
                template.setVideoLibraryAssetId(null);
                template.setVideoSummary(normalizeVideoSummary(form.getVideoSummary()));
                return;
            }
            throw new IllegalArgumentException("Vui lòng cấu hình nguồn video hợp lệ");
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private void applyTemplateBodyToLesson(Lesson lesson, LessonTemplate template,
                                           Long assetOwnerId, Long userId) {
        String type = template.getContentType();
        if (CONTENT_TYPE_RICHTEXT.equals(type)) {
            lesson.switchContentTypeTo(CONTENT_TYPE_RICHTEXT);
            String html = template.getContentRichtext() == null ? "" : template.getContentRichtext();
            lesson.updateContent(HtmlSanitizer.sanitize(html));
            if (VIDEO_PROVIDER_UPLOAD.equals(template.getVideoProvider())
                    && template.getVideoLibraryAssetId() != null) {
                LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                        assetOwnerId, template.getVideoLibraryAssetId());
                if (!KIND_VIDEO.equals(asset.getKind())) {
                    throw new IllegalArgumentException("Video đã chọn không hợp lệ");
                }
                lesson.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
                lesson.setVideoLibraryAssetId(asset.getId());
                lesson.setVideoUrl(asset.getStoredPath());
                lesson.setVideoSummary(template.getVideoSummary());
                return;
            }
            if (template.getVideoUrl() != null && !template.getVideoUrl().isBlank()) {
                lesson.setVideoProvider(template.getVideoProvider());
                lesson.setVideoUrl(template.getVideoUrl());
                lesson.setVideoSummary(template.getVideoSummary());
            }
            return;
        }
        if (CONTENT_TYPE_PDF.equals(type)) {
            LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                    assetOwnerId, template.getPdfLibraryAssetId());
            // Attachment row first so pdf_attachment_id CHECK can pass after type switch.
            LessonAttachment row = new LessonAttachment(
                    lesson.getId(), asset.getOriginalFilename(), asset.getStoredPath(),
                    asset.getMimeType(), asset.getSizeBytes(), userId, asset.getId(),
                    ORIGIN_CANONICAL_TEMPLATE);
            LessonAttachment savedAtt = attachmentRepository.saveAndFlush(row);
            lesson.setPdfAttachmentId(savedAtt.getId());
            lesson.switchContentTypeTo(CONTENT_TYPE_PDF);
            // switchContentTypeTo nulls pdf_attachment_id — restore after switch.
            lesson.setPdfAttachmentId(savedAtt.getId());
            return;
        }
        if (CONTENT_TYPE_VIDEO.equals(type)) {
            applyTemplateVideoToLesson(lesson, template, assetOwnerId);
            return;
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private void applyTemplateVideoToLesson(Lesson lesson, LessonTemplate template,
                                            Long assetOwnerId) {
        String provider = template.getVideoProvider();
        if (VIDEO_PROVIDER_YOUTUBE.equals(provider) || VIDEO_PROVIDER_VIMEO.equals(provider)) {
            lesson.switchContentTypeTo(CONTENT_TYPE_VIDEO);
            lesson.setVideoProvider(provider);
            lesson.setVideoUrl(template.getVideoUrl());
            lesson.setVideoSummary(template.getVideoSummary());
            return;
        }
        if (VIDEO_PROVIDER_UPLOAD.equals(provider)) {
            LibraryAsset asset = libraryService.getOwnedAssetForUpdate(
                    assetOwnerId, template.getVideoLibraryAssetId());
            lesson.switchContentTypeTo(CONTENT_TYPE_VIDEO);
            lesson.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
            lesson.setVideoLibraryAssetId(asset.getId());
            lesson.setVideoUrl(asset.getStoredPath());
            lesson.setVideoSummary(template.getVideoSummary());
            return;
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private Lesson materializeDraft(Long sectionId, String title, String contentType, Long userId) {
        short nextOrder = (short) (lessonRepository.findMaxDisplayOrder(sectionId) + 1);
        Lesson lesson = new Lesson(sectionId, title, nextOrder, userId);
        // Constructor defaults RICHTEXT+""; PDF/VIDEO body filled after first save
        // so we have a lesson id for attachment FKs.
        Lesson saved = lessonRepository.saveAndFlush(lesson);
        if (!CONTENT_TYPE_RICHTEXT.equals(contentType)) {
            // Keep as RICHTEXT empty until body is applied — avoids CHECK violation.
            return saved;
        }
        return saved;
    }

    private LessonTemplate getOwned(Long ownerId, Long templateId) {
        return templateRepository.findByIdAndOwnerId(templateId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND));
    }

    /** Only the assigned Subject Leader may change the shared authoring lock. */
    @Transactional
    public boolean setSubjectLibraryLocked(Long userId, Role role, Long subjectId,
                                           boolean locked) {
        Department subject = subjectResolver.require(userId, role, subjectId);
        if (role != Role.LEADER || !userId.equals(subject.getLeaderUserId())) {
            throw new AccessDeniedException(
                    "Chỉ trưởng bộ môn phụ trách mã môn mới được khóa khung chương trình");
        }
        subject.setLibraryLocked(locked);
        return locked;
    }

    private Department requireAuthoringOpen(Long userId, Role role, Long subjectId) {
        Department subject = subjectResolver.require(userId, role, subjectId);
        if (subject.isLibraryLocked()) {
            throw new AccessDeniedException(
                    "Khung chương trình đã được trưởng bộ môn khóa; hãy mở khóa trước khi chỉnh sửa");
        }
        return subject;
    }

    private static void requireTemplateSubject(LessonTemplate template, Long subjectId) {
        if (!subjectId.equals(template.getSubjectId())) {
            throw new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** Normalizes the optional plain-text video summary before persistence. */
    private static String normalizeVideoSummary(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("Tóm tắt video tối đa 1000 ký tự");
        }
        return normalized;
    }

    private LessonTemplateRow toRow(LessonTemplate t, String subjectCode,
                                    boolean canManage, Map<Long, String> uploaderNames) {
        List<LessonResourceRow> resources = resourceRows(t);
        return new LessonTemplateRow(
                t.getId(), subjectCode, t.getChapterOrder(), t.getChapterTitle(),
                t.getDisplayOrder(), t.getTitle(), t.getContentType(),
                t.getOwnerId(), uploaderDisplayName(t.getOwnerId(), uploaderNames),
                t.getUpdatedAt(), resources.size(), canManage, resources);
    }

    /** Resolves all uploader names for one page in a single repository call. */
    private Map<Long, String> uploaderNames(List<LessonTemplate> templates) {
        LinkedHashSet<Long> ids = templates.stream()
                .map(LessonTemplate::getOwnerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> names = new LinkedHashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private static String uploaderDisplayName(Long uploaderUserId,
                                              Map<Long, String> uploaderNames) {
        String displayName = uploaderNames.get(uploaderUserId);
        return displayName == null || displayName.isBlank()
                ? "Người dùng #" + uploaderUserId
                : displayName;
    }

    private List<LessonResourceRow> resourceRows(LessonTemplate template) {
        List<LessonResourceRow> rows = new ArrayList<>();
        if (template.getPdfLibraryAssetId() != null) {
            assetRepository.findById(template.getPdfLibraryAssetId()).ifPresent(asset ->
                    rows.add(new LessonResourceRow(null, "PDF", "PDF chính",
                            asset.getOriginalFilename())));
        }
        if (template.getVideoLibraryAssetId() != null) {
            assetRepository.findById(template.getVideoLibraryAssetId()).ifPresent(asset ->
                    rows.add(new LessonResourceRow(null, "VIDEO", "Video tải lên",
                            asset.getOriginalFilename())));
        } else if (template.getVideoUrl() != null && !template.getVideoUrl().isBlank()) {
            rows.add(new LessonResourceRow(null, "VIDEO_URL", "Video URL", template.getVideoUrl()));
        }
        templateAttachmentRepository.findByTemplateIdOrderByDisplayOrderAsc(template.getId())
                .forEach(attachment -> rows.add(new LessonResourceRow(
                        attachment.getLibraryAssetId(),
                        resourceKind(attachment.getOriginalFilename(), attachment.getMimeType()),
                        "Tài liệu đính kèm", attachment.getOriginalFilename())));
        return List.copyOf(rows);
    }

    private static String resourceKind(String filename, String mimeType) {
        String lower = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if ("application/pdf".equalsIgnoreCase(mimeType) || lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "PPTX";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "XLSX";
        return "FILE";
    }

    private static int requirePositive(int value, String message) {
        if (value < 1) throw new IllegalArgumentException(message);
        return value;
    }

    private static int insertionOrder(List<LessonTemplate> ordered, int chapterNumber) {
        return ordered.stream().filter(row -> row.getChapterOrder() <= chapterNumber)
                .mapToInt(LessonTemplate::getDisplayOrder).max().orElse(0) + 1;
    }

    private static String existingChapterTitle(List<LessonTemplate> ordered, int chapterNumber) {
        return ordered.stream().filter(row -> row.getChapterOrder() == chapterNumber)
                .map(LessonTemplate::getChapterTitle).findFirst().orElse(null);
    }

    private static void shiftFrom(List<LessonTemplate> rows, int fromInclusive, int delta) {
        rows.stream().filter(row -> row.getDisplayOrder() >= fromInclusive)
                .forEach(row -> row.updateSequence(row.getChapterOrder(),
                        row.getChapterTitle(), row.getDisplayOrder() + delta));
    }

    private static void shiftAfter(List<LessonTemplate> rows, int afterExclusive, int delta) {
        rows.stream().filter(row -> row.getDisplayOrder() > afterExclusive)
                .forEach(row -> row.updateSequence(row.getChapterOrder(),
                        row.getChapterTitle(), row.getDisplayOrder() + delta));
    }

    private static String canonicalChapter(int number, String description) {
        return "Chương " + number + " · " + description;
    }

    private static String canonicalLesson(int number, String description) {
        return "Bài " + number + " · " + description;
    }

    private static String stripChapterPrefix(String value) {
        return stripNumberedPrefix(value, "Chương");
    }

    private static String stripLessonPrefix(String value) {
        return stripNumberedPrefix(value, "Bài");
    }

    private static String stripNumberedPrefix(String value, String label) {
        if (value == null) return "";
        return value.replaceFirst("(?iu)^" + label + "\\s+\\d+\\s*(?:[·.:-]\\s*)?", "").trim();
    }

    private static PageRequest pageRequest(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? DEFAULT_LIBRARY_PAGE_SIZE
                : Math.min(size, MAX_LIBRARY_PAGE_SIZE);
        return PageRequest.of(p, s);
    }

    private static String normalizeQ(String q) {
        if (q == null) return null;
        String t = q.trim();
        return t.isEmpty() ? null : t;
    }
}
