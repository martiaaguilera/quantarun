package io.github.martiaaguilera.quantarun.controlplane.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods = false)
class WebConfiguration {

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            @Value("${quantarun.api.max-request-body:64KB}") DataSize maxRequestBody) {
        var registration = new FilterRegistrationBean<>(new RequestBodyLimitFilter(maxRequestBody.toBytes()));
        registration.addUrlPatterns("/api/*");
        // Runs before authentication, so an unauthenticated caller cannot make the server read a huge body either.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
