package io.hibira.chat

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChatSocketTest {
    @Test
    fun signsInSubscribesAndForwardsChatEvents() = runBlocking {
        val server = MockWebServer()
        val received = LinkedBlockingQueue<String>()
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                webSocket.send("""{"event":"pusher:connection_established","data":"{\"socket_id\":\"1.2\",\"activity_timeout\":120}"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val msg = JsonValues.parse(text).asMap()
                when (msg["event"]) {
                    "pusher:signin" -> webSocket.send("""{"event":"pusher:signin_success","data":"{\"user_data\":\"{\\\"id\\\":\\\"ann\\\"}\"}"}""")
                    "pusher:subscribe" -> webSocket.send("""{"event":"pusher_internal:subscription_succeeded","channel":"#server-to-user-ann","data":"{}"}""")
                }
            }
        }))
        server.start()
        val client = ChatClient("a", { jwt("ann") }, server.url("/").toString())
        val auths = mutableListOf<String>()
        val socket = ChatSocket("key1", client, { sid -> auths += sid; UserAuth("key1:sig", """{"id":"ann"}""") },
            host = "ws://${server.hostName}:${server.port}")
        val live = async { withTimeout(3000) { client.live.first() } }
        delay(50)
        socket.connect()
        live.await()
        assertEquals(ChatSocket.State.LIVE, socket.state.value)
        assertEquals(listOf("1.2"), auths)
        val signin = JsonValues.parse(received.poll(1, TimeUnit.SECONDS)!!).asMap()
        assertEquals("pusher:signin", signin["event"])
        assertEquals("key1:sig", signin["data"].asMap()["auth"])
        val sub = JsonValues.parse(received.poll(1, TimeUnit.SECONDS)!!).asMap()
        assertEquals("#server-to-user-ann", sub["data"].asMap()["channel"])
        assertEquals("ann", socket.user)

        val event = async { withTimeout(3000) { client.events.first() } }
        delay(50)
        serverSocket!!.send("""{"event":"chat.typing","channel":"#server-to-user-ann","data":"{\"conversation_id\":\"c\",\"user_id\":\"bob\",\"typing\":true}"}""")
        assertEquals(ChatEvent.Typing("c", "bob", true), event.await())
        socket.close()
        runCatching { server.shutdown() }
        Unit
    }
}
