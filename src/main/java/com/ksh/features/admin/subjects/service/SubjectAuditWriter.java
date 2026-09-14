package com.ksh.features.admin.subjects.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksh.entities.SubjectActivity;
import com.ksh.features.admin.subjects.repository.SubjectActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Single insertion point for {@code subjects_activities} rows. */
@Component
class SubjectAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(SubjectAuditWriter.class);

    private final SubjectActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    SubjectAuditWriter(SubjectActivityRepository activityRepository,
                       ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    void write(Long subjectId, String type, String message,
               String metadata, Long actorId) {
        activityRepository.save(
                new SubjectActivity(subjectId, type, message, metadata, actorId));
    }

    String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize subject activity metadata", exception);
            return null;
        }
    }
}
