package dev.colorcode.app

import org.junit.Assert.*
import org.junit.Test

class CaptureMatcherTest {
    @Test fun lateTimedOutJpegCannotCompleteRetry() {
        val matcher = CaptureMatcher<String>()
        val first = matcher.begin(); matcher.started(first, 100); matcher.cancel(first)
        val retry = matcher.begin(); matcher.started(retry, 200)
        assertNull(matcher.frame(100, "stale JPEG"))
        assertTrue(matcher.isPending(retry))
        assertEquals(CaptureMatcher.Match(retry, "retry JPEG"), matcher.frame(200, "retry JPEG"))
    }

    @Test fun imageBeforeMetadataWaitsForMatchingRequestTimestamp() {
        val matcher = CaptureMatcher<String>()
        val request = matcher.begin()
        assertNull(matcher.frame(200, "current JPEG"))
        assertEquals(CaptureMatcher.Match(request, "current JPEG"), matcher.started(request, 200))
        assertNull(matcher.frame(200, "duplicate JPEG"))
    }

    @Test fun staleImageAndMetadataBeforeRetryMetadataAreRejected() {
        val matcher = CaptureMatcher<String>()
        val first = matcher.begin(); matcher.cancel(first)
        val retry = matcher.begin()
        assertNull(matcher.frame(100, "old"))
        assertNull(matcher.started(first, 100))
        assertNull(matcher.started(retry, 200))
        assertEquals(CaptureMatcher.Match(retry, "new"), matcher.frame(200, "new"))
    }

    @Test fun oldFailureOrTimeoutCannotCancelNewRequest() {
        val matcher = CaptureMatcher<String>()
        val first = matcher.begin(); assertTrue(matcher.cancel(first))
        val retry = matcher.begin()
        assertFalse(matcher.cancel(first))
        assertTrue(matcher.isPending(retry))
        assertTrue(matcher.cancel(retry))
        assertNull(matcher.frame(200, "after pause"))
    }

    @Test fun unknownTimestampFailsClosed() {
        val matcher = CaptureMatcher<String>()
        val request = matcher.begin()
        assertNull(matcher.started(request, 0))
        assertNull(matcher.frame(0, "unidentified"))
        assertTrue(matcher.cancel(request))
    }
}
