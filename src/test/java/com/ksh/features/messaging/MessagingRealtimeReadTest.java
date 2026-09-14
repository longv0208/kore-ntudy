package com.ksh.features.messaging;

import com.ksh.entities.Conversation;
import com.ksh.entities.Message;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.messaging.dto.MessagingDtos.PushPayload;
import com.ksh.features.messaging.repository.ConversationRepository;
import com.ksh.features.messaging.repository.MessageRepository;
import com.ksh.features.messaging.service.MessagingService;
import com.ksh.features.messaging.support.MessagingAccess;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingRealtimeReadTest {

    @Test
    void pushKeepsBoundedSnippetAndExactBody() {
        Fixture f = fixture();
        String body = "x".repeat(120) + "\nexact tail";
        Conversation conversation = Conversation.between(10L, 20L);
        Message saved = mock(Message.class);
        User peer = mock(User.class);
        User sender = mock(User.class);
        when(f.conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(f.messages.saveAndFlush(any(Message.class))).thenReturn(saved);
        when(saved.getId()).thenReturn(91L);
        when(saved.getBody()).thenReturn(body);
        when(saved.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 29, 12, 0));
        when(f.messages.countUnreadForUser(20L)).thenReturn(4L);
        when(f.users.findById(20L)).thenReturn(Optional.of(peer));
        when(f.users.findById(10L)).thenReturn(Optional.of(sender));
        when(peer.isActive()).thenReturn(true);
        when(peer.getEmail()).thenReturn("peer@example.test");
        when(sender.getFullName()).thenReturn("Sender");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            f.service.send(10L, 7L, body);
            verify(f.template, never()).convertAndSendToUser(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        verify(f.template).convertAndSendToUser(
                eq("peer@example.test"), eq("/queue/messages"), payload.capture());
        assertEquals(body, payload.getValue().fullBody());
        assertTrue(payload.getValue().snippet().length() < body.length());
        assertTrue(payload.getValue().snippet().endsWith("…"));
    }

    @Test
    void lockedRecipientNeverReceivesAfterCommitFullBodyPush() {
        Fixture f = fixture();
        Conversation conversation = Conversation.between(10L, 20L);
        Message saved = mock(Message.class);
        User lockedPeer = mock(User.class);
        when(f.conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(f.messages.saveAndFlush(any(Message.class))).thenReturn(saved);
        when(saved.getId()).thenReturn(92L);
        when(saved.getBody()).thenReturn("private body");
        when(saved.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 8, 11, 12, 0));
        when(f.messages.countUnreadForUser(20L)).thenReturn(1L);
        when(f.users.findById(10L)).thenReturn(Optional.empty());
        when(f.users.findById(20L)).thenReturn(Optional.of(lockedPeer));
        when(lockedPeer.isActive()).thenReturn(true);
        when(lockedPeer.isLocked()).thenReturn(true);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            f.service.send(10L, 7L, "private body");
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(f.template, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void ownedMarkReadUpdatesOnlyPeerMessagesAndReturnsFreshTotal() {
        Fixture f = fixture();
        when(f.conversations.findById(7L))
                .thenReturn(Optional.of(Conversation.between(10L, 20L)));
        when(f.messages.countUnreadForUser(10L)).thenReturn(2L);

        assertEquals(2L, f.service.markConversationRead(10L, 7L));

        verify(f.messages).markReadBulk(eq(7L), eq(10L), any(LocalDateTime.class));
        verify(f.messages).countUnreadForUser(10L);
    }

    @Test
    void foreignConversationCannotBeMarkedRead() {
        Fixture f = fixture();
        when(f.conversations.findById(7L))
                .thenReturn(Optional.of(Conversation.between(20L, 30L)));

        assertThrows(ResponseStatusException.class,
                () -> f.service.markConversationRead(10L, 7L));

        verify(f.messages, never()).markReadBulk(any(), any(), any());
    }

    @Test
    void browserUsesFullBodyThenOwnedMarkReadEndpoint() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/resources/static/js/messaging.js"), StandardCharsets.UTF_8);

        assertTrue(source.contains("appendMessage(payload.fullBody, false)"));
        assertTrue(source.contains("'/my/messages/' + encodeURIComponent(openConvId) + '/read'"));
        assertTrue(source.indexOf("appendMessage(payload.fullBody, false)")
                < source.indexOf("markOpenConversationRead()", source.indexOf("payload.fullBody")));
    }

    private static Fixture fixture() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        UserRepository users = mock(UserRepository.class);
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MessagingService service = new MessagingService(
                conversations, messages, users, mock(MessagingAccess.class), template,
                mock(ClassRepository.class), mock(EnrollmentRepository.class),
                mock(SubjectRepository.class));
        return new Fixture(service, conversations, messages, users, template);
    }

    private record Fixture(MessagingService service,
                           ConversationRepository conversations,
                           MessageRepository messages,
                           UserRepository users,
                           SimpMessagingTemplate template) {
    }
}
