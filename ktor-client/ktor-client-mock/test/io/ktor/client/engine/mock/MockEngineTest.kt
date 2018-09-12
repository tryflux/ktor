package io.ktor.client.engine.mock

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import kotlinx.coroutines.experimental.*
import org.junit.Test
import kotlin.test.*

class MockEngineTest {
    @Test
    fun testBasic() = testBlocking {
        val client = HttpClient(MockEngine {
            if (url.toString().endsWith("/fail")) {
                responseError(HttpStatusCode.BadRequest)
            } else {
                responseOk("$url")
            }
        })

        client.call { url("http://127.0.0.1/normal-request") }.apply {
            assertEquals("http://127.0.0.1/normal-request", response.readText())
            assertEquals(HttpStatusCode.OK, response.status)
        }

        client.call { url("http://127.0.0.1/fail") }.apply {
            assertEquals("Bad Request", response.readText())
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    private fun testBlocking(callback: suspend () -> Unit): Unit = run { runBlocking { callback() } }
}
