package com.ping.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavKeysTest {

    private val json = Json { serializersModule = navKeyModule }
    private val serializer = PolymorphicSerializer(NavKey::class)

    private val samples: List<Screen> =
        listOf(Home, Profile, Contacts, ContactDetail("c-1"), Exchange, Room, Practice, RollCall)

    @Test
    fun `every screen key has a sample`() {
        val sealed = Screen.serializer().descriptor.getElementDescriptor(1)
        val all = (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet()
        assertEquals(all, samples.map { it::class.qualifiedName }.toSet())
    }

    @Test
    fun `every screen key survives a round trip as a NavKey`() {
        samples.forEach { key ->
            assertEquals(key, json.decodeFromString(serializer, json.encodeToString(serializer, key)))
        }
    }
}
