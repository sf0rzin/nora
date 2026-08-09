package br.com.nora.api.application.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.iam.PolicyEvaluator;
import br.com.nora.api.domain.iam.PolicyStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Avaliador de autorizacao para a aplicacao. Aplica o bypass de Root e roteia ao {@link
 * PolicyEvaluator}.
 */
@Service
public class AuthorizationService {

    private final UserRepository users;
    private final IamRepository iam;

    public AuthorizationService(UserRepository users, IamRepository iam) {
        this.users = users;
        this.iam = iam;
    }

    public boolean isAllowed(UUID userId, UUID tenantId, String action, String resource) {
        return isAllowed(userId, tenantId, action, resource, Map.of());
    }

    /**
     * Versao com request context (usado para conditions que dependem de atributos do recurso, do
     * usuario ou do request).
     */
    public boolean isAllowed(
            UUID userId,
            UUID tenantId,
            String action,
            String resource,
            Map<String, String> requestContext) {
        if (users.isRoot(userId, tenantId)) {
            return true;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return PolicyEvaluator.isAllowed(stmts, action, resource, requestContext);
    }

    /** Conveniencia: lanca {@link IamException#forbidden} caso a autorizacao falhe. */
    public void require(UUID userId, UUID tenantId, String action, String resource) {
        require(userId, tenantId, action, resource, Map.of());
    }

    /** Conveniencia com request context: lanca {@link IamException#forbidden} caso negado. */
    public void require(
            UUID userId,
            UUID tenantId,
            String action,
            String resource,
            Map<String, String> requestContext) {
        if (!isAllowed(userId, tenantId, action, resource, requestContext)) {
            throw IamException.forbidden(action);
        }
    }

    /**
     * Pre-check sem conditions: garante que o usuario tenha pelo menos um Allow para
     * action+resource, ignorando conditions. Ideal para list-endpoints onde conditions sao
     * avaliadas por item.
     */
    public void requireAnyAllow(UUID userId, UUID tenantId, String action, String resource) {
        if (users.isRoot(userId, tenantId)) {
            return;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        if (!PolicyEvaluator.hasAnyAllow(stmts, action, resource)) {
            throw IamException.forbidden(action);
        }
    }

    /**
     * Responde, ANTES de ir ao banco, se a decisao de {@code action} e a mesma para todo recurso
     * coberto por {@code wildcardResource}. Presente = pode decidir de uma vez; {@code empty} = tem
     * mesmo de avaliar item a item com {@link #filterAllowed}.
     *
     * <p>Serve para listagens. Filtrar item a item obriga a carregar o conjunto inteiro antes de
     * paginar; quando nenhuma statement distingue dois itens do conjunto, esse scan produz sempre a
     * mesma resposta e a paginacao pode ficar no SQL.
     *
     * <p>Root cai aqui como allow uniforme, que e literalmente o que {@link #filterAllowed} faz com
     * ele: devolve a lista intacta. Antes disto, o endpoint de listagem varria o tenant todo para
     * nao filtrar nada.
     */
    public Optional<Boolean> uniformDecision(
            UUID userId, UUID tenantId, String action, String wildcardResource) {
        if (users.isRoot(userId, tenantId)) {
            return Optional.of(true);
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return PolicyEvaluator.uniformDecision(stmts, action, wildcardResource);
    }

    /**
     * Filtra in-memory uma coleção por permissão, resolvendo o bypass de Root e os statements do
     * usuário UMA ÚNICA vez para todo o conjunto. Use em list-endpoints com conditions por item:
     * evita o N+1 de chamar {@link #isAllowed} por elemento (que reconsulta {@code isRoot} + {@code
     * collectStatementsForUser} a cada chamada). A semântica de avaliação é idêntica — mesmo {@link
     * PolicyEvaluator}, mesmo bypass de Root.
     *
     * @param resourceFn ARN do recurso para cada item
     * @param contextFn atributos do recurso (conditions) para cada item
     */
    public <T> List<T> filterAllowed(
            UUID userId,
            UUID tenantId,
            String action,
            List<T> items,
            java.util.function.Function<T, String> resourceFn,
            java.util.function.Function<T, Map<String, String>> contextFn) {
        if (users.isRoot(userId, tenantId)) {
            return items;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return items.stream()
                .filter(
                        it ->
                                PolicyEvaluator.isAllowed(
                                        stmts, action, resourceFn.apply(it), contextFn.apply(it)))
                .toList();
    }
}
