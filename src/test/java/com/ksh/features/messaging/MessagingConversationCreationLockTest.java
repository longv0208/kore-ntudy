package com.ksh.features.messaging;

import com.ksh.entities.Conversation;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.messaging.repository.ConversationRepository;
import com.ksh.features.messaging.repository.MessageRepository;
import com.ksh.features.messaging.service.MessagingService;
import com.ksh.features.messaging.support.MessagingAccess;
import com.ksh.security.Role;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessagingConversationCreationLockTest {

    @Test
    void locksStableLowerParticipantBeforePairLookup() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        UserRepository users = mock(UserRepository.class);
        MessagingAccess access = mock(MessagingAccess.class);
        Conversation existing = mock(Conversation.class);
        when(access.canStartConversation(20L, Role.STUDENT, 7L)).thenReturn(true);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(mock(User.class)));
        when(conversations.findByUserLoIdAndUserHiId(7L, 20L))
                .thenReturn(Optional.of(existing));
        when(existing.getId()).thenReturn(55L);

        MessagingService service = service(conversations, users, access);

        assertEquals(55L, service.getOrCreateConversation(20L, Role.STUDENT, 7L));
        InOrder order = inOrder(users, conversations);
        order.verify(users).findByIdForUpdate(7L);
        order.verify(conversations).findByUserLoIdAndUserHiId(7L, 20L);
    }

    @Test
    void classAndGenericFlowsShareTheSameLockedHelper() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/ksh/features/messaging/service/MessagingService.java"),
                StandardCharsets.UTF_8);

        assertEquals(3, occurrences(source, "getOrCreateNormalizedConversation("),
                "declaration plus generic and class-scoped callers must use one helper");
        int helper = source.indexOf("private Long getOrCreateNormalizedConversation");
        int lock = source.indexOf("userRepository.findByIdForUpdate(lo)", helper);
        int lookup = source.indexOf(
                "conversationRepository.findByUserLoIdAndUserHiId(lo, hi)", helper);
        assertTrue(helper >= 0 && lock > helper && lookup > lock);
    }

    @Test
    void ineligiblePairDoesNotAcquireCreationLock() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        UserRepository users = mock(UserRepository.class);
        MessagingAccess access = mock(MessagingAccess.class);
        when(access.canStartConversation(20L, Role.STUDENT, 7L)).thenReturn(false);

        MessagingService service = service(conversations, users, access);
        try {
            service.getOrCreateConversation(20L, Role.STUDENT, 7L);
        } catch (RuntimeException expected) {
            // The existing no-leak 404 behavior is outside this lock contract.
        }
        verifyNoInteractions(users, conversations);
    }

    private static MessagingService service(ConversationRepository conversations,
                                            UserRepository users,
                                            MessagingAccess access) {
        return new MessagingService(
                conversations,
                mock(MessageRepository.class),
                users,
                access,
                mock(SimpMessagingTemplate.class),
                mock(ClassRepository.class),
                mock(EnrollmentRepository.class),
                mock(SubjectRepository.class));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
