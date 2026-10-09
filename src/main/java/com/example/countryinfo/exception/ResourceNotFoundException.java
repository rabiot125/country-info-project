package com.example.countryinfo.exception;

/** A stored country with the given id does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(Long id) {
        super("Country with id " + id + " not found");
    }
}
