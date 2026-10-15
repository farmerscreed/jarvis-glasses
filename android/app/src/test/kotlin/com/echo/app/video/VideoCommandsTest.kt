package com.echo.app.video

import com.echo.app.meeting.MeetingCommands
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCommandsTest {
    @Test fun startVideo() {
        assertTrue(VideoCommands.isStart("Jarvis, record a video"))
        assertTrue(VideoCommands.isStart("take a video of this"))
        assertTrue(VideoCommands.isStart("start video recording"))
        assertTrue(VideoCommands.isStart("Jarvis video this"))
        assertFalse(VideoCommands.isStart("stop the video"))
        assertFalse(VideoCommands.isStart("what's in this video?"))
        assertFalse(VideoCommands.isStart("record this meeting"))
    }

    @Test fun stopVideo() {
        assertTrue(VideoCommands.isStop("Jarvis, stop the video"))
        assertTrue(VideoCommands.isStop("end video"))
        assertFalse(VideoCommands.isStop("stop recording")) // a meeting command, handled there
    }

    @Test fun walks() {
        assertTrue(VideoCommands.isStartWalk("Jarvis, start a site walk"))
        assertTrue(VideoCommands.isStartWalk("begin the farm walk"))
        assertTrue(VideoCommands.isStopWalk("stop the walk"))
        assertTrue(VideoCommands.isStopWalk("Jarvis end my site walk"))
        assertFalse(VideoCommands.isStartWalk("I walked to the site"))
    }

    @Test fun meetingPhrasesDontClash() {
        // Video phrases must not start or stop a meeting recording, and vice versa.
        assertFalse(MeetingCommands.isStart("Jarvis, record a video"))
        assertFalse(MeetingCommands.isStart("start a site walk"))
        assertFalse(MeetingCommands.isStop("stop the video"))
        assertFalse(VideoCommands.isStart("record this meeting with Mr Ade"))
    }
}
