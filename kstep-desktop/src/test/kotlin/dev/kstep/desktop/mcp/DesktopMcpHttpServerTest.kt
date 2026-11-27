package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val ONE_MIB = 1 shl 20

/** A running server on an ephemeral port with its own token store. */
private class Fx(
    val host: FakeHost = FakeHost(snap()),
    dir: Path,
) : AutoCloseable {
    val tokens = McpTokenStore(dir)
    val token = tokens.getOrCreate()

    @Volatile var current: McpToken? = token
    val activity = McpActivityTracker()
    val server = DesktopMcpHttpServer(McpProtocol(McpToolRegistry(host), host.appVersion), { current }, activity)
    val port = server.start(0)
    val t: String get() = token.value

    fun call(
        body: String,
        extra: List<String> = emptyList(),
    ) = Raw.mcp(port, body, t, extra = extra)

    override fun close() = server.stop()
}

private fun <T> withServer(
    host: FakeHost = FakeHost(snap()),
    block: (Fx) -> T,
): T = withDir { dir -> Fx(host, dir).use(block) }

private fun JsonObject.result() = getValue("result").jsonObject

private fun JsonObject.errorCode() =
    getValue("error")
        .jsonObject
        .getValue("code")
        .jsonPrimitive.content
        .toInt()

private fun chunked(payload: ByteArray): ByteArray =
    (Integer.toHexString(payload.size) + "\r\n").toByteArray() + payload + "\r\n0\r\n\r\n".toByteArray()

class DesktopMcpHttpServerTest :
    StringSpec({
        McpHttpDefaults.apply()
        val ping = rpc("ping")

        "the happy path: initialize, notification, ping, tools/list and both tools" {
            withServer { fx ->
                val init =
                    fx.call(
                        rpc("initialize", params = """{"protocolVersion":"2025-06-18","capabilities":{}}"""),
                    )
                init.status shouldBe 200
                init.header("content-type") shouldBe "application/json"
                val result = init.json().result()
                result.getValue("protocolVersion").jsonPrimitive.content shouldBe "2025-06-18"
                result
                    .getValue("serverInfo")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content shouldBe "kstep-desktop"
                result
                    .getValue("serverInfo")
                    .jsonObject
                    .getValue("version")
                    .jsonPrimitive.content shouldBe "9.9.9-test"
                val old = fx.call(rpc("initialize", params = """{"protocolVersion":"1999-01-01"}"""))
                old
                    .json()
                    .result()
                    .getValue("protocolVersion")
                    .jsonPrimitive.content shouldBe "2025-03-26"

                val note = fx.call("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
                note.status shouldBe 202
                note.body shouldBe ""

                fx.call(ping).json().result() shouldBe JsonObject(emptyMap())
                val list =
                    fx
                        .call(rpc("tools/list"))
                        .json()
                        .result()
                        .getValue("tools")
                        .jsonArray
                list
                    .map {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content
                    }.shouldContainExactly(
                        "kstep_desktop_get_status",
                        "kstep_desktop_get_script",
                        "kstep_desktop_get_errors",
                        "kstep_desktop_get_parts",
                        "kstep_desktop_get_assembly_tree",
                        "kstep_desktop_get_selection",
                        "kstep_desktop_get_view",
                        "kstep_desktop_find_in_script",
                    )

                val status =
                    fx.call(rpc("tools/call", params = """{"name":"kstep_desktop_get_status","arguments":{}}"""))
                status.status shouldBe 200
                val text =
                    status
                        .json()
                        .result()
                        .getValue("content")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("text")
                text.jsonPrimitive.content shouldContain "\"run\":\"ok\""
                val script =
                    fx.call(
                        rpc("tools/call", params = """{"name":"kstep_desktop_get_script"}"""),
                        extra = listOf("MCP-Protocol-Version: 2025-06-18"),
                    )
                val structured =
                    script
                        .json()
                        .result()
                        .getValue("structuredContent")
                        .jsonObject
                structured.getValue("text").jsonPrimitive.content shouldBe "box(1, 1, 1)\n"
            }
        }

        "structuredContent only comes with the newer protocol version" {
            withServer { fx ->
                val plain = fx.call(rpc("tools/call", params = """{"name":"kstep_desktop_get_script"}"""))
                plain.json().result().containsKey("structuredContent") shouldBe false
            }
        }

        "bad tokens, another scheme and two headers give 401 and the body is never opened" {
            withServer { fx ->
                val wrong = "A".repeat(43)
                val cases =
                    listOf(
                        Raw.mcp(fx.port, ping, null),
                        Raw.mcp(fx.port, ping, wrong),
                        Raw.mcp(fx.port, ping, null, authorization = "Basic ${fx.t}"),
                        Raw.mcp(fx.port, ping, fx.t.dropLast(1)),
                        Raw.mcp(fx.port, ping, fx.t, extra = listOf("Authorization: Bearer ${fx.t}")),
                    )
                cases.forEach {
                    it.status shouldBe 401
                    it.header("www-authenticate") shouldBe "Bearer"
                    it.body shouldBe ""
                }
                fx.server.bodyOpens.get() shouldBe 0
            }
        }

        "a large declared body without a token gives 401 and the body is never opened" {
            withServer { fx ->
                val r =
                    Raw.request(
                        fx.port,
                        headers = listOf("Host: 127.0.0.1:${fx.port}", "Content-Type: application/json"),
                        body = "{}".toByteArray(),
                        declaredLength = 2L * ONE_MIB,
                    )
                r.status shouldBe 401
                fx.server.bodyOpens.get() shouldBe 0
            }
        }

        "no token is accepted while the store has none" {
            withServer { fx ->
                fx.current = null
                fx.call(ping).status shouldBe 401
            }
        }

        "the host must be exactly one of the two loopback names with the bound port" {
            withServer { fx ->
                listOf("evil.com:${fx.port}", "127.0.0.1:${fx.port + 1}", "127.0.0.1", "localhost").forEach {
                    Raw.mcp(fx.port, ping, fx.t, hostHeader = it).status shouldBe 403
                }
                Raw.mcp(fx.port, ping, fx.t, hostHeader = "LOCALHOST:${fx.port}").status shouldBe 200
                Raw.mcp(fx.port, ping, fx.t, hostHeader = "localhost:${fx.port}").status shouldBe 200
                Raw
                    .mcp(
                        fx.port,
                        ping,
                        fx.t,
                        hostHeader = "127.0.0.1:${fx.port}",
                        extra = listOf("Host: evil.com"),
                    ).status shouldBeIn
                    // Two Host headers: our check answers 403; a newer JDK (Temurin 21.0.13 on CI) already refuses
                    // the request with 400 before our handler runs. Either way it is rejected and the body stays closed.
                    listOf(400, 403)
                Raw.mcp(fx.port, ping, fx.t, hostHeader = null).status shouldBe 403
                fx.server.bodyOpens.get() shouldBe 2
            }
        }

        "the origin may be absent, but when present it must be exactly our own" {
            withServer { fx ->
                fun origin(value: String) = Raw.mcp(fx.port, ping, fx.t, extra = listOf("Origin: $value")).status
                origin("http://evil.com") shouldBe 403
                origin("null") shouldBe 403
                origin("https://127.0.0.1:${fx.port}") shouldBe 403
                origin("http://127.0.0.1") shouldBe 403
                origin("http://localhost:${fx.port}") shouldBe 200
                origin("http://127.0.0.1:${fx.port}") shouldBe 200
                fx.call(ping).status shouldBe 200
            }
        }

        "GET, DELETE, OPTIONS, PUT and HEAD give 405 with Allow POST and no CORS headers" {
            withServer { fx ->
                listOf("GET", "DELETE", "OPTIONS", "PUT", "HEAD").forEach { method ->
                    val extra =
                        if (method ==
                            "OPTIONS"
                        ) {
                            listOf("Access-Control-Request-Method: POST", "Origin: http://evil.com")
                        } else {
                            emptyList()
                        }
                    val r = Raw.mcp(fx.port, "", fx.t, method = method, extra = extra, contentType = null)
                    r.status shouldBe 405
                    r.header("allow") shouldBe "POST"
                    r.headers.keys.none { it.startsWith("access-control-") } shouldBe true
                }
                fx.server.bodyOpens.get() shouldBe 0
            }
        }

        "only exactly /mcp without a query is served" {
            withServer { fx ->
                listOf("/mcpx", "/mcp/", "/", "/MCP", "/mcp?x=1", "/other").forEach {
                    Raw.mcp(fx.port, ping, fx.t, path = it).status shouldBe 404
                }
            }
        }

        "only application/json is accepted, with parameters allowed" {
            withServer { fx ->
                Raw.mcp(fx.port, ping, fx.t, contentType = "text/plain").status shouldBe 415
                Raw.mcp(fx.port, ping, fx.t, contentType = null).status shouldBe 415
                Raw.mcp(fx.port, ping, fx.t, contentType = "application/json; charset=utf-8").status shouldBe 200
            }
        }

        "the body is limited to 1 MiB: declared, chunked and exactly at the limit" {
            withServer { fx ->
                val declared =
                    Raw.request(
                        fx.port,
                        headers =
                            listOf(
                                "Host: 127.0.0.1:${fx.port}",
                                "Authorization: Bearer ${fx.t}",
                                "Content-Type: application/json",
                            ),
                        body = "{}".toByteArray(),
                        declaredLength = ONE_MIB + 1L,
                    )
                declared.status shouldBe 413
                fx.server.bodyOpens.get() shouldBe 0

                val big = ByteArray(ONE_MIB + 1) { ' '.code.toByte() }
                val viaChunks =
                    Raw.send(
                        fx.port,
                        (
                            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${fx.port}\r\nAuthorization: Bearer ${fx.t}\r\n" +
                                "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        ).toByteArray() + chunked(big),
                    )
                viaChunks.status shouldBe 413

                val exact = ping.toByteArray() + ByteArray(ONE_MIB - ping.length) { ' '.code.toByte() }
                exact.size shouldBe ONE_MIB
                val ok =
                    Raw.request(
                        fx.port,
                        headers =
                            listOf(
                                "Host: 127.0.0.1:${fx.port}",
                                "Authorization: Bearer ${fx.t}",
                                "Content-Type: application/json",
                            ),
                        body = exact,
                    )
                ok.status shouldBe 200
            }
        }

        "broken JSON, invalid UTF-8, a batch and a non-object are answered with the right JSON-RPC error" {
            withServer { fx ->
                fx.call("{not json").also {
                    it.status shouldBe 400
                    it.json().errorCode() shouldBe -32700
                }
                val badUtf8 =
                    Raw.request(
                        fx.port,
                        headers =
                            listOf(
                                "Host: 127.0.0.1:${fx.port}",
                                "Authorization: Bearer ${fx.t}",
                                "Content-Type: application/json",
                            ),
                        body = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()),
                    )
                badUtf8.status shouldBe 400
                badUtf8.json().errorCode() shouldBe -32700
                fx.call("[{}]").also {
                    it.status shouldBe 400
                    it.json().errorCode() shouldBe -32600
                }
                fx.call("\"x\"").also {
                    it.status shouldBe 400
                    it.json().errorCode() shouldBe -32600
                }
                // a very deep nesting must not take the server down
                val deep = fx.call("[".repeat(100_000))
                deep.status shouldBe 400
                fx.call(ping).status shouldBe 200
                val deepObject = fx.call("{\"a\":".repeat(20_000) + "1" + "}".repeat(20_000))
                deepObject.status shouldBe 400
                fx.call(ping).status shouldBe 200
            }
        }

        "unknown methods, unknown tools and bad ids" {
            withServer { fx ->
                fx.call(rpc("foo/bar")).also {
                    it.status shouldBe 200
                    it.json().errorCode() shouldBe -32601
                }
                fx.call(rpc("tools/call", params = """{"name":"nope"}""")).json().errorCode() shouldBe -32602
                fx
                    .call(
                        rpc("tools/call", params = """{"name":"kstep_desktop_get_status","arguments":[1]}"""),
                    ).json()
                    .errorCode() shouldBe
                    -32602
                fx.call("""{"jsonrpc":"2.0","id":null,"method":"ping"}""").also {
                    it.status shouldBe 400
                    it.json().errorCode() shouldBe -32600
                }
                fx.call("""{"jsonrpc":"2.0","id":{},"method":"ping"}""").status shouldBe 400
                fx.call("""{"jsonrpc":"2.0","id":[1],"method":"ping"}""").status shouldBe 400
                fx.call("""{"jsonrpc":"1.0","id":1,"method":"ping"}""").status shouldBe 400
                fx
                    .call(
                        """{"jsonrpc":"2.0","id":"abc","method":"ping"}""",
                    ).json()
                    .getValue("id")
                    .jsonPrimitive.content shouldBe
                    "abc"
            }
        }

        "the protocol version header is checked when it is present" {
            withServer { fx ->
                fx.call(ping, extra = listOf("MCP-Protocol-Version: 1999-01-01")).status shouldBe 400
                fx.call(ping, extra = listOf("MCP-Protocol-Version: 2025-06-18")).status shouldBe 200
                fx.call(ping, extra = listOf("MCP-Protocol-Version: 2025-03-26")).status shouldBe 200
                fx.call(ping).status shouldBe 200
            }
        }

        "no answer to a rejected request leaks an exception, a stack frame, a path or the token" {
            withServer { fx ->
                val answers =
                    listOf(
                        Raw.mcp(fx.port, ping, null),
                        Raw.mcp(fx.port, ping, fx.t, hostHeader = "evil.com"),
                        Raw.mcp(fx.port, ping, fx.t, method = "GET"),
                        Raw.mcp(fx.port, ping, fx.t, path = "/x"),
                        Raw.mcp(fx.port, ping, fx.t, contentType = "text/plain"),
                        fx.call("{broken"),
                        fx.call("[]"),
                        fx.call(rpc("foo/bar")),
                        fx.call(rpc("tools/call", params = """{"name":"nope"}""")),
                    )
                answers.forEach { a ->
                    val all = a.body + a.headers.toString()
                    listOf("Exception", "at dev.", "at java.", "/Users/", "/tmp/", fx.t).forEach {
                        all shouldNotContain
                            it
                    }
                }
            }
        }

        "neither the token nor the script text reaches the log" {
            val marker = "SECRET-SCRIPT-MARKER-4711"
            val captured = ByteArrayOutputStream()
            val old = System.err
            System.setErr(PrintStream(captured, true))
            try {
                withServer(FakeHost(snap(text = "// $marker\n"))) { fx ->
                    fx.call(rpc("tools/call", params = """{"name":"kstep_desktop_get_script"}""")).status shouldBe 200
                    Raw.mcp(fx.port, ping, "B".repeat(43)).status shouldBe 401
                    fx.call("{broken").status shouldBe 400
                    val log = captured.toString()
                    // the check is only meaningful if the debug line of the server really was written
                    log shouldContain "-> 401"
                    log shouldNotContain fx.t
                    log shouldNotContain marker
                    log shouldNotContain "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
                }
            } finally {
                System.setErr(old)
            }
        }

        "regenerating the token cuts off the old one at once and the new one works without a restart" {
            withServer { fx ->
                fx.call(ping).status shouldBe 200
                val old = fx.t
                fx.current = fx.tokens.regenerate()
                Raw.mcp(fx.port, ping, old).status shouldBe 401
                Raw.mcp(fx.port, ping, fx.current!!.value).status shouldBe 200
            }
        }

        "only authenticated, well-formed calls count as activity" {
            withServer { fx ->
                Raw.mcp(fx.port, ping, null).status shouldBe 401
                fx.call("{broken").status shouldBe 400
                fx.activity.lastLitAtMs.value shouldBe Long.MIN_VALUE / 2
                fx.call(ping).status shouldBe 200
                (fx.activity.lastLitAtMs.value > 0) shouldBe true
            }
        }

        "rejections close the connection" {
            withServer { fx ->
                Raw.mcp(fx.port, ping, null).header("connection") shouldBe "close"
                Raw.mcp(fx.port, ping, null).header("cache-control") shouldBe "no-store"
            }
        }

        "after stop no mcp thread is alive, no dispatcher thread is left and nobody can connect" {
            fun names() = Thread.getAllStackTraces().keys.map { it.name }
            val before = names().count { it == "HTTP-Dispatcher" }
            var port = 0
            withServer { fx ->
                port = fx.port
                fx.call(ping).status shouldBe 200
            }
            Thread.sleep(300)
            names().filter { it.startsWith("kstep-mcp") } shouldBe emptyList()
            names().count { it == "HTTP-Dispatcher" } shouldBe before
            val refused =
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
                    false
                } catch (_: Exception) {
                    true
                }
            refused shouldBe true
        }

        "the server is bound to 127.0.0.1 only" {
            withServer { fx ->
                fx.server.port shouldNotBe null
                val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                address.isLoopbackAddress shouldBe true
                val lan =
                    NetworkInterface
                        .getNetworkInterfaces()
                        .toList()
                        .flatMap { it.inetAddresses.toList() }
                        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it is java.net.Inet4Address }
                if (lan != null) {
                    val reached =
                        try {
                            Socket().use { it.connect(InetSocketAddress(lan, fx.port), 1000) }
                            true
                        } catch (_: Exception) {
                            false
                        }
                    reached shouldBe false
                }
            }
        }

        "under overload the server answers or drops, never hangs, and recovers" {
            val latch = CountDownLatch(1)
            val host = FakeHost(snap(), onRead = { latch.await(20, TimeUnit.SECONDS) })
            withServer(host) { fx ->
                val body = rpc("tools/call", params = """{"name":"kstep_desktop_get_status"}""")
                val results = java.util.concurrent.CopyOnWriteArrayList<Int>()
                val threads =
                    (1..30).map {
                        Thread { results += Raw.mcp(fx.port, body, fx.t).status }.also { t ->
                            t.isDaemon = true
                            t.start()
                        }
                    }
                Thread.sleep(1500)
                // the saturated server must refuse the surplus quickly (503 or a closed connection)
                (results.count { it == -1 || it == 503 } > 0) shouldBe true
                latch.countDown()
                threads.forEach { it.join(15_000) }
                host.onRead = {}
                fx.call(ping).status shouldBe 200
            }
        }

        "a body that is sent too slowly is cut off by the response timer, and the server keeps working" {
            withServer { fx ->
                Socket().use { socket ->
                    socket.soTimeout = 25_000
                    socket.connect(InetSocketAddress("127.0.0.1", fx.port), 3000)
                    val head =
                        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${fx.port}\r\nAuthorization: Bearer ${fx.t}\r\n" +
                            "Content-Type: application/json\r\nContent-Length: 200\r\n\r\n{"
                    socket.getOutputStream().write(head.toByteArray())
                    socket.getOutputStream().flush()
                    val started = System.nanoTime()
                    // the server must give up on its own: it answers or closes, we never send the rest
                    val first =
                        try {
                            socket.getInputStream().read()
                        } catch (_: Exception) {
                            -1
                        }
                    val seconds = (System.nanoTime() - started) / 1_000_000_000
                    (seconds < 20) shouldBe true
                    first.let { it == -1 || it == 'H'.code } shouldBe true
                }
                fx.call(ping).status shouldBe 200
            }
        }

        "the answer is a JsonArray free object for tools/list" {
            withServer { fx ->
                val tools =
                    fx
                        .call(rpc("tools/list"))
                        .json()
                        .result()
                        .getValue("tools")
                (tools is JsonArray) shouldBe true
                (
                    tools.jsonArray[0]
                        .jsonObject
                        .getValue(
                            "inputSchema",
                        ).jsonObject
                        .getValue("type") as JsonPrimitive
                ).content shouldBe
                    "object"
            }
        }
    })
