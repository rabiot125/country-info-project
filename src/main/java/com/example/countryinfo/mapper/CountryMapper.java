package com.example.countryinfo.mapper;

import com.example.countryinfo.client.CountryDetails;
import com.example.countryinfo.dto.CountryResponse;
import com.example.countryinfo.dto.LanguageDto;
import com.example.countryinfo.dto.UpdateCountryRequest;
import com.example.countryinfo.model.CountryInfo;
import com.example.countryinfo.model.Language;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Hand-written mapping keeps the entity/DTO boundary explicit and free of annotation-processor magic. */
@Component
public class CountryMapper {

    public CountryInfo toEntity(CountryDetails details) {
        CountryInfo entity = new CountryInfo();
        entity.setIsoCode(details.isoCode());
        entity.setName(details.name());
        entity.setCapitalCity(details.capitalCity());
        entity.setPhoneCode(details.phoneCode());
        entity.setContinentCode(details.continentCode());
        entity.setCurrencyIsoCode(details.currencyIsoCode());
        entity.setFlagUrl(details.flagUrl());
        entity.replaceLanguages(dedupe(details.languages().stream()
                .map(l -> new Language(l.isoCode(), l.name()))
                .toList()));
        return entity;
    }

    public void applyUpdate(CountryInfo entity, UpdateCountryRequest request) {
        entity.setName(request.name().trim());
        entity.setCapitalCity(blankToNull(request.capitalCity()));
        entity.setPhoneCode(blankToNull(request.phoneCode()));
        entity.setContinentCode(blankToNull(request.continentCode()));
        entity.setCurrencyIsoCode(blankToNull(request.currencyIsoCode()));
        entity.setFlagUrl(blankToNull(request.flagUrl()));
        entity.replaceLanguages(dedupe(request.languages().stream()
                .map(l -> new Language(blankToNull(l.isoCode()), l.name().trim()))
                .toList()));
    }

    public CountryResponse toResponse(CountryInfo entity) {
        return new CountryResponse(
                entity.getId(),
                entity.getIsoCode(),
                entity.getName(),
                entity.getCapitalCity(),
                entity.getPhoneCode(),
                entity.getContinentCode(),
                entity.getCurrencyIsoCode(),
                entity.getFlagUrl(),
                entity.getLanguages().stream()
                        .map(l -> new LanguageDto(l.getIsoCode(), l.getName()))
                        .toList(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** Upstream data occasionally repeats a language; keep the first occurrence per ISO code/name. */
    private static List<Language> dedupe(List<Language> languages) {
        Map<String, Language> unique = new LinkedHashMap<>();
        for (Language language : languages) {
            String key = (language.getIsoCode() != null ? language.getIsoCode() : language.getName())
                    .toLowerCase(Locale.ROOT);
            unique.putIfAbsent(key, language);
        }
        return List.copyOf(unique.values());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
