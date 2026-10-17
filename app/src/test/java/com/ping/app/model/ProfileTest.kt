package com.ping.app.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileTest {

    private val full = Profile(displayName = "Ana", phone = "123", email = "a@b.c", social = "@ana", note = "hi")

    @Test
    fun `the default mask shares every filled field`() {
        assertEquals(
            mapOf("name" to "Ana", "phone" to "123", "email" to "a@b.c", "social" to "@ana", "note" to "hi"),
            full.toShareableMap(),
        )
        assertEquals(mapOf("name" to "Ana"), Profile(displayName = "Ana", phone = " ").toShareableMap())
    }

    @Test
    fun `a mask keeps only the chosen fields`() {
        assertEquals(mapOf("name" to "Ana", "email" to "a@b.c"), full.toShareableMap(setOf("name", "email")))
        assertEquals(emptyMap<String, String>(), full.toShareableMap(emptySet()))
    }
}
