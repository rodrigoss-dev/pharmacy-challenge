package pharmacy.infra.api

import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.stream.Stream

/** A Server-Sent Events stream of the app under test, as lines. Close it to hang up. */
fun sse(baseUrl: URL, path: String): Stream<String> = HttpClient.newHttpClient()
    .send(HttpRequest.newBuilder(URI.create("$baseUrl".trimEnd('/') + path)).build(), HttpResponse.BodyHandlers.ofLines())
    .body()

/** Reads the stream up to the first event that carries data, then hangs up. */
fun firstEvent(baseUrl: URL, path: String): Map<String, String> = sse(baseUrl, path).use { lines ->
    lines.iterator().asSequence().chunkedByBlankLines().map { it.toEvent() }.first { "data" in it }
}

/** The events with data in a stream that ends. */
fun List<String>.toEvents() = asSequence().chunkedByBlankLines().map { it.toEvent() }.filter { "data" in it }.toList()

private fun Sequence<String>.chunkedByBlankLines(): Sequence<List<String>> = sequence {
    val event = mutableListOf<String>()
    for (line in this@chunkedByBlankLines) {
        if (line.isEmpty()) {
            if (event.isNotEmpty()) yield(event.toList())
            event.clear()
        } else {
            event += line
        }
    }
    if (event.isNotEmpty()) yield(event.toList())
}

/** `event: board` and `data: {...}` lines; comments (heartbeats) start with a colon and are left out. */
private fun List<String>.toEvent(): Map<String, String> =
    filterNot { it.startsWith(":") }.associate { it.substringBefore(':') to it.substringAfter(':').trimStart() }
