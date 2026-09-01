package com.ksh.features.profile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileHomeDashboardUiContractTest {

    @Test
    void profileUsesDedicatedResponsiveControlsAndAccessibleAvatarFeedback() throws IOException {
        String template = read("src/main/resources/templates/profile.html");
        String css = read("src/main/resources/static/css/profile.css");
        String script = read("src/main/resources/static/js/profile.js");

        assertThat(template)
                .contains("/css/profile.css")
                .contains("id=\"avatarFile\"")
                .contains("for=\"avatarFile\"")
                .contains("accept=\"image/jpeg,image/png,image/webp\"")
                .contains("role=\"alert\"")
                .contains("aria-live=\"polite\"")
                .contains("/js/profile.js")
                .doesNotContain("style=");
        assertThat(css)
                .contains("grid-template-columns: minmax(260px, 320px) minmax(0, 1fr)")
                .contains("@media (max-width: 600px)")
                .contains(":focus-visible");
        assertThat(script)
                .contains("avatarInput.addEventListener('change'")
                .contains("bio.addEventListener('input'")
                .doesNotContain("fetch(");
    }

    @Test
    void homeProvidesRoleAwareActionsAndContainsNoSprintPlaceholder() throws IOException {
        String template = read("src/main/resources/templates/home.html");
        String css = read("src/main/resources/static/css/home.css");

        assertThat(template)
                .contains("Khu vực dành cho bạn")
                .contains("@{/lecturer/dashboard}")
                .contains("@{/lecturer/classes}")
                .contains("@{/practice}")
                .contains("@{/admin/dashboard}")
                .contains("@{/profile}")
                .contains("home-mascot-sprite")
                .contains("Bạch Hổ chào bạn trở lại")
                .doesNotContain("Sprint ")
                .doesNotContain("style=");
        assertThat(css)
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                .contains("baekho_celebration_128.png")
                .contains("@keyframes home-baekho-welcome")
                .contains("prefers-reduced-motion: reduce")
                .contains("@media (max-width: 520px)")
                .contains(":focus-visible");
    }

    @Test
    void sharedShellUsesBaekhoBrandAndPersistedAvatar() throws IOException {
        String head = read("src/main/resources/templates/fragments/head.html");
        String header = read("src/main/resources/templates/fragments/app-header.html");
        String css = read("src/main/resources/static/css/app-shell.css");
        String login = read("src/main/resources/templates/auth/login.html");
        String forgot = read("src/main/resources/templates/auth/forgot-password.html");
        String reset = read("src/main/resources/templates/auth/reset-password.html");

        assertThat(head)
                .contains("/images/brand/ksh-logo.png")
                .contains("rel=\"apple-touch-icon\"");
        assertThat(header)
                .contains("class=\"logo-mark\"")
                .contains("/images/brand/ksh-logo.png")
                .contains("#authentication.principal.avatarUrl")
                .contains("class=\"avatar-initial\"");
        assertThat(css)
                .contains(".logo-mark img")
                .contains(".user-chip .avatar img")
                .contains("object-fit: cover");
        assertThat(login).contains("/images/brand/ksh-logo.png");
        assertThat(forgot).contains("/images/brand/ksh-logo.png");
        assertThat(reset).contains("/images/brand/ksh-logo.png");
    }

    @Test
    void lecturerDashboardUsesNeutralInlineIconsAndMobileTableLabels() throws IOException {
        String template = read("src/main/resources/templates/lecturer/dashboard.html");
        String css = read("src/main/resources/static/css/lecturer-dashboard.css");

        assertThat(template)
                .contains("class=\"ldash-metric-icon is-blue\"")
                .contains("data-label=\"Lớp học\"")
                .contains("data-label=\"Tiến độ trung bình\"")
                .contains("data-label=\"Học kỳ\"")
                .contains("class=\"ldash-progress\"")
                .contains("<progress max=\"100\"")
                .contains("Quản lý lớp học")
                .contains("Kho bài giảng")
                .contains("Chưa có lớp học trong phạm vi của bạn")
                .doesNotContain("font-awesome")
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("fa-solid")
                .doesNotContain("style=");
        assertThat(css)
                .contains("background: #f8faff")
                .contains("--ld-blue: #1677f2")
                .contains(".ldash-search {\n  display: flex")
                .contains(".lecturer-dash .sr-only")
                .contains("flex: 1 1 auto")
                .contains(".ldash-semester[data-semester^=\"SP\"]")
                .contains(".ldash-semester[data-semester^=\"SU\"]")
                .contains(".ldash-semester[data-semester^=\"FA\"]")
                .contains("progress::-webkit-progress-value")
                .contains("content: attr(data-label)")
                .contains("@media (max-width: 520px)");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
