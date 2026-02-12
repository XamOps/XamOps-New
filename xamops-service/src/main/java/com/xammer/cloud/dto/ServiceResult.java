package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Generic wrapper for service results that may succeed, fail, or partially succeed.
 * Useful for handling errors gracefully without throwing exceptions.
 *
 * @param <T> The type of data being returned
 */
@Data
@AllArgsConstructor
public class ServiceResult<T> {
    private T data;
    private List<String> errors;
    private boolean success;
    private boolean partial;  // Some data retrieved despite errors

    /**
     * Create a successful result with data.
     */
    public static <T> ServiceResult<T> success(T data) {
        return new ServiceResult<>(data, new ArrayList<>(), true, false);
    }

    /**
     * Create a failed result with an error message and default data.
     */
    public static <T> ServiceResult<T> failure(String error, T defaultData) {
        List<String> errors = new ArrayList<>();
        errors.add(error);
        return new ServiceResult<>(defaultData, errors, false, false);
    }

    /**
     * Create a partial success result with some data and an error.
     */
    public static <T> ServiceResult<T> partial(T data, String error) {
        List<String> errors = new ArrayList<>();
        errors.add(error);
        return new ServiceResult<>(data, errors, true, true);
    }

    /**
     * Get the data as an Optional.
     */
    public Optional<T> getData() {
        return Optional.ofNullable(data);
    }

    /**
     * Check if there are any errors.
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    /**
     * Get the first error message if available.
     */
    public Optional<String> getFirstError() {
        return errors != null && !errors.isEmpty()
            ? Optional.of(errors.get(0))
            : Optional.empty();
    }
}
