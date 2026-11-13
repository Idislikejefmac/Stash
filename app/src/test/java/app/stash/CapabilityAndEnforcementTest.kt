package app.stash

import app.stash.capture.DirectSink
import app.stash.capture.Inbound
import app.stash.capture.InboundSink
import app.stash.data.CaptureSource
import app.stash.data.Capabilities
import app.stash.data.CapabilityGate
import app.stash.data.ExtractState
import app.stash.data.LocationFix
import app.stash.data.MemoryOutboundCounter
import app.stash.data.PermissionCheck
import app.stash.data.Store
import app.stash.data.SwitchStore
import app.stash.domain.Capability
import app.stash.domain.CapabilityRules
import app.stash.domain.Outbound
import app.stash.domain.OutboundRefused
import app.stash.domain.RecordKind
import app.stash.model.hosted.GatedPoster
import app.stash.model.hosted.HttpPoster
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.ExtractionPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneOffset

class MemorySwitches(private val default: Boolean = true) : SwitchStore {
    private val map = mutableMapOf<Capability, Boolean>()
    override fun isSwitchOn(c: Capability) = map[c] ?: default
    override fun setSwitch(c: Capability, on: Boolean) { map[c] = on }
}

/** Capabilities, the outbound register, and the tests that keep the rules true as the code grows (docs/cards/13 E). */
@RunWith(RobolectricTestRunner::class)
class CapabilityAndEnforcementTest {

    // ------------------------------------------------------------------ Capabilities.isOn

    @Test fun isOnIsTheSwitchAndThePermissionAndTheBuild() {
        for (sw in listOf(false, true)) for (perm in listOf(false, true)) for (build in listOf(false, true)) {
            assertEquals("switch=$sw perm=$perm build=$build", sw && perm && build, CapabilityRules.isOn(sw, perm, build))
        }
    }

    @Test fun capabilitiesReadTheSwitchThePermissionAndTheBuildSeparately() {
        val cap = Capability.LOCATION_AT_CAPTURE
        for (sw in listOf(false, true)) for (granted in listOf(false, true)) for (inBuild in listOf(false, true)) {
            val switches = MemorySwitches().also { it.setSwitch(cap, sw) }
            // Any one of the capability's permissions is enough.
            val perms = PermissionCheck { it == cap.permissions.last() && granted }
            val build = if (inBuild) Capability.entries.toSet() else Capability.entries.toSet() - cap
            val caps = Capabilities(switches, perms, build)
            assertEquals(sw && granted && inBuild, caps.isOn(cap))
            assertEquals(granted, caps.permissionGranted(cap))
            assertEquals(sw && inBuild, caps.enabled(cap))
        }
    }

    @Test fun aCapabilityThatNeedsNoPermissionIsOnWithJustItsSwitch() {
        val caps = Capabilities(MemorySwitches(), PermissionCheck { false })
        assertTrue(caps.isOn(Capability.HOSTED_MODEL))
        assertFalse(caps.isOn(Capability.VOICE_CAPTURE)) // microphone not granted
    }

    @Test fun everyCapabilityHasAPlainNameAndAWithoutItSentence() {
        for (c in Capability.entries) {
            assertTrue(c.plainName.isNotBlank())
            assertTrue(c.withoutIt.endsWith("."))
        }
    }

    // ------------------------------------------------------------------ the outbound register and the poster

    @Test fun everyOutboundKindNamesItsCapabilityDestinationAndWhatItSends() {
        for (k in Outbound.entries) {
            assertTrue(k.destination.isNotBlank())
            assertTrue(k.sends.contains("Never a Private note"))
        }
    }

    @Test fun aKindWhoseCapabilityIsOffIsRefusedBeforeAnyConnectionAndNotCounted() {
        val switches = MemorySwitches().also { it.setSwitch(Capability.HOSTED_MODEL, false) }
        val counter = MemoryOutboundCounter()
        var connected = false
        val poster = GatedPoster(CapabilityGate(Capabilities(switches, PermissionCheck { true }), counter, FakeClock()), HttpPoster { _, _, _, _ -> connected = true; "x" })
        try {
            poster.post(Outbound.EXTRACT_NOTE, "https://example.invalid", emptyMap(), "{}")
            fail("should have been refused")
        } catch (e: OutboundRefused) {
            assertEquals(Outbound.EXTRACT_NOTE, e.kind)
        }
        assertFalse(connected)
        assertNull(counter.stats(Outbound.EXTRACT_NOTE))
    }

    @Test fun countsRiseByKindThisMonthAndRestartNextMonth() {
        val clock = FakeClock(1_794_398_400_000L) // 2026-11-11
        val counter = MemoryOutboundCounter()
        val gate = CapabilityGate(Capabilities(MemorySwitches(), PermissionCheck { true }), counter, clock)
        val poster = GatedPoster(gate, HttpPoster { _, _, _, _ -> "ok" })
        repeat(2) { poster.post(Outbound.EXTRACT_NOTE, "u", emptyMap(), "{}") }
        poster.post(Outbound.ANSWER_QUESTION, "u", emptyMap(), "{}")
        assertEquals(2, counter.stats(Outbound.EXTRACT_NOTE)!!.countThisMonth)
        assertEquals(1, counter.stats(Outbound.ANSWER_QUESTION)!!.countThisMonth)
        assertEquals("2026-11", counter.stats(Outbound.EXTRACT_NOTE)!!.month)
        assertEquals(clock.now(), counter.stats(Outbound.EXTRACT_NOTE)!!.lastAt)
        clock.t += 31L * 24 * 3600_000
        poster.post(Outbound.EXTRACT_NOTE, "u", emptyMap(), "{}")
        assertEquals(1, counter.stats(Outbound.EXTRACT_NOTE)!!.countThisMonth)
        assertEquals("2026-12", counter.stats(Outbound.EXTRACT_NOTE)!!.month)
    }

    // ------------------------------------------------------------------ the scans (docs/cards/13 E 15 and 17)

    private fun mainSources(): List<Pair<String, String>> {
        val root = File("src/main/java")
        assertTrue("run from the app folder", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.invariantSeparatorsPath.removePrefix("src/main/java/") to it.readText() }.toList()
    }

    @Test fun onlyTheHostedPackageMayOpenAConnection() {
        val banned = listOf("java.net.", "HttpURLConnection", "okhttp", "Socket(", "WebView")
        val offenders = mainSources().filter { (path, _) -> !path.startsWith("app/stash/model/hosted/") }
            .flatMap { (path, text) -> banned.filter { it in text }.map { "$path: $it" } }
        assertTrue("network code outside model/hosted: $offenders", offenders.isEmpty())
        assertTrue(mainSources().any { (p, t) -> p.startsWith("app/stash/model/hosted/") && "HttpURLConnection" in t })
    }

    @Test fun nothingLogsExceptDbg() {
        val banned = listOf("android.util.Log", "println(", "printStackTrace(")
        val offenders = mainSources().filter { (path, _) -> path != "app/stash/domain/Dbg.kt" }
            .flatMap { (path, text) -> banned.filter { it in text }.map { "$path: $it" } }
        assertTrue("logging outside Dbg: $offenders", offenders.isEmpty())
    }

    @Test fun noHttpPosterIsUsedWithoutAKindOrOutsideTheGate() {
        // The only constructor of the raw poster in the app is the one wrapped by the gate.
        val users = mainSources().filter { (_, t) -> "UrlConnectionPoster(" in t }.map { it.first }
        assertEquals(listOf("app/stash/data/Container.kt", "app/stash/model/hosted/Anthropic.kt").sorted(), users.sorted())
    }

    // ------------------------------------------------------------------ everything optional off (docs/cards/13 E 18)

    @Test fun withEveryOptionalCapabilityOffCaptureExtractionSearchAndOpenTasksStillWork() = runBlocking {
        val db = TestSupport.memoryDb()
        try {
            val clock = FakeClock(1_794_398_400_000L)
            val repo = TestSupport.repo(db, clock)
            val caps = Capabilities(MemorySwitches(default = false), PermissionCheck { false })
            Capability.entries.forEach { assertFalse(caps.isOn(it)) }
            // The wiring Container does: location only if on; hosted extractor only if on; no network, no key.
            val sink = DirectSink(repo.captures, { emptyList() })
            val somewhere = LocationFix(40.0, -105.0, 10.0)
            suspend fun say(text: String): app.stash.data.CaptureEntity {
                clock.advance(60_000)
                val fix = if (caps.isOn(Capability.LOCATION_AT_CAPTURE)) somewhere else null
                return (sink.accept(Inbound.Capture.now(text, text, CaptureSource.TYPED, false, fix, "test", clock, "UTC")) as InboundSink.Result.Saved).capture
            }
            val task = say("I need to remember to buy ground beef at Costco")
            val pool = say("Pool test: free chlorine 5, pH 7.2, alkalinity 70")
            assertNull(task.lat)
            val pipeline = ExtractionPipeline(
                store = { Store.Ready(db) }, rules = RulesExtractor(), hostedExtractor = { null },
                hostedOn = { caps.isOn(Capability.HOSTED_MODEL) }, hasKey = { false }, networkAvailable = { false },
                clock = clock, zone = { ZoneOffset.UTC },
            )
            assertEquals(2, pipeline.runPass().processed)
            assertEquals(ExtractState.DONE, db.captures().get(task.id)!!.extractState)
            assertEquals(RecordKind.TASK, db.records().forCapture(task.id).single().kind)
            assertEquals(RecordKind.READING, db.records().forCapture(pool.id).single().kind)
            // Search by words.
            val hits = repo.index.search("beef")
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.all { it.captureId == task.id })
            // Open tasks.
            val open = db.tasks().openTasks("none", 10)
            assertEquals(1, open.size)
            assertNotNull(open.single().title)
        } finally {
            db.close()
        }
    }
}
