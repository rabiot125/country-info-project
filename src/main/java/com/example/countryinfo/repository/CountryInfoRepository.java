package com.example.countryinfo.repository;

import com.example.countryinfo.model.CountryInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CountryInfoRepository extends JpaRepository<CountryInfo, Long> {

    Optional<CountryInfo> findByIsoCode(String isoCode);

    /**
     * Case- and accent-insensitive in practice: the column uses utf8mb4_0900_ai_ci,
     * so "cote d'ivoire" also matches a stored "Côte D'Ivoire".
     */
    Optional<CountryInfo> findFirstByNameIgnoreCase(String name);
}
