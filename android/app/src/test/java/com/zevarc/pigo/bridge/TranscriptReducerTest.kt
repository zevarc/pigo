package com.zevarc.pigo.bridge

import com.zevarc.pigo.model.ChatTurn
import com.zevarc.pigo.model.PiUiState
import com.zevarc.pigo.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the bridge event stream. These mirror the Bridge's own
 * normalizer tests, so a protocol drift on either side fails here.
 */
class TranscriptReducerTest {

    private fun reduce(vararg events: String): PiUiState {
        val reducer = TranscriptReducer()
        var state = PiUiState(activeSessionId = "s1")
        for (raw in events) state = reducer.reduce(state, Wire.parse(raw))
        return state
    }

    @Test
    fun `text deltas append into a single streaming turn`() {
        val state = reduce(
            """{"type":"agent_start","sessionId":"s1","seq":1}""",
            """{"type":"assistant_message_start","sessionId":"s1","seq":2,"messageId":"a1"}""",
            """{"type":"message_delta","sessionId":"s1","seq":3,"delta":"Hel"}""",
            """{"type":"message_delta","sessionId":"s1","seq":4,"delta":"lo"}""",
        )
        assertEquals(1, state.turns.size)
        assertEquals("Hello", state.turns.single().content)
        assertTrue(state.turns.single().isStreaming)
        assertTrue(state.isAgentRunning)
    }

    @Test
    fun `assistant_message_end replaces reconstructed text`() {
        val state = reduce(
            """{"type":"assistant_message_start","sessionId":"s1","seq":1,"messageId":"a1"}""",
            """{"type":"message_delta","sessionId":"s1","seq":2,"delta":"par"}""",
            """{"type":"assistant_message_end","sessionId":"s1","seq":3,"messageId":"a1","text":"authoritative"}""",
        )
        assertEquals("authoritative", state.turns.single().content)
        assertFalse(state.turns.single().isStreaming)
    }

    @Test
    fun `agent_end does not stop the run but agent_settled does`() {
        val afterAgentEnd = reduce(
            """{"type":"agent_start","sessionId":"s1","seq":1}""",
            """{"type":"agent_end","sessionId":"s1","seq":2}""",
        )
        assertTrue("agent_end must not clear the running flag", afterAgentEnd.isAgentRunning)

        val settled = reduce(
            """{"type":"agent_start","sessionId":"s1","seq":1}""",
            """{"type":"agent_end","sessionId":"s1","seq":2}""",
            """{"type":"agent_settled","sessionId":"s1","seq":3,"aborted":false}""",
        )
        assertFalse(settled.isAgentRunning)
    }

    @Test
    fun `aborted settle still stops the run`() {
        val state = reduce(
            """{"type":"agent_start","sessionId":"s1","seq":1}""",
            """{"type":"agent_settled","sessionId":"s1","seq":2,"aborted":true}""",
        )
        assertFalse(state.isAgentRunning)
    }

    @Test
    fun `duplicate and out of order seq are dropped`() {
        val state = reduce(
            """{"type":"agent_start","sessionId":"s1","seq":5}""",
            """{"type":"message_delta","sessionId":"s1","seq":5,"delta":"dup"}""",
            """{"type":"message_delta","sessionId":"s1","seq":4,"delta":"older"}""",
        )
        assertTrue(state.turns.isEmpty())
    }

    @Test
    fun `tool lifecycle builds one card and keeps the summary`() {
        val reducer = TranscriptReducer()
        var state = PiUiState(activeSessionId = "s1")
        listOf(
            """{"type":"agent_start","sessionId":"s1","seq":1}""",
            """{"type":"assistant_message_start","sessionId":"s1","seq":2,"messageId":"a1"}""",
            """{"type":"tool_start","sessionId":"s1","seq":3,"toolCallId":"c1","name":"bash","summary":"git status"}""",
            """{"type":"tool_progress","sessionId":"s1","seq":4,"toolCallId":"c1","output":"partial"}""",
            """{"type":"tool_end","sessionId":"s1","seq":5,"toolCallId":"c1","isError":false,"output":"clean","durationMs":12}""",
        ).forEach { state = reducer.reduce(state, Wire.parse(it)) }

        val tool = state.turns.single().toolCalls.single()
        assertEquals("c1", tool.id)
        assertEquals("bash", tool.name)
        assertEquals("git status", tool.summary)
        assertEquals(ToolStatus.OK, tool.status)
        assertEquals("clean", tool.output)
        assertEquals(12L, tool.durationMs)
        assertFalse(tool.isExpanded)
    }

    @Test
    fun `failing tool is marked as error`() {
        val reducer = TranscriptReducer()
        var state = PiUiState(activeSessionId = "s1")
        listOf(
            """{"type":"tool_start","sessionId":"s1","seq":1,"toolCallId":"c1","name":"read","summary":"a.txt"}""",
            """{"type":"tool_end","sessionId":"s1","seq":2,"toolCallId":"c1","isError":true,"output":"ENOENT"}""",
        ).forEach { state = reducer.reduce(state, Wire.parse(it)) }

        assertEquals(ToolStatus.ERROR, state.turns.single().toolCalls.single().status)
    }

    @Test
    fun `sync_response replays missed events in order`() {
        val state = reduce(
            """{"type":"sync_response","sessionId":"s1","latestSeq":3,"isAgentRunning":true,"events":[
                 {"type":"agent_start","sessionId":"s1","seq":1},
                 {"type":"message_delta","sessionId":"s1","seq":2,"delta":"a"},
                 {"type":"message_delta","sessionId":"s1","seq":3,"delta":"b"}
               ]}""",
        )
        assertEquals("ab", state.turns.single().content)
        assertTrue(state.isAgentRunning)
    }

    @Test
    fun `sync_response without full history asks for a reload`() {
        val state = reduce(
            """{"type":"sync_response","sessionId":"s1","latestSeq":900,"needsHistoryReload":true,"events":[]}""",
        )
        assertTrue(state.notices.any { it.contains("滑出窗口") })
    }

    @Test
    fun `events from another session only change its running badge`() {
        val state = reduce(
            """{"type":"sessions_snapshot","sessions":[
                 {"id":"s1","title":"api","cwd":"/p/api","isAgentRunning":false},
                 {"id":"s2","title":"notes","cwd":"/p/notes","isAgentRunning":false}
               ]}""",
            """{"type":"agent_start","sessionId":"s2","seq":1}""",
        )
        assertEquals("s1", state.activeSessionId)
        assertEquals(0, state.turns.size)
        assertTrue(state.sessions.first { it.id == "s2" }.isAgentRunning)
        assertFalse(state.sessions.first { it.id == "s1" }.isAgentRunning)
    }

    @Test
    fun `process exit stops the run and explains itself`() {
        val state = reduce(
            """{"type":"agent_start","sessionId":"s1","seq":1}""",
            """{"type":"session_process_exit","sessionId":"s1","seq":2,"code":null,"signal":"SIGTERM"}""",
        )
        assertFalse(state.isAgentRunning)
        assertTrue(state.notices.any { it.contains("SIGTERM") })
    }

    @Test
    fun `prompt rejection surfaces as an error banner`() {
        val state = reduce(
            """{"type":"error","sessionId":"s1","code":"prompt_rejected","message":"Agent is streaming"}""",
        )
        assertEquals("Agent is streaming", state.errorBanner)
    }

    @Test
    fun `message snapshot rebuilds the transcript`() {
        val state = reduce(
            """{"type":"messages_snapshot","sessionId":"s1","messages":[
                 {"role":"user","content":"q1"},
                 {"role":"assistant","id":"a1","content":[{"type":"text","text":"a1"}]},
                 {"role":"user","content":[{"type":"text","text":"q2"}]}
               ]}""",
        )
        assertEquals(listOf("q1", "a1", "q2"), state.turns.map { it.content })
        assertEquals(
            listOf(ChatTurn.Role.USER, ChatTurn.Role.ASSISTANT, ChatTurn.Role.USER),
            state.turns.map { it.role },
        )
    }

    @Test
    fun `cursor resets when switching sessions`() {
        val reducer = TranscriptReducer()
        var state = PiUiState(activeSessionId = "s1")
        state = reducer.reduce(state, Wire.parse("""{"type":"agent_start","sessionId":"s1","seq":42}"""))
        assertEquals(42L, reducer.cursor())

        reducer.resetCursor()
        state = state.copy(activeSessionId = "s2", turns = emptyList())
        state = reducer.reduce(state, Wire.parse("""{"type":"agent_start","sessionId":"s2","seq":1}"""))
        assertTrue(state.isAgentRunning)
        assertEquals(1L, reducer.cursor())
    }

    @Test
    fun `an extension dialog becomes a pending ui request`() {
        val state = reduce(
            """{"type":"ui_request","sessionId":"s1","requestId":"req-1","method":"select","title":"Pick","options":["a","b"]}""",
        )
        val request = state.uiRequest!!
        assertEquals("req-1", request.requestId)
        assertEquals("select", request.method)
        assertEquals(listOf("a", "b"), request.options)
    }

    @Test
    fun `input dialog keeps placeholder, editor keeps prefill`() {
        val input = reduce(
            """{"type":"ui_request","sessionId":"s1","requestId":"r","method":"input","title":"Password","placeholder":"min 4 chars"}""",
        ).uiRequest!!
        assertEquals("min 4 chars", input.placeholder)

        val editor = reduce(
            """{"type":"ui_request","sessionId":"s1","requestId":"r","method":"editor","title":"Edit","prefill":"draft"}""",
        ).uiRequest!!
        assertEquals("draft", editor.prefill)
    }

    @Test
    fun `commands snapshot feeds the slash palette`() {
        val state = reduce(
            """{"type":"commands_snapshot","sessionId":"s1","commands":[
                 {"name":"model","description":"Switch model","source":"extension"},
                 {"name":"skill:drug-tip-card","source":"skill"}
               ]}""",
        )
        assertEquals(listOf("model", "skill:drug-tip-card"), state.commands.map { it.name })
        assertEquals("Switch model", state.commands.first().description)
    }

    @Test
    fun `extension status and widgets are tracked by key`() {
        val state = reduce(
            """{"type":"status","sessionId":"s1","statusKey":"ext","statusText":"working"}""",
            """{"type":"widget","sessionId":"s1","widgetKey":"w","widgetLines":["line1"]}""",
        )
        assertEquals("working", state.extensionStatus["ext"])
        assertEquals(listOf("line1"), state.widgets["w"])
    }

    @Test
    fun `clearing a status or widget removes the entry`() {
        val state = reduce(
            """{"type":"status","sessionId":"s1","statusKey":"ext","statusText":"working"}""",
            """{"type":"status","sessionId":"s1","statusKey":"ext"}""",
            """{"type":"widget","sessionId":"s1","widgetKey":"w","widgetLines":["a"]}""",
            """{"type":"widget","sessionId":"s1","widgetKey":"w"}""",
        )
        assertTrue(state.extensionStatus.isEmpty())
        assertTrue(state.widgets.isEmpty())
    }

    @Test
    fun `extension notify surfaces as a notice`() {
        val state = reduce(
            """{"type":"notice","sessionId":"s1","kind":"extension_notify","message":"No stored search results"}""",
        )
        assertTrue(state.notices.any { it.contains("No stored search results") })
    }

    @Test
    fun `editor_text prefills the composer`() {
        val state = reduce("""{"type":"editor_text","sessionId":"s1","text":"/model "}""")
        assertEquals("/model ", state.inputText)
    }

    @Test
    fun `resume list carries everything a picker row needs`() {
        val state = reduce(
            """{"type":"pi_sessions_snapshot","sessions":[
                 {"id":"01a215bb","cwd":"/Users/me/Labs/pigo","name":null,"preview":"fix the flaky test","updatedAt":1733234401000,"sizeBytes":5033164},
                 {"id":"01a20692","cwd":"/Users/me/StudioProjects/ZevBond","name":"zevbond","preview":"/init","updatedAt":1733234402000,"sizeBytes":1024}
               ]}""",
        )
        assertEquals(2, state.resumeEntries.size)
        val first = state.resumeEntries.first()
        assertEquals("01a215bb", first.id)
        assertEquals("/Users/me/Labs/pigo", first.cwd)
        assertEquals("fix the flaky test", first.preview)
        assertNull(first.name)
        assertEquals("zevbond", state.resumeEntries.last().name)
    }

    @Test
    fun `a late sessions snapshot must not switch the active session`() {
        val reducer = TranscriptReducer()
        var state = PiUiState()
        // Connected, a session became active via resume.
        state = reducer.reduce(
            state,
            Wire.parse("""{"type":"session_created","session":{"id":"new","title":"pigo","cwd":"/w"}}"""),
        )
        assertEquals("new", state.activeSessionId)

        // A snapshot produced before the resume lands afterwards and does not
        // list that session yet: the old code fell back to the first id.
        state = reducer.reduce(
            state,
            Wire.parse("""{"type":"sessions_snapshot","sessions":[{"id":"other","title":"x","cwd":"/o"}]}"""),
        )
        assertEquals("new", state.activeSessionId)
    }

    @Test
    fun `a sessions snapshot keeps the transcript`() {
        val reducer = TranscriptReducer()
        var state = PiUiState(activeSessionId = "s1")
        state = reducer.reduce(
            state,
            Wire.parse("""{"type":"messages_snapshot","sessionId":"s1","messages":[{"role":"user","content":"hi"}]}"""),
        )
        assertEquals(1, state.turns.size)
        state = reducer.reduce(
            state,
            Wire.parse("""{"type":"sessions_snapshot","sessions":[{"id":"s1","title":"t","cwd":"/w","isAgentRunning":true}]}"""),
        )
        // Refreshing the list must not blank the transcript.
        assertEquals(1, state.turns.size)
        assertTrue(state.isAgentRunning)
    }

    @Test
    fun `unknown event types are ignored`() {
        val state = reduce("""{"type":"brand_new_thing","sessionId":"s1","seq":1}""")
        assertNull(state.errorBanner)
        assertEquals(0, state.turns.size)
    }
}
