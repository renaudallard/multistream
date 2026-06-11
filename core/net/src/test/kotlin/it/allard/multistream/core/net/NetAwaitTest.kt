package it.allard.multistream.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class NetAwaitTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun readsBodyBuffered() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val response = buildClient().await(Request.Builder().url(server.url("/")).build())
        assertEquals("hello", response.body?.string())
    }

    @Test fun rejectsOversizedBody() = runBlocking {
        // One byte past the 32 MB cap so readCapped() must reject it instead of buffering it all.
        server.enqueue(MockResponse().setBody(Buffer().apply { write(ByteArray(33 * 1024 * 1024)) }))
        try {
            buildClient().await(Request.Builder().url(server.url("/")).build())
            fail("expected the body cap to reject the oversized response")
        } catch (e: IOException) {
            // expected
        }
        Unit
    }
}
