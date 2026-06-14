package br.com.nora.api.api.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra o {@link RequiresPermissionInterceptor} para aplicar {@link RequiresPermission}. */
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
