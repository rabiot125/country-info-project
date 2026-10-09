package com.example.countryinfo.repository;

import com.example.countryinfo.model.CountryInfo;
import com.example.countryinfo.model.Language;
import com.example.countryinfo.support.MySqlContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs Flyway V1 against real MySQL and lets Hibernate validate the result
 * (ddl-auto=validate), so a drift between entities and migrations fails the build.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CountryInfoRepositoryIT extends MySqlContainerSupport {

    @Autowired
    private CountryInfoRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsCountryWithLanguages() {
        CountryInfo saved = repository.saveAndFlush(country("KE", "Kenya", "English", "Swahili"));
        entityManager.clear();

        CountryInfo loaded = repository.findByIsoCode("KE").orElseThrow();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getLanguages()).extracting(Language::getName).containsExactly("English", "Swahili");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void uniqueIsoCodeConstraintRejectsDuplicates() {
        repository.saveAndFlush(country("KE", "Kenya"));

        assertThatThrownBy(() -> repository.saveAndFlush(country("KE", "Kenya again")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nameLookupIsCaseAndAccentInsensitive() {
        repository.saveAndFlush(country("CI", "Côte D'Ivoire"));

        assertThat(repository.findFirstByNameIgnoreCase("cote d'ivoire")).isPresent();
        assertThat(repository.findFirstByNameIgnoreCase("CÔTE D'IVOIRE")).isPresent();
    }

    @Test
    void replacingLanguagesRemovesOrphans() {
        CountryInfo saved = repository.saveAndFlush(country("KE", "Kenya", "English", "Swahili"));

        saved.replaceLanguages(List.of(new Language("fr", "French")));
        repository.saveAndFlush(saved);
        entityManager.clear();

        Long languageRows = entityManager
                .createQuery("select count(l) from Language l", Long.class)
                .getSingleResult();
        assertThat(languageRows).isEqualTo(1L);
    }

    @Test
    void deletingCountryCascadesToLanguages() {
        CountryInfo saved = repository.saveAndFlush(country("KE", "Kenya", "English"));

        repository.delete(saved);
        repository.flush();

        Long languageRows = entityManager
                .createQuery("select count(l) from Language l", Long.class)
                .getSingleResult();
        assertThat(languageRows).isZero();
    }

    private static CountryInfo country(String iso, String name, String... languages) {
        CountryInfo c = new CountryInfo();
        c.setIsoCode(iso);
        c.setName(name);
        c.setCapitalCity("Capital");
        for (String language : languages) {
            c.addLanguage(new Language(language.substring(0, 2).toLowerCase(), language));
        }
        return c;
    }
}
