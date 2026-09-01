package com.ksh.features.questionbank.repository;

import com.ksh.features.questionbank.entity.QuestionBankItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * Repository for subject-scoped shared question contributions.
 */
public interface QuestionBankItemRepository extends JpaRepository<QuestionBankItem, Long> {

    interface SubjectQuestionCount {
        Long getSubjectId();
        long getQuestionCount();
        LocalDateTime getLastUpdatedAt();
    }

    List<QuestionBankItem> findBySubjectIdOrderByUpdatedAtDescIdDesc(Long subjectId);

    @Query("""
            SELECT i
            FROM QuestionBankItem i
            WHERE i.subjectId = :subjectId
              AND (:status IS NULL OR i.workflowStatus = :status)
              AND (:query IS NULL OR LOWER(i.content) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY i.updatedAt DESC, i.id DESC
            """)
    Page<QuestionBankItem> findPage(@Param("subjectId") Long subjectId,
                                    @Param("status") String status,
                                    @Param("query") String query,
                                    Pageable pageable);

    @Query("""
            SELECT COUNT(i.id)
            FROM QuestionBankItem i
            WHERE i.subjectId = :subjectId
              AND i.workflowStatus = :status
              AND (:query IS NULL OR LOWER(i.content) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    long countForWorkspace(@Param("subjectId") Long subjectId,
                           @Param("status") String status,
                           @Param("query") String query);

    List<QuestionBankItem> findBySubjectIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
            Long subjectId, Collection<String> workflowStatuses);

    Optional<QuestionBankItem> findByIdAndSubjectId(Long id, Long subjectId);

    @Query("""
            SELECT i.subjectId AS subjectId,
                   COUNT(i.id) AS questionCount,
                   MAX(i.updatedAt) AS lastUpdatedAt
            FROM QuestionBankItem i
            WHERE i.subjectId IN :subjectIds
            GROUP BY i.subjectId
            """)
    List<SubjectQuestionCount> summarizeSubjects(@Param("subjectIds") Collection<Long> subjectIds);
}
