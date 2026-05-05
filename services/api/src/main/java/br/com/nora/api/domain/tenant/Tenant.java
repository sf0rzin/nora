package br.com.nora.api.domain.tenant;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agregado de tenant. No MVP cada usuario Core que faz signup ganha um tenant pessoal proprio (US01
 * + persona Lucas). Em Enterprise (US06, fora deste escopo) os usuarios sao convidados para um
 * tenant existente.
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

    private final UUID id;
    private final String name;
    private final String slug;
    private final Status status;
    private final Plan plan;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Tenant(
            UUID id,
            String name,
            String slug,
            Status status,
            Plan plan,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.name = requireNonBlank(name, "name");
        this.slug = requireValidSlug(slug);
        this.status = Objects.requireNonNull(status);
        this.plan = Objects.requireNonNull(plan);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
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

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
