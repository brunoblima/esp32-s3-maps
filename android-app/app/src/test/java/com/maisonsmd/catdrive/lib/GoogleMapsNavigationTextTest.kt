package com.maisonsmd.catdrive.lib

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleMapsNavigationTextTest {
    @Test
    fun separatesDistanceAndInstructionFromMapsTitle() {
        val direction = GoogleMapsNavigationText.parseDirection(
            title = "70 m · Vire à direita na R. Tiradentes",
            primaryInfo = "70 m",
            secondaryInfo = "R. Tiradentes"
        )

        assertEquals("70 m", direction.distance)
        assertEquals("Vire à direita na R. Tiradentes", direction.nextRoad)
        assertEquals("R. Tiradentes", direction.nextRoadAdditionalInfo)
    }

    @Test
    fun usesPrimaryInfoForDistanceWhenTitleHasOnlyInstruction() {
        val direction = GoogleMapsNavigationText.parseDirection(
            title = "Vire à direita na R. Tiradentes",
            primaryInfo = "70 m",
            secondaryInfo = "R. Tiradentes"
        )

        assertEquals("70 m", direction.distance)
        assertEquals("Vire à direita na R. Tiradentes", direction.nextRoad)
        assertEquals("R. Tiradentes", direction.nextRoadAdditionalInfo)
    }

    @Test
    fun doesNotTreatInstructionOnlyTitleAsDistance() {
        val direction = GoogleMapsNavigationText.parseDirection(
            title = "Siga em direção à R. Tiradentes",
            primaryInfo = null,
            secondaryInfo = "R. Tiradentes"
        )

        assertEquals("", direction.distance)
        assertEquals("Siga em direção à R. Tiradentes", direction.nextRoad)
        assertEquals("R. Tiradentes", direction.nextRoadAdditionalInfo)
    }

    @Test
    fun separatesDistanceAndInstructionWhenMapsUsesNonBreakingSpace() {
        val direction = GoogleMapsNavigationText.parseDirection(
            title = "70\u00A0m · Vire à direita na R. Tiradentes",
            primaryInfo = "70\u00A0m",
            secondaryInfo = "R. Tiradentes"
        )

        assertEquals("70 m", direction.distance)
        assertEquals("Vire à direita na R. Tiradentes", direction.nextRoad)
        assertEquals("R. Tiradentes", direction.nextRoadAdditionalInfo)
    }

    @Test
    fun treatsStandaloneDistanceAsDistanceNotInstruction() {
        val direction = GoogleMapsNavigationText.parseDirection(
            title = "70\u00A0m",
            primaryInfo = null,
            secondaryInfo = "R. Tiradentes"
        )

        assertEquals("70 m", direction.distance)
        assertEquals("", direction.nextRoad)
        assertEquals("R. Tiradentes", direction.nextRoadAdditionalInfo)
    }
}
