package com.meeting.common;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

@Slf4j
public class RetryUtils {

    private RetryUtils() {}

    /**
     * Retry an operation with exponential backoff.
     * Retries on any exception. Suitable for transient failures like network timeouts,
     * rate limits, and temporary server errors.
     *
     * @param operationName label for logging
     * @param maxAttempts   total number of attempts (1 = no retry)
     * @param initialDelayMs delay before first retry (doubles each attempt)
     * @param supplier      the operation to retry
     * @return the result
     * @throws RuntimeException if all attempts fail
     */
    public static <T> T retryWithBackoff(String operationName, int maxAttempts,
                                          long initialDelayMs, Supplier<T> supplier) {
        Exception lastException = null;
        long delayMs = initialDelayMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("{} failed (attempt {}/{}): {}. Retrying in {}ms",
                            operationName, attempt, maxAttempts, e.getMessage(), delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(operationName + " interrupted", ie);
                    }
                    delayMs *= 2;
                } else {
                    log.error("{} failed after {} attempts: {}", operationName, maxAttempts, e.getMessage(), e);
                }
            }
        }
        throw new RuntimeException(operationName + " failed after " + maxAttempts + " attempts",
                lastException);
    }
}