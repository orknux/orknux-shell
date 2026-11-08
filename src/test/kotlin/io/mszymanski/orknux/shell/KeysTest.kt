package io.mszymanski.orknux.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeysTest {
    @Test
    fun `a Ctrl letter is the byte it arrives as, and back`() {
        assertEquals(15, Keys.code("ctrl+o"))
        assertEquals(19, Keys.code("Ctrl+S"))
        assertEquals("ctrl+t", Keys.specOf(20))
        assertEquals("Ctrl+S", Keys.label("ctrl+s"))
    }

    @Test
    fun `what the terminal or the line already means is refused, and so is anything but Ctrl and a letter`() {
        listOf("ctrl+c", "ctrl+d", "ctrl+m", "ctrl+q", "ctrl+l", "ctrl+z").forEach { assertNull(Keys.code(it), it) }
        listOf("alt+enter", "f5", "s", "ctrl+1", "ctrl+shift+s").forEach { assertNull(Keys.code(it), it) }
        assertNull(Keys.specOf(27))
    }

    @Test
    fun `a shortcut set is used, one set to something unusable falls back to the default`() {
        assertEquals("ctrl+s", Keys.specFor("send-now", emptyMap()))
        assertEquals("ctrl+g", Keys.specFor("send-now", mapOf("send-now" to "ctrl+g")))
        assertEquals("ctrl+s", Keys.specFor("send-now", mapOf("send-now" to "alt+enter")))
    }
}
