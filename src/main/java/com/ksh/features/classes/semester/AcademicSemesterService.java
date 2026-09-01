package com.ksh.features.classes.semester;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Resolves the active semester, processing a due boundary before a class is created. */
@Service
public class AcademicSemesterService {
    public static final String SETTING_KEY = "academic.current_semester";
    private final SemesterCatalogService catalog;

    public AcademicSemesterService(SemesterCatalogService catalog) {
        this.catalog = catalog;
    }

    @Transactional
    public AcademicSemester current() {
        return AcademicSemester.parse(catalog.current().code());
    }

    @Transactional
    public String currentCode() {
        return current().code();
    }

    @Transactional
    public List<String> registeredCodes() {
        return catalog.list().stream().map(SemesterCatalogService.Entry::code).toList();
    }
}
