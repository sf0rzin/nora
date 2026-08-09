package br.com.nora.api.infrastructure.config;

import br.com.nora.api.api.security.AuthCookies;
import br.com.nora.api.application.identity.AuthService;
import br.com.nora.api.application.identity.AuthService.AuthSettings;
import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.application.ports.OneTimeTokenRepository;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AuthService wiring. Keeps the application service free of Spring. */
@Configuration
public class AuthConfig {

    @Bean
    public AuthSettings authSettings(
            @Value("${nora.app.public-base-url:http://localhost:3000}") String publicBaseUrl,
            @Value("${nora.security.email-verification.expires-seconds:86400}") long emailVerifyTtl,
            @Value("${nora.security.password-reset.expires-seconds:3600}") long pwdResetTtl,
            @Value("${nora.security.jwt.expires-seconds:900}") long jwtTtl,
            @Value("${nora.security.refresh-token.expires-seconds:2592000}") long refreshTtl,
            @Value("${nora.security.expose-dev-tokens:false}") boolean exposeDev) {
        return new AuthSettings(
                publicBaseUrl,
                Duration.ofSeconds(emailVerifyTtl),
                Duration.ofSeconds(pwdResetTtl),
                Duration.ofSeconds(jwtTtl),
                Duration.ofSeconds(refreshTtl),
                exposeDev);
    }

    @Bean
    public AuthCookies authCookies(
            @Value("${nora.auth.cookie-secure:false}") boolean cookieSecure,
            @Value("${nora.auth.cookie-domain:}") String cookieDomain) {
        return new AuthCookies(cookieSecure, cookieDomain);
    }

    @Bean
    public AuthService authService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            OneTimeTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            JwtIssuer jwtIssuer,
            EmailSender emailSender,
            AuditPort audit,
            Clock clock,
            AuthSettings settings) {
        return new AuthService(
                tenantRepository,
                userRepository,
                tokenRepository,
                refreshTokenRepository,
                passwordHasher,
                tokenGenerator,
                jwtIssuer,
                emailSender,
                audit,
                clock,
                settings);
    }
}
