package io.github.mam15mon.dsh.feature.conversation

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStateTest {
    @Test fun editorTextIsSharedWithDraftConsumers() {
        val composer = ComposerState()
        composer.editor.edit {
            append("ab😀c")
            selection = TextRange(2, 4)
        }

        assertEquals("ab😀c", composer.text)
        composer.text = "ab😀cd"
        assertEquals(TextRange(2, 4), composer.editor.selection)

        composer.clear()
        assertTrue(composer.isEmpty)
        assertEquals(TextRange.Zero, composer.editor.selection)
    }

    @Test fun replacingDraftClampsTheSelectionToTheNewText() {
        val composer = ComposerState()
        composer.text = "hello"
        composer.editor.edit { selection = TextRange(5, 1) }

        composer.text = "hi"

        assertEquals("hi", composer.editor.text.toString())
        assertEquals(TextRange(2, 1), composer.editor.selection)
    }
}
