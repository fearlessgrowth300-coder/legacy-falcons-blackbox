package top.niunaijun.blackboxa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.niunaijun.blackbox.utils.TrieTree

class TrieTreeTest {
    @Test
    fun `search prefers the most specific redirect prefix`() {
        val rules = TrieTree().apply {
            add("/storage/emulated/0/DCIM")
            add("/storage/emulated/0")
        }

        assertEquals(
            "/storage/emulated/0/DCIM",
            rules.search("/storage/emulated/0/DCIM/Camera/photo.jpg")
        )
        assertEquals(
            "/storage/emulated/0",
            rules.search("/storage/emulated/0/Android/data/example")
        )
        assertNull(rules.search("/data/user/0/example"))
    }
}
