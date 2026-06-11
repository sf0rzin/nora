package br.com.nora.api.infrastructure.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configuracao base de seguranca.
 *
 * <p>Politica padrao: tudo autenticado, exceto endpoints publicos. Filtro JWT instalado pelas
 * stories US01-US04: para cada request com header Authorization Bearer, popula o SecurityContext.
 */
@Configuration
public class SecurityConfig {

    @Value("${nora.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Value("${nora.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}")
    private String allowedMethods;

    @Value("${nora.cors.allowed-headers:*}")
    private String allowedHeaders;

    @Value("${nora.cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Value("${nora.cors.max-age-seconds:3600}")
    private long maxAgeSeconds;

    private static final String[] PUBLIC_ENDPOINTS = {
        "/healthz",
        "/actuator/health",
        "/actuator/info",
        "/auth/signup",
        "/auth/verify-email",
        "/auth/login",
        // Round 2 / Subfase 1.3 A: refresh nao tem JWT valido quando chega (esse e o ponto);
        // logout e idempotente — sem token = no-op em vez de 401.
        "/auth/refresh",
        "/auth/logout",
        "/auth/password/reset/request",
        "/auth/password/reset/confirm",
        // GOAL Fase 3: reenvio de verificacao e para quem NAO consegue logar (EMAIL_NOT_VERIFIED)
        // — publico por design, com rate limit por e-mail + resposta anti-enumeracao.
        "/auth/verify-email/resend",
        // US06: aceite de convite usa o token como credencial — endpoint publico por design.
        "/iam/invites/*/accept",
        // JWKS publico (RFC 7517): validators externos buscam aqui a chave publica RSA.
        // Ativo so quando algorithm=RS256 (bean condicional); em HS256 retorna 404 natural.
        "/.well-known/jwks.json",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http.cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_ENDPOINTS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        config.setAllowedOrigins(origins.stream().map(String::trim).toList());
        config.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
        config.setAllowedHeaders(List.of(allowedHeaders));
        config.setAllowCredentials(allowCredentials);
        config.setMaxAge(maxAgeSeconds);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // OWASP 2023+ recomenda strength >= 12 para BCrypt. Default da SpringSec e 10.
        // Strength 12 ~ 250ms por hash em hw moderno; aceitavel para login esporadico
        // mas defesa real contra password cracking offline em caso de dump do DB.
        return new BCryptPasswordEncoder(12);
    }
}
