package com.example.countryinfo.exception;

/** Transient upstream failure (timeout, connection error, HTTP 5xx). The only exception type that is retried. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
