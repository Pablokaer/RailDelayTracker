package com.irishrail.web;

/** A request this application will not act on. Rendered as a 400 by {@link ApiExceptionHandler}. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
