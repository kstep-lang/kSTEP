package dev.kstep.tests

import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21InstancePart
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import dev.kstep.step21.Part21WriteException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun testHeader(): Part21Header =
    Part21Header(fileName = "x.step", timestamp = "2026-09-02T00:00:00", schemaIdentifiers = listOf("S"))

private fun stripSourceLine(instance: Part21EntityInstance): Part21EntityInstance =
    when (instance) {
        is Part21SimpleInstance -> instance.copy(sourceLine = 0)
        is Part21ComplexInstance -> instance.copy(sourceLine = 0)
    }

/** ADR-0009's "document algebra": [Part21Document.renumbered] and [Part21Document.concat]. */
class Part21DocumentMergeTest :
    StringSpec({
        "renumbered shifts a direct #N reference" {
            val doc = Part21Document(testHeader(), listOf(Part21SimpleInstance(1, "FOO", listOf(Part21Value.Ref(2)))))
            val shifted = doc.renumbered(10)
            shifted.instances.single().id shouldBe 11
            (shifted.instances.single() as Part21SimpleInstance).args shouldBe listOf(Part21Value.Ref(12))
        }

        "renumbered shifts a reference nested in a ListValue" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(
                        Part21SimpleInstance(
                            1,
                            "FOO",
                            listOf(Part21Value.ListValue(listOf(Part21Value.Ref(2), Part21Value.Ref(3)))),
                        ),
                    ),
                )
            val shifted = doc.renumbered(5)
            (shifted.instances.single() as Part21SimpleInstance).args shouldBe
                listOf(Part21Value.ListValue(listOf(Part21Value.Ref(7), Part21Value.Ref(8))))
        }

        "renumbered shifts a reference nested in a Typed parameter" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(
                        Part21SimpleInstance(1, "FOO", listOf(Part21Value.Typed("BAR", listOf(Part21Value.Ref(2))))),
                    ),
                )
            val shifted = doc.renumbered(3)
            (shifted.instances.single() as Part21SimpleInstance).args shouldBe
                listOf(Part21Value.Typed("BAR", listOf(Part21Value.Ref(5))))
        }

        "renumbered shifts references in every part of a complex instance, and the instance's own id" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(Part21ComplexInstance(1, listOf(Part21InstancePart("A", listOf(Part21Value.Ref(2)))))),
                )
            val shifted = doc.renumbered(100)
            val instance = shifted.instances.single() as Part21ComplexInstance
            instance.id shouldBe 101
            instance.parts.single().args shouldBe listOf(Part21Value.Ref(102))
        }

        "concat appends instances from multiple groups and keeps the base header" {
            val base = Part21Document(testHeader(), listOf(Part21SimpleInstance(1, "FOO", emptyList())))
            val groupA = listOf(Part21SimpleInstance(2, "BAR", emptyList()))
            val groupB = listOf(Part21SimpleInstance(3, "BAZ", emptyList()))
            val merged = Part21Document.concat(base, groupA, groupB)
            merged.header shouldBe base.header
            merged.instances.map { it.id } shouldBe listOf(1, 2, 3)
        }

        // Regression for the fail-open integer-overflow gap: renumbered's `it.id + offset` used
        // to wrap silently (e.g. id=2_000_000_000 shifted by offset=2_000_000_000 landed at
        // -294_967_296) rather than throwing, so the resulting document only failed much later --
        // either at render() (Part21Renderer now rejects non-positive ids, see below) or, if it
        // rendered anyway before that fix, as a document kSTEP's own reader rejected with a
        // confusing "expected digits after '#'" syntax error.
        "renumbered throws Part21WriteException rather than silently overflowing an instance id" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(Part21SimpleInstance(2_000_000_000, "FOO", listOf(Part21Value.Ref(2_000_000_000)))),
                )
            shouldThrow<Part21WriteException> { doc.renumbered(2_000_000_000) }
        }

        "renumbered throws Part21WriteException rather than silently overflowing a referenced id" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(Part21SimpleInstance(1, "FOO", listOf(Part21Value.Ref(2_000_000_000)))),
                )
            shouldThrow<Part21WriteException> { doc.renumbered(2_000_000_000) }
        }

        // Defense in depth for the same fail-open gap, at the renderer itself: a caller that
        // hand-constructs a Part21EntityInstance/Part21Value.Ref with a non-positive id -- not
        // only one reached via renumbered's now-fixed arithmetic -- must still be rejected rather
        // than silently written as e.g. "#-5", which no conformant Part-21 reader (including
        // kSTEP's own Part21Tokenizer.parseInstanceId, id > 0) accepts.
        "the renderer rejects a hand-constructed instance with a non-positive id" {
            val doc = Part21Document(testHeader(), listOf(Part21SimpleInstance(-5, "FOO", emptyList())))
            shouldThrow<Part21EncodingException> { doc.render() }
        }

        "the renderer rejects a hand-constructed Ref with a non-positive id" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(Part21SimpleInstance(1, "FOO", listOf(Part21Value.Ref(0)))),
                )
            shouldThrow<Part21EncodingException> { doc.render() }
        }

        "concat throws Part21WriteException on a colliding instance id" {
            val base = Part21Document(testHeader(), listOf(Part21SimpleInstance(1, "FOO", emptyList())))
            val colliding = listOf(Part21SimpleInstance(1, "BAR", emptyList()))
            shouldThrow<Part21WriteException> { Part21Document.concat(base, colliding) }
        }

        "the renderer rejects a programmatically-built Num value that does not match the number grammar" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(Part21SimpleInstance(1, "FOO", listOf(Part21Value.Num("1); INJECTED(")))),
                )
            shouldThrow<Part21EncodingException> { doc.render() }
        }

        // ADR-0009's Security section, "Logging": a caller that logs a Part21EncodingException
        // must not have an unbounded amount of parsed foreign content land in one log line.
        "the renderer truncates an overlong echoed value in its exception message" {
            val overlong = "a".repeat(500) + "\\" // 501 chars, triggers the reverse-solidus check
            val doc =
                Part21Document(testHeader(), listOf(Part21SimpleInstance(1, "FOO", listOf(Part21Value.Str(overlong)))))
            val exception = shouldThrow<Part21EncodingException> { doc.render() }
            exception.message shouldContain "truncated, 501 characters total"
            (exception.message?.length ?: 0) shouldBeLessThan 450
        }

        "the renderer rejects a Str value with an embedded backslash even inside an opaque instance" {
            val doc =
                Part21Document(testHeader(), listOf(Part21SimpleInstance(1, "FOO", listOf(Part21Value.Str("a\\b")))))
            shouldThrow<Part21EncodingException> { doc.render() }
        }

        "the renderer rejects an entity name that is not a valid Part-21 identifier" {
            val doc = Part21Document(testHeader(), listOf(Part21SimpleInstance(1, "FOO); INJECTED(", emptyList())))
            shouldThrow<Part21EncodingException> { doc.render() }
        }

        "a well-formed document round-trips through render and readRawDocument" {
            val doc =
                Part21Document(
                    testHeader(),
                    listOf(
                        Part21SimpleInstance(
                            1,
                            "FOO",
                            listOf(Part21Value.Str("hi"), Part21Value.Num("1.E-07"), Part21Value.Enumeration("T")),
                        ),
                        Part21ComplexInstance(2, listOf(Part21InstancePart("BAR", listOf(Part21Value.Derived)))),
                    ),
                )
            val rendered = doc.render()
            val reparsed = Part21Reader.readRawDocument(rendered, Part21ReadMode.TOLERANT)
            // sourceLine is a parse-position artifact, not semantic content — strip it before comparing.
            reparsed.instances.map { stripSourceLine(it) } shouldBe doc.instances.map { stripSourceLine(it) }
        }
    })
