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

    /**
     * Regex de dominio corporativo (US32). Aceita {@code acme.com}, {@code sub.acme.com.br}, {@code
     * a-b.io}. Rejeita inicio/fim com hifen, falta de TLD ({@code acme}), prefixo {@code @}, sufixo
     * {@code .}, caracteres invalidos. Validacao espera o input ja normalizado (lowercase + trim)
     * na camada de aplicacao.
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

    /** Construtor legado mantido para chamadas que ainda nao conhecem o campo opcional. */
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
     * Normaliza um dominio corporativo: trim + lowercase. Retorna {@code null} quando o input for
     * {@code null} ou em branco apos normalizacao.
     */
    public static String normalizeEmailDomain(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Valida um dominio corporativo ja normalizado. Retorna {@code true} se o input for {@code
     * null} (sem restricao) ou casar o regex; {@code false} caso contrario.
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
     * Retorna uma nova instancia com o dominio corporativo atualizado, preservando os demais
     * campos. {@code newDomain} pode ser {@code null} para limpar a restricao. O caller e
     * responsavel por gravar via {@link
     * br.com.nora.api.application.ports.TenantRepository#save(Tenant)}.
     */
    /**
     * Retorna uma nova instancia com o nome atualizado (renomear workspace nas configuracoes),
     * preservando slug e demais campos. O caller grava via {@code TenantRepository.save}.
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
