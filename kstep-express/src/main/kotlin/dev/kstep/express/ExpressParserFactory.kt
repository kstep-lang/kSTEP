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

        return parser.syntax()
    }
}
