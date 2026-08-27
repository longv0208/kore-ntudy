package com.ksh.features.flashcards.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.Department;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.flashcards.dto.FlashcardDtos.ClassOption;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ksh.features.flashcards.dto.FlashcardDtos.CardView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckSaveResult;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ksh.features.flashcards.dto.FlashcardDtos.StudentDeckList;
import com.ksh.features.flashcards.dto.FlashcardDtos.SubjectOption;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardDeckRepository;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import com.ksh.features.flashcards.support.DeckAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.ksh.common.IConstant.DEFAULT_DECK_PAGE_SIZE;
import static com.ksh.common.IConstant.MSG_SHARE_CLASS_INVALID;

/** Deck CRUD, listing and sharing (KSH-5.x). */
@Service
public class DeckService {

    private final FlashcardDeckRepository deckRepository;
    private final FlashcardRepository cardRepository;
    private final DeckAccessResolver accessResolver;
    private final DeckSummaryAssembler assembler;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final CardService cardService;
    private final DepartmentRepository subjectRepository;

    public DeckService(FlashcardDeckRepository deckRepository,
                       FlashcardRepository cardRepository,
                       DeckAccessResolver accessResolver,
                       DeckSummaryAssembler assembler,
                       EnrollmentRepository enrollmentRepository,
                       ClassRepository classRepository,
                       CardService cardService,
                       DepartmentRepository subjectRepository) {
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
        this.accessResolver = accessResolver;
        this.assembler = assembler;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.cardService = cardService;
        this.subjectRepository = subjectRepository;
    }

    /** Creates a new PRIVATE deck owned by the caller; returns its id. */
    @Transactional
    public Long createDeck(Long ownerId, DeckForm form) {
        FlashcardDeck deck = new FlashcardDeck(ownerId, form.title().trim(),
                trimToNull(form.description()));
        deck.assignSubject(requireActiveSubject(form.subjectId()));
        return deckRepository.save(deck).getId();
    }

    /** Atomically creates a deck and returns its ordered persisted cards. */
    @Transactional
    public DeckSaveResult createDeckWithCards(Long ownerId, DeckForm form, List<CardItem> items) {
        Long deckId = createDeck(ownerId, form);
        List<CardView> cards = cardService.replaceCards(deckId, ownerId,
                items == null ? List.of() : items);
        return new DeckSaveResult(deckId, cards);
    }

    /** Updates a deck's metadata; owner-only. */
    @Transactional
    public void updateMetadata(Long deckId, Long ownerId, DeckForm form) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.updateMetadata(form.title().trim(), trimToNull(form.description()));
        deck.assignSubject(requireActiveSubject(form.subjectId()));
        deckRepository.save(deck);
    }

    /** Soft-deletes a deck; owner-only. */
    @Transactional
    public void softDelete(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.markDeleted();
        deckRepository.save(deck);
    }

    /** Detail view-model for the launcher page (owner or shared member). */
    @Transactional(readOnly = true)
    public DeckDetailView getDetail(Long deckId, Long userId) {
        DeckAccessResolver.ResolvedDeck resolved = accessResolver.resolve(deckId, userId);
        if (resolved.access() == DeckAccessResolver.DeckAccess.NONE) {
            throw new EntityNotFoundException(DeckAccessResolver.NF_MSG);
        }
        FlashcardDeck deck = resolved.deck();
        long count = cardRepository.countByDeckId(deckId);
        List<Long> sharedClassIds = sharedClassIds(deck);
        List<ClassOption> sharedClasses = classOptions(sharedClassIds);
        String className = sharedClasses.isEmpty() ? null
                : sharedClasses.size() == 1 ? sharedClasses.get(0).name()
                : sharedClasses.get(0).name() + " + " + (sharedClasses.size() - 1) + " lớp";
        List<ClassOption> shareClasses = resolved.isOwner() ? shareableClasses(userId) : List.of();
        String ownerName = assembler.toSummaries(List.of(deck), userId).get(0).ownerName();
        return new DeckDetailView(deck.getId(), deck.getTitle(), deck.getDescription(),
                count, resolved.isOwner(), deck.isShared(), deck.getClassId(),
                className, shareClasses, ownerName, deck.isPublicLink(),
                resolved.isOwner() ? deck.getShareToken() : null,
                sharedClassIds, sharedClasses);
    }

    /**
     * One SSR page of the caller's own decks (newest-first) plus all decks shared
     * to their ACTIVE-enrolled classes. Only own decks paginate (the numbered
     * pager navigates by {@code ?page=N}); shared decks are returned in full.
     * Card counts for the page are resolved in one batch query (no N+1).
     *
     * @param page zero-based page index (negative clamps to 0)
     */
    @Transactional(readOnly = true)
    public StudentDeckList listForStudent(Long userId, int page) {
        return listForStudent(userId, page, "");
    }

    /** Searches visible decks by title or subject code/name. */
    @Transactional(readOnly = true)
    public StudentDeckList listForStudent(Long userId, int page, String query) {
        String keyword = query == null ? "" : query.trim();
        Page<DeckSummary> ownPage = ownDecksPage(userId, page, keyword);
        List<Long> classIds = activeClassIds(userId);
        List<FlashcardDeck> shared = classIds.isEmpty() ? List.of()
                : deckRepository.findByVisibilityOrderByUpdatedAtDesc(
                        FlashcardDeck.VISIBILITY_SHARED).stream()
                        .filter(deck -> !deck.getOwnerId().equals(userId))
                        .filter(deck -> targetsAnyClass(deck, classIds))
                        .toList();
        List<DeckSummary> sharedSummaries = assembler.toSummaries(shared, userId).stream()
                .filter(deck -> matchesKeyword(deck, keyword)).toList();
        return new StudentDeckList(ownPage, sharedSummaries);
    }

    /**
     * All decks the caller may use in an in-browser mixed study session.
     * This deliberately reuses the existing OWNER / SHARED visibility model:
     * no cross-user private deck is exposed and no session rows are persisted.
     */
    @Transactional(readOnly = true)
    public List<DeckSummary> listStudyDeckOptions(Long userId) {
        List<FlashcardDeck> viewable = new ArrayList<>(
                deckRepository.findByOwnerIdOrderByUpdatedAtDesc(userId));
        List<Long> classIds = activeClassIds(userId);
        if (!classIds.isEmpty()) {
            viewable.addAll(deckRepository.findByVisibilityOrderByUpdatedAtDesc(
                            FlashcardDeck.VISIBILITY_SHARED).stream()
                    .filter(deck -> !deck.getOwnerId().equals(userId))
                    .filter(deck -> targetsAnyClass(deck, classIds)).toList());
        }
        return assembler.toSummaries(viewable, userId);
    }

    /**
     * One page of the caller's own decks as a {@code Page<DeckSummary>},
     * newest-first. The deck page is fetched with the paging query, then its
     * content is batch-assembled into summaries and re-wrapped preserving the
     * original {@code Pageable} and total count (so {@code totalPages} etc. stay
     * correct for the pager). id is a stable tiebreaker so same-second decks keep
     * a fixed order and never drift between pages.
     */
    private Page<DeckSummary> ownDecksPage(Long userId, int page, String keyword) {
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_DECK_PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<FlashcardDeck> deckPage = deckRepository.searchOwned(userId, keyword, pageable);
        List<DeckSummary> summaries = assembler.toSummaries(deckPage.getContent(), userId);
        return new PageImpl<>(summaries, pageable, deckPage.getTotalElements());
    }

    /** SHARED decks targeting a class (surfaced on the class page). */
    @Transactional(readOnly = true)
    public List<DeckSummary> listSharedForClass(Long classId, Long userId) {
        List<FlashcardDeck> shared = deckRepository.findByVisibilityOrderByUpdatedAtDesc(
                        FlashcardDeck.VISIBILITY_SHARED).stream()
                .filter(deck -> targetsAnyClass(deck, List.of(classId))).toList();
        return assembler.toSummaries(shared, userId);
    }

    /** Shares a deck to one of the owner's classes; owner-only. */
    @Transactional
    public void share(Long deckId, Long ownerId, Long classId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        if (classId == null || !isOwnersClass(ownerId, classId)) {
            throw new AccessDeniedException(MSG_SHARE_CLASS_INVALID);
        }
        deck.shareTo(classId);
        deckRepository.save(deck);
    }

    /** Replaces the complete set of class targets selected in the deck UI. */
    @Transactional
    public void syncShares(Long deckId, Long ownerId, List<Long> classIds) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        List<Long> targets = classIds == null ? List.of() : classIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        for (Long classId : targets) {
            if (!isOwnersClass(ownerId, classId)) {
                throw new AccessDeniedException(MSG_SHARE_CLASS_INVALID);
            }
        }
        deck.unshare();
        targets.forEach(deck::shareTo);
        deckRepository.save(deck);
    }

    /** Reverts a deck to PRIVATE; owner-only. */
    @Transactional
    public void unshare(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.unshare();
        deckRepository.save(deck);
    }

    /** Removes one class target while preserving all remaining class shares. */
    @Transactional
    public void unshare(Long deckId, Long ownerId, Long classId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        if (classId == null || !deck.getSharedClassIds().contains(classId)) {
            throw new IllegalArgumentException(MSG_SHARE_CLASS_INVALID);
        }
        deck.unshareFrom(classId);
        deckRepository.save(deck);
    }

    /** Classes the owner may share to (ACTIVE-enrolled or owns as lecturer). */
    @Transactional(readOnly = true)
    public List<ClassOption> shareableClasses(Long userId) {
        List<Long> classIds = activeClassIds(userId);
        classRepository.findAllByLecturerId(userId).forEach(c -> {
            if (!classIds.contains(c.getId())) classIds.add(c.getId());
        });
        List<ClassOption> options = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            options.add(new ClassOption(c.getId(), c.getName()));
        }
        return options;
    }

    /** Active canonical subjects available when creating or editing a deck. */
    @Transactional(readOnly = true)
    public List<SubjectOption> activeSubjects() {
        return subjectRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(subject -> new SubjectOption(subject.getId(), subject.getCode(), subject.getName()))
                .toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean isOwnersClass(Long ownerId, Long classId) {
        boolean enrolled = enrollmentRepository.findByUserIdAndClassId(ownerId, classId)
                .map(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus())).orElse(false);
        if (enrolled) return true;
        return classRepository.findById(classId)
                .map(c -> ownerId.equals(c.getLecturerId())).orElse(false);
    }

    private List<Long> activeClassIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        for (Enrollment e : enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_ACTIVE)) {
            ids.add(e.getClassId());
        }
        return ids;
    }

    private List<Long> sharedClassIds(FlashcardDeck deck) {
        List<Long> ids = new ArrayList<>(deck.getSharedClassIds());
        if (ids.isEmpty() && deck.getClassId() != null) ids.add(deck.getClassId());
        return ids;
    }

    private boolean targetsAnyClass(FlashcardDeck deck, List<Long> classIds) {
        return sharedClassIds(deck).stream().anyMatch(classIds::contains);
    }

    private boolean matchesKeyword(DeckSummary deck, String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        String needle = keyword.toLowerCase(java.util.Locale.ROOT);
        return containsIgnoreCase(deck.title(), needle)
                || containsIgnoreCase(deck.subjectCode(), needle)
                || containsIgnoreCase(deck.subjectName(), needle);
    }

    private boolean containsIgnoreCase(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(lowerNeedle);
    }

    private List<ClassOption> classOptions(List<Long> classIds) {
        if (classIds.isEmpty()) return List.of();
        List<ClassOption> options = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            options.add(new ClassOption(c.getId(), c.getName()));
        }
        return options;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Long requireActiveSubject(Long subjectId) {
        if (subjectId == null) return null;
        return subjectRepository.findById(subjectId)
                .filter(Department::isActive)
                .map(Department::getId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Mã môn không tồn tại hoặc đang bị ẩn"));
    }

}
