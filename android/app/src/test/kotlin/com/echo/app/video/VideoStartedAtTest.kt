package com.echo.app.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class VideoStartedAtTest {
    private fun t(s: String) = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).parse(s)!!.time

    @Test fun trustsTheGlassesNameWhenItsClockIsRight() {
        val arrived = t("20261016172500000")
        assertEquals(t("20261016172000000"), VideoIngest.startedAt("20261016172000000", arrived))
    }

    @Test fun ignoresAGlassesClockThatIsMonthsOff() {
        val arrived = t("20261016172500000")
        assertEquals(arrived, VideoIngest.startedAt("20260618034452825", arrived))
    }

    @Test fun ignoresATimeInTheFuture() {
        val arrived = t("20261016172500000")
        assertEquals(arrived, VideoIngest.startedAt("20261020100000000", arrived))
    }

    @Test fun noTimeInTheNameUsesArrival() {
        assertEquals(1234L, VideoIngest.startedAt("clip", 1234L))
    }
}
