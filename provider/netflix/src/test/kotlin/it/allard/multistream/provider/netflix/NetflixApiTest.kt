package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetflixApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NetflixApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = NetflixApi(client = buildClient(), homeUrl = server.url("/browse").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_extractsTitlesFromFalcorVideos() = runBlocking {
        val memberApi = server.url("/api/shakti/BUILD").toString().removeSuffix("/")
        server.enqueue(
            MockResponse().setBody(
                "<script>netflix.reactContext = {\"models\":{\"services\":{\"data\":{\"memberapi\":\"$memberApi\"}}," +
                    "\"userInfo\":{\"data\":{\"authURL\":\"AUTH123\"}}," +
                    "\"serverDefs\":{\"data\":{\"BUILD_IDENTIFIER\":\"BUILD\"}}}};</script>",
            ),
        )
        val t = "${'$'}type"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"jsonGraph\":{\"videos\":{" +
                    "\"80057281\":{\"title\":{\"$t\":\"atom\",\"value\":\"Stranger Things\"}," +
                    "\"summary\":{\"$t\":\"atom\",\"value\":{\"type\":\"show\"}}}," +
                    "\"70264888\":{\"title\":{\"$t\":\"atom\",\"value\":\"Black Mirror\"}," +
                    "\"summary\":{\"$t\":\"atom\",\"value\":{\"type\":\"show\"}}}" +
                    "}}}",
            ),
        )

        val results = api.search("stranger", "NetflixId=abc; SecureNetflixId=def", Region("US"))
        assertEquals(2, results.size)
        val st = results.first { it.title == "Stranger Things" }
        assertEquals(MediaType.SERIES, st.type)
        assertEquals("https://www.netflix.com/title/80057281", st.ref.deepLinkHint)

        // session page fetched, then a pathEvaluator POST carrying authURL
        assertTrue(server.takeRequest().path!!.contains("/browse"))
        val post = server.takeRequest()
        assertTrue(post.path!!.contains("/pathEvaluator"))
        assertTrue(post.body.readUtf8().contains("authURL=AUTH123"))
    }

    @Test fun notLoggedIn_throwsAuthError() {
        server.enqueue(MockResponse().setBody("<html>login page without reactContext</html>"))
        try {
            runBlocking { api.search("x", "bad=cookie", Region("US")) }
            throw AssertionError("expected NetflixApiException")
        } catch (e: NetflixApiException) {
            assertTrue(e.authError)
        }
    }
}
