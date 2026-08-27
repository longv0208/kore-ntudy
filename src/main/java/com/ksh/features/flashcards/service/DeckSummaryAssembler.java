package com.ksh.features.flashcards.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.User;
import com.ksh.entities.Department;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ksh.features.flashcards.entity.FlashcardDeck;
import com.ksh.features.flashcards.repository.FlashcardRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Batch-assembles {@link DeckSummary} rows from decks, resolving card counts,
 * owner names and class names in bulk (no N+1). Extracted from
 * {@code DeckService} to keep each class within the file-size budget.
 */
@Component
public class DeckSummaryAssembler {

    private final FlashcardRepository cardRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository subjectRepository;

    public DeckSummaryAssembler(FlashcardRepository cardRepository,
                                ClassRepository classRepository,
                                UserRepository userRepository,
                                DepartmentRepository subjectRepository) {
        this.cardRepository = cardRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
    }

    /** Maps decks to summaries; {@code owner} is true when a deck's owner is the caller. */
    public List<DeckSummary> toSummaries(List<FlashcardDeck> decks, Long callerId) {
        if (decks.isEmpty()) return List.of();
        Map<Long, Long> counts = cardCounts(decks);
        Map<Long, String> ownerNames = ownerNames(decks);
        Map<Long, String> classNames = classNames(decks);
        Map<Long, Department> subjects = subjects(decks);
        List<DeckSummary> out = new ArrayList<>(decks.size());
        for (FlashcardDeck d : decks) {
            Department subject = subjects.get(d.getSubjectId());
            out.add(new DeckSummary(d.getId(), d.getTitle(), d.getDescription(),
                    counts.getOrDefault(d.getId(), 0L), d.isShared(),
                    d.getOwnerId().equals(callerId), ownerNames.get(d.getOwnerId()),
                    sharedClassLabel(d, classNames),
                    d.getSubjectId(), subject == null ? null : subject.getCode(),
                    subject == null ? null : subject.getName()));
        }
        return out;
    }

    private Map<Long, Long> cardCounts(List<FlashcardDeck> decks) {
        List<Long> ids = decks.stream().map(FlashcardDeck::getId).toList();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : cardRepository.countByDeckIds(ids)) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private String sharedClassLabel(FlashcardDeck deck, Map<Long, String> classNames) {
        List<String> names = new ArrayList<>();
        Collection<Long> ids = deck.getSharedClassIds();
        if (ids != null) {
            for (Long id : ids) {
                if (classNames.get(id) != null) names.add(classNames.get(id));
            }
        }
        if (names.isEmpty() && deck.getClassId() != null && classNames.get(deck.getClassId()) != null) {
            names.add(classNames.get(deck.getClassId()));
        }
        if (names.isEmpty()) return null;
        return names.size() == 1 ? names.get(0) : names.get(0) + " + " + (names.size() - 1) + " lớp";
    }

    private Map<Long, String> ownerNames(List<FlashcardDeck> decks) {
        Collection<Long> ownerIds = decks.stream()
                .map(FlashcardDeck::getOwnerId).distinct().toList();
        Map<Long, String> map = new HashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            map.put(u.getId(), u.getFullName());
        }
        return map;
    }

    private Map<Long, String> classNames(List<FlashcardDeck> decks) {
        Collection<Long> classIds = decks.stream()
                .flatMap(deck -> {
                    Collection<Long> ids = deck.getSharedClassIds();
                    if (ids == null || ids.isEmpty()) {
                        return deck.getClassId() == null ? java.util.stream.Stream.empty()
                                : java.util.stream.Stream.of(deck.getClassId());
                    }
                    return ids.stream();
                }).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> map = new HashMap<>();
        if (classIds.isEmpty()) return map;
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    private Map<Long, Department> subjects(List<FlashcardDeck> decks) {
        Collection<Long> subjectIds = decks.stream().map(FlashcardDeck::getSubjectId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Department> map = new HashMap<>();
        if (subjectIds.isEmpty()) return map;
        for (Department subject : subjectRepository.findAllById(subjectIds)) {
            map.put(subject.getId(), subject);
        }
        return map;
    }
}
