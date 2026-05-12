package br.com.nora.api.api.dto.iam;

import java.util.List;

/**
 * Resposta da listagem de convites. Schema em {@code
 * docs/api/examples/iam-invite-list-response.json}. No MVP a paginacao e implementada client-side:
 * o backend devolve sempre {@code page=1} e o {@code total} reflete o tamanho da lista — paginacao
 * server-side e Should/Could.
 */
public record InviteListResponse(List<InviteResponse> items, int total, int page, int size) {}
