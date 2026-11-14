package com.maisonsmd.catdrive.lib

import org.junit.Assert.assertEquals
import org.junit.Test

class BlePayloadEncoderTest {
    @Test
    fun encodeSettingsUsesFirmwareKeysAndOrder() {
        assertEquals(
            "lightTheme=true\nbrightness=50\nspeedLimit=60",
            BlePayloadEncoder.encodeSettings(true, 50, 60)
        )
    }

    @Test
    fun encodeNavigationUsesFirmwareKeysAndSanitizesText() {
        assertEquals(
            "nextRd=Main Street... East\n" +
                    "nextRdDesc=Turn left\n" +
                    "distToNext=200 m\n" +
                    "totalDist=5 km\n" +
                    "eta=10:30\n" +
                    "ete=12 min\n" +
                    "iconHash=abc123",
            BlePayloadEncoder.encodeNavigation(
                "Main\u00a0Street… East",
                "Turn\nleft",
                "200 m",
                "5 km",
                "10:30",
                "12 min",
                "abc123"
            )
        )
    }

    @Test
    fun encodeNavigationUsesEmptyValuesForMissingFields() {
        assertEquals(
            "nextRd=\nnextRdDesc=\ndistToNext=\ntotalDist=\neta=\nete=\niconHash=",
            BlePayloadEncoder.encodeNavigation(null, null, null, null, null, null, "")
        )
    }
}
