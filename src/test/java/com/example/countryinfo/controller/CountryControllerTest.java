package com.example.countryinfo.controller;

import com.example.countryinfo.dto.CountryResponse;
import com.example.countryinfo.dto.LanguageDto;
import com.example.countryinfo.exception.CountryNotFoundException;
import com.example.countryinfo.exception.DuplicateCountryException;
import com.example.countryinfo.exception.InvalidUpstreamResponseException;
import com.example.countryinfo.exception.ResourceNotFoundException;
import com.example.countryinfo.exception.UpstreamUnavailableException;
import com.example.countryinfo.service.CountryService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller validation, status codes and the shared error shape, with the service mocked. */
@WebMvcTest(CountryController.class)
@ActiveProfiles("test")
class CountryControllerTest {

    private static final String BASE = "/api/v1/countries";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CountryService service;

    // ---- happy paths ---------------------------------------------------------------

    @Test
    void createReturns201WithLocation() throws Exception {
        when(service.create(any())).thenReturn(kenya());

        postJson("{\"name\":\"kenya\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/countries/1")))
                .andExpect(jsonPath("$.isoCode").value("KE"))
                .andExpect(jsonPath("$.languages[1].name").value("Swahili"));
    }

    @Test
    void echoesCorrelationIdAndUsesItAsTraceId() throws Exception {
        when(service.get(42L)).thenThrow(new ResourceNotFoundException(42L));

        mvc.perform(get(BASE + "/42").header("X-Correlation-Id", "abc-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "abc-123"))
                .andExpect(jsonPath("$.traceId").value("abc-123"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value(BASE + "/42"))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsUnsafe() throws Exception {
        when(service.get(1L)).thenReturn(kenya());

        mvc.perform(get(BASE + "/1").header("X-Correlation-Id", "bad id\nwith newline"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", not(containsString(" "))));
    }

    // ---- 400 -----------------------------------------------------------------------

    @Test
    void blankNameIs400WithFieldDetails() throws Exception {
        postJson("{\"name\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasItem(containsString("name"))));
        verifyNoInteractions(service);
    }

    @Test
    void digitsInNameAre400() throws Exception {
        postJson("{\"name\":\"k3nya\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void overlongNameIs400() throws Exception {
        postJson("{\"name\":\"" + "a".repeat(101) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postJson("{\"name\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get(BASE + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void updateWithoutLanguagesIs400() throws Exception {
        mvc.perform(put(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kenya\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("languages"))));
    }

    // ---- 405 / 415 -----------------------------------------------------------------

    @Test
    void unsupportedMethodIs405() throws Exception {
        mvc.perform(patch(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.TEXT_PLAIN).content("kenya"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // ---- service exceptions -> status mapping ---------------------------------------

    @Test
    void unknownCountryIs404() throws Exception {
        when(service.create(any())).thenThrow(new CountryNotFoundException("Atlantis"));
        postJson("{\"name\":\"atlantis\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUNTRY_NOT_FOUND"));
    }

    @Test
    void duplicateIs409WithExistingId() throws Exception {
        when(service.create(any())).thenThrow(new DuplicateCountryException(5L, "KE"));
        postJson("{\"name\":\"kenya\"}")
                .andExpect(status().isConflict())
                .andExpect(header().string("Location", "/api/v1/countries/5"))
                .andExpect(jsonPath("$.code").value("COUNTRY_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.details", hasItem("existingId=5")));
    }

    @Test
    void invalidUpstreamIs502AndHidesDetails() throws Exception {
        when(service.create(any())).thenThrow(new InvalidUpstreamResponseException("SOAP fault: secret internals"));
        postJson("{\"name\":\"kenya\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"))
                .andExpect(jsonPath("$.message", not(containsString("secret"))));
    }

    @Test
    void upstreamUnavailableIs503WithRetryAfter() throws Exception {
        when(service.create(any())).thenThrow(new UpstreamUnavailableException("down", new java.net.SocketTimeoutException()));
        postJson("{\"name\":\"kenya\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void openCircuitIs503() throws Exception {
        when(service.create(any())).thenThrow(
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("countryInfoSoap")));
        postJson("{\"name\":\"kenya\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_CIRCUIT_OPEN"));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetail() throws Exception {
        when(service.create(any())).thenThrow(new IllegalStateException("SQL: SELECT * FROM secret_table"));
        postJson("{\"name\":\"kenya\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message", not(containsString("SQL"))));
    }

    @Test
    void deleteMissingIs404() throws Exception {
        doThrow(new ResourceNotFoundException(99L)).when(service).delete(99L);
        mvc.perform(delete(BASE + "/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteExistingIs204() throws Exception {
        mvc.perform(delete(BASE + "/1"))
                .andExpect(status().isNoContent());
    }

    // ---- helpers -------------------------------------------------------------------

    private ResultActions postJson(String body) throws Exception {
        return mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static CountryResponse kenya() {
        return new CountryResponse(1L, "KE", "Kenya", "Nairobi", "254", "AF", "KES", "http://flags/Kenya.jpg",
                List.of(new LanguageDto("en", "English"), new LanguageDto("sw", "Swahili")),
                LocalDateTime.now(), LocalDateTime.now());
    }
}
