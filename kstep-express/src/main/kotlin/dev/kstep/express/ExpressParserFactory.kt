package dev.kstep.express

import dev.kstep.express.grammar.ExpressLexer
import dev.kstep.express.grammar.ExpressParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

/** Thrown when the EXPRESS source has a syntax error. */
class ExpressSyntaxException(
    message: String,
) : RuntimeException(message)

/**
 * Thin Kotlin wrapper around the ANTLR-generated EXPRESS parser
 * (ISO 10303-11 grammar, vendored from `lutaml/express-grammar`, see /NOTICE).
 */
object ExpressParserFactory {
    /**
     * Parses an EXPRESS schema source string starting at the `syntax` rule
     * (`syntax : schemaDecl+ EOF`). Throws [ExpressSyntaxException] on the
     * first syntax error instead of silently returning a partial tree.
     */
    fun parse(source: String): ExpressParser.SyntaxContext = runCatchingStackOverflow { newParser(source).syntax() }

    /**
     * Parses a single EXPRESS `expression` (grammar rule 216), used by
     * `dev.kstep.express.validation.WhereRuleExpressionBuilder` to re-parse the verbatim
     * `WHERE`-rule text [dev.kstep.express.semantic.ExpressSemanticModelBuilder] already
     * extracted once. `expression` (unlike `syntax`) has no trailing `EOF` in the grammar,
     * so a malformed re-parse could otherwise succeed on a mere prefix of [source] — this
     * explicitly checks that every token was consumed and throws [ExpressSyntaxException]
     * if anything trails after the parsed expression.
     */
    fun parseExpression(source: String): ExpressParser.ExpressionContext {
        val parser = newParser(source)
        val tree = runCatchingStackOverflow { parser.expression() }
        val trailing = parser.currentToken
        if (trailing.type != Token.EOF) {
            throw ExpressSyntaxException(
                "unexpected trailing input after expression, starting at line ${trailing.line}:" +
                    "${trailing.charPositionInLine} '${trailing.text}'",
            )
        }
        return tree
    }

    // Shared lexer/parser setup with the throwing error listener, so a future fix to error
    // handling (or the StackOverflow guard below) automatically applies to every entry
    // point instead of only the one it happened to be added to first.
    private fun newParser(source: String): ExpressParser {
        val lexer = ExpressLexer(CharStreams.fromString(source))
        val parser = ExpressParser(CommonTokenStream(lexer))

        val throwingErrorListener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ): Unit = throw ExpressSyntaxException("line $line:$charPositionInLine $msg")
            }

        lexer.removeErrorListeners()
        lexer.addErrorListener(throwingErrorListener)
        parser.removeErrorListeners()
        parser.addErrorListener(throwingErrorListener)
        return parser
    }

    // ANTLR's generated parser implements several grammar rules (e.g.
    // parameterType -> generalizedTypes -> generalAggregationTypes -> generalSetType/
    // generalListType/generalBagType/generalArrayType -> parameterType, and the
    // left-recursive expression/term/factor rules) as genuine recursive-descent Java
    // method calls, one JVM stack frame per nesting level. Malformed or adversarial
    // input (e.g. thousands of nested "SET OF SET OF ..." or deeply parenthesized WHERE-rule
    // expressions) can therefore exhaust the JVM stack *during parsing itself*, before any
    // downstream tree-walk code (which may have its own depth guards) ever runs. Converting
    // that StackOverflowError into the same structured ExpressSyntaxException used for
    // ordinary syntax errors keeps this the one place callers need to handle, instead of
    // letting a raw Error escape.
    private fun <T> runCatchingStackOverflow(block: () -> T): T =
        try {
            block()
        } catch (overflow: StackOverflowError) {
            throw ExpressSyntaxException(
                "EXPRESS source exceeds the maximum supported nesting depth (stack overflow while parsing)",
            )
        }
}
