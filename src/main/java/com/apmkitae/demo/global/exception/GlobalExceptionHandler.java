package com.apmkitae.demo.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleOrderNotFound(OrderNotFoundException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(InvalidOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleInvalidOrderStatus(InvalidOrderStatusException e) {
        return Map.of("message", e.getMessage());
    }
}
