package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ExportTargetsTest :
    StringSpec({
        fun withDir(block: (Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                block(dir)
            } finally {
                Files.walk(dir).use { s ->
                    s.sorted(Comparator.reverseOrder()).forEach {
                        try {
                            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
                        } catch (_: Exception) {
                            // not POSIX or a link
                        }
                        Files.deleteIfExists(it)
                    }
                }
            }
        }

        fun invalid(check: TargetCheck) = check.shouldBeInstanceOf<TargetCheck.Invalid>()

        "a fresh name in a writable folder is accepted and reports no existing file" {
            withDir { dir ->
                val ok = ExportTargets.check(dir.resolve("a.step")).shouldBeInstanceOf<TargetCheck.Ok>()
                ok.existing shouldBe null
                ok.file shouldBe dir.resolve("a.step")
                // the probe file is gone
                Files.list(dir).use { it.count() } shouldBe 0L
            }
        }

        "an existing file reports its size" {
            withDir { dir ->
                Files.write(dir.resolve("a.step"), ByteArray(1234))
                ExportTargets.check(dir.resolve("a.step")).shouldBeInstanceOf<TargetCheck.Ok>().existing shouldBe
                    ExistingFile(1234)
            }
        }

        "a symbolic link as the target is refused (KSTEP-D-020), even a broken one" {
            withDir { dir ->
                val real = Files.write(dir.resolve("real.step"), ByteArray(3))
                val link = dir.resolve("link.step")
                val broken = dir.resolve("broken.step")
                try {
                    Files.createSymbolicLink(link, real)
                    Files.createSymbolicLink(broken, dir.resolve("nowhere"))
                } catch (_: UnsupportedOperationException) {
                    return@withDir
                } catch (_: IOException) {
                    return@withDir
                }
                invalid(ExportTargets.check(link)).code shouldBe DesktopErrorCodes.EXPORT_TARGET_INVALID
                invalid(ExportTargets.check(broken)).code shouldBe DesktopErrorCodes.EXPORT_TARGET_INVALID
            }
        }

        "a symbolic link as the parent resolves to the real folder" {
            withDir { dir ->
                val real = Files.createDirectory(dir.resolve("real"))
                try {
                    Files.createSymbolicLink(dir.resolve("link"), real)
                } catch (_: UnsupportedOperationException) {
                    return@withDir
                } catch (_: IOException) {
                    return@withDir
                }
                val ok = ExportTargets.check(dir.resolve("link").resolve("a.step")).shouldBeInstanceOf<TargetCheck.Ok>()
                ok.dir shouldBe real.toRealPath()
            }
        }

        "a directory as the target is refused (KSTEP-D-020)" {
            withDir { dir ->
                Files.createDirectory(dir.resolve("folder.step"))
                invalid(ExportTargets.check(dir.resolve("folder.step"))).code shouldBe
                    DesktopErrorCodes.EXPORT_TARGET_INVALID
            }
        }

        "a missing folder is refused and the message names no path" {
            withDir { dir ->
                val bad = invalid(ExportTargets.check(dir.resolve("missing").resolve("a.step")))
                bad.code shouldBe DesktopErrorCodes.EXPORT_TARGET_INVALID
                bad.message shouldNotContain dir.toString()
            }
        }

        "an over-long file name is refused" {
            withDir { dir ->
                invalid(ExportTargets.check(dir.resolve("a".repeat(300) + ".step"))).code shouldBe
                    DesktopErrorCodes.EXPORT_TARGET_INVALID
            }
        }

        "a folder that cannot be written to is KSTEP-D-021" {
            withDir { dir ->
                val folder = Files.createDirectory(dir.resolve("ro"))
                try {
                    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"))
                } catch (_: UnsupportedOperationException) {
                    return@withDir
                }
                if (Files.isWritable(folder)) return@withDir // running as root: the mode does not bind
                invalid(ExportTargets.check(folder.resolve("a.step"))).code shouldBe
                    DesktopErrorCodes.EXPORT_TARGET_NOT_WRITABLE
            }
        }
    })
