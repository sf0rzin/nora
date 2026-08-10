package br.com.nora.api.api.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link RequiresPermissionInterceptor} to apply {@link RequiresPermission}.
 *
 * <p>Registered for every path on purpose: the interceptor is also the authorization default deny
 * (#51), and a path allow-list here would be a second place to forget an endpoint. It scopes itself
 * to NORA's own handlers and honours {@link AuthorizationNotRequired}.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequiresPermissionInterceptor requiresPermission;

    public WebMvcConfig(RequiresPermissionInterceptor requiresPermission) {
        this.requiresPermission = requiresPermission;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requiresPermission);
    }
}
