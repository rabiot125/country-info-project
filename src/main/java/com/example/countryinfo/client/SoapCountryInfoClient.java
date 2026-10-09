package com.example.countryinfo.client;

import com.example.countryinfo.client.soap.generated.ArrayOftLanguage;
import com.example.countryinfo.client.soap.generated.CountryISOCode;
import com.example.countryinfo.client.soap.generated.CountryISOCodeResponse;
import com.example.countryinfo.client.soap.generated.FullCountryInfo;
import com.example.countryinfo.client.soap.generated.FullCountryInfoResponse;
import com.example.countryinfo.client.soap.generated.TCountryInfo;
import com.example.countryinfo.config.CacheConfig;
import com.example.countryinfo.exception.CountryNotFoundException;
import com.example.countryinfo.exception.InvalidUpstreamResponseException;
import com.example.countryinfo.exception.UpstreamUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * SOAP adapter for the oorsprong.org CountryInfoService.
 *
 * <p>Decorator order (outermost first): cache -> retry -> circuit breaker -> bulkhead -> call.
 * The cache is outermost (see {@link CacheConfig}) so a cached name never touches the
 * circuit breaker; retry wraps the breaker so every attempt is counted by it, and an open
 * breaker fails fast without being retried.
 *
 * <p>All Spring-WS exceptions are translated here into three domain exceptions, which is
 * what the Resilience4j retry/record/ignore rules are configured against.
 */
@Component
public class SoapCountryInfoClient implements CountryInfoClient {

    public static final String RESILIENCE_INSTANCE = "countryInfoSoap";
    static final String METRIC_NAME = "soap.client.requests";

    private static final Logger log = LoggerFactory.getLogger(SoapCountryInfoClient.class);
    private static final Pattern ISO_ALPHA2 = Pattern.compile("^[A-Z]{2}$");

    private final WebServiceTemplate webServiceTemplate;
    private final MeterRegistry meterRegistry;

    public SoapCountryInfoClient(WebServiceTemplate countryInfoWebServiceTemplate, MeterRegistry meterRegistry) {
        this.webServiceTemplate = countryInfoWebServiceTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ISO_CODE_CACHE, key = "#countryName")
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @Bulkhead(name = RESILIENCE_INSTANCE)
    public String resolveIsoCode(String countryName) {
        return timed("CountryISOCode", () -> {
            CountryISOCode request = new CountryISOCode();
            request.setSCountryName(countryName);

            CountryISOCodeResponse response = send(request, CountryISOCodeResponse.class);
            String code = response.getCountryISOCodeResult() == null
                    ? "" : response.getCountryISOCodeResult().trim();

            // The service answers unknown names with a sentence such as
            // "No country found by that name" instead of a fault, so validate the shape.
            if (!ISO_ALPHA2.matcher(code).matches()) {
                throw new CountryNotFoundException(countryName);
            }
            log.info("soap.iso_resolved countryName={} isoCode={}", countryName, code);
            return code;
        });
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @Bulkhead(name = RESILIENCE_INSTANCE)
    public CountryDetails fetchCountryInfo(String isoCode) {
        return timed("FullCountryInfo", () -> {
            FullCountryInfo request = new FullCountryInfo();
            request.setSCountryISOCode(isoCode);

            TCountryInfo info = send(request, FullCountryInfoResponse.class).getFullCountryInfoResult();
            if (info == null || isBlank(info.getSName()) || isBlank(info.getSISOCode())) {
                throw new InvalidUpstreamResponseException("FullCountryInfo returned an empty result for " + isoCode);
            }
            if (!isoCode.equalsIgnoreCase(info.getSISOCode().trim())) {
                throw new InvalidUpstreamResponseException(
                        "FullCountryInfo returned ISO code " + info.getSISOCode() + " for request " + isoCode);
            }
            return new CountryDetails(
                    info.getSISOCode().trim().toUpperCase(Locale.ROOT),
                    info.getSName().trim(),
                    trimToNull(info.getSCapitalCity()),
                    trimToNull(info.getSPhoneCode()),
                    trimToNull(info.getSContinentCode()),
                    trimToNull(info.getSCurrencyISOCode()),
                    trimToNull(info.getSCountryFlag()),
                    toLanguages(info.getLanguages()));
        });
    }

    private <T> T send(Object request, Class<T> responseType) {
        try {
            Object response = webServiceTemplate.marshalSendAndReceive(request);
            if (!responseType.isInstance(response)) {
                throw new InvalidUpstreamResponseException("Unexpected SOAP response type: "
                        + (response == null ? "empty body" : response.getClass().getSimpleName()));
            }
            return responseType.cast(response);
        } catch (SoapFaultClientException e) {
            throw new InvalidUpstreamResponseException("SOAP fault: " + e.getFaultStringOrReason(), e);
        } catch (WebServiceIOException e) {
            // Covers connect/read timeouts, refused connections and non-2xx HTTP
            // (WebServiceTransportException is a subclass).
            throw new UpstreamUnavailableException("Country info service is unavailable", e);
        } catch (XmlMappingException e) {
            throw new InvalidUpstreamResponseException("Unparseable SOAP response", e);
        } catch (WebServiceClientException e) {
            throw new InvalidUpstreamResponseException("SOAP client error: " + e.getMessage(), e);
        }
    }

    /** Times every attempt (not just the final outcome) so retries are visible in metrics. */
    private <T> T timed(String operation, Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return call.get();
        } catch (CountryNotFoundException e) {
            outcome = "not_found";
            throw e;
        } catch (UpstreamUnavailableException e) {
            outcome = "unavailable";
            throw e;
        } catch (InvalidUpstreamResponseException e) {
            outcome = "invalid_response";
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            long elapsedNanos = System.nanoTime() - start;
            Timer.builder(METRIC_NAME)
                    .description("Outbound SOAP calls to the country info service, per attempt")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            if ("success".equals(outcome) || "not_found".equals(outcome)) {
                log.debug("soap.call operation={} outcome={} latencyMs={}", operation, outcome, elapsedMs);
            } else {
                log.warn("soap.call operation={} outcome={} latencyMs={}", operation, outcome, elapsedMs);
            }
        }
    }

    private static List<CountryDetails.LanguageDetails> toLanguages(ArrayOftLanguage languages) {
        if (languages == null || languages.getTLanguage() == null) {
            return List.of();
        }
        return languages.getTLanguage().stream()
                .filter(Objects::nonNull)
                .filter(l -> !isBlank(l.getSName()))
                .map(l -> new CountryDetails.LanguageDetails(trimToNull(l.getSISOCode()), l.getSName().trim()))
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
