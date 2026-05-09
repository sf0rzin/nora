package br.com.nora.api.application.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.iam.PolicyEvaluator;
import br.com.nora.api.domain.iam.PolicyStatement;
import java.util.List;
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
        if (users.isRoot(userId, tenantId)) {
            return true;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return PolicyEvaluator.isAllowed(stmts, action, resource);
    }

    /** Conveniencia: lanca {@link IamException#forbidden} caso a autorizacao falhe. */
    public void require(UUID userId, UUID tenantId, String action, String resource) {
        if (!isAllowed(userId, tenantId, action, resource)) {
            throw IamException.forbidden(action);
        }
    }
}
