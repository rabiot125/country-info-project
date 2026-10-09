package com.example.countryinfo.service;

import com.example.countryinfo.client.CountryDetails;
import com.example.countryinfo.client.CountryInfoClient;
import com.example.countryinfo.dto.CountryResponse;
import com.example.countryinfo.dto.CreateCountryRequest;
import com.example.countryinfo.exception.CountryNotFoundException;
import com.example.countryinfo.exception.DuplicateCountryException;
import com.example.countryinfo.exception.InvalidRequestException;
import com.example.countryinfo.exception.UpstreamUnavailableException;
import com.example.countryinfo.mapper.CountryMapper;
import com.example.countryinfo.model.CountryInfo;
import com.example.countryinfo.repository.CountryInfoRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryServiceTest {

    @Mock
    private CountryInfoRepository repository;
    @Mock
    private CountryInfoClient client;

    private SimpleMeterRegistry meterRegistry;
    private CountryService service;

    private static final CountryDetails KENYA = new CountryDetails("KE", "Kenya", "Nairobi", "254", "AF", "KES",
            "http://flags/Kenya.jpg",
            List.of(new CountryDetails.LanguageDetails("en", "English"),
                    new CountryDetails.LanguageDetails("sw", "Swahili")));

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CountryService(repository, client, new CountryMapper(), meterRegistry);
    }

    @Test
    void createsCountryOnHappyPath() {
        when(repository.findFirstByNameIgnoreCase("Kenya")).thenReturn(Optional.empty());
        when(client.resolveIsoCode("Kenya")).thenReturn("KE");
        when(repository.findByIsoCode("KE")).thenReturn(Optional.empty());
        when(client.fetchCountryInfo("KE")).thenReturn(KENYA);
        when(repository.saveAndFlush(any(CountryInfo.class))).thenAnswer(inv -> withId(inv.getArgument(0), 1L));

        CountryResponse response = service.create(new CreateCountryRequest("  kenya "));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.isoCode()).isEqualTo("KE");
        assertThat(response.capitalCity()).isEqualTo("Nairobi");
        assertThat(response.phoneCode()).isEqualTo("254");
        assertThat(response.currencyIsoCode()).isEqualTo("KES");
        assertThat(response.languages()).extracting("name").containsExactly("English", "Swahili");
        assertThat(meterRegistry.counter("countries.created").count()).isEqualTo(1.0);
    }

    @Test
    void existingNameIsAnsweredFromDatabaseWithoutCallingSoap() {
        when(repository.findFirstByNameIgnoreCase("Kenya")).thenReturn(Optional.of(stored(7L, "KE")));

        assertThatThrownBy(() -> service.create(new CreateCountryRequest("KENYA")))
                .isInstanceOfSatisfying(DuplicateCountryException.class,
                        e -> assertThat(e.getExistingId()).isEqualTo(7L));
        verifyNoInteractions(client);
    }

    @Test
    void existingIsoCodeIsRejectedBeforeFetchingDetails() {
        when(repository.findFirstByNameIgnoreCase("Kenya")).thenReturn(Optional.empty());
        when(client.resolveIsoCode("Kenya")).thenReturn("KE");
        when(repository.findByIsoCode("KE")).thenReturn(Optional.of(stored(3L, "KE")));

        assertThatThrownBy(() -> service.create(new CreateCountryRequest("kenya")))
                .isInstanceOf(DuplicateCountryException.class);
        verify(client, never()).fetchCountryInfo(anyString());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentInsertLosingTheRaceBecomesConflict() {
        when(repository.findFirstByNameIgnoreCase("Kenya")).thenReturn(Optional.empty());
        when(client.resolveIsoCode("Kenya")).thenReturn("KE");
        when(repository.findByIsoCode("KE"))
                .thenReturn(Optional.empty())               // pre-check
                .thenReturn(Optional.of(stored(9L, "KE"))); // after the constraint fired
        when(client.fetchCountryInfo("KE")).thenReturn(KENYA);
        when(repository.saveAndFlush(any(CountryInfo.class)))
                .thenThrow(new DataIntegrityViolationException("uk_country_info_iso_code"));

        assertThatThrownBy(() -> service.create(new CreateCountryRequest("kenya")))
                .isInstanceOfSatisfying(DuplicateCountryException.class,
                        e -> assertThat(e.getExistingId()).isEqualTo(9L));
        assertThat(meterRegistry.counter("countries.created").count()).isZero();
    }

    @Test
    void unknownCountryPropagatesAndNothingIsSaved() {
        when(repository.findFirstByNameIgnoreCase("Atlantis")).thenReturn(Optional.empty());
        when(client.resolveIsoCode("Atlantis")).thenThrow(new CountryNotFoundException("Atlantis"));

        assertThatThrownBy(() -> service.create(new CreateCountryRequest("atlantis")))
                .isInstanceOf(CountryNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void upstreamFailurePropagatesAndNothingIsSaved() {
        when(repository.findFirstByNameIgnoreCase("Kenya")).thenReturn(Optional.empty());
        when(client.resolveIsoCode("Kenya"))
                .thenThrow(new UpstreamUnavailableException("down", new RuntimeException()));

        assertThatThrownBy(() -> service.create(new CreateCountryRequest("kenya")))
                .isInstanceOf(UpstreamUnavailableException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnknownSortProperty() {
        assertThatThrownBy(() -> service.list(PageRequest.of(0, 10, Sort.by("password"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("password");
        verifyNoInteractions(repository);
    }

    private static CountryInfo stored(Long id, String iso) {
        CountryInfo c = new CountryInfo();
        c.setId(id);
        c.setIsoCode(iso);
        c.setName("Kenya");
        return c;
    }

    private static CountryInfo withId(CountryInfo entity, Long id) {
        entity.setId(id);
        return entity;
    }
}
