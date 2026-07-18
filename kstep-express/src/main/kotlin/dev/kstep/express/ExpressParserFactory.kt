package dev.kstep.express

import dev.kstep.express.grammar.ExpressLexer
import dev.kstep.express.grammar.ExpressParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

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
    fun parse(source: String): ExpressParser.SyntaxContext {
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

        // ANTLR's generated parser implements several grammar rules (e.g.
        // parameterType -> generalizedTypes -> generalAggregationTypes -> generalSetType/
        // generalListType/generalBagType/generalArrayType -> parameterType, and the
        // left-recursive expression/term/factor rules) as genuine recursive-descent Java
        // method calls, one JVM stack frame per nesting level. Malformed or adversarial
        // input (e.g. thousands of nested "SET OF SET OF ...") can therefore exhaust the
        // JVM stack *during parsing itself*, before any downstream tree-walk code (which
        // may have its own depth guards) ever runs. Converting that StackOverflowError into
        // the same structured ExpressSyntaxException used for ordinary syntax errors keeps
        // this the one place callers need to handle, instead of letting a raw Error escape.
        return try {
            parser.syntax()
        } catch (overflow: StackOverflowError) {
            throw ExpressSyntaxException(
                "EXPRESS source exceeds the maximum supported nesting depth (stack overflow while parsing)",
            )
        }
    }
}
