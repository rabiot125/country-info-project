package com.example.countryinfo.dto;

import java.time.Instant;
import java.util.List;

/** The single error shape returned for every non-2xx response. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String traceId,
        List<String> details
) {
}
