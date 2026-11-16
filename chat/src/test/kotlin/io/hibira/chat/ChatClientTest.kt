package io.hibira.chat

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

fun jwt(sub: String, exp: Long = System.currentTimeMillis() / 1000 + 3600): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
        enc.encodeToString("""{"sub":"$sub","exp":$exp}""".toByteArray()) + ".sig"
}

class ChatClientTest {
    private lateinit var server: MockWebServer
    private var tokens = 0
    private lateinit var client: ChatClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ChatClient("app-1", { tokens++; jwt("ann") }, server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun readsTheUserFromTheToken() {
        assertEquals("ann", ChatClient.tokenUser(jwt("ann")))
        assertEquals("", ChatClient.tokenUser("nope"))
    }

    @Test
    fun sendsSignedRequestsToTheUserRoutes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"message":{"id":"m1","seq":4,"sender":"ann","kind":"text","text":"hi","reactions":{"👍":["bob"]},"created_at":"t"},"push":{"users":1}}"""
        ))
        val res = client.send("trip 1", "hi", metadata = mapOf("reply_to" to 2))
        assertEquals(4L, res.message.seq)
        assertEquals(listOf("bob"), res.message.reactions["👍"])
        assertEquals(1, res.pushedUsers)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/apps/app-1/chat/me/conversations/trip%201/messages", req.path)
        assertTrue(req.getHeader("Authorization")!!.startsWith("Bearer ey"))
        val body = JsonValues.parse(req.body.readUtf8()).asMap()
        assertEquals("hi", body["text"])
        assertEquals(2L, body["metadata"].asMap()["reply_to"])
        assertEquals("ann", client.userId)
    }

    @Test
    fun fetchesANewTokenAfterA401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"messages":[{"id":"a","seq":1,"text":"x"}],"last_seq":1}"""))
        val page = client.messages("c", last = 10)
        assertEquals(1, page.messages.size)
        assertEquals(2, tokens)
        assertEquals("/apps/app-1/chat/me/conversations/c/messages?last=10", server.takeRequest().path)
    }

    @Test
    fun throwsTheApiError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"you are muted"}"""))
        val e = runCatching { client.typing("c") }.exceptionOrNull() as ChatException
        assertEquals(403, e.status)
        assertEquals("you are muted", e.message)
    }

    @Test
    fun parsesEvents() = runBlocking {
        val got = async { withTimeout(2000) { client.events.first() } }
        kotlinx.coroutines.yield()
        Thread.sleep(50)
        assertTrue(client.handleEvent("chat.read", """{"conversation_id":"c","user_id":"bob","read_seq":7}"""))
        assertEquals(ChatEvent.Read("c", "bob", 7), got.await())
        assertTrue(!client.handleEvent("other", "{}"))
    }

    @Test
    fun uploadsStraightToStorage() = runBlocking {
        val uploadUrl = server.url("/bucket/f1").toString()
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"attachment":{"id":"f1","name":"a.png","content_type":"image/png","size":3},"upload":{"url":"$uploadUrl","method":"PUT","headers":{"x-amz-acl":"private"}}}"""
        ))
        server.enqueue(MockResponse().setResponseCode(200))
        val a = client.upload("c", "a.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals("f1", a.id)
        val ask = JsonValues.parse(server.takeRequest().body.readUtf8()).asMap()
        assertEquals(3L, ask["size"])
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("private", put.getHeader("x-amz-acl"))
        assertEquals(3L, put.bodySize)
    }
}
