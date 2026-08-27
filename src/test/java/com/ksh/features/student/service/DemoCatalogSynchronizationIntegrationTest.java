package com.ksh.features.student.service;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures the canonical demo semester is visible consistently to learners. */
@SpringBootTest
@Transactional
class DemoCatalogSynchronizationIntegrationTest {

    @Autowired private StudentClassesService studentClassesService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void seeded_student_active_enrollments_are_visible_in_my_classes_and_catalog() {
        User student = userRepository.findByEmailIgnoreCase(
                "toannqhe180972@fpt.edu.vn").orElseThrow();

        var enrolled = studentClassesService.listEnrolledClasses(student.getId());
        assertThat(enrolled).hasSize(21);
        assertThat(enrolled)
                .anySatisfy(row -> {
                    assertThat(row.className()).isEqualTo("KOR311 - LeTT");
                    assertThat(row.classCode()).isEqualTo("KOR311");
                });

        var catalog = studentClassesService.listActiveCatalog(
                student.getId(), "KOR311 - LeTT", 0, 25);
        assertThat(catalog.getContent())
                .singleElement()
                .satisfies(row -> assertThat(row.alreadyEnrolled()).isTrue());
    }

    @Test
    void seeded_subject_owners_are_real_leader_accounts() {
        Integer invalidOwners = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM subjects subject_row
                JOIN users owner ON owner.id = subject_row.leader_user_id
                WHERE owner.role <> 'LEADER'
                """, Integer.class);

        assertThat(invalidOwners).isZero();
    }
}
