package com.clinicops.app.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Internationalisation wiring.
 *
 * <p>User language is held in a cookie so it survives sessions; the
 * {@link LocaleChangeInterceptor} lets the user toggle via {@code ?lang=ta} /
 * {@code ?lang=en} (the header switcher uses this). Falls back to the request's
 * {@code Accept-Language} header for the first request, then to English.
 *
 * <p>Resource bundles live under {@code src/main/resources/messages*.properties}
 * and reload on edit (dev convenience).
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    public static final Locale ENGLISH = Locale.ENGLISH;
    public static final Locale TAMIL   = Locale.forLanguageTag("ta");
    public static final List<Locale> SUPPORTED = List.of(ENGLISH, TAMIL);

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasenames("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setUseCodeAsDefaultMessage(true);  // missing key → render the key itself, never blow up
        ms.setCacheSeconds(60);
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("CLINIC_LOCALE");
        resolver.setDefaultLocale(ENGLISH);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookiePath("/");
        // Initial cookie miss → fall back to the request's Accept-Language header.
        resolver.setLanguageTagCompliant(true);
        return resolver;
    }

    /** Fallback delegate for the case where no cookie is set: respect Accept-Language. */
    @Bean
    public AcceptHeaderLocaleResolver acceptHeaderLocaleResolver() {
        AcceptHeaderLocaleResolver r = new AcceptHeaderLocaleResolver();
        r.setSupportedLocales(SUPPORTED);
        r.setDefaultLocale(ENGLISH);
        return r;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
        lci.setParamName("lang");
        return lci;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
