package io.hibira.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun msg(seq: Int, sender: String = "bob", text: String = "m$seq") =
    """{"id":"id$seq","seq":$seq,"sender":"$sender","kind":"text","text":"$text","created_at":"t$seq"}"""

class ConversationControllerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ChatClient
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/apps/a/chat/me")
                synchronized(paths) { paths += "${request.method} $path" }
                return when {
                    path == "/conversations/c" -> MockResponse().setBody(
                        """{"conversation":{"id":"c","name":"Trip","members":[{"user_id":"ann","read_seq":3},{"user_id":"bob","read_seq":5}],"last_seq":120}}"""
                    )
                    path == "/conversations/c/messages?last=50" -> MockResponse().setBody(
                        """{"messages":[${(71..120).joinToString(",") { msg(it) }}],"last_seq":120}"""
                    )
                    path == "/conversations/c/messages?after=20&limit=50" -> MockResponse().setBody(
                        """{"messages":[${(21..70).joinToString(",") { msg(it) }}],"last_seq":120}"""
                    )
                    path == "/conversations/c/messages?after=0&limit=20" -> MockResponse().setBody(
                        """{"messages":[${(1..20).joinToString(",") { msg(it) }}],"last_seq":120}"""
                    )
                    path.startsWith("/conversations/c/messages?after=121") -> MockResponse().setBody(
                        """{"messages":[${msg(121)}],"last_seq":121}"""
                    )
                    path.startsWith("/conversations/c/messages?last=") -> MockResponse().setBody(
                        """{"messages":[${msg(120, text = "edited")}, ${msg(121)}],"last_seq":121}"""
                    )
                    path == "/conversations/c/read" -> MockResponse().setBody("""{"read_seq":121}""")
                    path == "/conversations/c/messages" && request.method == "POST" -> MockResponse().setResponseCode(201).setBody(
                        """{"message":${msg(122, "ann", "hello")},"push":{"users":0}}"""
                    )
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        server.start()
        client = ChatClient("a", { jwt("ann") }, server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun until(check: () -> Boolean) = withTimeout(3000) {
        while (!check()) delay(10)
    }

    @Test
    fun loadsPagesLiveEventsAndCatchesUp() = runBlocking {
        val c = client.conversationController("c")
        c.start()
        until { !c.state.value.loading }
        assertEquals(50, c.state.value.messages.size)
        assertTrue(c.state.value.hasOlder)
        assertEquals(5L, c.state.value.reads["bob"])

        c.loadOlder()
        assertEquals(100, c.state.value.messages.size)
        c.loadOlder()
        assertEquals(120, c.state.value.messages.size)
        assertFalse(c.state.value.hasOlder)
        assertEquals((1L..120L).toList(), c.state.value.messages.map { it.seq })

        client.handleEvent("chat.typing", """{"conversation_id":"c","user_id":"bob","typing":true}""")
        until { "bob" in c.state.value.typing }
        client.handleEvent("chat.message", """{"conversation_id":"c","message":${msg(121)}}""")
        until { c.state.value.lastSeq == 121L }
        assertFalse("bob" in c.state.value.typing)
        client.handleEvent("chat.message", """{"conversation_id":"other","message":${msg(500)}}""")
        client.handleEvent("chat.read", """{"conversation_id":"c","user_id":"bob","read_seq":121}""")
        until { c.state.value.reads["bob"] == 121L }
        assertEquals(listOf("bob"), c.state.value.readBy(121, "ann"))

        client.notifyLive()
        until { c.state.value.messages.first { it.seq == 120L }.text == "edited" }
        assertTrue(paths.any { it.startsWith("GET /conversations/c/messages?after=121") })

        c.markRead()
        assertEquals(121L, c.state.value.reads["ann"])
        val sent = c.send("hello")
        assertEquals(122L, sent.seq)
        assertEquals(122L, c.state.value.lastSeq)

        client.handleEvent("chat.left", """{"conversation_id":"c"}""")
        until { c.state.value.closed }
        c.close()
    }
}
