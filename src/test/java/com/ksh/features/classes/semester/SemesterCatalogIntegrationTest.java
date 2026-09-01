package com.ksh.features.classes.semester;

import com.ksh.entities.SystemSetting;
import com.ksh.features.admin.settings.repository.SystemSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@SpringBootTest(properties = "app.semesters.auto-transition-enabled=false")
@AutoConfigureMockMvc
@Transactional
class SemesterCatalogIntegrationTest {
    @Autowired SemesterCatalogService catalog;
    @Autowired AcademicSemesterService current;
    @Autowired SystemSettingsRepository settings;
    @Autowired MockMvc mvc;

    private void active(String code, LocalDateTime start, LocalDateTime end) {
        var anchor = settings.findBySettingKey(AcademicSemesterService.SETTING_KEY).orElseThrow();
        anchor.setSettingValue(code);
        settings.saveAndFlush(anchor);
        var row = settings.findBySettingKey(SemesterCatalogService.PREFIX + code)
                .orElseGet(() -> new SystemSetting(SemesterCatalogService.PREFIX + code, "", "ACADEMIC"));
        row.setSettingValue("v2|" + start + "|" + (end == null ? "" : end) + "|1");
        settings.saveAndFlush(row);
    }

    @Test void transitionClosesCurrentWithoutChangingHistoricalClasses() {
        var now = LocalDateTime.now(SemesterCatalogService.ZONE).withNano(0);
        active("SU26", now.minusDays(1), null);
        var next = catalog.transition("SU26", 1, now.plusDays(30), 1L);
        assertThat(current.currentCode()).isEqualTo("FA26");
        assertThat(catalog.list()).filteredOn(e -> e.code().equals("SU26")).singleElement()
                .extracting(SemesterCatalogService.Entry::endAt).isEqualTo(next.startAt());
        assertThat(catalog.list()).filteredOn(SemesterCatalogService.Entry::latest).hasSize(1);
    }

    @Test void currentReadProcessesDueSemesterExactlyOnce() {
        var now = LocalDateTime.now(SemesterCatalogService.ZONE).withNano(0);
        var boundary = now.minusMinutes(5);
        active("SU26", now.minusDays(1), boundary);
        assertThat(current.currentCode()).isEqualTo("FA26");
        assertThat(current.currentCode()).isEqualTo("FA26");
        assertThat(catalog.current().startAt()).isEqualTo(boundary);
    }

    @Test @WithUserDetails("admin@ksh.edu.vn")
    void rendersOnlyControlledLifecycleActions() throws Exception {
        mvc.perform(get("/admin/semesters")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Hẹn lịch chuyển kỳ")))
                .andExpect(content().string(containsString("expectedRevision")))
                .andExpect(content().string(not(containsString("name=\"code\""))))
                .andExpect(content().string(not(containsString("name=\"start\""))));
        mvc.perform(post("/admin/semesters").with(csrf()).param("code", "FA29")
                .param("start", "2029-09-01").param("end", "2029-12-31"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test @WithUserDetails("lecturer@ksh.edu.vn")
    void lecturerCannotManageCatalog() throws Exception {
        mvc.perform(get("/admin/semesters")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/semesters/transition").with(csrf())
                .param("expectedCurrent", "SU26").param("expectedRevision", "1"))
                .andExpect(status().isForbidden());
    }

    @Test @WithUserDetails("kor_leader@ksh.edu.vn")
    void koreanLeaderSeesAllAssignedSubjects() throws Exception {
        mvc.perform(get("/lecturer/library/list")).andExpect(status().isOk())
                .andExpect(content().string(containsString("KOR311")))
                .andExpect(content().string(containsString("KRL502")));
    }
}
