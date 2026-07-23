package top.niunaijun.blackboxa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.niunaijun.blackbox.core.GuestProxy

class GuestGeoPolicyTest {
    private val cls = GuestProxy::class.java

    @Test
    fun knownCountryUsesCoherentCarrier() {
        val sim = call("simForCountry", arrayOf(String::class.java), "gb") as Array<*>
        assertArrayEquals(arrayOf("23410", "gb", "O2"), sim)
    }

    @Test
    fun unknownValidCountryDoesNotExposePhysicalCarrier() {
        val sim = call("simForCountry", arrayOf(String::class.java), "se") as Array<*>
        assertArrayEquals(arrayOf("", "se", ""), sim)
        assertNull(call("simForCountry", arrayOf(String::class.java), "not-a-country"))
    }

    @Test
    fun validatesCoordinatesAndTimezone() {
        val sig: Array<Class<*>> = arrayOf(
            Double::class.javaObjectType,
            Double::class.javaObjectType
        )
        assertTrue(call("validLatLng", sig, 53.4808, -2.2426) as Boolean)
        assertFalse(call("validLatLng", sig, 500.0, -2.2426) as Boolean)
        assertEquals(
            "Europe/London",
            call("normalizeTimezone", arrayOf(String::class.java), "Europe/London")
        )
        assertEquals("", call("normalizeTimezone", arrayOf(String::class.java), "Not/A_Zone"))
    }

    private fun call(name: String, signature: Array<Class<*>>, vararg args: Any): Any? {
        return cls.getDeclaredMethod(name, *signature).run {
            isAccessible = true
            invoke(null, *args)
        }
    }
}
