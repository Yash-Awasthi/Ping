package com.ping.app.rollcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollCallProtocolTest {

    @Test
    fun `student advert round trips and is cleaned`() {
        val ad = RollCallProtocol.studentAdvert("0a1b2c3d", " cs-21 07 ", "deadbeef", "Ada|Lovelace\n")
        assertEquals(
            RollCallProtocol.StudentAdvert("0a1b2c3d", "CS-2107", "deadbeef", "AdaLovelace"),
            RollCallProtocol.parseStudent(ad),
        )
    }

    @Test
    fun `admin advert round trips and junk is rejected`() {
        assertEquals(
            RollCallProtocol.AdminAdvert("0a1b2c3d", "Physics2B"),
            RollCallProtocol.parseAdmin(RollCallProtocol.adminAdvert("0a1b2c3d", "Physics|2B")),
        )
        assertNull(RollCallProtocol.parseAdmin("A|xyz|x"))
        assertNull(RollCallProtocol.parseAdmin("S|0a1b2c3d|x"))
        assertNull(RollCallProtocol.parseStudent("S|0a1b2c3d||deadbeef|x"))
        assertNull(RollCallProtocol.parseStudent("S|0a1b2c3d|7|nothex!!|x"))
        assertNull(RollCallProtocol.parseStudent("abc|token"))
    }

    @Test
    fun `csv fields are quoted and formulas neutralised`() {
        assertEquals("plain", RollCallProtocol.csvField("plain"))
        assertEquals("\"a,b\"", RollCallProtocol.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", RollCallProtocol.csvField("say \"hi\""))
        assertEquals("'=SUM(A1)", RollCallProtocol.csvField("=SUM(A1)"))
    }

    @Test
    fun `tokens are eight hex chars an admin advert accepts`() {
        val token = RollCallProtocol.newToken()
        assertEquals(token, RollCallProtocol.parseAdmin(RollCallProtocol.adminAdvert(token, "x"))?.session)
    }
}
