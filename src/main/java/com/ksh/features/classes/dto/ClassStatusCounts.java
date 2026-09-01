package com.ksh.features.classes.dto;

/** Filter-scoped class lifecycle totals used by the four lecturer workspace tabs. */
public record ClassStatusCounts(long active, long pending, long rejected, long archived) {
    public static ClassStatusCounts empty() {
        return new ClassStatusCounts(0, 0, 0, 0);
    }
}
