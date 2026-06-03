package it.allard.multistream.provider.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM verification of the deep-link URL formats (verified against each app's manifest). */
class DeepLinksTest {

    @Test fun netflix_formats() {
        assertEquals("https://www.netflix.com/title/80057281", DeepLinks.netflixTitle("80057281"))
        assertEquals("nflx://www.netflix.com/title/80057281", DeepLinks.netflixTitleScheme("80057281"))
        assertEquals("https://www.netflix.com/search?q=the+office", DeepLinks.netflixSearch("the office"))
    }

    @Test fun disney_formats() {
        assertEquals("https://www.disneyplus.com/browse/entity-3jLIGMDYINqD", DeepLinks.disneyEntity("3jLIGMDYINqD"))
        assertEquals("disneyplus://abc123", DeepLinks.disneyScheme("abc123"))
    }

    @Test fun prime_format() {
        assertEquals("https://app.primevideo.com/detail?gti=B0ABCDEFG", DeepLinks.primeDetail("B0ABCDEFG"))
    }
}
