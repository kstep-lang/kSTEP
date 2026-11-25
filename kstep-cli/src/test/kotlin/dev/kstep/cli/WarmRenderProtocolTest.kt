package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.util.Base64

class WarmRenderProtocolTest :
    StringSpec({
        fun reply(
            id: Long = 7,
            out: ByteArray = "x\n".toByteArray(),
            err: ByteArray = ByteArray(0),
        ) = WarmRenderProtocol.Reply(id, 0, out, err, overflow = false, recycle = false, recycleReason = null)

        "a request survives a round trip, also with unusual characters in the paths" {
            val r = WarmRenderProtocol.Request(3, "/tmp/\"a\" b/ä.kstep.kts", "/tmp/o.glb")
            WarmRenderProtocol.decodeRequest(WarmRenderProtocol.encodeRequest(r)) shouldBe r
        }

        "a reply survives a round trip with arbitrary bytes" {
            val bytes = ByteArray(256) { it.toByte() }
            val back = WarmRenderProtocol.decodeReply(WarmRenderProtocol.encodeReply(reply(out = bytes)), 7)!!
            back.stdout.toList() shouldBe bytes.toList()
            back.exit shouldBe 0
        }

        "the encoded reply is a single line" {
            val line = WarmRenderProtocol.encodeReply(reply(out = "a\nb\r\nc".toByteArray()))
            line.contains('\n') shouldBe false
        }

        "a reply with another id, another version or a missing field is refused" {
            val ok = WarmRenderProtocol.encodeReply(reply())
            WarmRenderProtocol.decodeReply(ok, 8) shouldBe null
            WarmRenderProtocol.decodeReply(ok.replace("\"v\":1", "\"v\":2"), 7) shouldBe null
            WarmRenderProtocol.decodeReply(ok.replace("\"exit\":0,", ""), 7) shouldBe null
            WarmRenderProtocol.decodeReply("not json", 7) shouldBe null
            WarmRenderProtocol.decodeReply("[1,2]", 7) shouldBe null
        }

        "a capture beyond its bound or invalid Base64 is refused" {
            val tooBig =
                Base64.getEncoder().encodeToString(ByteArray(WarmRenderProtocol.MAX_CAPTURED_STDERR_BYTES + 1))
            val ok = WarmRenderProtocol.encodeReply(reply())
            WarmRenderProtocol.decodeReply(ok.replace("\"err64\":\"\"", "\"err64\":\"$tooBig\""), 7) shouldBe null
            WarmRenderProtocol.decodeReply(ok.replace("\"out64\":\"eAo=\"", "\"out64\":\"@@@@\""), 7) shouldBe null
        }

        "request decoding refuses wrong version, missing fields and non-strings" {
            WarmRenderProtocol.decodeRequest("""{"v":2,"id":1,"script":"a","out":"b"}""") shouldBe null
            WarmRenderProtocol.decodeRequest("""{"v":1,"id":1,"script":"a"}""") shouldBe null
            WarmRenderProtocol.decodeRequest("""{"v":1,"id":1,"script":5,"out":"b"}""") shouldBe null
            WarmRenderProtocol.decodeRequest("") shouldBe null
        }

        "the hello line is recognised and nothing else is" {
            WarmRenderProtocol.isHello(WarmRenderProtocol.helloLine()) shouldBe true
            WarmRenderProtocol.isHello("""{"v":1,"hello":false}""") shouldBe false
            WarmRenderProtocol.isHello("hello") shouldBe false
            WarmRenderProtocol.isHello(WarmRenderProtocol.encodeReply(reply())) shouldBe false
        }

        "readLine splits lines, drops a carriage return and bounds the length" {
            val input = ByteArrayInputStream("ab\r\ncd\n".toByteArray())
            (WarmRenderProtocol.readLine(input, 10) as WarmRenderProtocol.Line.Text).bytes.decodeToString() shouldBe
                "ab"
            (WarmRenderProtocol.readLine(input, 10) as WarmRenderProtocol.Line.Text).bytes.decodeToString() shouldBe
                "cd"
            WarmRenderProtocol.readLine(input, 10).shouldBeInstanceOf<WarmRenderProtocol.Line.EndOfStream>()
            WarmRenderProtocol.readLine(ByteArrayInputStream("abcdef\n".toByteArray()), 3) shouldBe
                WarmRenderProtocol.Line.TooLong
            // an unfinished last line is not a line
            WarmRenderProtocol.readLine(ByteArrayInputStream("abc".toByteArray()), 10) shouldBe
                WarmRenderProtocol.Line.EndOfStream
        }

        "the reply line bound covers both captures in Base64" {
            val full =
                reply(
                    out = ByteArray(WarmRenderProtocol.MAX_CAPTURED_STDOUT_BYTES),
                    err = ByteArray(WarmRenderProtocol.MAX_CAPTURED_STDERR_BYTES),
                )
            (WarmRenderProtocol.encodeReply(full).length <= WarmRenderProtocol.MAX_REPLY_LINE_BYTES) shouldBe true
        }
    })
