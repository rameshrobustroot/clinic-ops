package com.clinicops.app.config;

import com.clinicops.app.legal.PlatformAdminScopeInterceptor;
import com.clinicops.app.legal.TermsAcceptanceInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TermsAcceptanceInterceptor termsAcceptanceInterceptor;
    private final PlatformAdminScopeInterceptor platformAdminScopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Confines platform admin to /platform first; then the terms gate applies to clinic OWNERs.
        registry.addInterceptor(platformAdminScopeInterceptor);
        registry.addInterceptor(termsAcceptanceInterceptor);
    }
}
