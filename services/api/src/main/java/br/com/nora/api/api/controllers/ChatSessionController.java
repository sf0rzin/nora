package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.chat.ChatMessageCreateRequest;
import br.com.nora.api.api.dto.chat.ChatMessageResponse;
import br.com.nora.api.api.dto.chat.ChatSessionCreateRequest;
import br.com.nora.api.api.dto.chat.ChatSessionDetailResponse;
import br.com.nora.api.api.dto.chat.ChatSessionRenameRequest;
import br.com.nora.api.api.dto.chat.ChatSessionSummaryResponse;
import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.chat.ChatSessionService;
import br.com.nora.api.application.chat.ChatSessionService.SessionDetail;
import br.com.nora.api.application.ports.ChatSessionRepository.ChatSessionSummaryRow;
import br.com.nora.api.domain.chat.ChatMessage;
import br.com.nora.api.domain.chat.ChatRole;
import br.com.nora.api.domain.chat.ChatSession;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat session history for the NORA assistant. Each session is scoped by tenant_id (ADR 0002 + RLS
 * ADR 0028) and by the principal's user_id: a user only sees and manipulates their own sessions.
 * Authentication is enforced by SecurityConfig (non-public route); the fine-grained per-user scope
 * lives in the service/repository.
 */
@RestController
@RequestMapping("/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chat;

    public ChatSessionController(ChatSessionService chat) {
        this.chat = chat;
    }

    @GetMapping
    @AuthorizationNotRequired(reason = "Self: lists only the caller's own sessions.")
    public List<ChatSessionSummaryResponse> list() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return chat.list(principal.tenantId(), principal.userId()).stream()
                .map(ChatSessionController::toSummary)
                .toList();
    }

    @PostMapping
    @AuthorizationNotRequired(reason = "Self: creates a session owned by the caller.")
    public ResponseEntity<ChatSessionSummaryResponse> create(
            @RequestBody(required = false) ChatSessionCreateRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        String title = body == null ? null : body.title();
        ChatSession created = chat.create(principal.tenantId(), principal.userId(), title);
        return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(created, 0, null));
    }

    @GetMapping("/{id}")
    @AuthorizationNotRequired(reason = "Self: scoped by the caller's own user_id.")
    public ChatSessionDetailResponse get(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        SessionDetail detail = chat.get(id, principal.tenantId(), principal.userId());
        return toDetail(detail);
    }

    @PostMapping("/{id}/messages")
    @AuthorizationNotRequired(reason = "Self: scoped by the caller's own user_id.")
    public ResponseEntity<ChatMessageResponse> appendMessage(
            @PathVariable("id") UUID id, @Valid @RequestBody ChatMessageCreateRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        ChatRole role = ChatRole.fromWire(body.role());
        ChatMessage saved =
                chat.appendMessage(
                        id, principal.tenantId(), principal.userId(), role, body.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(toMessage(saved));
    }

    @PatchMapping("/{id}")
    @AuthorizationNotRequired(reason = "Self: scoped by the caller's own user_id.")
    public ChatSessionSummaryResponse rename(
            @PathVariable("id") UUID id, @Valid @RequestBody ChatSessionRenameRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        ChatSession updated =
                chat.rename(id, principal.tenantId(), principal.userId(), body.title());
        return toSummary(updated, 0, null);
    }

    @DeleteMapping("/{id}")
    @AuthorizationNotRequired(reason = "Self: scoped by the caller's own user_id.")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        chat.delete(id, principal.tenantId(), principal.userId());
        return ResponseEntity.noContent().build();
    }

    private static ChatSessionSummaryResponse toSummary(ChatSessionSummaryRow row) {
        return new ChatSessionSummaryResponse(
                row.session().id(),
                row.session().title(),
                row.session().createdAt(),
                row.session().updatedAt(),
                row.messageCount(),
                row.lastSnippet());
    }

    private static ChatSessionSummaryResponse toSummary(
            ChatSession s, int messageCount, String lastSnippet) {
        return new ChatSessionSummaryResponse(
                s.id(), s.title(), s.createdAt(), s.updatedAt(), messageCount, lastSnippet);
    }

    private static ChatSessionDetailResponse toDetail(SessionDetail detail) {
        ChatSession s = detail.session();
        List<ChatMessageResponse> messages =
                detail.messages().stream().map(ChatSessionController::toMessage).toList();
        return new ChatSessionDetailResponse(
                s.id(), s.title(), s.createdAt(), s.updatedAt(), messages);
    }

    private static ChatMessageResponse toMessage(ChatMessage m) {
        return new ChatMessageResponse(m.id(), m.role().wire(), m.content(), m.createdAt());
    }
}
