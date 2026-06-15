package it.allard.multistream.provider.api

import java.net.URLEncoder

/**
 * Pure deep-link URL templates per provider, deliberately free of Android types so the formats
 * (the load-bearing "launch directly" knowledge, verified from each app's manifest) are unit-
 * testable on the JVM. Providers wrap these in package-pinned VIEW intents via [Launcher].
 */
object DeepLinks {
    fun netflixTitle(id: String) = "https://www.netflix.com/title/$id"
    fun netflixTitleScheme(id: String) = "nflx://www.netflix.com/title/$id"
    fun netflixSearch(query: String) = "https://www.netflix.com/search?q=${encode(query)}"

    fun disneyEntity(id: String) = "https://www.disneyplus.com/browse/entity-$id"
    fun disneyScheme(id: String) = "disneyplus://$id"

    fun primeDetail(asin: String) = "https://app.primevideo.com/detail?gti=$asin"

    /** etincelle (alternative Molotov client) deep link to a show by its Fubo kind and id. */
    fun etincelle(kind: String, id: String) = "etincelle://$kind/$id"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
