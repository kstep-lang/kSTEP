package dev.kstep.desktop

import dev.kstep.cli.DesktopLaunchCodes
import dev.kstep.cli.DesktopLaunchText
import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.drop.DropEvaluator
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

/** The three families of KSTEP-D codes never share a value (ADR-0083). */
class ErrorCodeUniquenessTest :
    StringSpec({
        fun codes(type: Class<*>): List<String> =
            type.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .onEach { it.isAccessible = true }
                .map { it.get(null) as String }

        "no code value is used twice across desktop, AI and launcher codes" {
            val all =
                codes(DesktopErrorCodes::class.java) +
                    codes(AiErrorCodes::class.java) +
                    codes(DesktopLaunchCodes::class.java)
            (all.size > 10) shouldBe true
            all
                .groupBy { it }
                .filter { it.value.size > 1 }
                .keys
                .shouldBeEmpty()
        }

        "the CLI keeps its own copy of the supported-types text equal to the desktop's" {
            DesktopLaunchText.ONLY_SUPPORTED shouldBe DropEvaluator.ONLY_SUPPORTED
        }
    })
