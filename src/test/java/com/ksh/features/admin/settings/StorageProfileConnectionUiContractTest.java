package com.ksh.features.admin.settings;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageProfileConnectionUiContractTest {

    @Test
    void sharedClientUsesCsrfHeaderAndDoesNotPutTokenInUrl() throws IOException {
        String script = read("src/main/resources/static/js/admin-settings-storage-profiles.js");

        assertThat(script)
                .contains("meta[name=\"_csrf\"]", "meta[name=\"_csrf_header\"]")
                .contains("encodeURIComponent(profileCode)")
                .contains("AbortController")
                .doesNotContain("?_csrf=");
    }

    @Test
    void createFormCannotTestAProfileThatHasNotBeenSaved() throws IOException {
        String template = read(
                "src/main/resources/templates/admin/settings-storage-profile-form.html");

        assertThat(template)
                .contains("mode == 'edit'")
                .contains("Kiểm tra kết nối đã lưu")
                .contains("Bài kiểm tra dùng cấu hình đã lưu")
                .doesNotContain("<script>\n");
    }

    @Test
    void overviewAndPracticeCardsBothExposeProfileScopedTests() throws IOException {
        assertThat(read("src/main/resources/templates/admin/settings-storage.html"))
                .contains("data-profile-code=\"GENERAL_UPLOADS\"")
                .contains("admin-settings-storage-profiles.js");
        assertThat(read("src/main/resources/templates/admin/settings-storage-profiles.html"))
                .contains("data-profile-code=${profile.profileCode}")
                .contains("admin-settings-storage-profiles.js");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
