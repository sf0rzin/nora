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
 * Aspect que aplica RLS Postgres no inicio de cada {@code @Transactional}.
 *
 * <p>Le {@link TenantContextHolder#get()} e executa {@code SET LOCAL nora.current_tenant_id =
 * '<uuid>'} via {@link EntityManager}. O SET LOCAL e scoped a transacao corrente — auto-reseta no
 * commit/rollback. As policies criadas na migration V016 entao filtram rows automaticamente.
 *
 * <p>Bean condicional: {@code nora.security.rls.enforce=true} ativa. Default off porque exige que o
 * app rode com role Postgres sem BYPASSRLS (ver V016 comments). Em tests/dev com Testcontainers o
 * app conecta como owner que bypassa RLS — aspect tambem fica inerte (ainda registrado mas efeito
 * zero).
 *
 * <p>Order: precisa rodar DENTRO da transacao (depois do {@code @Transactional} advice abrir a tx).
 * Spring {@code @Transactional} interceptor tem ordem {@code Ordered.LOWEST_PRECEDENCE}. Aspect com
 * ordem mais baixa (numericamente maior) executa por ULTIMO no entry — i.e., dentro da tx.
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
            // Usar bind parameter pra evitar SQL injection (paranoia: tenantId vem de
            // UUID parsed do JWT, ja seguro, mas defensive).
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
