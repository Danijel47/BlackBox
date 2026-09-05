package com.blackbox.wow.service;

import org.springframework.web.client.RestClientResponseException;

public final class HousingMarketUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final int MAXIMUM_CAUSE_DEPTH = 10;

    private final DataSource dataSource;
    private final Integer httpStatus;

    private HousingMarketUnavailableException(DataSource dataSource, RuntimeException cause) {
        super(dataSource.displayName() + " could not be loaded.", cause);
        this.dataSource = dataSource;
        this.httpStatus = findHttpStatus(cause);
    }

    public static HousingMarketUnavailableException from(DataSource dataSource, RuntimeException cause) {
        return new HousingMarketUnavailableException(dataSource, cause);
    }

    public String adminMessage() {
        if (httpStatus != null) {
            return "Housing sales data is temporarily unavailable: "
                    + dataSource.displayName() + " returned HTTP " + httpStatus + ".";
        }
        return "Housing sales data is temporarily unavailable: "
                + dataSource.displayName() + " could not be loaded. Check the server logs.";
    }

    private static Integer findHttpStatus(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAXIMUM_CAUSE_DEPTH; depth++) {
            if (current instanceof RestClientResponseException responseException) {
                return responseException.getStatusCode().value();
            }
            current = current.getCause();
        }
        return null;
    }

    public enum DataSource {
        BLIZZARD_DECOR("Blizzard decor catalog"),
        SADDLEBAG_TSM("Saddlebag Exchange TSM data");

        private final String displayName;

        DataSource(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }
}
