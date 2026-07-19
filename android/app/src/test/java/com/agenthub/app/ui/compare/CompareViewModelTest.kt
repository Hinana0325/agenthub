package com.agenthub.app.ui.compare

import org.junit.Assert.*
import org.junit.Test

class CompareViewModelTest {
    @Test
    fun `CompareUiState default values`() {
        val state = CompareUiState()
        assertEquals("Agent A", state.agentAName)
        assertEquals("Agent B", state.agentBName)
        assertEquals("", state.agentAResponse)
        assertEquals("", state.agentBResponse)
        assertFalse(state.isComparing)
        assertFalse(state.isAComplete)
        assertFalse(state.isBComplete)
        assertNull(state.error)
        assertFalse(state.isCancelled)
    }

    @Test
    fun `CompareUiState copy updates correctly`() {
        val state = CompareUiState()
        val updated = state.copy(
            agentAName = "GPT-4",
            agentBName = "Claude",
            isComparing = true,
            agentAResponse = "Hello from A"
        )
        assertEquals("GPT-4", updated.agentAName)
        assertEquals("Claude", updated.agentBName)
        assertTrue(updated.isComparing)
        assertEquals("Hello from A", updated.agentAResponse)
        assertFalse(updated.isAComplete)
    }

    @Test
    fun `CompareUiState cancel stops comparing`() {
        val state = CompareUiState(isComparing = true, agentAResponse = "partial")
        val cancelled = state.copy(isComparing = false, isCancelled = true)
        assertFalse(cancelled.isComparing)
        assertTrue(cancelled.isCancelled)
        assertEquals("partial", cancelled.agentAResponse) // response preserved
    }
}
