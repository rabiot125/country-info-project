package com.example.countryinfo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "language")
public class Language {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "iso_code", length = 10)
    private String isoCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_info_id", nullable = false)
    private CountryInfo countryInfo;

    protected Language() {
        // for JPA
    }

    public Language(String isoCode, String name) {
        this.isoCode = isoCode;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getIsoCode() { return isoCode; }
    public String getName() { return name; }
    public CountryInfo getCountryInfo() { return countryInfo; }
    void setCountryInfo(CountryInfo countryInfo) { this.countryInfo = countryInfo; }
}
