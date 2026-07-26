package top.niunaijun.blackboxa.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    @Test
    fun scrubRemovesCredentialsAndAccountIdentifiers() {
        val raw = "user=alice@example.com password=hunter2 token=abc " +
            "sessionid-AbC123 host=172.58.135.87"
        val scrubbed = CrashReporter.scrub(raw)
        assertFalse(scrubbed.contains("alice@example.com"))
        assertFalse(scrubbed.contains("hunter2"))
        assertFalse(scrubbed.contains("abc"))
        assertFalse(scrubbed.contains("AbC123"))
        assertFalse(scrubbed.contains("172.58.135.87"))
        assertTrue(scrubbed.contains("user=<redacted>"))
        assertTrue(scrubbed.contains("<ip>"))
    }
}
