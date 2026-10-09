package com.example.countryinfo.client;

import com.example.countryinfo.exception.CountryNotFoundException;
import com.example.countryinfo.exception.InvalidUpstreamResponseException;
import com.example.countryinfo.exception.UpstreamUnavailableException;

/**
 * Port to the upstream country data provider. The service layer depends only on this
 * interface and on the neutral {@link CountryDetails} record, never on generated SOAP
 * types, so the provider can be mocked in tests or swapped (e.g. for a REST source).
 */
public interface CountryInfoClient {

    /**
     * @param countryName normalised name, e.g. "South Africa"
     * @return upper-case ISO 3166-1 alpha-2 code, e.g. "ZA"
     * @throws CountryNotFoundException          when the provider does not know the name
     * @throws UpstreamUnavailableException      on timeouts, I/O errors or HTTP 5xx (after retries)
     * @throws InvalidUpstreamResponseException  on SOAP faults or unparseable payloads
     */
    String resolveIsoCode(String countryName);

    /**
     * @param isoCode ISO alpha-2 code previously returned by {@link #resolveIsoCode(String)}
     */
    CountryDetails fetchCountryInfo(String isoCode);
}
