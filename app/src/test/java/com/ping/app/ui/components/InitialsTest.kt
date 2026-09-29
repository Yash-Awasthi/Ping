package com.ping.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialsTest {
    @Test
    fun `one word gives one letter and several give first and last`() {
        assertEquals("A", initials("ada"))
        assertEquals("AL", initials("Ada King Lovelace"))
        assertEquals("AL", initials("  ada   lovelace "))
    }

    @Test
    fun `nothing to show gives a question mark`() {
        assertEquals("?", initials(""))
        assertEquals("?", initials("   "))
    }
}
