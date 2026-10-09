package com.example.countryinfo.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Bound from app.soap.*; fails startup if the endpoint is missing rather than at first request. */
@Validated
@ConfigurationProperties(prefix = "app.soap")
public record SoapClientProperties(
        @NotBlank String endpointUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
