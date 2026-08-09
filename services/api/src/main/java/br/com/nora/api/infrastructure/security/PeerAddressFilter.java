package br.com.nora.api.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guarda o endereço do PEER TCP antes que qualquer outro filtro o reescreva.
 *
 * <p>A aplicação roda com {@code server.forward-headers-strategy: framework}, que instala o {@code
 * ForwardedHeaderFilter} do Spring. Esse filtro embrulha o request de modo que {@code
 * getRemoteAddr()} passa a devolver o elemento mais à ESQUERDA do {@code X-Forwarded-For} — que é
 * justamente o segmento que o cliente escreve. Ou seja: depois dele, {@code getRemoteAddr()} não é
 * o endereço de quem conectou, é um valor escolhido pelo chamador.
 *
 * <p>Isso torna {@code getRemoteAddr()} inutilizável como fallback do {@link AuthRateLimiter}: em
 * qualquer request sem o header da borda — ou com {@code nora.security.trusted-client-ip-header}
 * vazio, como nos testes — o atacante voltaria a escolher o próprio balde mandando um XFF novo a
 * cada tentativa, que é exatamente a falha que o limitador existe para fechar.
 *
 * <p>Rodando com {@link Ordered#HIGHEST_PRECEDENCE} este filtro vê o request cru e publica o peer
 * num atributo, imune ao embrulho posterior.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PeerAddressFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = PeerAddressFilter.class.getName() + ".peer";

    /**
     * Endereço do peer TCP, ou o {@code getRemoteAddr()} corrente quando o filtro não rodou (ex.:
     * MockMvc standalone). Nunca {@code null}.
     */
    static String peerAddress(HttpServletRequest request) {
        Object stored = request.getAttribute(ATTRIBUTE);
        if (stored instanceof String s && !s.isBlank()) {
            return s;
        }
        String fallback = request.getRemoteAddr();
        return fallback == null ? "" : fallback;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ATTRIBUTE, request.getRemoteAddr());
        chain.doFilter(request, response);
    }
}
