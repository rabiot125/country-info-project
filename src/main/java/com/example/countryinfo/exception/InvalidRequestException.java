package com.example.countryinfo.exception;

/** Client error detected in the service layer (e.g. an unsupported sort property). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
