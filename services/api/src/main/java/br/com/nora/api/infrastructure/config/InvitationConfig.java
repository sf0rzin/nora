package br.com.nora.api.infrastructure.config;

import br.com.nora.api.application.iam.InvitationService;
import br.com.nora.api.application.iam.InvitationService.InvitationSettings;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.InvitationRepository;
import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring do InvitationService (US06). Mantemos o servico de aplicacao livre de Spring. */
@Configuration
public class InvitationConfig {

    @Bean
    public InvitationSettings invitationSettings(
            @Value("${nora.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${nora.security.jwt.expires-seconds:900}") long jwtTtl,
            @Value("${nora.security.refresh-token.expires-seconds:2592000}") long refreshTtl) {
        return new InvitationSettings(
                frontendBaseUrl, Duration.ofSeconds(jwtTtl), Duration.ofSeconds(refreshTtl));
    }

    @Bean
    public InvitationService invitationService(
            InvitationRepository invitations,
            TenantRepository tenants,
            UserRepository users,
            IamRepository iam,
            SecureTokenGenerator tokenGenerator,
            PasswordHasher passwordHasher,
            EmailSender emailSender,
            JwtIssuer jwtIssuer,
            RefreshTokenRepository refreshTokenRepository,
            Clock clock,
            InvitationSettings settings) {
        return new InvitationService(
                invitations,
                tenants,
                users,
                iam,
                tokenGenerator,
                passwordHasher,
                emailSender,
                jwtIssuer,
                refreshTokenRepository,
                clock,
                settings);
    }
}
