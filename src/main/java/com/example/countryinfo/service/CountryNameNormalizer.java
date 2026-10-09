package com.example.countryinfo.service;

import java.util.Locale;

/**
 * Normalises user input into the form the SOAP service matches on.
 *
 * <p>The brief asks for "sentence case" (kenya -> Kenya). The upstream lookup is an exact
 * match on title-cased names, so multi-word names need every word capitalised
 * ("south africa" -> "South Africa", not "South africa"). Hyphenated parts are capitalised
 * too ("guinea-bissau" -> "Guinea-Bissau"). Letters after an apostrophe are left lower case
 * ("cote d'ivoire" -> "Cote D'ivoire").
 */
public final class CountryNameNormalizer {

    private CountryNameNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);

        StringBuilder out = new StringBuilder(collapsed.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < collapsed.length(); ) {
            int codePoint = collapsed.codePointAt(i);
            if (capitalizeNext && Character.isLetter(codePoint)) {
                out.appendCodePoint(Character.toTitleCase(codePoint));
                capitalizeNext = false;
            } else {
                out.appendCodePoint(codePoint);
            }
            if (codePoint == ' ' || codePoint == '-') {
                capitalizeNext = true;
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
