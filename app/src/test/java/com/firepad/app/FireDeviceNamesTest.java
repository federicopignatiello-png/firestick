package com.firepad.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FireDeviceNamesTest {
    @Test public void recognizesFireAndAmazonNames() {
        assertTrue(FireDeviceNames.looksLikeFireTv("Fire TV Stick"));
        assertTrue(FireDeviceNames.looksLikeFireTv("Living Room Fire Stick"));
        assertTrue(FireDeviceNames.looksLikeFireTv("firestick"));
        assertTrue(FireDeviceNames.looksLikeFireTv("Amazon Fire TV"));
    }

    @Test public void rejectsBlankAndUnrelatedNames() {
        assertFalse(FireDeviceNames.looksLikeFireTv(null));
        assertFalse(FireDeviceNames.looksLikeFireTv(""));
        assertFalse(FireDeviceNames.looksLikeFireTv("   "));
        assertFalse(FireDeviceNames.looksLikeFireTv("Galaxy Buds"));
        assertFalse(FireDeviceNames.looksLikeFireTv("Living Room TV"));
    }

    @Test public void amazonManufacturerIdIsTheBluetoothSigAssignment() {
        assertEquals(0x0171, FireDeviceNames.AMAZON_MANUFACTURER_ID);
        assertTrue(FireDeviceNames.isAmazonManufacturer(0x0171));
        assertFalse(FireDeviceNames.isAmazonManufacturer(0x0172));
    }
}
