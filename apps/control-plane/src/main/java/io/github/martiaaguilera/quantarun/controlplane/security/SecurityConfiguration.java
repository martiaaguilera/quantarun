package io.github.martiaaguilera.quantarun.controlplane.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration implements WebMvcConfigurer {

    @Bean
    FilterRegistrationBean<BearerAuthenticationFilter> bearerAuthenticationFilter(
            SecurityProperties properties, ApiKeys apiKeys) {
        var registration = new FilterRegistrationBean<>(new BearerAuthenticationFilter(properties, apiKeys));
        registration.addUrlPatterns("/api/*");
        // After the body-size limit, before anything that could touch business state.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }

    private static final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Caller.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Caller resolveArgument(
                MethodParameter parameter,
                @Nullable ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                @Nullable WebDataBinderFactory binderFactory) {
            var request = webRequest.getNativeRequest(HttpServletRequest.class);
            var caller = request == null ? null : request.getAttribute(BearerAuthenticationFilter.CALLER_ATTRIBUTE);
            if (caller instanceof Caller resolved) {
                return resolved;
            }
            // Reaching a controller without a caller means a mapping escaped the filter: fail loudly, never
            // anonymously.
            throw new IllegalStateException("No authenticated caller for " + parameter.getExecutable());
        }
    }
}
