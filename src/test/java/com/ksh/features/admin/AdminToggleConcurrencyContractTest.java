package com.ksh.features.admin;

import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.admin.settings.repository.AiProviderRepository;
import com.ksh.features.admin.settings.repository.AiSystemPromptRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminToggleConcurrencyContractTest {

    @Test
    void parityToggleRepositoriesUsePessimisticWriteLocks() throws Exception {
        assertPessimisticWrite(SubjectRepository.class);
        assertPessimisticWrite(AiProviderRepository.class);
        assertPessimisticWrite(AiSystemPromptRepository.class);
    }

    @Test
    void parityToggleServicesReadMutableStateThroughTheirLockedLookup() throws Exception {
        assertToggleUsesLockedLookup(read(
                "admin/subjects/service/SubjectService.java"), "boolean toggleActive(");
        assertToggleUsesLockedLookup(read(
                "admin/settings/service/AiProviderService.java"), "Optional<Boolean> toggleEnabled(");
        assertToggleUsesLockedLookup(read(
                "admin/settings/service/AiSystemPromptService.java"), "Optional<Boolean> toggleEnabled(");
    }

    private static void assertPessimisticWrite(Class<?> repositoryType) throws Exception {
        Method method = repositoryType.getMethod("findByIdForUpdate", Long.class);
        Lock lock = method.getAnnotation(Lock.class);
        assertNotNull(lock, repositoryType.getSimpleName() + " must declare a row lock");
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    private static void assertToggleUsesLockedLookup(String source, String methodSignature) {
        int method = source.indexOf(methodSignature);
        int lock = source.indexOf("findByIdForUpdate(id)", method);
        int mutation = Math.max(
                source.indexOf("toggleActive()", method),
                source.indexOf("setEnabled(!", method));
        assertTrue(method >= 0 && lock > method && mutation > lock,
                "toggle must lock its row before reading and flipping state");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/ksh/features").resolve(relative),
                StandardCharsets.UTF_8);
    }
}
