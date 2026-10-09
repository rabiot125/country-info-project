package com.example.countryinfo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI countryInfoOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Country Info Service")
                .version("v1")
                .description("Resolves a country name through the oorsprong.org SOAP service, "
                        + "stores the result in MySQL and exposes CRUD operations."));
    }
}
