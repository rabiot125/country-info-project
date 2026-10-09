package com.example.countryinfo.dto;

public final class ValidationPatterns {

    /** Unicode letters (so "Côte" is accepted), spaces, hyphens and apostrophes; must contain a letter. */
    public static final String COUNTRY_NAME = "^[\\s'\\-]*\\p{L}[\\p{L}\\s'\\-]*$";

    private ValidationPatterns() {
    }
}
