package br.com.nora.api.domain.tenant;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant aggregate. In the MVP each Core user who signs up gets their own personal tenant (US01 +
 * Lucas persona). In Enterprise (US06, out of this scope) users are invited to an existing tenant.
 */
public final class Tenant {

    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    public enum Plan {
        FREE,
        PRO,
        ENTERPRISE
    }

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    /**
     * Corporate domain regex (US32). Accepts {@code acme.com}, {@code sub.acme.com.br}, {@code
     * a-b.io}. Rejects leading/trailing hyphen, missing TLD ({@code acme}), {@code @} prefix,
     * {@code .} suffix, invalid characters. Validation expects the input already normalized
     * (lowercase + trim) in the application layer.
     */
    private static final Pattern EMAIL_DOMAIN_PATTERN =
            Pattern.compile(
                    "^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$");

    private final UUID id;
    private final String name;
    private final String slug;
    private final Status status;
    private final Plan plan;
    private final String allowedEmailDomain;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Tenant(
            UUID id,
            String name,
            String slug,
            Status status,
            Plan plan,
            String allowedEmailDomain,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.name = requireNonBlank(name, "name");
        this.slug = requireValidSlug(slug);
        this.status = Objects.requireNonNull(status);
        this.plan = Objects.requireNonNull(plan);
        this.allowedEmailDomain = allowedEmailDomain;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /** Legacy constructor kept for callers that do not yet know the optional field. */
    public Tenant(
            UUID id,
            String name,
            String slug,
            Status status,
            Plan plan,
            Instant createdAt,
            Instant updatedAt) {
        this(id, name, slug, status, plan, null, createdAt, updatedAt);
    }

    public static String slugify(String raw) {
        String s =
                Objects.requireNonNull(raw)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9-]+", "-")
                        .replaceAll("-+", "-")
                        .replaceAll("^-|-$", "");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("cannot slugify empty value");
        }
        return s.length() > 63 ? s.substring(0, 63) : s;
    }

    /**
     * Normalizes a corporate domain: trim + lowercase. Returns {@code null} when the input is
     * {@code null} or blank after normalization.
     */
    public static String normalizeEmailDomain(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Validates an already normalized corporate domain. Returns {@code true} if the input is {@code
     * null} (no restriction) or matches the regex; {@code false} otherwise.
     */
    public static boolean isValidEmailDomain(String normalized) {
        if (normalized == null) {
            return true;
        }
        return EMAIL_DOMAIN_PATTERN.matcher(normalized).matches();
    }

    private static String requireValidSlug(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("invalid tenant slug");
        }
        return slug;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }

    public Status status() {
        return status;
    }

    public Plan plan() {
        return plan;
    }

    public String allowedEmailDomain() {
        return allowedEmailDomain;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Returns a new instance with the corporate domain updated, preserving the remaining fields.
     * {@code newDomain} may be {@code null} to clear the restriction. The caller is responsible for
     * persisting via {@link br.com.nora.api.application.ports.TenantRepository#save(Tenant)}.
     */
    /**
     * Returns a new instance with the name updated (rename workspace in the settings), preserving
     * slug and the remaining fields. The caller persists via {@code TenantRepository.save}.
     */
    public Tenant withName(String newName, Instant updatedAt) {
        return new Tenant(
                this.id,
                requireNonBlank(newName, "name"),
                this.slug,
                this.status,
                this.plan,
                this.allowedEmailDomain,
                this.createdAt,
                Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    public Tenant withAllowedEmailDomain(String newDomain, Instant updatedAt) {
        return new Tenant(
                this.id,
                this.name,
                this.slug,
                this.status,
                this.plan,
                newDomain,
                this.createdAt,
                Objects.requireNonNull(updatedAt, "updatedAt"));
    }
}
