package com.ksh.features.classes.semester;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Value object for the three-semester academic calendar: SP, SU and FA. */
public record AcademicSemester(Term term, int year) implements Comparable<AcademicSemester> {

    private static final Pattern CODE = Pattern.compile("^(SP|SU|FA)(\\d{2})$");

    public enum Term {
        SP("Xuân"), SU("Hè"), FA("Thu");

        private final String label;

        Term(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public AcademicSemester {
        if (term == null || year < 2000 || year > 2099) {
            throw new IllegalArgumentException("Học kỳ phải có dạng SPyy, SUyy hoặc FAyy");
        }
    }

    public static AcademicSemester parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = CODE.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Học kỳ phải có dạng SPyy, SUyy hoặc FAyy");
        }
        return new AcademicSemester(Term.valueOf(matcher.group(1)),
                2000 + Integer.parseInt(matcher.group(2)));
    }

    public static AcademicSemester from(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Ngày học kỳ không được để trống");
        Term term = date.getMonthValue() <= 4 ? Term.SP
                : date.getMonthValue() <= 8 ? Term.SU : Term.FA;
        return new AcademicSemester(term, date.getYear());
    }

    public String code() {
        return term.name() + String.format(Locale.ROOT, "%02d", year % 100);
    }

    public String displayName() {
        return "Học kỳ " + term.label() + " " + year;
    }

    public int orderKey() {
        return year * 3 + term.ordinal();
    }

    /** The calendar cannot skip a term, including the FA → SP year boundary. */
    public AcademicSemester next() {
        return term == Term.FA ? new AcademicSemester(Term.SP, year + 1)
                : new AcademicSemester(Term.values()[term.ordinal() + 1], year);
    }

    @Override
    public int compareTo(AcademicSemester other) {
        return Integer.compare(orderKey(), other.orderKey());
    }
}
