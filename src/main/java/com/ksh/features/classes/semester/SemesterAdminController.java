package com.ksh.features.classes.semester;

import com.ksh.security.KshUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.LocalDateTime;

@Controller
@RequestMapping("/admin/semesters")
@PreAuthorize("hasRole('ADMIN')")
public class SemesterAdminController {
    private final SemesterCatalogService catalog;
    public SemesterAdminController(SemesterCatalogService catalog) { this.catalog = catalog; }
    @GetMapping
    public String page(Model model) {
        var semesters = catalog.list();
        model.addAttribute("semesters", semesters);
        model.addAttribute("currentSemester", semesters.stream().filter(SemesterCatalogService.Entry::latest)
                .findFirst().orElseThrow());
        return "admin/semesters";
    }

    @PostMapping("/schedule")
    public String schedule(@RequestParam String expectedCurrent, @RequestParam long expectedRevision,
                           @RequestParam(required = false) String endAt,
                           @AuthenticationPrincipal KshUserDetails user, RedirectAttributes redirect) {
        try {
            var saved = catalog.schedule(expectedCurrent, expectedRevision, parseOptional(endAt), user.getId());
            redirect.addFlashAttribute("flashSuccess", saved.endAt() == null
                    ? "Đã bỏ lịch tự động. Học kỳ chỉ kết thúc khi bạn chuyển kỳ."
                    : "Đã hẹn kết thúc " + saved.code() + "; hệ thống sẽ tự mở " + saved.nextCode() + " đúng thời điểm.");
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            redirect.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/semesters";
    }

    @PostMapping("/transition")
    public String transition(@RequestParam String expectedCurrent, @RequestParam long expectedRevision,
                             @RequestParam(required = false) String nextEndAt,
                             @AuthenticationPrincipal KshUserDetails user, RedirectAttributes redirect) {
        try {
            var next = catalog.transition(expectedCurrent, expectedRevision, parseOptional(nextEndAt), user.getId());
            redirect.addFlashAttribute("flashSuccess", "Đã kết thúc " + expectedCurrent + " và bắt đầu " + next.code() + ".");
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            redirect.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/semesters";
    }

    private LocalDateTime parseOptional(String raw) {
        return raw == null || raw.isBlank() ? null : LocalDateTime.parse(raw).withNano(0);
    }
}
