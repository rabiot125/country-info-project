package com.example.countryinfo.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "country_info",
        uniqueConstraints = @UniqueConstraint(name = "uk_country_info_iso_code", columnNames = "iso_code"))
public class CountryInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "iso_code", nullable = false, length = 2)
    private String isoCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "capital_city", length = 100)
    private String capitalCity;

    @Column(name = "phone_code", length = 10)
    private String phoneCode;

    @Column(name = "continent_code", length = 5)
    private String continentCode;

    @Column(name = "currency_iso_code", length = 5)
    private String currencyIsoCode;

    @Column(name = "flag_url", length = 255)
    private String flagUrl;

    @OneToMany(mappedBy = "countryInfo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("name ASC")
    private List<Language> languages = new ArrayList<>();

    /** Optimistic locking: concurrent PUTs on the same row fail with 409 instead of silently overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Replaces the language list in place so orphanRemoval deletes rows that are no longer present. */
    public void replaceLanguages(Collection<Language> newLanguages) {
        languages.clear();
        newLanguages.forEach(this::addLanguage);
    }

    public void addLanguage(Language language) {
        language.setCountryInfo(this);
        languages.add(language);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIsoCode() { return isoCode; }
    public void setIsoCode(String isoCode) { this.isoCode = isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCapitalCity() { return capitalCity; }
    public void setCapitalCity(String capitalCity) { this.capitalCity = capitalCity; }
    public String getPhoneCode() { return phoneCode; }
    public void setPhoneCode(String phoneCode) { this.phoneCode = phoneCode; }
    public String getContinentCode() { return continentCode; }
    public void setContinentCode(String continentCode) { this.continentCode = continentCode; }
    public String getCurrencyIsoCode() { return currencyIsoCode; }
    public void setCurrencyIsoCode(String currencyIsoCode) { this.currencyIsoCode = currencyIsoCode; }
    public String getFlagUrl() { return flagUrl; }
    public void setFlagUrl(String flagUrl) { this.flagUrl = flagUrl; }
    public List<Language> getLanguages() { return languages; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
