package com.ksh.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level contract for the progressive detail-tab enhancement.
 *
 * <p>The project has no JavaScript unit-test runner, so this pins the integration
 * seams that are otherwise easy to break during template refactors. Browser
 * behaviour remains a separate end-to-end validation concern.
 */
class DetailTabsContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void sharedOrchestratorUsesTheKshNamespaceAndKeepsHardNavigationFallback()
            throws Exception {
        String source = read("static/js/detail-tabs.js");

        assertThat(source)
                .contains("window.KshDetailTabs")
                .contains("ksh:detail-tab-loaded")
                .contains("dirtyGuard.confirmNavigation()")
                .contains("dirtyGuard.beginNavigation()")
                .contains("dirtyGuard.handlePopState(event")
                .contains("dirtyGuard.allowHardNavigation()")
                .contains("window.KshDirtyFormGuard.create(panel)")
                .contains("credentials: 'same-origin'")
                .contains("navigationSequence")
                .contains("window.AbortController")
                .contains("requestId !== navigationSequence")
                .contains("window.location.href = url")
                .doesNotContain("UlpDetailTabs", "ulp:detail-tab-loaded");

        assertThat(source).containsOnlyOnce("navigate(href, true);");
        assertThat(source.indexOf("if (!dirtyGuard.confirmNavigation()) return;"))
                .as("Cancel is decided before the only click navigation")
                .isLessThan(source.indexOf("navigate(href, true);"));
        assertThat(source.indexOf("dirtyGuard.beginNavigation();"))
                .as("dirty decision precedes teardown/loading")
                .isLessThan(source.indexOf("showLoading();"));
        assertThat(source.indexOf("window.KshDirtyFormGuard.create(panel)"))
                .as("create mode installs native navigation protection")
                .isLessThan(source.indexOf("if (!tabsNav) return;"));
        assertThat(source.indexOf("panel.innerHTML = fresh.innerHTML;"))
                .as("the fresh panel exists before its baseline is reset")
                .isLessThan(source.indexOf("dirtyGuard.reset();"));
    }

    @Test
    void everyAdoptedTemplateExposesOnePanelAndLoadsTheSharedScript() throws Exception {
        List<String> templates = List.of(
                "templates/admin/users-form.html",
                "templates/admin/subjects-form.html",
                "templates/admin/settings-ai-form.html",
                "templates/classes/detail-settings.html");

        for (String template : templates) {
            String source = read(template);
            assertThat(source)
                    .as(template)
                    .containsOnlyOnce("id=\"tabPanel\"")
                    .contains("th:src=\"@{/js/detail-tabs-dirty-guard.js}\"")
                    .contains("th:src=\"@{/js/detail-tabs.js}\"");
            assertThat(source.indexOf("@{/js/detail-tabs-dirty-guard.js}"))
                    .as(template + " loads the guard before the orchestrator")
                    .isPositive()
                    .isLessThan(source.indexOf("@{/js/detail-tabs.js}"));
        }
    }

    @Test
    void dirtyGuardProtectsAjaxAndHardNavigationWithoutTreatingGetSearchAsDraft()
            throws Exception {
        String source = read("static/js/detail-tabs-dirty-guard.js");

        assertThat(source)
                .contains("panelSnapshot")
                .contains("[contenteditable]")
                .contains("isExplicitGet(form)")
                .contains("window.confirm(MESSAGE)")
                .contains("window.addEventListener('beforeunload'")
                .contains("window.addEventListener('submit'")
                .contains("window.addEventListener('reset'")
                .contains("if (!event.defaultPrevented)")
                .contains("pendingTraversal")
                .contains("if (this.mutationPending || this.pendingTraversal) return false")
                .contains("window.history.go(-delta)")
                .contains("window.history.go(pending.delta)")
                .contains("window.history.replaceState(this.renderedState")
                .contains("element.closest('[data-dirty-guard=\"ignore\"]')")
                .contains("confirmNavigation: function ()")
                .contains("beginMutation: function (confirmDirty)")
                .contains("cancelMutation: function ()")
                .contains("completeMutation: function ()")
                .contains("this.suspended = true")
                .contains("activeGuard.reset()");
    }

    @Test
    void asynchronousMutationsFreezeThePanelUntilFailureOrCommittedRedirect()
            throws Exception {
        String guard = read("static/js/detail-tabs-dirty-guard.js");
        assertThat(guard)
                .contains("if (this.mutationPending) return true")
                .contains("this.panel.setAttribute('inert', '')")
                .contains("this.panel.setAttribute('aria-busy', 'true')")
                .contains("this.clearMutationLock()")
                .contains("this.allowHardNavigation()");

        String lecturer = read("static/js/test-lecturer-form.js");
        assertThat(lecturer)
                .contains("window.KshDirtyFormGuard.beginMutation(true)")
                .contains("window.KshDirtyFormGuard.beginMutation(false)")
                .contains("window.KshDirtyFormGuard.cancelMutation()")
                .contains("window.KshDirtyFormGuard.completeMutation()");
        assertThat(lecturer.indexOf("window.KshDirtyFormGuard.beginMutation(false)"))
                .as("the save locks edits before asynchronous image rewriting")
                .isLessThan(lecturer.indexOf("Promise.resolve().then(rewriteAllDataImages)"));

        String ai = read("static/js/test-lecturer-ai-questions.js");
        assertThat(ai)
                .contains("window.KshDirtyFormGuard.beginMutation(true)")
                .contains("window.KshDirtyFormGuard.cancelMutation()")
                .contains("window.KshDirtyFormGuard.completeMutation()");
        assertThat(ai.indexOf("window.KshDirtyFormGuard.beginMutation(true)"))
                .as("the AI panel locks before the persisted insert request")
                .isLessThan(ai.indexOf("window.FcCommon.postJson(confirmUrl"));
    }

    @Test
    void testTabsOwnTheirLifecycleAndInviteHandlersAreDelegated() throws Exception {
        String tabs = read("static/js/test-detail-tabs.js");
        assertThat(tabs)
                .contains("data-ajax-tabs', 'owned'")
                .contains("detail-tab-loading")
                .contains("aria-busy")
                .contains("dirtyGuard.confirmNavigation()")
                .contains("dirtyGuard.beginNavigation()")
                .contains("dirtyGuard.handlePopState(event")
                .contains("dirtyGuard.allowHardNavigation()")
                .contains("dirtyGuard.reset()")
                .contains("window.KshDirtyFormGuard.create(panel)")
                .contains("navigationSequence")
                .contains("window.AbortController")
                .contains("requestId !== navigationSequence");

        assertThat(tabs).containsOnlyOnce("navigate(href, true);");
        assertThat(tabs)
                .contains("if (isTab && link.classList.contains('active')")
                .contains("sameUrl = new URL(href, window.location.origin).href ===")
                .contains("if (sameUrl) {");
        assertThat(tabs.indexOf("if (sameUrl) {"))
                .as("an exact active-tab click is suppressed before dirty confirmation")
                .isLessThan(tabs.indexOf("if (!dirtyGuard.confirmNavigation()) return;"));
        assertThat(tabs.indexOf("if (!dirtyGuard.confirmNavigation()) return;"))
                .as("Cancel is decided before monitor teardown/loading")
                .isLessThan(tabs.indexOf("navigate(href, true);"));
        assertThat(tabs.indexOf("dirtyGuard.beginNavigation();"))
                .isLessThan(tabs.indexOf("showLoading();"));
        assertThat(tabs.indexOf("window.KshDirtyFormGuard.create(panel)"))
                .as("test create mode installs native navigation protection")
                .isLessThan(tabs.indexOf("if (!tabsNav) return;"));
        assertThat(tabs.indexOf("panel.innerHTML = fresh.innerHTML;"))
                .isLessThan(tabs.indexOf("dirtyGuard.reset();"));

        String template = read("templates/tests/lecturer-form.html");
        assertThat(template)
                .contains("th:src=\"@{/js/detail-tabs-dirty-guard.js}\"")
                .contains("th:src=\"@{/js/test-detail-tabs.js}\"");
        assertThat(template.indexOf("@{/js/detail-tabs-dirty-guard.js}"))
                .isPositive()
                .isLessThan(template.indexOf("@{/js/test-detail-tabs.js}"));
        assertThat(template)
                .contains("id=\"lfBankPicker\"")
                .contains("id=\"lfAiGenPanel\"")
                .contains("data-dirty-guard=\"ignore\"")
                .contains("${clazz == null} ? 'lf-no-sidebar'")
                .contains("<th:block th:if=\"${clazz != null}\">")
                .contains("classSidebar(${clazz}, 'tests')")
                .doesNotContain("<aside th:if=\"${mode == 'edit' and clazz != null}\"");

        assertThat(read("static/js/test-lecturer-form.js"))
                .contains("window.KshDirtyFormGuard.markClean()")
                .contains("window.KshDirtyFormGuard.completeMutation()")
                .contains("window.KshDirtyFormGuard.beginMutation(true)");
        assertThat(read("static/js/test-lecturer-ai-questions.js"))
                .contains("window.KshDirtyFormGuard.beginMutation(true)")
                .contains("window.KshDirtyFormGuard.completeMutation()");

    }

    private static String read(String relative) throws IOException {
        return Files.readString(RESOURCES.resolve(relative), StandardCharsets.UTF_8);
    }
}
