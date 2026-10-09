package com.example.countryinfo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Full replacement of the editable fields. The ISO code is the resource's natural key
 * and is deliberately not editable.
 */
public record UpdateCountryRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = ValidationPatterns.COUNTRY_NAME,
                message = "name may only contain letters, spaces, hyphens and apostrophes")
        String name,

        @Size(max = 100) String capitalCity,

        @Size(max = 10) @Pattern(regexp = "^[0-9+\\- ]*$", message = "phoneCode may only contain digits, +, - and spaces")
        String phoneCode,

        @Size(max = 5) String continentCode,

        @Size(max = 5) String currencyIsoCode,

        @Size(max = 255) @Pattern(regexp = "^(https?://.*)?$", message = "flagUrl must be an http(s) URL")
        String flagUrl,

        @NotNull(message = "languages is required (send [] for none)")
        @Size(max = 50)
        List<@Valid @NotNull LanguageDto> languages
) {
}
