package it.allard.multistream.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val release =
        """{"tag_name":"v9.9.9","assets":[{"name":"multistream.apk",
           "browser_download_url":"https://github.com/x/multistream.apk"}]}"""

    @Test fun failedCheckRetriesAndRecoversOnNextRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody(release))
            val checker = UpdateChecker("0.2.6", server.url("/latest").toString())
            checker.refresh()
            assertNull(checker.update.value)
            checker.refresh()
            assertEquals("9.9.9", checker.update.value?.version)
        }
    }

    @Test fun sendsEtagAndAcceptsNotModified() = runBlocking {
        MockWebServer().use { server ->
            // First check: current release, no update; second check: 304 against the stored ETag.
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.2.6","assets":[]}""").setHeader("ETag", "\"abc\""))
            server.enqueue(MockResponse().setResponseCode(304))
            val checker = UpdateChecker("0.2.6", server.url("/latest").toString())
            checker.refresh()
            checker.refresh()
            assertNull(checker.update.value)
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun foundUpdateStopsFurtherChecks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release))
            val checker = UpdateChecker("0.2.6", server.url("/latest").toString())
            checker.refresh()
            checker.refresh()
            assertEquals("9.9.9", checker.update.value?.version)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun newerPatchIsAnUpdate() = assertTrue(isNewer("0.2.2", "0.2.1"))

    @Test fun newerMinorIsAnUpdate() = assertTrue(isNewer("0.3.0", "0.2.9"))

    @Test fun sameVersionIsNotAnUpdate() = assertFalse(isNewer("0.2.1", "0.2.1"))

    @Test fun olderVersionIsNotAnUpdate() = assertFalse(isNewer("0.2.0", "0.2.1"))

    @Test fun leadingVTagIsIgnored() = assertTrue(isNewer("v0.2.2", "0.2.1"))

    @Test fun numericSegmentsCompareAsNumbers() = assertTrue(isNewer("0.10.0", "0.9.0"))

    @Test fun unequalLengthShorterIsNotNewer() = assertFalse(isNewer("0.2", "0.2.1"))

    @Test fun unequalLengthLongerCanBeNewer() = assertTrue(isNewer("0.2.1", "0.2"))
}
