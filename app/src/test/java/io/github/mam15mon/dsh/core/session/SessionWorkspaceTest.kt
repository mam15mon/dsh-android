package io.github.mam15mon.dsh.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionWorkspaceTest {
    private val home = "/data/user/0/com.dsh.android/files/home"

    @Test fun `resolve falls back to app home whenever root is disabled`() {
        assertEquals(home, SessionWorkspaces.resolve("/data/media/0/proj", "/data/media/0/other", rootEnabled = false, appHome = home))
        assertEquals(home, SessionWorkspaces.resolve(null, "/data/media/0/other", rootEnabled = false, appHome = home))
    }

    @Test fun `resolve honors explicit session workspace over global default`() {
        assertEquals("/data/media/0/a", SessionWorkspaces.resolve("/data/media/0/a", "/data/media/0/b", rootEnabled = true, appHome = home))
        assertEquals(home, SessionWorkspaces.resolve("", "/data/media/0/b", rootEnabled = true, appHome = home))
    }

    @Test fun `resolve follows global default when session workspace is unset`() {
        assertEquals("/data/media/0/b", SessionWorkspaces.resolve(null, "/data/media/0/b", rootEnabled = true, appHome = home))
        assertEquals(home, SessionWorkspaces.resolve(null, "", rootEnabled = true, appHome = home))
    }

    @Test fun `initial preserves explicit pick or snapshots active root project`() {
        assertEquals("", SessionWorkspaces.initial("", "/data/media/0/b", rootEnabled = true))
        assertEquals("/data/media/0/a", SessionWorkspaces.initial("/data/media/0/a", "/data/media/0/b", rootEnabled = true))
        assertEquals("/data/media/0/b", SessionWorkspaces.initial(null, "/data/media/0/b", rootEnabled = true))
        assertNull(SessionWorkspaces.initial(null, "", rootEnabled = true))
        assertNull(SessionWorkspaces.initial(null, "/data/media/0/b", rootEnabled = false))
    }
}
