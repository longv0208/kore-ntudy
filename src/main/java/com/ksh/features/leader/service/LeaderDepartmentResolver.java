package com.ksh.features.leader.service;

import com.ksh.entities.Department;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the working department for a LEADER user.
 *
 * <p>Preference order: department where {@code leader_user_id} matches the user,
 * otherwise the department referenced by {@code users.subject_id} when it
 * exists (and preferably is active).
 */
@Service
public class LeaderDepartmentResolver {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    public LeaderDepartmentResolver(DepartmentRepository departmentRepository,
                                  UserRepository userRepository) {
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
    }

    /**
     * @param userId current authenticated LEADER user id
     * @return resolved department, or empty when neither rule matches
     */
    @Transactional(readOnly = true)
    public Optional<Department> resolve(Long userId) {
        return resolveAll(userId).stream().findFirst();
    }

    /**
     * All active subject catalog rows curated by this leader account.
     *
     * <p>Legacy data can use both {@code subjects.leader_user_id} and
     * {@code users.subject_id}. Keep the primary subject alongside explicit
     * assignments so it does not disappear when another assignment exists.
     */
    @Transactional(readOnly = true)
    public List<Department> resolveAll(Long userId) {
        List<Department> assigned = departmentRepository
                .findByLeaderUserIdOrderByCodeAsc(userId).stream()
                .filter(Department::isActive)
                .toList();
        List<Department> resolved = new ArrayList<>(assigned);
        userRepository.findById(userId)
                .map(User::getSubjectId)
                .filter(id -> id != null)
                .flatMap(departmentRepository::findById)
                .filter(Department::isActive)
                .filter(primary -> resolved.stream()
                        .noneMatch(current -> current.getId().equals(primary.getId())))
                .ifPresent(resolved::add);
        return List.copyOf(resolved);
    }
}
