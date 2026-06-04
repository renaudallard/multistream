package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.Region
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
        api = NetflixApi(homeUrl = server.url("/browse").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_extractsTitlesFromFalcorVideos() = runBlocking {
        val url = server.url("/api/shakti/BUILD")
        val hostPort = "${url.host}:${url.port}"
        // memberapi is an object {protocol,hostname,path[]} with Netflix's \xHH escapes in the path.
        server.enqueue(
            MockResponse().setBody(
                "<script>netflix.reactContext = {\"models\":{\"services\":{\"data\":{\"memberapi\":" +
                    "{\"protocol\":\"${url.scheme}\",\"hostname\":\"$hostPort\",\"path\":[\"\\x2Fapi\\x2Fshakti\\x2FBUILD\"]}}}," +
                    "\"userInfo\":{\"data\":{\"authURL\":\"AUTH123\",\"userGuid\":\"GUID42\"}}," +
                    "\"serverDefs\":{\"data\":{\"BUILD_IDENTIFIER\":\"BUILD\"}}}};</script>",
            ),
        )
        val t = "${'$'}type"
        // Matches the live shape: byTerm -> titles ref -> byReference list -> reference -> videos[id].
        // videos also contains an unrelated home suggestion (99999999) that must be ignored.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"jsonGraph\":{" +
                    "\"search\":{" +
                    "\"byTerm\":{\"|stranger\":{\"titles\":{\"47\":{\"$t\":\"ref\",\"value\":[\"search\",\"byReference\",\"KEY\"]}}}}," +
                    "\"byReference\":{\"KEY\":{" +
                    "\"0\":{\"reference\":{\"$t\":\"ref\",\"value\":[\"videos\",\"80057281\"]}}," +
                    "\"1\":{\"reference\":{\"$t\":\"ref\",\"value\":[\"videos\",\"70264888\"]}}}}}," +
                    "\"videos\":{" +
                    "\"80057281\":{\"title\":{\"$t\":\"atom\",\"value\":\"Stranger Things\"}," +
                    "\"summary\":{\"$t\":\"atom\",\"value\":{\"type\":\"show\"}}}," +
                    "\"70264888\":{\"title\":{\"$t\":\"atom\",\"value\":\"Black Mirror\"}," +
                    "\"summary\":{\"$t\":\"atom\",\"value\":{\"type\":\"show\"}}}," +
                    "\"99999999\":{\"title\":{\"$t\":\"atom\",\"value\":\"Home Suggestion\"}," +
                    "\"summary\":{\"$t\":\"atom\",\"value\":{\"type\":\"movie\"}}}" +
                    "}}}",
            ),
        )

        // "Black Mirror" is one of Netflix's themed suggestions; it doesn't contain the query and is
        // filtered out, leaving only the real match.
        val results = api.search("stranger", "NetflixId=abc; SecureNetflixId=def", Region("US"))
        assertEquals(1, results.size)
        val st = results.first { it.title == "Stranger Things" }
        assertEquals(MediaType.SERIES, st.type)
        assertEquals("https://www.netflix.com/title/80057281", st.ref.deepLinkHint)

        // session page fetched, then a pathEvaluator POST carrying authURL + the reference path
        assertTrue(server.takeRequest().path!!.contains("/browse"))
        val post = server.takeRequest()
        assertTrue(post.path!!.contains("/pathEvaluator"))
        assertEquals("GUID42", post.getHeader("x-netflix.request.client.user.guid"))
        val postBody = post.body.readUtf8()
        assertTrue(postBody.contains("authURL=AUTH123"))
        assertTrue(postBody.contains("\"|stranger\",\"titles\""))
        assertTrue(postBody.contains("\"reference\",[\"summary\",\"title\"]"))
    }

    @Test fun getSeasons_parsesMetadata() = runBlocking {
        val memberApi = server.url("/api/shakti/BUILD").toString().removeSuffix("/")
        server.enqueue(
            MockResponse().setBody(
                "<script>netflix.reactContext = {\"models\":{\"services\":{\"data\":{\"memberapi\":\"$memberApi\"}}," +
                    "\"userInfo\":{\"data\":{\"authURL\":\"A\"}}}};</script>",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"video":{"seasons":[{"seq":1,"episodes":[
                   {"seq":1,"title":"Pilot","runtime":1800},{"seq":2,"title":"Two","runtime":1800}
                ]}]}}""".trimIndent(),
            ),
        )
        val seasons = api.getSeasons("80057281", "NetflixId=x")
        assertEquals(1, seasons.size)
        assertEquals(2, seasons[0].episodes.size)
        assertEquals("Pilot", seasons[0].episodes[0].title)
        assertEquals(30, seasons[0].episodes[0].runtimeMin)
        assertTrue(server.takeRequest().path!!.contains("/browse"))
        assertTrue(server.takeRequest().path!!.contains("/metadata?movieid=80057281"))
    }

    @Test fun notLoggedIn_throwsAuthError() {
        // The session refresh re-fetches /browse once on the auth error, so both attempts see login.
        repeat(2) { server.enqueue(MockResponse().setBody("<html>login page without reactContext</html>")) }
        try {
            runBlocking { api.search("x", "bad=cookie", Region("US")) }
            throw AssertionError("expected NetflixApiException")
        } catch (e: NetflixApiException) {
            assertTrue(e.authError)
        }
    }
}
