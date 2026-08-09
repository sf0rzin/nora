package br.com.nora.api.infrastructure.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Aspect that applies Postgres RLS at the start of each {@code @Transactional}.
 *
 * <p>Reads {@link TenantContextHolder#get()} and runs {@code SET LOCAL nora.current_tenant_id =
 * '<uuid>'} via {@link EntityManager}. The SET LOCAL is scoped to the current transaction —
 * auto-resets on commit/rollback. The policies created in migration V016 then filter rows
 * automatically.
 *
 * <p>Conditional bean: {@code nora.security.rls.enforce=true} activates it. Default off because it
 * requires the app to run with a Postgres role without BYPASSRLS (see V016 comments). In tests/dev
 * with Testcontainers the app connects as the owner, which bypasses RLS — the aspect is inert there
 * too (still registered but zero effect).
 *
 * <p>Order: it must run INSIDE the transaction (after the {@code @Transactional} advice opens the
 * tx). Spring's {@code @Transactional} interceptor has order {@code Ordered.LOWEST_PRECEDENCE}. An
 * aspect with lower order (numerically higher) runs LAST on entry — i.e. inside the tx.
 */
@Aspect
@Component
@ConditionalOnProperty(name = "nora.security.rls.enforce", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantRlsAspect {

    private static final Logger LOG = LoggerFactory.getLogger(TenantRlsAspect.class);

    @PersistenceContext private EntityManager em;

    @Around(
            "@annotation(org.springframework.transaction.annotation.Transactional) "
                    + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public Object setTenantOnTransaction(ProceedingJoinPoint pjp) throws Throwable {
        UUID tenantId = TenantContextHolder.get();
        if (tenantId != null) {
            // Use a bind parameter to avoid SQL injection (paranoia: tenantId comes from
            // a UUID parsed out of the JWT, already safe, but defensive).
            em.createNativeQuery("SELECT set_config('nora.current_tenant_id', ?, true)")
                    .setParameter(1, tenantId.toString())
                    .getSingleResult();
            if (LOG.isTraceEnabled()) {
                LOG.trace("RLS tenant set: {} on {}", tenantId, pjp.getSignature().toShortString());
            }
        }
        return pjp.proceed();
    }
}
