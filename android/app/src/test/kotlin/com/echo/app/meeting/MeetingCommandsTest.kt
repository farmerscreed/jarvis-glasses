package com.echo.app.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingCommandsTest {
    @Test fun startPhrases() {
        assertTrue(MeetingCommands.isStart("Jarvis, record this meeting"))
        assertTrue(MeetingCommands.isStart("start recording"))
        assertTrue(MeetingCommands.isStart("Jarvis start the meeting recording"))
        assertTrue(MeetingCommands.isStart("record the conversation with Mr Ade"))
        assertFalse(MeetingCommands.isStart("what did we record yesterday?"))
        assertFalse(MeetingCommands.isStart("remember this person"))
    }

    @Test fun stopMarkStatus() {
        assertTrue(MeetingCommands.isStop("Jarvis, stop recording"))
        assertTrue(MeetingCommands.isStop("end the meeting"))
        assertFalse(MeetingCommands.isStop("stop translating"))
        assertTrue(MeetingCommands.isMark("Jarvis, mark that"))
        assertTrue(MeetingCommands.isMark("bookmark this — price agreed"))
        assertFalse(MeetingCommands.isMark("market price of cement"))
        assertTrue(MeetingCommands.isStatus("am I still recording?"))
        assertTrue(MeetingCommands.isStatus("are you recording"))
    }

    @Test fun labelsAndTitles() {
        assertEquals("price agreed", MeetingCommands.markLabel("mark that — price agreed"))
        assertEquals("price agreed", MeetingCommands.markLabel("Jarvis, mark that: price agreed."))
        assertEquals("", MeetingCommands.markLabel("mark that"))
        assertEquals("Meeting with Mr Ade about cement", MeetingCommands.title("Jarvis, record this meeting with Mr Ade about cement"))
        assertEquals("", MeetingCommands.title("record this meeting"))
    }
}
