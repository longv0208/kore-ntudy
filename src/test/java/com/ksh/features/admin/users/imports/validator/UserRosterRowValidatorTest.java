package com.ksh.features.admin.users.imports.validator;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.entities.UserFactory;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.admin.users.imports.dto.UserImportRowStatus;
import com.ksh.features.admin.users.imports.parser.UserRosterParser;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRosterRowValidatorTest {

    @Mock private UserRepository userRepository;
    @Mock private SubjectRepository subjectRepository;

    private UserRosterRowValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UserRosterRowValidator(userRepository, subjectRepository);
        Subject korean = new Subject("Korean Language", "KOR20", null, true);
        ReflectionTestUtils.setField(korean, "id", 20L);
        when(subjectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(korean));
    }

    @Test
    void validRows_defaultStudentResolveSubjectAndNormalizeExistingLookup() {
        when(userRepository.findByEmailIncludingDeleted("minji@example.edu.vn"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIncludingDeleted("lecturer@example.edu.vn"))
                .thenReturn(Optional.empty());

        var rows = validator.validate(List.of(
                raw(2, " Minji@Example.edu.vn ", "Minji Kim", "", "", "0901234567"),
                raw(3, "lecturer@example.edu.vn", "Jisoo Kim", "lecturer",
                        " korean language ", "")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getStatus()).isEqualTo(UserImportRowStatus.CREATABLE);
        assertThat(rows.get(0).getRole()).isEqualTo(Role.STUDENT);
        assertThat(rows.get(0).isRoleDefaulted()).isTrue();
        assertThat(rows.get(1).getRole()).isEqualTo(Role.LECTURER);
        assertThat(rows.get(1).getSubjectId()).isEqualTo(20L);
    }

    @Test
    void invalidAndDuplicateRowsAreFailClosedAndLeaderCannotBypassAssignmentFlow() {
        var rows = validator.validate(List.of(
                raw(2, "", "No Email", "STUDENT", "", ""),
                raw(3, "bad-email", "Bad", "STUDENT", "", ""),
                raw(4, "leader@example.edu.vn", "Leader", "LEADER", "KOR20", ""),
                raw(5, "unknown@example.edu.vn", "Unknown", "LECTURER", "NOPE", ""),
                raw(6, "dup@example.edu.vn", "First", "STUDENT", "", ""),
                raw(7, " DUP@example.edu.vn ", "Second", "STUDENT", "", "")));

        assertThat(rows).extracting(row -> row.getStatus()).containsExactly(
                UserImportRowStatus.MISSING_EMAIL,
                UserImportRowStatus.INVALID_EMAIL,
                UserImportRowStatus.INVALID_ROLE,
                UserImportRowStatus.UNKNOWN_SUBJECT,
                UserImportRowStatus.CREATABLE,
                UserImportRowStatus.DUPLICATE_IN_FILE);
        assertThat(rows.get(2).getDetail()).contains("LEADER phải được gán tại màn Môn học");
    }

    @Test
    void existingPendingAccountIsSkippedWithPendingStatus() {
        User pending = UserFactory.newPendingActivation(
                "existing@example.edu.vn", "unknown-hash", "Existing",
                Role.STUDENT, null, null);
        when(userRepository.findByEmailIncludingDeleted("existing@example.edu.vn"))
                .thenReturn(Optional.of(pending));

        var row = validator.validate(List.of(raw(2, "existing@example.edu.vn",
                "Existing", "STUDENT", "", ""))).get(0);

        assertThat(row.getStatus()).isEqualTo(UserImportRowStatus.ALREADY_EXISTS);
        assertThat(row.getExistingStatusLabel()).isEqualTo("PENDING");
    }

    private static UserRosterParser.RawRosterRow raw(int row, String email,
                                                      String fullName, String role,
                                                      String subject, String phone) {
        return new UserRosterParser.RawRosterRow(row, email, fullName, role, subject, phone);
    }
}
