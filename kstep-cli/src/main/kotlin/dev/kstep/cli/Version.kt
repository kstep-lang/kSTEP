package dev.kstep.cli

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel

private const val VERSION_RESOURCE = "/kstep-cli-version.properties"

/** The kSTEP version baked into this jar by the `generateVersionResource` task; "unknown" if absent. */
internal fun cliVersion(): String =
    runCatching {
        CliCommand::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { stream ->
            java.util
                .Properties()
                .apply { load(stream) }
                .getProperty("version")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

/** The kSTEP version baked into this jar, for callers outside the CLI (the desktop About dialog). "unknown" if absent. */
public fun kstepVersion(): String = cliVersion()

/** Prints `kstep <version>`; in the native distribution additionally one line per native bridge. */
internal fun printVersion() {
    println("kstep ${cliVersion()}")
    if (NativeDist.isActive()) {
        for (line in NativeDist.bridgeStatusLines()) println(line)
    }
}

/**
 * Support for the opt-in native runtime archive (ADR-0050). Everything here is gated on the
 * `kstep.native.dist` system property, which only the archive's `bin/kstep` launcher sets, so the
 * standard JVM-only zip keeps its exact behaviour (JSON `geometry_unavailable` documents etc.).
 */
internal object NativeDist {
    const val PROPERTY: String = "kstep.native.dist"

    fun isActive(): Boolean = System.getProperty(PROPERTY) == "true"

    fun missingOcctHint(reason: String): String =
        "hint: the OCCT geometry bridge could not be loaded ($reason). The native archive's bridge is linked " +
            "against OCCT 7.9.x at the library location it was built against; install it there " +
            "(Ubuntu: sudo apt install libocct-data-exchange-dev libocct-modeling-algorithms-dev; " +
            "macOS: brew install opencascade)."

    fun occtHintOrNull(): String? =
        when (val occt = OcctKernel.availability()) {
            is OcctAvailability.Unavailable -> missingOcctHint(occt.reason)
            is OcctAvailability.Available -> null
        }

    fun bridgeStatusLines(): List<String> =
        listOf(
            when (val occt = OcctKernel.availability()) {
                is OcctAvailability.Available -> "occt: available ${occt.occtVersion}"
                is OcctAvailability.Unavailable -> "occt: unavailable: ${occt.reason}"
            },
            when (val gcs = PlaneGcsSolver.availability()) {
                is PlaneGcsAvailability.Available -> "planegcs: available ${gcs.planeGcsCommit}"
                is PlaneGcsAvailability.Unavailable -> "planegcs: unavailable: ${gcs.reason}"
            },
        )
}
