package br.com.nora.api.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Atribui um request id correlacionável a cada request HTTP e o expõe no MDC ({@value #MDC_KEY}),
 * no header de resposta ({@value #HEADER}) e — via {@code logging.pattern.level} (application.yml)
 * — em toda linha de log do request. O {@code GlobalExceptionHandler} usa o mesmo id como {@code
 * traceId} da resposta de erro, então um id reportado pelo usuário amarra request → logs → tenant /
 * user, viabilizando investigar incidentes em produção (antes o traceId era um UUID descartável sem
 * correlação com nenhum log).
 *
 * <p>Roda ANTES da cadeia do Spring Security ({@link Ordered#HIGHEST_PRECEDENCE}) pra que o id já
 * esteja no MDC durante a autenticação. Reutiliza um {@code X-Request-Id} de entrada (de um proxy /
 * edge como o Cloudflare) quando ele é são — sanitizado contra log injection — senão gera um UUID.
 * Limpa o MDC no finally pra não vazar entre requests que reusam a mesma thread do pool do Tomcat.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // Só aceita um id de entrada que pareça seguro (8–64 chars alfanuméricos, hífen, ponto,
    // underscore) — evita log injection / poluição do MDC com header arbitrário do cliente.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitizeOrGenerate(req.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        res.setHeader(HEADER, requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitizeOrGenerate(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
