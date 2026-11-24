package com.ecomassistant.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Exact authored catalog tokens; no regional size conversion. */
public final class CatalogSizes {
    public static final String FIELD = "sizeTokens";
    public static final String PAYLOAD_VERSION = "size-tokens-v1";
    private CatalogSizes() {}
    public static String normalize(String size) {
        if (size == null) return null;
        String token = size.trim().toUpperCase(Locale.ROOT);
        if (!token.matches("(?:[A-Z]{1,12}|[0-9]{1,3}(?:\\.[0-9])?)"))
            throw new IllegalArgumentException("Expected one exact catalog size token");
        return token;
    }
    public static List<String> tokens(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(CatalogSizes::normalize).distinct().toList();
    }
}
