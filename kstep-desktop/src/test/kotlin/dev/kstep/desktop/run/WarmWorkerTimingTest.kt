package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.WarmWorkerSettings.Mode
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The measurement behind ADR-0081: wall-clock time of `WorkerRunner.run` cold, spare and reuse. Off unless
 * `-Pkstep.desktop.bench=true`; it prints a table and writes `build/warm-worker-timing.txt`. Not named after OCCT:
 * it does not assert anything about geometry, only reports numbers.
 */
class WarmWorkerTimingTest :
    StringSpec({
        val enabled = System.getProperty("kstep.desktop.bench") == "true"
        val occt = OcctKernel.availability() is OcctAvailability.Available

        fun median(values: List<Long>) = values.sorted()[values.size / 2]

        fun row(
            label: String,
            values: List<Long>,
        ) = "%-34s median %5d ms  range %d..%d ms  runs %s".format(
            label,
            median(values),
            values.min(),
            values.max(),
            values,
        )

        fun timed(block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1_000_000
        }

        fun measure(
            name: String,
            fixture: String,
            lines: MutableList<String>,
        ) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            try {
                val script = TestSupport.copyFixture(dir, fixture, "bench.kstep.kts")
                var generation = 0L

                // cold: one run to warm the disk cache, then five measured
                val cold = WorkerRunner(session)
                cold.run(script, ++generation) { }
                lines += row("$name / cold", List(5) { timed { cold.run(script, ++generation) { } } })

                // spare: wait until the next process is ready before each run
                val spareClient = WarmWorkerClient(session, WarmWorkerSettings(Mode.SPARE, 1, 120_000), null)
                val spare = WorkerRunner(session, warm = spareClient)
                val first = timed { spare.run(script, ++generation) { } }
                lines +=
                    "%-34s %5d ms (start + warm-up + run, nothing ready beforehand)".format(
                        "$name / spare, first run",
                        first,
                    )
                val spareRuns =
                    List(5) {
                        spareClient.awaitReady(120_000)
                        timed { spare.run(script, ++generation) { } }
                    }
                lines += row("$name / spare, process ready", spareRuns)
                spareClient.close()

                // reuse: the first run starts the process, runs 2..6 are warm
                val reuseClient = WarmWorkerClient(session, WarmWorkerSettings(Mode.REUSE, 20, 120_000), null)
                val reuse = WorkerRunner(session, warm = reuseClient)
                val firstReuse = timed { reuse.run(script, ++generation) { } }
                lines += "%-34s %5d ms (start + warm-up + run)".format("$name / reuse, first run", firstReuse)
                lines += row("$name / reuse, runs 2..6", List(5) { timed { reuse.run(script, ++generation) { } } })
                reuseClient.close()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "timing of cold, spare and reuse".config(enabled = enabled, timeout = 1800.seconds) {
            val lines = ArrayList<String>()
            lines +=
                "machine: ${System.getProperty(
                    "os.name",
                )} ${System.getProperty("os.arch")}, JDK ${System.getProperty("java.version")}"
            lines +=
                "OCCT: " +
                (
                    OcctKernel.availability().let {
                        if (it is OcctAvailability.Available) "available" else "not available"
                    }
                )
            measure("no geometry", "hello-assembly.kstep.kts", lines)
            if (occt) {
                measure("two coloured parts", "export-colour-assembly-two-parts.kstep.kts", lines)
            } else {
                lines += "two coloured parts: not measured (no OCCT)"
            }
            val text = lines.joinToString("\n")
            println(text)
            Files.writeString(
                Path.of("build").also { Files.createDirectories(it) }.resolve("warm-worker-timing.txt"),
                text + "\n",
            )
        }
    })
