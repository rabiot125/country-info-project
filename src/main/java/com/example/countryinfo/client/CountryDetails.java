package com.example.countryinfo.client;

import java.util.List;

/** Provider-neutral view of the upstream country payload. */
public record CountryDetails(
        String isoCode,
        String name,
        String capitalCity,
        String phoneCode,
        String continentCode,
        String currencyIsoCode,
        String flagUrl,
        List<LanguageDetails> languages
) {
    public record LanguageDetails(String isoCode, String name) {
    }
}
