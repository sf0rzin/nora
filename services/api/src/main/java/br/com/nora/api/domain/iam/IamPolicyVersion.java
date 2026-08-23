package br.com.nora.api.domain.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One immutable revision of a policy, as stored in {@code iam_policy_versions} since V006.
 *
 * <p>The table has been written on every create and every edit since it existed and had no reader:
 * no SELECT, no endpoint, nothing in the UI and nothing in MCP. That made it a backup rather than
 * an audit trail — {@code iam_audit_events} records THAT a policy changed but never what it said
 * before, so answering "what did this policy allow in March" meant a shell on the database. This
 * type, and the read path it feeds, is what turns the rows into the history US36 promised.
 *
 * @param policyId the policy this revision belongs to
 * @param version 1-based, monotonically increasing; {@code IamPolicy.currentVersion} points at the
 *     highest one
 * @param document the document exactly as it was written at that version
 * @param createdBy who wrote it, or {@code null} when that user has since been deleted (the FK is
 *     {@code ON DELETE SET NULL}, because losing the author must not lose the revision)
 * @param createdAt when it was written
 */
public record IamPolicyVersion(
        UUID policyId,
        int version,
        PolicyDocument document,
        UUID createdBy,
        OffsetDateTime createdAt) {}
