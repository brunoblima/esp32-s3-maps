package com.maisonsmd.catdrive.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NavigationIconTest {
    @Test
    fun twoMissingIconsAreEqual() {
        assertEquals(NavigationIcon(), NavigationIcon())
    }

    @Test
    fun navigationIconDiffersFromOtherTypes() {
        assertNotEquals(NavigationIcon(), "not an icon")
    }
}
