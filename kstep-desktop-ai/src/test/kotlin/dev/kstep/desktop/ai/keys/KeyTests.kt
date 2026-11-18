package dev.kstep.desktop.ai.keys

import dev.kstep.desktop.ai.provider.AiProvider
import dev.kstep.desktop.ai.settings.AiSettings
import dev.kstep.desktop.ai.settings.SettingsStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

const val KEY = "sk-ant-TEST-0000000000000000000000"

class RecordingRunner(
    private val results: (List<String>) -> ProcessResult = { ProcessResult(0, "", false) },
) : SecretCommandRunner {
    val commands = ArrayList<List<String>>()
    val stdins = ArrayList<String?>()

    override fun run(
        command: List<String>,
        stdin: CharArray?,
    ): ProcessResult {
        commands += command
        stdins += stdin?.let { String(it) }
        return results(command)
    }
}

class SecretTest :
    FunSpec({
        test("toString, templates and equality never show the value") {
            val s = Secret.fromEnv(KEY)
            s.toString() shouldBe "Secret(redacted)"
            "$s" shouldNotContain "sk-ant"
            (s == Secret.fromEnv(KEY)) shouldBe false
            s.length() shouldBe KEY.length
        }
        test("wipe zeroes the value") {
            val chars = KEY.toCharArray()
            val s = Secret.of(chars)
            chars.all { it == '\u0000' } shouldBe true
            s.wipe()
            s.isWiped() shouldBe true
            s.occursIn(KEY) shouldBe false
        }
        test("occursIn and redact") {
            val s = Secret.fromEnv("abc-secret")
            s.occursIn("x abc-secret y") shouldBe true
            s.redact("x abc-secret y") shouldBe "x <redacted> y"
        }
        test("the class is not serializable") {
            (java.io.Serializable::class.java.isAssignableFrom(Secret::class.java)) shouldBe false
        }
    })

class MacOsKeychainBackendTest :
    FunSpec({
        val p = AiProvider.ANTHROPIC

        test(
            "put sends the secret twice over stdin, never in the command, with an absolute binary and a per-provider service",
        ) {
            val r =
                RecordingRunner { cmd ->
                    if (cmd[1] ==
                        "find-generic-password"
                    ) {
                        ProcessResult(0, KEY + "\n", false)
                    } else {
                        ProcessResult(0, "", false)
                    }
                }
            val b = MacOsKeychainBackend(r, user = "tester")
            b.put(p, Secret.fromEnv(KEY)) shouldBe null
            val put = r.commands.first()
            put[0] shouldBe "/usr/bin/security"
            put.joinToString(" ") shouldNotContain KEY
            put.contains("dev.kstep.desktop.ai.anthropic") shouldBe true
            r.stdins.first() shouldBe "$KEY\n$KEY\n"
        }
        test("a read-back with a different length is an error") {
            val r =
                RecordingRunner { cmd ->
                    if (cmd[1] ==
                        "find-generic-password"
                    ) {
                        ProcessResult(0, "short\n", false)
                    } else {
                        ProcessResult(0, "", false)
                    }
                }
            MacOsKeychainBackend(r, user = "t").put(p, Secret.fromEnv(KEY)) shouldBe StoreFailure.ERROR
        }
        test("timeout, not found and errors map without stderr") {
            MacOsKeychainBackend(RecordingRunner { ProcessResult(-1, "", true) }).get(p) shouldBe
                KeyLookup.StoreFailed(StoreFailure.TIMEOUT)
            MacOsKeychainBackend(RecordingRunner { ProcessResult(44, "", false) }).get(p) shouldBe KeyLookup.Missing
            MacOsKeychainBackend(RecordingRunner { ProcessResult(1, "", false) }).get(p) shouldBe
                KeyLookup.StoreFailed(StoreFailure.ERROR)
            MacOsKeychainBackend(RecordingRunner { ProcessResult(44, "", false) }).has(p) shouldBe false
            MacOsKeychainBackend(RecordingRunner { ProcessResult(-1, "", true) }).has(p) shouldBe null
            MacOsKeychainBackend(RecordingRunner { ProcessResult(44, "", false) }).delete(p) shouldBe null
        }
        test("an empty secret is refused") {
            MacOsKeychainBackend(RecordingRunner()).put(p, Secret.fromEnv("  ")) shouldBe StoreFailure.ERROR
        }
    })

class LinuxSecretToolBackendTest :
    FunSpec({
        val p = AiProvider.ANTHROPIC
        val fakeBinary =
            Files
                .createTempFile("secret-tool", "")
                .also {
                    it.toFile().setExecutable(true)
                    it.toFile().deleteOnExit()
                }.toString()

        test("store sends the secret over stdin without a newline and not in argv") {
            val r = RecordingRunner()
            val b = LinuxSecretToolBackend(r, listOf(fakeBinary))
            b.put(p, Secret.fromEnv(KEY)) shouldBe null
            r.commands.single().joinToString(" ") shouldNotContain KEY
            r.stdins.single() shouldBe KEY
            r.commands.single().contains("dev.kstep.desktop.ai") shouldBe true
        }
        test("without the binary the backend is unavailable") {
            val b = LinuxSecretToolBackend(RecordingRunner(), listOf("/nonexistent/secret-tool"))
            b.isAvailable() shouldBe false
            b.put(p, Secret.fromEnv(KEY)) shouldBe StoreFailure.UNAVAILABLE
        }
        test("isAvailable accepts exit 0/1 only and a timeout is unavailable") {
            LinuxSecretToolBackend(
                RecordingRunner { ProcessResult(1, "", false) },
                listOf(fakeBinary),
            ).isAvailable() shouldBe
                true
            LinuxSecretToolBackend(
                RecordingRunner { ProcessResult(-1, "", true) },
                listOf(fakeBinary),
            ).isAvailable() shouldBe
                false
            LinuxSecretToolBackend(
                RecordingRunner { ProcessResult(2, "", false) },
                listOf(fakeBinary),
            ).isAvailable() shouldBe
                false
        }
        test("lookup maps found and missing") {
            LinuxSecretToolBackend(
                RecordingRunner {
                    ProcessResult(0, "v\n", false)
                },
                listOf(fakeBinary),
            ).get(p).shouldBeInstanceOf<KeyLookup.Found>()
            LinuxSecretToolBackend(RecordingRunner { ProcessResult(1, "", false) }, listOf(fakeBinary)).get(p) shouldBe
                KeyLookup.Missing
        }
    })

class KeyResolverTest :
    FunSpec({
        val p = AiProvider.ANTHROPIC

        test("the environment source reads only the fixed variable") {
            val asked = ArrayList<String>()
            val r =
                KeyResolver(NoKeychainBackend, SessionKeyStore(), {
                    asked += it
                    if (it ==
                        "KSTEP_AI_ANTHROPIC_KEY"
                    ) {
                        KEY
                    } else {
                        "other"
                    }
                })
            r.resolve(p, KeySource.ENVIRONMENT).shouldBeInstanceOf<KeyLookup.Found>()
            asked shouldBe listOf("KSTEP_AI_ANTHROPIC_KEY")
            KeyResolver(NoKeychainBackend, SessionKeyStore(), { null }).resolve(p, KeySource.ENVIRONMENT) shouldBe
                KeyLookup.Missing
        }
        test("session source and no-keychain behaviour") {
            val s = SessionKeyStore()
            val r = KeyResolver(NoKeychainBackend, s, { null })
            r.resolve(p, KeySource.SESSION) shouldBe KeyLookup.Missing
            s.put(p, Secret.fromEnv("x"))
            r.resolve(p, KeySource.SESSION).shouldBeInstanceOf<KeyLookup.Found>()
            r.resolve(p, KeySource.KEYCHAIN) shouldBe KeyLookup.StoreFailed(StoreFailure.UNAVAILABLE)
            NoKeychainBackend.isAvailable() shouldBe false
            s.wipeAll()
            r.resolve(p, KeySource.SESSION) shouldBe KeyLookup.Missing
        }
    })

class SettingsStoreTest :
    FunSpec({
        val p = AiProvider.ANTHROPIC

        fun store() =
            SettingsStore(Files.createTempDirectory("kstep-settings").resolve("sub"), { Instant.ofEpochSecond(1000) })

        test("roundtrip, and the file never holds a key") {
            val st = store()
            val s = st.grant(AiSettings(keySource = KeySource.SESSION), p)
            st.save(s)
            st.load() shouldBe s
            val dir = Files.createTempDirectory("kstep-settings2")
            val st2 = SettingsStore(dir)
            st2.save(st2.grant(AiSettings(), p))
            val text = Files.readString(dir.resolve("desktop-ai.json")).lowercase()
            text shouldNotContain "key\":"
            text shouldNotContain "secret"
            text shouldNotContain "sk-ant"
        }
        test("broken, oversized and unknown-provider files give the defaults") {
            val dir = Files.createTempDirectory("kstep-settings3")
            val st = SettingsStore(dir)
            Files.writeString(dir.resolve("desktop-ai.json"), "{not json")
            st.load() shouldBe AiSettings()
            Files.writeString(dir.resolve("desktop-ai.json"), " ".repeat(70_000))
            st.load() shouldBe AiSettings()
            Files.writeString(dir.resolve("desktop-ai.json"), """{"providerId":"evil.example.com"}""")
            st.load() shouldBe AiSettings()
        }
        test("file mode 0600 on POSIX and no temp files left") {
            val dir = Files.createTempDirectory("kstep-settings4")
            val st = SettingsStore(dir)
            st.save(AiSettings())
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("desktop-ai.json"))) shouldBe
                    "rw-------"
            }
            Files.list(dir).use { it.count() } shouldBe 1
        }
        test("consent is per host: grant, revoke, changed host asks again") {
            val st = store()
            var s = AiSettings()
            st.hasConsent(s, p) shouldBe false
            s = st.grant(s, p)
            st.hasConsent(s, p) shouldBe true
            st.hasConsent(
                s.copy(consents = mapOf(p.id to s.consents.getValue(p.id).copy(host = "old.example.com"))),
                p,
            ) shouldBe
                false
            st.hasConsent(st.revoke(s, p), p) shouldBe false
        }
    })
