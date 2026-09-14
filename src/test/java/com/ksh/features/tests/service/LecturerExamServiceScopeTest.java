package com.ksh.features.tests.service;

import com.ksh.entities.ClassEntity;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ksh.features.tests.dto.LecturerTestDtos.LecturerTestMetrics;
import com.ksh.features.tests.entity.Test;
import com.ksh.features.tests.repository.QuestionRepository;
import com.ksh.features.tests.repository.TestRepository;
import com.ksh.features.tests.support.TestAccessResolver;
import com.ksh.features.upload.ExamImageStorageService;
import com.ksh.security.Role;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LecturerExamServiceScopeTest {

    @Mock private TestRepository testRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private ClassRepository classRepository;
    @Mock private SubjectRepository subjectRepository;
    @Mock private TestAccessResolver accessResolver;
    @Mock private TestActivityWriter activityWriter;
    @Mock private TakeViewBuilder takeViewBuilder;
    @Mock private ExamQuestionBankWriter questionBankWriter;
    @Mock private ExamQuestionBankPickerService questionBankPicker;
    @Mock private ExamImageStorageService examImageStorage;

    @InjectMocks private LecturerExamService service;

    @org.junit.jupiter.api.Test
    void editingIndependentSourceCannotAttachItToAClass() {
        Test independent = new Test(7L, Test.TYPE_MOCK);
        independent.setSubjectId(10L);
        independent.setClassId(null);
        when(accessResolver.requireManageableForUpdate(55L, 7L)).thenReturn(independent);

        ExamForm forged = form(55L, 10L, 101L);

        assertThrows(IllegalArgumentException.class, () -> service.save(7L, forged));
        verifyNoInteractions(testRepository, subjectRepository, examImageStorage);
    }

    @org.junit.jupiter.api.Test
    void editingClassLocalTestCannotMoveItToAnotherClass() {
        Test local = new Test(7L, Test.TYPE_MOCK);
        local.setSubjectId(10L);
        local.setClassId(101L);
        when(accessResolver.requireManageableForUpdate(55L, 7L)).thenReturn(local);

        ExamForm forged = form(55L, 10L, 202L);

        assertThrows(IllegalArgumentException.class, () -> service.save(7L, forged));
        verifyNoInteractions(testRepository, subjectRepository, examImageStorage);
    }

    @org.junit.jupiter.api.Test
    void catalogMetricsUseTheSamePermissionScopeAndRealRepositoryTotals() {
        ClassEntity managedClass = org.mockito.Mockito.mock(ClassEntity.class);
        when(managedClass.getId()).thenReturn(101L);
        when(accessResolver.managementRole(7L)).thenReturn(Role.LECTURER);
        when(accessResolver.manageableClasses(7L, Role.LECTURER))
                .thenReturn(List.of(managedClass));
        when(testRepository.summarizeManageable(
                7L, List.of(-1L), List.of(-1L), false, true))
                .thenReturn(List.<Object[]>of(new Object[]{48L, 36L, 8L, 1286L}));

        LecturerTestMetrics metrics = service.metricsFor(7L);

        assertThat(metrics).isEqualTo(new LecturerTestMetrics(48, 36, 8, 1286));
    }

    @org.junit.jupiter.api.Test
    void lecturerCatalogQueriesPrivateRowsByCreatorNotByManagedClass() {
        ClassEntity managedClass = org.mockito.Mockito.mock(ClassEntity.class);
        when(managedClass.getId()).thenReturn(101L);
        when(accessResolver.managementRole(7L)).thenReturn(Role.LECTURER);
        when(accessResolver.manageableClasses(7L, Role.LECTURER))
                .thenReturn(List.of(managedClass));
        when(testRepository.searchManageable(
                eq(7L), eq(List.of(-1L)), eq(List.of(-1L)), eq(false), eq(true),
                eq(null), eq(""), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(Page.empty());

        assertThat(service.listOwned(7L, 0).getContent()).isEmpty();

        verify(testRepository).searchManageable(
                eq(7L), eq(List.of(-1L)), eq(List.of(-1L)), eq(false), eq(true),
                eq(null), eq(""), eq(null), eq(null), any(Pageable.class));
    }

    private ExamForm form(Long id, Long subjectId, Long classId) {
        return new ExamForm(id, "Đề kiểm tra", null, subjectId, classId,
                Test.TYPE_MOCK, Test.STATUS_DRAFT, Test.TIME_MODE_INDIVIDUAL,
                30, null, null, null, false, false,
                null, null, List.of(), false);
    }
}
