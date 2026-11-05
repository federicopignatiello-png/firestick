package com.firepad.app;

import java.util.Locale;

/** Name and manufacturer checks used to sort a discovery list. They never hide other devices. */
public final class FireDeviceNames {
    /** Bluetooth SIG company identifier for Amazon.com Services LLC. */
    public static final int AMAZON_MANUFACTURER_ID = 0x0171;

    private FireDeviceNames() {}

    public static boolean isAmazonManufacturer(int companyId) {
        return companyId == AMAZON_MANUFACTURER_ID;
    }

    public static boolean looksLikeFireTv(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) return false;
        return normalized.contains("fire") || normalized.contains("amazon");
    }
}
