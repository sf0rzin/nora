package br.com.nora.api.api.dto.iam;

import java.util.UUID;

/**
 * One entry of {@code GET /iam/users} — the tenant directory the IAM screens pick a subject from.
 *
 * <p>Four fields and no more. A directory answers "which user do I mean", so it carries the id
 * every other IAM endpoint takes, the two things a human recognises a colleague by, and the Root
 * flag, which is the one attribute that changes what the answer will be: {@code IamService} refuses
 * to bound the Root and the authorization service bypasses it, so a screen that offers Root as a
 * target is offering an operation that will be refused.
 *
 * <p>Status, timestamps and anything credential-shaped are deliberately absent. This endpoint is
 * reachable by a delegated admin holding {@code iam:group:read}, and every additional field is
 * something they can learn about a colleague without needing it to do their job.
 */
public record DirectoryUserDto(UUID id, String displayName, String email, boolean root) {}
