package com.example.countryinfo.exception;

/** The country is already stored; carries the existing id so clients can follow it. */
public class DuplicateCountryException extends RuntimeException {

    private final Long existingId;
    private final String isoCode;

    public DuplicateCountryException(Long existingId, String isoCode) {
        super("Country with ISO code " + isoCode + " already exists (id " + existingId + ")");
        this.existingId = existingId;
        this.isoCode = isoCode;
    }

    public Long getExistingId() {
        return existingId;
    }

    public String getIsoCode() {
        return isoCode;
    }
}
