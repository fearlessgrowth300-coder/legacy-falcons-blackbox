package top.niunaijun.blackboxa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.niunaijun.blackboxa.cloud.Updater

class UpdaterRolloutTest {
    @Test
    fun cohortIsStableAndVersionSpecific() {
        val id = "install-123"
        val first = Updater.rolloutBucket(id, "top.niunaijun.blackbox", 405)
        assertEquals(first, Updater.rolloutBucket(id, "top.niunaijun.blackbox", 405))
        assertNotEquals(first, Updater.rolloutBucket(id, "top.niunaijun.blackbox", 406))
        assertTrue(first in 0..99)
    }

    @Test
    fun rolloutBoundariesAreFailSafe() {
        assertFalse(Updater.isBucketEligible("a", "top.niunaijun.blackbox", 405, 0))
        assertFalse(Updater.isBucketEligible("a", "top.niunaijun.blackbox", 405, -1))
        assertTrue(Updater.isBucketEligible("a", "top.niunaijun.blackbox", 405, 100))
        assertTrue(Updater.isBucketEligible("a", "top.niunaijun.blackbox", 405, 101))
    }

    @Test
    fun tenPercentCohortIsApproximatelyTenPercent() {
        val eligible = (0 until 10_000).count {
            Updater.isBucketEligible("install-$it", "top.niunaijun.blackbox", 405, 10)
        }
        assertTrue("eligible=$eligible", eligible in 850..1150)
    }
}
