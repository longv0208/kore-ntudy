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

    @Test
    void retired_approval_page_is_not_available() {
        assertThat(TEMPLATE).doesNotExist();
    }

    @Test
    void leader_navigation_does_not_offer_class_approval() throws IOException {
        for (String template : new String[] {"fragments/leader-sidebar.html", "leader/dashboard.html"}) {
            assertThat(Files.readString(Path.of("src/main/resources/templates", template), StandardCharsets.UTF_8))
                    .doesNotContain("/leader/approvals");
        }
    }
}
