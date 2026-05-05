package br.com.nora.api.infrastructure.config;

import br.com.nora.api.application.identity.AuthService;
import br.com.nora.api.application.identity.AuthService.AuthSettings;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.OneTimeTokenRepository;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring do AuthService. Mantem o servico de aplicacao livre de Spring. */
@Configuration
public class AuthConfig {

    @Bean
    public AuthSettings authSettings(
            @Value("${nora.app.public-base-url:http://localhost:3000}") String publicBaseUrl,
            @Value("${nora.security.email-verification.expires-seconds:86400}") long emailVerifyTtl,
            @Value("${nora.security.password-reset.expires-seconds:3600}") long pwdResetTtl,
            @Value("${nora.security.jwt.expires-seconds:3600}") long jwtTtl,
            @Value("${nora.security.expose-dev-tokens:false}") boolean exposeDev) {
        return new AuthSettings(
                publicBaseUrl,
                Duration.ofSeconds(emailVerifyTtl),
                Duration.ofSeconds(pwdResetTtl),
                Duration.ofSeconds(jwtTtl),
                exposeDev);
    }

    @Bean
    public AuthService authService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            OneTimeTokenRepository tokenRepository,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            EmailSender emailSender,
            Clock clock,
            AuthSettings settings) {
        return new AuthService(
                tenantRepository,
                userRepository,
                tokenRepository,
                passwordHasher,
                tokenGenerator,
                emailSender,
                clock,
                settings);
    }
}
