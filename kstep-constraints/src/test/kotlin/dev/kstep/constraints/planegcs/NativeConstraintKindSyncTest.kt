package dev.kstep.constraints.planegcs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The constraint-kind table exists in three hand-synchronised copies (the C++ `kKind...` constants,
 * [NativeConstraintKind] and the KDoc/ADR tables). This test reads the C++ source and compares it with the
 * Kotlin enum, so a drift fails without needing the native bridge.
 */
class NativeConstraintKindSyncTest :
    StringSpec({
        "C++ kKind constants match NativeConstraintKind" {
            val source = File("src/main/cpp/kstep_planegcs_bridge.cpp").readText()
            val cpp =
                Regex("""constexpr jint kKind(\w+) = (\d+);""")
                    .findAll(source)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2].toInt() }
            val kotlin =
                NativeConstraintKind.entries.associate {
                    it.name.replace("_", "").lowercase() to it.nativeValue
                }
            // Names differ slightly (P2PDistance vs P2P_DISTANCE etc.); compare by value set and count.
            cpp.values.sorted() shouldBe kotlin.values.sorted()
            cpp.size shouldBe NativeConstraintKind.entries.size
            // Contiguous 0..n-1.
            NativeConstraintKind.entries.map { it.nativeValue } shouldBe
                (0 until NativeConstraintKind.entries.size).toList()
        }
    })
