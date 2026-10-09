package com.example.countryinfo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCountryRequest(
        @Schema(example = "kenya", description = "Country name in any case; letters, spaces, hyphens and apostrophes only")
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        @Pattern(regexp = ValidationPatterns.COUNTRY_NAME,
                message = "name may only contain letters, spaces, hyphens and apostrophes")
        String name
) {
}
