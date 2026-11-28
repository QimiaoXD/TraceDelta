package io.tracedelta.trace;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates OpenTelemetry-compatible lowercase hex trace IDs (16 bytes / 32 hex chars)
 * and span IDs (8 bytes / 16 hex chars), guaranteed non-zero.
 */
public final class TraceIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private TraceIdGenerator() {}

    public static String newTraceId() {
        return randomNonZeroHex(16);
    }

    public static String newSpanId() {
        return randomNonZeroHex(8);
    }

    private static String randomNonZeroHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        do {
            RANDOM.nextBytes(bytes);
        } while (isAllZero(bytes));
        return HEX.formatHex(bytes);
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
