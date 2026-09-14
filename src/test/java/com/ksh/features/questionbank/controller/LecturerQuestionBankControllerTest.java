package com.ksh.features.questionbank.controller;

import com.ksh.features.questionbank.service.QuestionBankItemService;
import com.ksh.features.questionbank.service.QuestionBankTestGenerationService;
import com.ksh.features.questionbank.dto.QuestionBankViews.CatalogMetrics;
import com.ksh.features.questionbank.dto.QuestionBankViews.SubjectCatalogView;
import com.ksh.security.KshUserDetails;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LecturerQuestionBankControllerTest {

    private final QuestionBankItemService itemService = mock(QuestionBankItemService.class);
    private final QuestionBankTestGenerationService generationService =
            mock(QuestionBankTestGenerationService.class);
    private final LecturerQuestionBankController controller =
            new LecturerQuestionBankController(itemService, generationService);
    private final KshUserDetails user = mock(KshUserDetails.class);

    @Test
    void create_form_is_enabled_when_actor_has_a_subject() {
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.LECTURER);
        when(itemService.hasSubject(7L, Role.LECTURER)).thenReturn(true);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.createForm(user, model)).isEqualTo("questionbank/form");
        assertThat(model.get("emptySubject")).isEqualTo(false);
        assertThat(model).doesNotContainKeys("categories", "emptyCategories");
    }

    @Test
    void create_form_is_blocked_without_a_subject() {
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.LECTURER);
        when(itemService.hasSubject(7L, Role.LECTURER)).thenReturn(false);
        ExtendedModelMap model = new ExtendedModelMap();

        controller.createForm(user, model);

        assertThat(model.get("emptySubject")).isEqualTo(true);
    }

    @Test
    void list_without_subject_opens_catalog_instead_of_implicitly_selecting_first_subject() {
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.LECTURER);
        when(itemService.hasSubject(7L, Role.LECTURER)).thenReturn(true);
        when(itemService.subjectOptions(7L, Role.LECTURER)).thenReturn(java.util.List.of());
        when(itemService.subjectCatalogView(7L, Role.LECTURER, "kor", "ALL", "UPDATED_DESC"))
                .thenReturn(new SubjectCatalogView(
                        new CatalogMetrics(0, 0, 0, 0), java.util.List.of()));
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.list(null, null, "kor", "ALL", "UPDATED_DESC", 0, 50, user, model))
                .isEqualTo("questionbank/list");
        assertThat(model.get("catalogMode")).isEqualTo(true);
        assertThat(model).containsKeys("subjectCatalog", "catalogMetrics")
                .doesNotContainKey("workspace");
        verify(itemService, never()).workspace(7L, Role.LECTURER, null, "kor");
    }
}
