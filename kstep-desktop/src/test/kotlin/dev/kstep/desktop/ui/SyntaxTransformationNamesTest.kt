package dev.kstep.desktop.ui

import androidx.compose.ui.text.AnnotatedString
import dev.kstep.desktop.edit.TokenKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** ADR-0089: the colouring cache depends on the DSL names, not only on the text. */
class SyntaxTransformationNamesTest :
    StringSpec({
        "a changed name set gives new spans and new tokens for the same text" {
            var names = setOf("other")
            val transformation = SyntaxTransformation(EditorColors.LIGHT) { names }
            val text = "foo(1)\n"
            val before = transformation.filter(AnnotatedString(text)).text
            before.spanStyles.size shouldBe 1 // the number only
            transformation.tokensFor(text)!!.none { it.kind == TokenKind.DSL } shouldBe true
            names = setOf("foo")
            transformation.tokensFor(text) shouldBe null
            val after = transformation.filter(AnnotatedString(text)).text
            after.spanStyles.size shouldBe 2
            transformation.tokensFor(text)!!.any { it.kind == TokenKind.DSL } shouldBe true
        }
    })
