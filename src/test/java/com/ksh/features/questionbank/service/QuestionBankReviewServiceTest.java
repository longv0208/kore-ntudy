package com.ksh.features.questionbank.service;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.leader.service.LeaderSubjectResolver;
import com.ksh.features.questionbank.entity.QuestionBankItem;
import com.ksh.features.questionbank.repository.QuestionBankItemRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionBankReviewServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LeaderSubjectResolver resolver = mock(LeaderSubjectResolver.class);
    private final QuestionBankAccessPolicy accessPolicy = new QuestionBankAccessPolicy(resolver);
    private final QuestionBankItemRepository itemRepository = mock(QuestionBankItemRepository.class);
    private final QuestionBankReviewService service = new QuestionBankReviewService(
            userRepository, accessPolicy, itemRepository);
    private User leader;
    private QuestionBankItem item;

    @BeforeEach
    void setUp() {
        leader = mock(User.class);
        when(leader.getId()).thenReturn(30L);
        when(leader.getRole()).thenReturn(Role.LEADER);
        Subject subject = new Subject("Tiếng Hàn 3.1.1", "KOR311", null, true);
        ReflectionTestUtils.setField(subject, "id", 5L);
        subject.assignLeader(30L);
        when(userRepository.findById(30L)).thenReturn(Optional.of(leader));
        when(resolver.resolve(30L)).thenReturn(Optional.of(subject));
        when(resolver.resolveAll(30L)).thenReturn(List.of(subject));
        item = new QuestionBankItem(5L, 20L, QuestionBankItem.TYPE_MCQ,
                QuestionBankItem.STATUS_REVIEW, "<p>Question</p>", null);
        ReflectionTestUtils.setField(item, "id", 10L);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
    }

    @Test
    void leader_approves_review_item_in_own_subject() {
        service.approve(30L, 10L);

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(item.getReviewedBy()).isEqualTo(30L);
        assertThat(item.getApprovedAt()).isNotNull();
    }

    @Test
    void invalid_transition_is_rejected() {
        item.transitionWorkflow(QuestionBankItem.STATUS_DRAFT, null, null, null, null);

        assertThatThrownBy(() -> service.approve(30L, 10L))
                .isInstanceOf(QuestionBankValidationException.class);
    }

    @Test
    void leader_cannot_review_cross_subject_item() {
        QuestionBankItem crossSubject = new QuestionBankItem(6L, 20L, QuestionBankItem.TYPE_MCQ,
                QuestionBankItem.STATUS_REVIEW, "<p>Foreign</p>", null);
        ReflectionTestUtils.setField(crossSubject, "id", 10L);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(crossSubject));

        assertThatThrownBy(() -> service.approve(30L, 10L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void reject_trims_the_reviewer_note_and_preserves_rejected_state() {
        service.reject(30L, 10L, "  Thiếu dẫn chứng  ");

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_REJECTED);
        assertThat(item.getReviewNote()).isEqualTo("Thiếu dẫn chứng");
        assertThat(item.getReviewedBy()).isEqualTo(30L);
        verify(itemRepository).save(item);
    }

    @Test
    void archive_and_unarchive_restore_the_exact_prior_state() {
        item.transitionWorkflow(QuestionBankItem.STATUS_APPROVED, 30L, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());

        service.archive(30L, 10L, "   ");

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);
        assertThat(item.getStatusBeforeArchive()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(item.getReviewNote()).isNull();

        service.unarchive(30L, 10L);

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(item.getStatusBeforeArchive()).isNull();
        verify(itemRepository, org.mockito.Mockito.times(2)).save(item);
    }

    @Test
    void unarchive_uses_review_as_the_safe_legacy_fallback() {
        item.transitionWorkflow(QuestionBankItem.STATUS_ARCHIVED, 30L, null,
                java.time.LocalDateTime.now(), null);

        service.unarchive(30L, 10L);

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_REVIEW);
    }

    @Test
    void bulk_approve_deduplicates_ids_ignores_nulls_and_reports_partial_success() {
        QuestionBankItem invalid = new QuestionBankItem(5L, 20L, QuestionBankItem.TYPE_MCQ,
                QuestionBankItem.STATUS_DRAFT, "<p>Invalid</p>", null);
        ReflectionTestUtils.setField(invalid, "id", 11L);
        when(itemRepository.findById(11L)).thenReturn(Optional.of(invalid));

        QuestionBankReviewService.BulkResult result = service.approveAll(30L,
                java.util.Arrays.asList(10L, 10L, null, 11L));

        assertThat(result).isEqualTo(new QuestionBankReviewService.BulkResult(1, 1));
        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    void bulk_actions_handle_empty_and_missing_items_without_rolling_back_other_rows() {
        assertThat(service.rejectAll(30L, null, "note"))
                .isEqualTo(new QuestionBankReviewService.BulkResult(0, 0));

        when(itemRepository.findById(11L)).thenReturn(Optional.empty());
        QuestionBankReviewService.BulkResult archived = service.archiveAll(30L,
                List.of(10L, 11L), "archive");

        assertThat(archived).isEqualTo(new QuestionBankReviewService.BulkResult(1, 1));
        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);

        QuestionBankReviewService.BulkResult unarchived = service.unarchiveAll(30L,
                List.of(10L, 11L));

        assertThat(unarchived).isEqualTo(new QuestionBankReviewService.BulkResult(1, 1));
        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_REVIEW);
    }
}
