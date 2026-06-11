package br.com.nora.api.api.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Body de POST /chat/sessions. Título é opcional — derivado da 1ª mensagem quando ausente. */
@JsonInclude(Include.NON_NULL)
public record ChatSessionCreateRequest(String title) {}
