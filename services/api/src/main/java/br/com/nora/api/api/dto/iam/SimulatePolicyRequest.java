package br.com.nora.api.api.dto.iam;

import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code POST /iam/simulate} (US43).
 *
 * @param userId subject of the simulation — has to be a user of the caller's own tenant
 * @param action IAM action to test, e.g. {@code meeting:read}
 * @param resource resource ARN to test, e.g. {@code nora:tenant/TENANT:meeting/MEETING}
 * @param context attributes the statement conditions read; may be null or empty
 */
public record SimulatePolicyRequest(
        UUID userId, String action, String resource, Map<String, String> context) {}
