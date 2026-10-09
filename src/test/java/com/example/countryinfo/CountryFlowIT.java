package com.example.countryinfo;

import com.example.countryinfo.client.SoapCountryInfoClient;
import com.example.countryinfo.config.CacheConfig;
import com.example.countryinfo.repository.CountryInfoRepository;
import com.example.countryinfo.support.MySqlContainerSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: real HTTP into the app, real MySQL (Testcontainers), and WireMock playing the
 * SOAP provider, including slow, failing and faulting responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CountryFlowIT extends MySqlContainerSupport {

    private static final String SOAP_PATH = "/websamples.countryinfo/CountryInfoService.wso";
    private static final String ISO_OPERATION = "sCountryName";        // only in CountryISOCode requests
    private static final String INFO_OPERATION = "sCountryISOCode";    // only in FullCountryInfo requests

    private static final WireMockServer SOAP = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SOAP.start();
    }

    @DynamicPropertySource
    static void soapEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.soap.endpoint-url", () -> SOAP.baseUrl() + SOAP_PATH);
    }

    @AfterAll
    static void stopSoap() {
        SOAP.stop();
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private CountryInfoRepository repository;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;
    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        SOAP.resetAll();
        repository.deleteAll();
        circuitBreakers.circuitBreaker(SoapCountryInfoClient.RESILIENCE_INSTANCE).reset();
        cacheManager.getCache(CacheConfig.ISO_CODE_CACHE).clear();
    }

    @Test
    void createsKenyaThenRejectsDuplicateWithoutCallingSoapAgain() {
        stubSoap(ISO_OPERATION, 200, xml("soap/country-iso-code-kenya.xml"));
        stubSoap(INFO_OPERATION, 200, xml("soap/full-country-info-kenya.xml"));

        ResponseEntity<JsonNode> created = create("kenya");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        JsonNode body = created.getBody();
        assertThat(body.get("isoCode").asText()).isEqualTo("KE");
        assertThat(body.get("capitalCity").asText()).isEqualTo("Nairobi");
        assertThat(body.get("phoneCode").asText()).isEqualTo("254");
        assertThat(body.get("currencyIsoCode").asText()).isEqualTo("KES");
        assertThat(body.get("languages")).hasSize(2);

        SOAP.resetRequests();
        ResponseEntity<JsonNode> repeat = create("KENYA");

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repeat.getBody().get("code").asText()).isEqualTo("COUNTRY_ALREADY_EXISTS");
        assertThat(repository.count()).isEqualTo(1);
        SOAP.verify(0, postRequestedFor(urlEqualTo(SOAP_PATH)));   // served from the DB
    }

    @Test
    void crudRoundTrip() {
        stubSoap(ISO_OPERATION, 200, xml("soap/country-iso-code-kenya.xml"));
        stubSoap(INFO_OPERATION, 200, xml("soap/full-country-info-kenya.xml"));
        long id = create("kenya").getBody().get("id").asLong();

        ResponseEntity<JsonNode> list = http.getForEntity("/api/v1/countries?page=0&size=10&sort=name,asc", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("totalElements").asLong()).isEqualTo(1);

        String update = """
                {"name":"Kenya","capitalCity":"Nairobi City","phoneCode":"254","continentCode":"AF",
                 "currencyIsoCode":"KES","flagUrl":"http://example.org/ke.jpg",
                 "languages":[{"isoCode":"sw","name":"Swahili"}]}
                """;
        ResponseEntity<JsonNode> updated = http.exchange("/api/v1/countries/" + id, HttpMethod.PUT,
                json(update), JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("capitalCity").asText()).isEqualTo("Nairobi City");
        assertThat(updated.getBody().get("languages")).hasSize(1);

        ResponseEntity<Void> deleted = http.exchange("/api/v1/countries/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> gone = http.getForEntity("/api/v1/countries/" + id, JsonNode.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownCountryIs404AndNeverFetchesDetails() {
        stubSoap(ISO_OPERATION, 200, xml("soap/country-iso-code-not-found.xml"));

        ResponseEntity<JsonNode> response = create("atlantis");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("COUNTRY_NOT_FOUND");
        assertThat(response.getBody().get("traceId").asText()).isNotBlank();
        SOAP.verify(1, postRequestedFor(urlEqualTo(SOAP_PATH)));  // business "not found" is not retried
    }

    @Test
    void http500IsRetriedThreeTimesThenReturns503() {
        stubSoap(ISO_OPERATION, 500, "upstream exploded");

        ResponseEntity<JsonNode> response = create("kenya");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody().get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        SOAP.verify(3, postRequestedFor(urlEqualTo(SOAP_PATH)));
        assertThat(repository.count()).isZero();
    }

    @Test
    void slowUpstreamTimesOutQuicklyWith503() {
        SOAP.stubFor(post(urlEqualTo(SOAP_PATH))
                .willReturn(aResponse().withFixedDelay(2_000).withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(xml("soap/country-iso-code-kenya.xml"))));

        Instant start = Instant.now();
        ResponseEntity<JsonNode> response = create("kenya");
        Duration took = Duration.between(start, Instant.now());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // 3 attempts x 500 ms read timeout + small backoff, far below the 2 s x 3 the upstream wanted
        assertThat(took).isLessThan(Duration.ofMillis(4_000));
    }

    @Test
    void soapFaultIs502AndNotRetried() {
        stubSoap(ISO_OPERATION, 500, xml("soap/soap-fault.xml"));

        ResponseEntity<JsonNode> response = create("kenya");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
        SOAP.verify(1, postRequestedFor(urlEqualTo(SOAP_PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndThenFailsFastWithoutCallingUpstream() {
        stubSoap(ISO_OPERATION, 500, "down");

        create("kenya");                       // 3 failed attempts
        ResponseEntity<JsonNode> second = create("uganda");  // 4th failure opens the circuit
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(circuitBreakers.circuitBreaker(SoapCountryInfoClient.RESILIENCE_INSTANCE).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        SOAP.resetRequests();
        ResponseEntity<JsonNode> third = create("tanzania");

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(third.getBody().get("code").asText()).isEqualTo("UPSTREAM_CIRCUIT_OPEN");
        SOAP.verify(0, postRequestedFor(urlEqualTo(SOAP_PATH)));

        // The app itself stays healthy: reads still work while the upstream is down.
        assertThat(http.getForEntity("/api/v1/countries", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void validationErrorUsesStandardShape() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/countries", json("{\"name\":\"\"}"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fieldNames()).toIterable()
                .contains("timestamp", "status", "error", "code", "message", "path", "traceId", "details");
    }

    // ---- helpers -------------------------------------------------------------------

    private ResponseEntity<JsonNode> create(String name) {
        return http.postForEntity("/api/v1/countries", json("{\"name\":\"" + name + "\"}"), JsonNode.class);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static void stubSoap(String operationMarker, int status, String body) {
        SOAP.stubFor(post(urlEqualTo(SOAP_PATH))
                .withRequestBody(containing(operationMarker))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", body.startsWith("<?xml") ? "text/xml; charset=utf-8" : "text/plain")
                        .withBody(body)));
    }

    private static String xml(String classpathLocation) {
        try {
            return new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
