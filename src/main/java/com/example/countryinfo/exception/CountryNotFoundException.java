package com.example.countryinfo.exception;

/** The upstream provider does not recognise the requested country name. Business outcome: never retried. */
public class CountryNotFoundException extends RuntimeException {

    private final String countryName;

    public CountryNotFoundException(String countryName) {
        super("No country found with name '" + countryName + "'");
        this.countryName = countryName;
    }

    public String getCountryName() {
        return countryName;
    }
}
