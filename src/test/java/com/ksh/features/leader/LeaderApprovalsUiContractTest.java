package com.ksh.features.leader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderApprovalsUiContractTest {

    private static final Path TEMPLATE =
            Path.of("src/main/resources/templates/leader/approvals.html");
    private static final Path STYLES =
            Path.of("src/main/resources/static/css/leader-department.css");

    @Test
    void loads_the_shared_leader_layout_styles() throws IOException {
        String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);

        assertThat(template)
                .contains("@{/css/class-detail.css}")
                .contains("@{/css/detail-page.css}")
                .contains("class=\"col detail-page leader-approval-main\"")
                .doesNotContain("leaderEmpty");
    }

    @Test
    void provides_responsive_and_accessible_approval_actions() throws IOException {
        String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        String styles = Files.readString(STYLES, StandardCharsets.UTF_8);

        assertThat(template)
                .contains("class=\"admin-list-table-scroll leader-table-shell\"")
                .contains("class=\"leader-approval-queue\"")
                .contains("data-item-filter=\"#leader-approval-rows\"")
                .contains("data-filter-item")
                .contains("leader-empty-illustration")
                .contains("class=\"leader-approval-actions\"")
                .contains("class=\"sr-only\"")
                .contains("data-label=\"Hành động\"");
        assertThat(styles)
                .contains(".leader-approval-tabs")
                .contains(".leader-approval-filter")
                .contains(".leader-page .leader-workspace")
                .contains(".leader-approval-table tbody td::before")
                .contains("@media (max-width: 720px)");
    }
}
