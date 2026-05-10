package io.github.chineseastar.mcpreverse.sse

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseParserTest {

    @Test
    fun `single event with data`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed("event: endpoint")
        parser.feed("data: /message?id=abc123")
        parser.feed("")

        assertEquals(1, events.size)
        assertEquals("endpoint", events[0].first)
        assertEquals("/message?id=abc123", events[0].second)
    }

    @Test
    fun `multi line data`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed("event: message")
        parser.feed("data: line1")
        parser.feed("data: line2")
        parser.feed("data: line3")
        parser.feed("")

        assertEquals(1, events.size)
        assertEquals("message", events[0].first)
        assertEquals("line1\nline2\nline3", events[0].second)
    }

    @Test
    fun `multiple events`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed("event: endpoint")
        parser.feed("data: /msg1")
        parser.feed("")

        parser.feed("event: message")
        parser.feed("data: hello")
        parser.feed("")

        parser.feed("event: message")
        parser.feed("data: world")
        parser.feed("")

        assertEquals(3, events.size)
        assertEquals("endpoint", events[0].first)
        assertEquals("/msg1", events[0].second)
        assertEquals("message", events[1].first)
        assertEquals("hello", events[1].second)
        assertEquals("message", events[2].first)
        assertEquals("world", events[2].second)
    }

    @Test
    fun `default event type is empty string`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed("data: no event type")
        parser.feed("")

        assertEquals(1, events.size)
        assertEquals("", events[0].first)
        assertEquals("no event type", events[0].second)
    }

    @Test
    fun `blank lines with no pending data are ignored`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed("")
        parser.feed("")
        parser.feed("event: test")
        parser.feed("data: after blanks")
        parser.feed("")

        assertEquals(1, events.size)
    }

    @Test
    fun `comment lines are ignored`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed(": this is a comment")
        parser.feed("event: test")
        parser.feed("data: value")
        parser.feed("")

        assertEquals(1, events.size)
        assertEquals("test", events[0].first)
        assertEquals("value", events[0].second)
    }

    @Test
    fun `id and retry fields are ignored`() {
        val events = mutableListOf<Pair<String, String>>()
        val parser = SseParser { event, data -> events.add(event to data) }

        parser.feed("id: 42")
        parser.feed("retry: 10000")
        parser.feed("event: msg")
        parser.feed("data: content")
        parser.feed("")

        assertEquals(1, events.size)
        assertEquals("msg", events[0].first)
        assertEquals("content", events[0].second)
    }
}
