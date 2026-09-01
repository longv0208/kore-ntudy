package com.ksh.features.classes.dto;

/** Filter-scoped totals, independent of the selected lifecycle tab and page. */
public record ClassOverview(long total, long active, long archived, long students, long teachers) {
    public static ClassOverview empty() {
        return new ClassOverview(0, 0, 0, 0, 0);
    }
}
