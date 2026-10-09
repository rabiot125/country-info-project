package com.example.countryinfo.exception;

/** Upstream answered, but with a SOAP fault or a payload we cannot use. Not retried: repeating it gives the same answer. */
public class InvalidUpstreamResponseException extends RuntimeException {

    public InvalidUpstreamResponseException(String message) {
        super(message);
    }

    public InvalidUpstreamResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
