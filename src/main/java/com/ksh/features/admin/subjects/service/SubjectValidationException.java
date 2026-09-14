package com.ksh.features.admin.subjects.service;

/**
 * Domain exception for subject business-rule breaches (duplicate code,
 * ineligible leader, missing row). Message is Vietnamese UI text for toasts.
 */
public class SubjectValidationException extends RuntimeException {

    public SubjectValidationException(String message) {
        super(message);
    }
}
