package com.ksh.features.classes.semester;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Due periods advance even without user traffic; delayed restarts retain the exact boundary. */
@Component
@ConditionalOnProperty(name = "app.semesters.auto-transition-enabled", havingValue = "true", matchIfMissing = true)
public class SemesterTransitionScheduler {
    private final SemesterCatalogService catalog;
    public SemesterTransitionScheduler(SemesterCatalogService catalog) { this.catalog = catalog; }

    @Scheduled(fixedDelayString = "${app.semesters.transition-check-ms:30000}", initialDelay = 30000)
    public void transitionDueSemester() { catalog.advanceIfDue(); }
}
