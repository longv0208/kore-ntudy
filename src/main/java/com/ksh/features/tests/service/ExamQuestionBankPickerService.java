package com.ksh.features.tests.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.entity.QuestionBankOption;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.features.questionbank.repository.QuestionBankOptionRepository;
import com.ksh.features.questionbank.service.QuestionBankAccessPolicy;
import com.ksh.features.tests.dto.LecturerTestDtos.BankItemSnapshot;
import com.ksh.features.tests.dto.LecturerTestDtos.BankOptionSnapshot;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.support.TestAccessResolver;
import com.ksh.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads approved same-subject bank questions as immutable test snapshots. */
@Service
public class ExamQuestionBankPickerService {

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final TestAccessResolver testAccessResolver;
    private final ClassRepository classRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;

    public ExamQuestionBankPickerService(UserRepository userRepository,
                                         SubjectRepository subjectRepository,
                                         QuestionBankAccessPolicy accessPolicy,
                                         TestAccessResolver testAccessResolver,
                                         ClassRepository classRepository,
                                         QuestionBankItemRepository itemRepository,
                                         QuestionBankOptionRepository optionRepository) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
        this.testAccessResolver = testAccessResolver;
        this.classRepository = classRepository;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
    }

    @Transactional(readOnly = true)
    public List<BankItemSnapshot> searchApproved(Long userId, Role role, Long testId, String query) {
        Long subjectId = requireTestSubject(requireActor(userId, role), testId);
        String subjectCode = subjectCode(subjectId);
        String normalized = normalizeQuery(query);
        List<QuestionBankItem> items = itemRepository
                .findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                        subjectId, List.of(QuestionBankItem.STATUS_APPROVED))
                .stream()
                .filter(item -> normalized == null
                        || preview(item.getContent()).toLowerCase().contains(normalized)
                        || subjectCode.toLowerCase().contains(normalized))
                .limit(20)
                .toList();
        return snapshots(items, subjectCode);
    }

    @Transactional(readOnly = true)
    public List<BankItemSnapshot> approvedSnapshotsByIds(Long userId, Role role,
                                                         Long testId, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return List.of();
        Long subjectId = requireTestSubject(requireActor(userId, role), testId);
        String subjectCode = subjectCode(subjectId);
        Set<Long> wanted = new LinkedHashSet<>(itemIds);
        List<QuestionBankItem> items = itemRepository
                .findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                        subjectId, List.of(QuestionBankItem.STATUS_APPROVED))
                .stream().filter(item -> wanted.contains(item.getId())).toList();
        Map<Long, BankItemSnapshot> byId = new LinkedHashMap<>();
        for (BankItemSnapshot snapshot : snapshots(items, subjectCode)) {
            byId.put(snapshot.id(), snapshot);
        }
        List<BankItemSnapshot> ordered = new ArrayList<>();
        for (Long id : wanted) {
            if (byId.containsKey(id)) ordered.add(byId.get(id));
        }
        return ordered;
    }

    private List<BankItemSnapshot> snapshots(List<QuestionBankItem> items, String subjectCode) {
        if (items.isEmpty()) return List.of();
        Map<Long, List<QuestionBankOption>> options = new LinkedHashMap<>();
        for (QuestionBankOption option : optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(
                items.stream().map(QuestionBankItem::getId).toList())) {
            options.computeIfAbsent(option.getItemId(), ignored -> new ArrayList<>()).add(option);
        }
        return items.stream().map(item -> new BankItemSnapshot(
                item.getId(), subjectCode, item.getQuestionType(), item.getContent(),
                item.getExplanation(), options.getOrDefault(item.getId(), List.of()).stream()
                .map(option -> new BankOptionSnapshot(option.getContent(), option.isCorrect()))
                .toList())).toList();
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> forbidden());
        if (actor.getRole() != role) throw forbidden();
        return actor;
    }

    private Long requireTestSubject(User actor, Long testId) {
        Test test = testAccessResolver.requireManageable(testId, actor.getId(), actor.getRole());
        Long storedSubjectId = test.getSubjectId();
        if (storedSubjectId == null && test.getClassId() != null) {
            ClassEntity clazz = classRepository.findById(test.getClassId()).orElseThrow(this::forbidden);
            storedSubjectId = clazz.getSubjectId();
        }
        final Long subjectId = storedSubjectId;
        if (subjectId == null) throw forbidden();
        if (actor.getRole() != Role.ADMIN && !accessPolicy.canAccessSubject(actor, subjectId)) {
            throw forbidden();
        }
        return subjectId;
    }

    private String subjectCode(Long subjectId) {
        return subjectRepository.findById(subjectId).map(Subject::getCode)
                .orElseThrow(this::forbidden);
    }

    private AccessDeniedException forbidden() {
        return new AccessDeniedException("Bạn không có quyền dùng câu hỏi của mã môn này");
    }

    private static String normalizeQuery(String query) {
        return query == null || query.isBlank() ? null : query.trim().toLowerCase();
    }

    private static String preview(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
