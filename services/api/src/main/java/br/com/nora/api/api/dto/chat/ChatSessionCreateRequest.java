package br.com.nora.api.api.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Body of POST /chat/sessions. Title is optional — derived from the 1st message when absent. */
@JsonInclude(Include.NON_NULL)
public record ChatSessionCreateRequest(String title) {}
