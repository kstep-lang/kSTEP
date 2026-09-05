package dev.kstep.asciidoc

import dev.kstep.preview.RenderFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class AsciidocScannerTest :
    StringSpec({
        // A1
        "a document with no kstep construct reassembles byte-identically" {
            val text = "= Title\n\nSome prose.\n\nMore prose.\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.size shouldBe 1
            val textSegment = result.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            textSegment.lines.joinToString("\n") shouldBe text
        }

        // A2
        "a markdown kstep fence is recognized as an Inline block" {
            val text = "prose\n```kstep\nval x = 1\n```\nmore prose\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val block = result.segments.filterIsInstance<AdocSegment.KStepBlock>().single()
            block.kind shouldBe BlockKind.MARKDOWN_FENCE
            (block.script as ScriptRef.Inline).code shouldBe "val x = 1"
        }

        // A3
        "[source,kstep] with a ---- delimiter is recognized as a Delimited block" {
            val text = "[source,kstep]\n----\nval x = 1\n----\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val block = result.segments.filterIsInstance<AdocSegment.KStepBlock>().single()
            block.kind shouldBe BlockKind.DELIMITED
        }

        // A4
        "[kstep] alone with a ---- delimiter is recognized as a Delimited block" {
            val text = "[kstep]\n----\nval x = 1\n----\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments
                .filterIsInstance<AdocSegment.KStepBlock>()
                .single()
                .kind shouldBe BlockKind.DELIMITED
        }

        // A5
        "[kstep,bracket,png] parses target and format positionally" {
            val text = "[kstep,bracket,png]\n----\nval x = 1\n----\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val block = result.segments.filterIsInstance<AdocSegment.KStepBlock>().single()
            block.attributes.target shouldBe "bracket"
            block.attributes.format shouldBe RenderFormat.PNG
        }

        // A6
        "named attributes parse target/format/width/height/alt correctly" {
            val text = "[kstep,bracket,format=png,width=800,height=600,alt=\"A bracket\"]\n----\nval x = 1\n----\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val attrs =
                result.segments
                    .filterIsInstance<AdocSegment.KStepBlock>()
                    .single()
                    .attributes
            attrs.target shouldBe "bracket"
            attrs.format shouldBe RenderFormat.PNG
            attrs.width shouldBe 800
            attrs.height shouldBe 600
            attrs.alt shouldBe "A bracket"
        }

        // A7
        "a bare kstep::path[] macro is recognized as an External block" {
            val result = AsciidocScanner.scan("kstep::a.kstep.kts[]\n").shouldBeInstanceOf<ScanResult.Parsed>()
            val block = result.segments.filterIsInstance<AdocSegment.KStepBlock>().single()
            block.kind shouldBe BlockKind.MACRO
            (block.script as ScriptRef.External).rawPath shouldBe "a.kstep.kts"
        }

        // A8
        "kstep::path[format=png,target=foo] macro attributes parse correctly" {
            val result =
                AsciidocScanner
                    .scan(
                        "kstep::a.kstep.kts[format=png,target=foo]\n",
                    ).shouldBeInstanceOf<ScanResult.Parsed>()
            val attrs =
                result.segments
                    .filterIsInstance<AdocSegment.KStepBlock>()
                    .single()
                    .attributes
            attrs.format shouldBe RenderFormat.PNG
            attrs.target shouldBe "foo"
        }

        // A9
        "prose, a block, prose, a macro, and prose keep exact segment order and content" {
            val text = "p1\n```kstep\nc1\n```\np2\nkstep::x.kstep.kts[]\np3\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.size shouldBe 5
            (result.segments[0] as AdocSegment.Text).lines shouldBe listOf("p1")
            (result.segments[1] as AdocSegment.KStepBlock).kind shouldBe BlockKind.MARKDOWN_FENCE
            (result.segments[2] as AdocSegment.Text).lines shouldBe listOf("p2")
            (result.segments[3] as AdocSegment.KStepBlock).kind shouldBe BlockKind.MACRO
            // Trailing "" element is the split("\n")/join("\n") round-trip artifact of the
            // document's own trailing newline (see AsciidocScanner's KDoc on why this is
            // required for byte-identical reassembly, not a bug).
            (result.segments[4] as AdocSegment.Text).lines shouldBe listOf("p3", "")
        }

        // A10
        "an unclosed markdown kstep fence fails at the opening line" {
            val result = AsciidocScanner.scan("```kstep\nval x = 1\n").shouldBeInstanceOf<ScanResult.Failed>()
            result.line shouldBe 1
        }

        // A11
        "an unclosed [kstep]/---- delimited block fails" {
            val result = AsciidocScanner.scan("[kstep]\n----\nval x = 1\n").shouldBeInstanceOf<ScanResult.Failed>()
            result.line shouldBe 1
        }

        // A12
        "kstep::x[] inside a foreign ---- listing block stays plain Text" {
            val text = "----\nkstep::x.kstep.kts[]\n----\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
        }

        // A13
        "a markdown kstep fence inside a //// comment block stays plain Text" {
            val text = "////\n```kstep\nval x = 1\n```\n////\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
        }

        // A14
        "a line-commented macro stays plain Text" {
            val result = AsciidocScanner.scan("// kstep::x.kstep.kts[]\n").shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
        }

        // A15
        "an unknown block attribute is rejected" {
            AsciidocScanner.scan("[kstep,a,bogus=1]\n----\nx\n----\n").shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A16
        "format=text/glb/auto are all rejected for a kstep block" {
            AsciidocScanner.scan("[kstep,a,format=text]\n----\nx\n----\n").shouldBeInstanceOf<ScanResult.Failed>()
            AsciidocScanner.scan("[kstep,a,format=glb]\n----\nx\n----\n").shouldBeInstanceOf<ScanResult.Failed>()
            AsciidocScanner.scan("[kstep,a,format=auto]\n----\nx\n----\n").shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A17
        "out-of-range width/height are rejected" {
            AsciidocScanner.scan("[kstep,a,width=99999]\n----\nx\n----\n").shouldBeInstanceOf<ScanResult.Failed>()
            AsciidocScanner.scan("[kstep,a,width=0]\n----\nx\n----\n").shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A18
        "every unsafe target value is rejected" {
            val unsafe =
                listOf(
                    "../x",
                    "/abs",
                    "a/b",
                    "a\\b",
                    ".",
                    "..",
                    "\"\"",
                    "x".repeat(101),
                    "a\u0000b",
                )
            for (value in unsafe) {
                val text = "[kstep,target=$value]\n----\nx\n----\n"
                AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Failed>()
            }
        }

        // A19
        ":imagesdir: is recognized when safe and rejected when it escapes the root" {
            val ok = AsciidocScanner.scan(":imagesdir: images\n").shouldBeInstanceOf<ScanResult.Parsed>()
            ok.imagesDir shouldBe "images"
            AsciidocScanner.scan(":imagesdir: ../out\n").shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A24
        "a bare ':imagesdir:' with no value resets the attribute instead of failing the scan" {
            val result = AsciidocScanner.scan(":imagesdir:\nprose\n").shouldBeInstanceOf<ScanResult.Parsed>()
            result.imagesDir shouldBe null
        }

        // A25
        ":imagesdir: with a trailing slash is tolerated like Asciidoctor tolerates it" {
            val result = AsciidocScanner.scan(":imagesdir: images/\n").shouldBeInstanceOf<ScanResult.Parsed>()
            result.imagesDir shouldBe "images"
        }

        // A26
        "a kstep block only sees the ':imagesdir:' value already in effect at its OWN position" {
            val text = "kstep::a.kstep.kts[target=before]\n:imagesdir: assets\nkstep::b.kstep.kts[target=after]\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val blocks = result.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks.size shouldBe 2
            blocks[0].imagesDir shouldBe null
            blocks[1].imagesDir shouldBe "assets"
        }

        // A20
        "a CRLF document keeps its line endings when reassembled" {
            val text = "a\r\nb\r\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            (result.segments.single() as AdocSegment.Text).lines.joinToString("\n") shouldBe text
        }

        // A21
        "[kstep] not immediately followed by a ---- delimiter fails with a clear message" {
            val result = AsciidocScanner.scan("[kstep]\nnot a delimiter\n").shouldBeInstanceOf<ScanResult.Failed>()
            result.line shouldBe 1
            result.message.isNotBlank() shouldBe true
        }

        // A22
        "more than MAX_BLOCKS_PER_DOCUMENT kstep blocks fails" {
            val text = (1..AsciidocLimits.MAX_BLOCKS_PER_DOCUMENT + 1).joinToString("\n") { "kstep::x$it.kstep.kts[]" }
            AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A23
        "two blocks with the same explicit target fail" {
            val text = "kstep::a.kstep.kts[target=dup]\nkstep::b.kstep.kts[target=dup]\n"
            AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A27
        "':imagesdir!:' and ':!imagesdir:' reset the attribute like a bare ':imagesdir:' does" {
            val text1 =
                "kstep::a.kstep.kts[target=before]\n:imagesdir: assets\n:imagesdir!:\n" +
                    "kstep::b.kstep.kts[target=after]\n"
            val result1 = AsciidocScanner.scan(text1).shouldBeInstanceOf<ScanResult.Parsed>()
            val blocks1 = result1.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks1.size shouldBe 2
            blocks1[0].imagesDir shouldBe null
            blocks1[1].imagesDir shouldBe null
            result1.imagesDir shouldBe null

            val text2 = ":imagesdir: assets\n:!imagesdir:\nkstep::c.kstep.kts[]\n"
            val result2 = AsciidocScanner.scan(text2).shouldBeInstanceOf<ScanResult.Parsed>()
            val blocks2 = result2.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks2.single().imagesDir shouldBe null
        }

        // A28
        "an ':imagesdir:' value containing an attribute reference passes through a document with no kstep block" {
            val text = ":partsdir: parts\n:imagesdir: {partsdir}/images\n\nJust prose.\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.imagesDir shouldBe null
            (result.segments.single() as AdocSegment.Text).lines.joinToString("\n") shouldBe text
        }

        // A29
        "an ':imagesdir:' value containing an attribute reference fails only once a kstep block would consume it" {
            val text =
                ":imagesdir: {partsdir}/images\n\n" +
                    "kstep::a.kstep.kts[]\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Failed>()
            result.line shouldBe 3
            result.message.isNotBlank() shouldBe true

            // Same invalid value, but reset back to null via a bare ':imagesdir:' BEFORE the
            // block -- the block never sees it, so the scan must succeed.
            val reset =
                ":imagesdir: {partsdir}/images\n:imagesdir:\n\n" +
                    "kstep::a.kstep.kts[]\n"
            val resetResult = AsciidocScanner.scan(reset).shouldBeInstanceOf<ScanResult.Parsed>()
            resetResult.segments
                .filterIsInstance<AdocSegment.KStepBlock>()
                .single()
                .imagesDir shouldBe null
        }

        // A30
        "an unsafe (non-'{') ':imagesdir:' value still fails the scan immediately, even with no kstep block" {
            val prose = ":imagesdir: ../out\n\nJust prose.\n"
            AsciidocScanner.scan(prose).shouldBeInstanceOf<ScanResult.Failed>()
        }

        // A31
        "kstep::x[] inside a bare ``` markdown fence stays plain Text" {
            val text = "```\nkstep::secret.kstep.kts[]\n```\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
            (result.segments.single() as AdocSegment.Text).lines.joinToString("\n") shouldBe text
        }

        // A32
        "kstep::x[] inside a ```asciidoc markdown fence stays plain Text" {
            val text = "```asciidoc\nkstep::secret.kstep.kts[]\n```\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
        }

        // A33
        "a [kstep]/---- delimited block written inside a bare ``` markdown fence stays plain Text" {
            val text = "```\n[kstep]\n----\nval x = 1\n----\n```\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
        }

        // A34
        "a real ```kstep fence still opens a block for an ordinary document" {
            val text = "prose\n```kstep\nval x = 1\n```\nmore prose\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>().size shouldBe 1
        }

        // A35
        (
            "a ```kstep fence nested inside a longer ```` foreign fence stays plain Text, " +
                "and a real kstep block after it still opens"
        ) {
            // The doc-teaching-the-syntax case this scanner exists to protect: an outer 4-backtick
            // fence wraps an inner 3-backtick "```kstep" example verbatim. The outer fence's own
            // closer must be a run of >= 4 backticks -- the inner "```" line is NOT a valid closer
            // for it (CommonMark: a closing fence must be at least as long as its opener).
            val text =
                "````\n```kstep\nval x = 1\n```\n````\n\nkstep::bracket.kstep.kts[]\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val textLines =
                result.segments
                    .filterIsInstance<AdocSegment.Text>()
                    .flatMap { it.lines }
            // The whole fence (outer "````" markers plus the inner "```kstep" example
            // verbatim) reassembles as plain Text -- none of it is mistaken for a real block --
            // and the genuine macro line AFTER the fence still opens exactly one block.
            textLines shouldBe listOf("````", "```kstep", "val x = 1", "```", "````", "", "")
            val blocks = result.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks.size shouldBe 1
            blocks.single().kind shouldBe BlockKind.MACRO
        }

        // A36
        (
            "an unbalanced bare ``` foreign fence left open at EOF fails the scan instead of " +
                "silently swallowing the rest of the document"
        ) {
            // Before this fix, a lone unbalanced "```" line at column 0 silently turned off all
            // further block recognition for the remainder of the document -- the real kstep
            // macro below would have been swallowed as fence content with no diagnostic.
            val text = "```\nprose inside\n\nkstep::bracket.kstep.kts[]\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Failed>()
            result.line shouldBe 1
        }

        // A37
        (
            "an unbalanced foreign AsciiDoc delimiter (----/====/////// etc.) left open at EOF " +
                "fails the scan instead of silently swallowing the rest of the document"
        ) {
            // The exact analogue of A36, one delimiter spelling over: before this fix, a lone
            // unbalanced "----" (or "====", "////", ...) line silently turned off all further
            // block recognition for the remainder of the document -- the real kstep macro below
            // would have been swallowed as foreign-block content with no diagnostic, while
            // "Parsed(segments=1, kstepBlocks=0)" was returned as if the document were fine.
            val delimiters = listOf("----", "....", "====", "****", "____", "++++", "////")
            for (delim in delimiters) {
                val text = "$delim\nprose inside\n\nkstep::bracket.kstep.kts[]\n"
                val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Failed>()
                result.line shouldBe 1
            }

            // Control: the same delimiter, properly closed before EOF, still passes through as
            // plain Text and the block after it still opens -- this fix must not regress A12.
            val closed = "----\nprose inside\n----\n\nkstep::bracket.kstep.kts[]\n"
            val closedResult = AsciidocScanner.scan(closed).shouldBeInstanceOf<ScanResult.Parsed>()
            closedResult.segments.filterIsInstance<AdocSegment.KStepBlock>().size shouldBe 1
        }

        // A38
        (
            "an unbalanced foreign fence/delimiter left open at EOF with NO kstep construct in " +
                "the swallowed region still reassembles byte-identically instead of failing"
        ) {
            // A36/A37 fixed a real correctness bug (a swallowed kstep construct silently vanishing
            // with no diagnostic) by failing the scan whenever a foreign fence/delimiter is still
            // open at EOF -- but that guard, applied unconditionally, breaks Pass-Through for a
            // document with no kstep content at all: AsciiDoc's setext-style title underline
            // ("Title\n-----------\n") is syntactically indistinguishable from an unterminated
            // "-----" delimited block, and a document using it must still pass through unchanged
            // per ADR-0019's Pass-Through guarantee (which this scanner already honors for the
            // exact same class of "unrelated, legitimate document" in the ':imagesdir:' case, see
            // AsciidocScannerTest A28/A29). The fix: the EOF guard only fails when the swallowed
            // region (opener line through EOF) actually contains a real kstep construct -- these
            // three documents contain none.
            val setextTitle = "kSTEP Overview\n==============\n\nSome prose, no kstep at all.\n"
            val setextResult = AsciidocScanner.scan(setextTitle).shouldBeInstanceOf<ScanResult.Parsed>()
            setextResult.segments.size shouldBe 1
            val setextText = setextResult.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            setextText.lines.joinToString("\n") shouldBe setextTitle

            val setextSection =
                "= Real Title\n\nSection One\n-----------\n\nProse only.\n"
            val sectionResult = AsciidocScanner.scan(setextSection).shouldBeInstanceOf<ScanResult.Parsed>()
            sectionResult.segments.size shouldBe 1
            val sectionText = sectionResult.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            sectionText.lines.joinToString("\n") shouldBe setextSection

            val bareFence = "```\nprose inside, no kstep at all\n"
            val fenceResult = AsciidocScanner.scan(bareFence).shouldBeInstanceOf<ScanResult.Parsed>()
            fenceResult.segments.size shouldBe 1
            val fenceText = fenceResult.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            fenceText.lines.joinToString("\n") shouldBe bareFence
        }

        // A39
        (
            "a setext-style section title underline is never mistaken for a foreign delimiter " +
                "opener, even when a same-length underline appears again later"
        ) {
            // Before this fix, "Section One\n-----------\n" opened a bogus foreign "----"
            // delimiter that only closed again at the SECOND setext title's own underline,
            // silently swallowing the real kstep macro in between with no diagnostic at all.
            val text =
                "Section One\n-----------\n\nkstep::demo.kstep.kts[]\n\nSection Two\n-----------\n\nEnd.\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            val blocks = result.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks.size shouldBe 1
            blocks.single().kind shouldBe BlockKind.MACRO

            // A single setext title (no accidental second underline to pair up with) must still
            // reassemble byte-identically when it has no kstep content at all -- must not regress
            // A38.
            val single = "kSTEP Overview\n==============\n\nSome prose, no kstep at all.\n"
            val singleResult = AsciidocScanner.scan(single).shouldBeInstanceOf<ScanResult.Parsed>()
            singleResult.segments.size shouldBe 1
            val singleText = singleResult.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            singleText.lines.joinToString("\n") shouldBe single
        }

        // A40
        (
            "a genuine foreign delimited block directly under a setext-titled section still " +
                "opens, closes, and shields its kstep-looking content -- exactly the case ADR-0019's " +
                "Stolperfalle 3 documents, just with a setext title instead of a '==' one"
        ) {
            // Before this fix, the setext title's own underline ("Usage Guide\n-----------\n")
            // was itself mistaken for the delimiter opener, so the REAL "----" listing block a few
            // lines later was read as its (mismatched-length, so never-closing) content instead --
            // aborting the WHOLE scan with "unclosed '-----------' block" at the title line, even
            // though the document is entirely legitimate and every block in it is properly closed.
            val text =
                "Usage Guide\n-----------\n\nEmbed a diagram like this:\n\n[source,asciidoc]\n" +
                    "----\nkstep::demo.kstep.kts[]\n----\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
            val textSegment = result.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            textSegment.lines.joinToString("\n") shouldBe text
        }

        // A41 (Review Round 7, R7-1)
        (
            "a generic AsciiDoc admonition block ([TIP]/[NOTE]/[WARNING] + delimiter) shields a " +
                "kstep macro exactly like [source,asciidoc]/---- does -- its delimiter is never " +
                "mistaken for a setext title underline of the attribute line above it"
        ) {
            // Before this fix, the "====" (or "=====", "========", ...) delimiter's length
            // happened to land within one character of its "[TIP]"/"[NOTE]"/"[WARNING]" attribute
            // line above, so isSetextTitleUnderline mistook it for a setext underline and never
            // opened the admonition as a foreign delimited block -- the kstep macro INSIDE it was
            // rendered for real instead of shielded verbatim, reopening exactly the failure mode
            // ADR-0019's Stolperfalle 3 documents for '[source,asciidoc]'/'----'.
            val cases =
                listOf(
                    "[TIP]" to "====",
                    "[NOTE]" to "=====",
                    "[WARNING]" to "========",
                    "[quote]" to "======",
                    "[example]" to "========",
                )
            for ((attrLine, delim) in cases) {
                val text = "$attrLine\n$delim\nkstep::demo.kstep.kts[]\n$delim\n"
                val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
                result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
                result.segments.size shouldBe 1
                val textSegment = result.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
                textSegment.lines.joinToString("\n") shouldBe text
            }
        }

        // A42 (Review Round 7, R7-1)
        (
            "a block title line (\".Beispiel\") directly above a foreign delimiter shields a " +
                "kstep macro the same way, instead of the delimiter being mistaken for the block " +
                "title's setext underline"
        ) {
            val text = ".Beispiel\n========\nkstep::demo.kstep.kts[]\n========\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
            val textSegment = result.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            textSegment.lines.joinToString("\n") shouldBe text
        }

        // A43 (Review Round 7, R7-1)
        (
            "a delimiter-shaped line in the middle of a running paragraph (no blank line and not " +
                "at document start) is never treated as a setext title underline -- it opens a " +
                "genuine foreign block, exactly as Asciidoctor itself would read it"
        ) {
            // Asciidoctor only ever recognizes a two-line setext title at document start or
            // directly after a blank line. "Wie folgt:" here is a second paragraph line, not a
            // title -- so "----------" below it is genuinely a listing-block delimiter to
            // Asciidoctor, and must shield the kstep-looking macro inside it rather than let it
            // render for real.
            val text = "Ein Absatz.\nWie folgt:\n----------\nkstep::demo.kstep.kts[]\n----------\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()
            result.segments.filterIsInstance<AdocSegment.KStepBlock>() shouldBe emptyList()
            result.segments.size shouldBe 1
            val textSegment = result.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            textSegment.lines.joinToString("\n") shouldBe text
        }

        // A44 (Review Round 7, R7-1 regression guard)
        (
            "a genuine setext title with a blank line above it (or at document start) still " +
                "works unchanged after the R7-1 fix"
        ) {
            val atDocStart = "kSTEP Overview\n==============\n\nSome prose, no kstep at all.\n"
            val docStartResult = AsciidocScanner.scan(atDocStart).shouldBeInstanceOf<ScanResult.Parsed>()
            docStartResult.segments.size shouldBe 1
            val docStartText = docStartResult.segments.single().shouldBeInstanceOf<AdocSegment.Text>()
            docStartText.lines.joinToString("\n") shouldBe atDocStart

            val afterBlankLine =
                "= Real Title\n\nSection One\n-----------\n\nkstep::demo.kstep.kts[]\n"
            val afterBlankResult = AsciidocScanner.scan(afterBlankLine).shouldBeInstanceOf<ScanResult.Parsed>()
            val blocks = afterBlankResult.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks.size shouldBe 1
            blocks.single().kind shouldBe BlockKind.MACRO
        }

        // A45 (fresh review round, 2026-09-05 -- combined regression guard: Rounds 5-7 each
        // fixed one construct in isolation; nothing so far exercises a foreign markdown fence, a
        // genuine setext title, an admonition, AND a real kstep macro together in one document,
        // which is exactly where per-line scanner state (open foreign fence/delimiter, "was the
        // previous line blank") could have been left corrupted by an earlier branch and only show
        // up once several constructs are chained back to back)
        (
            "a document with a foreign markdown fence, a genuine setext title, an admonition, " +
                "and a real kstep macro all in sequence resolves each construct independently -- " +
                "exactly one kstep block, the other two kstep-looking lines shielded verbatim"
        ) {
            val text =
                "```asciidoc\n" +
                    "kstep::hidden.kstep.kts[]\n" +
                    "```\n" +
                    "\n" +
                    "Section One\n" +
                    "-----------\n" +
                    "\n" +
                    "[NOTE]\n" +
                    "====\n" +
                    "kstep::shielded.kstep.kts[]\n" +
                    "====\n" +
                    "\n" +
                    "kstep::real.kstep.kts[]\n"
            val result = AsciidocScanner.scan(text).shouldBeInstanceOf<ScanResult.Parsed>()

            val blocks = result.segments.filterIsInstance<AdocSegment.KStepBlock>()
            blocks.size shouldBe 1
            val block = blocks.single()
            block.kind shouldBe BlockKind.MACRO
            (block.script as ScriptRef.External).rawPath shouldBe "real.kstep.kts"

            // Both kstep-LOOKING lines inside the foreign fence and the admonition must survive
            // as inert text -- neither consumed as a second block nor dropped -- proving the two
            // shielding mechanisms (foreign markdown fence, foreign AsciiDoc delimiter) still work
            // side by side with a genuine setext title sitting between them and a real macro block
            // right after.
            val reassembledText =
                result.segments
                    .filterIsInstance<AdocSegment.Text>()
                    .joinToString("\n") { it.lines.joinToString("\n") }
            reassembledText shouldContain "kstep::hidden.kstep.kts[]"
            reassembledText shouldContain "kstep::shielded.kstep.kts[]"
            reassembledText shouldContain "Section One"
            reassembledText shouldContain "-----------"
        }
    })
