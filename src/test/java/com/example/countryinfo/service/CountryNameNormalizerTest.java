package com.example.countryinfo.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CountryNameNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "kenya                | Kenya",
            "KENYA                | Kenya",
            "kEnYa                | Kenya",
            "'  south   africa '  | South Africa",
            "united states        | United States",
            "cote d'ivoire        | Cote D'ivoire",
            "guinea-bissau        | Guinea-Bissau",
            "'\tnew  zealand  '   | New Zealand",
            "réunion              | Réunion",
    })
    void normalizes(String input, String expected) {
        assertThat(CountryNameNormalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"''", "'   '"})
    void blankBecomesEmpty(String input) {
        assertThat(CountryNameNormalizer.normalize(input)).isEmpty();
    }
}
