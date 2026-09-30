package io.github.martiaaguilera.quantarun.controlplane.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI quantaRunOpenApi() {
        var bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .description("Project API key (qr_...) or the operator admin token.");
        return new OpenAPI()
                .info(new Info()
                        .title("QuantaRun control plane API")
                        .version("v1")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("bearer", bearer))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
