package app.stash

import app.stash.data.CaptureEntity
import app.stash.data.Confidence
import app.stash.data.Direction
import app.stash.data.DueKind
import app.stash.data.MetricEntity
import app.stash.data.SubjectEntity
import app.stash.data.SubjectType
import app.stash.domain.Attention
import app.stash.domain.CheckedRecord
import app.stash.domain.DateExpr
import app.stash.domain.ExtractedReading
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionChecks
import app.stash.domain.ItemRecordRef
import app.stash.domain.MetricResolver
import app.stash.domain.Projection
import app.stash.domain.ProjectionContext
import app.stash.domain.Projector
import app.stash.domain.RecordKind
import app.stash.domain.SubjectResolver
import app.stash.model.rules.RulesExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val NOON_WED = 1_794_398_400_000L // 2026-11-11T12:00:00Z, a Wednesday

private fun subject(id: String, name: String, type: SubjectType = SubjectType.ITEM, aliases: String = "[]", merged: String? = null, sensitive: Boolean = false) =
    SubjectEntity(id, type, name, SubjectResolver.normalise(name), aliases, null, sensitive, merged, 1, 1)

class SubjectResolverTest {
    private fun resolver(vararg existing: SubjectEntity) = SubjectResolver(existing.toList(), 5L, newId = counter())
    private fun counter(): () -> String { var n = 0; return { "new${++n}" } }

    @Test fun normaliseDropsArticlesAndPlurals() {
        assertEquals("reading glass", SubjectResolver.normalise("my reading glasses"))
        assertEquals("tide", SubjectResolver.normalise("The Tide"))
        assertEquals("ground beef", SubjectResolver.normalise("a ground beef"))
        assertEquals("pool", SubjectResolver.normalise("our pool."))
        assertEquals("", SubjectResolver.normalise("the"))
        assertEquals("Reading glasses", SubjectResolver.displayName("my Reading glasses"))
    }

    @Test fun exactNameLinksWithoutCreating() {
        val r = resolver(subject("s1", "Tide"))
        val got = r.resolve("the tide", SubjectType.ITEM, false)!!
        assertEquals("s1", got.subject.id)
        assertFalse(got.created)
        assertNull(got.sameAsId)
        assertTrue(r.newSubjects.isEmpty())
    }

    @Test fun pluralAndSingularMeetInTheMiddle() {
        val r = resolver(subject("s1", "reading glasses"))
        assertEquals("s1", r.resolve("my reading glasses", SubjectType.ITEM, false)!!.subject.id)
    }

    @Test fun aliasLinks() {
        val r = resolver(subject("s1", "pool", SubjectType.THING, aliases = """["swimming pool","the pool"]"""))
        assertEquals("s1", r.resolve("Swimming Pool", SubjectType.THING, false)!!.subject.id)
    }

    @Test fun nearMissCreatesANewSubjectAndFlagsTheOldOne() {
        val r = resolver(subject("s1", "Frank", SubjectType.PERSON))
        val none = r.resolve("Franky", SubjectType.PERSON, false)!!
        assertTrue(none.created) // not a near match under the strict rule: different length
        assertNull(none.sameAsId)
        val r2 = resolver(subject("s2", "Alice", SubjectType.PERSON))
        val near = r2.resolve("Alise", SubjectType.PERSON, false)!!
        assertTrue(near.created)
        assertEquals("s2", near.sameAsId)
        assertEquals(1, r2.newSubjects.size)
        assertEquals("Alise", near.subject.name)
    }

    @Test fun noMatchCreates() {
        val r = resolver(subject("s1", "Tide"))
        val got = r.resolve("ground beef", SubjectType.ITEM, true)!!
        assertTrue(got.created)
        assertTrue(got.subject.sensitive)
        assertEquals("ground beef", got.subject.normName)
        assertEquals(SubjectType.ITEM, got.subject.type)
        assertEquals(5L, got.subject.createdAt)
    }

    @Test fun twoMentionsInOneCaptureShareOneSubject() {
        val r = resolver()
        val a = r.resolve("Costco", SubjectType.PLACE, false)!!
        val b = r.resolve("costco", SubjectType.PLACE, false)!!
        assertEquals(a.subject.id, b.subject.id)
        assertFalse(b.created)
        assertEquals(1, r.newSubjects.size)
    }

    @Test fun peopleAndPlacesDoNotMixButItemsAndThingsDo() {
        val r = resolver(subject("s1", "Ross", SubjectType.PERSON), subject("s2", "car", SubjectType.THING))
        assertTrue(r.resolve("Ross", SubjectType.PLACE, false)!!.created)
        assertEquals("s2", r.resolve("car", SubjectType.ITEM, false)!!.subject.id)
    }

    @Test fun mergedSubjectsFollowToTheirTarget() {
        val r = resolver(subject("a", "Franky", SubjectType.PERSON, merged = "b"), subject("b", "Frank", SubjectType.PERSON))
        assertEquals("b", r.resolve("Franky", SubjectType.PERSON, false)!!.subject.id)
    }

    @Test fun blankNameGivesNull() = assertNull(resolver().resolve("  the ", SubjectType.ITEM, false))

    @Test fun metricsAreFoundByNormalisedLabelWithinTheirSubject() {
        fun m(id: String, subj: String?, key: String) = MetricEntity(id, subj, key, key, "[]", null, 1, 1)
        val r = MetricResolver(listOf(m("m1", "pool", "ph"), m("m2", "tub", "ph")), 9L, newId = { "newm" })
        assertEquals("m1", r.resolve("pool", "pH", null).id)
        assertEquals("m2", r.resolve("tub", " PH ", null).id)
        val made = r.resolve("pool", "free chlorine", "ppm")
        assertEquals("newm", made.id)
        assertEquals("free chlorine", made.key)
        assertEquals("ppm", made.unit)
        assertEquals(made.id, r.resolve("pool", "Free  Chlorine", null).id)
        assertEquals(1, r.newMetrics.size)
        assertTrue(r.resolve(null, "pH", null).id != "m1") // a metric with no subject is its own
    }
}

class ProjectorTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 11)
    private fun counter(): () -> String { var n = 0; return { "id${++n}" } }

    private fun capture(text: String, id: String = "c1", at: Long = NOON_WED, sensitive: Boolean = false) =
        CaptureEntity(id = id, createdAt = at, updatedAt = at, rawText = text, text = text, sensitive = sensitive)

    private fun ctx(
        subjects: List<SubjectEntity> = emptyList(),
        metrics: List<MetricEntity> = emptyList(),
        items: List<ItemRecordRef> = emptyList(),
        open: Map<String, String> = emptyMap(),
    ) = ProjectionContext(subjects, metrics, items, open, today, zone, 7L, counter())

    private val rules = RulesExtractor()

    private fun project(text: String, c: ProjectionContext = ctx(), cap: CaptureEntity = capture(text)): Projection {
        val checked = ExtractionChecks.check(rules.parse(text), text, c.openTasks.keys)
        return Projector.project(cap, "x1", checked, c)
    }

    @Test fun twoTasksAndAPlacementMakeThreeRecordsWithTheRightSideRows() {
        val text = "I put the ladder in the shed, buy milk at Costco and call the dentist tomorrow"
        val checked = listOf(
            CheckedRecord(ExtractedRecord(RecordKind.TASK, "Buy milk", "buy milk at Costco", item = "milk", atPlace = "Costco", sure = Confidence.HIGH)),
            CheckedRecord(ExtractedRecord(RecordKind.TASK, "Call the dentist", "call the dentist tomorrow",
                due = DateExpr("tomorrow", named = "tomorrow"), perishable = false)),
            CheckedRecord(ExtractedRecord(RecordKind.PLACED, "Ladder in the shed", "I put the ladder in the shed", item = "ladder", where = "in the shed")),
        )
        val p = Projector.project(capture(text), "x1", checked, ctx())
        assertEquals(3, p.bundles.size)
        assertEquals(listOf(0, 1, 2), p.bundles.map { it.record.seq })
        assertEquals(listOf(RecordKind.TASK, RecordKind.TASK, RecordKind.PLACED), p.bundles.map { it.record.kind })
        assertTrue(p.bundles.all { it.record.captureId == "c1" && it.record.extractionId == "x1" && !it.record.pinned })

        val buy = p.bundles[0]
        assertNotNull(buy.task); assertNull(buy.whereabouts)
        assertEquals("Costco", buy.task!!.atPlaceText)
        assertNotNull(buy.task!!.atPlaceSubjectId)
        assertEquals(DueKind.NONE, buy.task!!.dueKind)
        assertEquals(buy.record.id, buy.task!!.recordId)
        assertEquals(NOON_WED, buy.task!!.lastActiveAt)
        assertNull(buy.task!!.expiresOn)
        assertEquals(Confidence.HIGH, buy.record.confidence)

        val call = p.bundles[1]
        assertEquals(DueKind.ON, call.task!!.dueKind)
        assertEquals("2026-11-12", call.task!!.dueFrom)
        assertEquals("tomorrow", call.task!!.dueText)

        val shelf = p.bundles[2]
        assertNull(shelf.task)
        assertEquals("ladder", shelf.whereabouts!!.itemText)
        assertEquals("in the shed", shelf.whereabouts!!.whereText)
        assertEquals(Direction.PLACED, shelf.whereabouts!!.direction)
        assertEquals(shelf.whereabouts!!.itemSubjectId, shelf.record.subjectId)

        // Subjects: milk, Costco (place), ladder; each once.
        assertEquals(setOf("milk", "costco", "ladder"), p.newSubjects.map { it.normName }.toSet())
        assertEquals(SubjectType.PLACE, p.newSubjects.first { it.normName == "costco" }.type)
    }

    @Test fun taskDueKindsMapToColumns() {
        fun due(e: DateExpr): app.stash.domain.CheckedRecord = CheckedRecord(ExtractedRecord(RecordKind.TASK, "t", "t", due = e))
        fun taskFor(e: DateExpr) = Projector.project(capture("t"), "x", listOf(due(e)), ctx()).bundles.single().task!!
        assertEquals(DueKind.SOON, taskFor(DateExpr("soon", named = "soon")).dueKind)
        val by = taskFor(DateExpr("by Friday", weekday = "fri", strictness = "by"))
        assertEquals(DueKind.BY, by.dueKind); assertEquals("2026-11-13", by.dueTo); assertNull(by.dueFrom)
        val w = taskFor(DateExpr("this weekend", named = "this_weekend"))
        assertEquals(DueKind.WINDOW, w.dueKind); assertEquals("2026-11-14", w.dueFrom); assertEquals("2026-11-15", w.dueTo)
        assertEquals(DueKind.NONE, taskFor(DateExpr("someday", named = "someday")).dueKind)
    }

    @Test fun datesAreResolvedAgainstTheDayOfTheCaptureNotTheDayOfProjection() {
        val earlier = capture("t", at = NOON_WED - 7 * 24 * 3600_000L) // the Wednesday before
        val p = Projector.project(
            earlier, "x", listOf(CheckedRecord(ExtractedRecord(RecordKind.TASK, "t", "t", due = DateExpr("tomorrow", named = "tomorrow")))),
            ProjectionContext(today = LocalDate.of(2026, 11, 4), zone = zone, now = 7L, newId = counter()),
        )
        assertEquals("2026-11-05", p.bundles.single().task!!.dueFrom)
    }

    @Test fun poolReadingIsOneRecordThreeReadingsOneSubjectThreeMetrics() {
        val text = "Pool test free chlorine 5 pH 7.2 alkalinity 70"
        val p = project(text)
        assertEquals(1, p.bundles.size)
        val b = p.bundles.single()
        assertEquals(RecordKind.READING, b.record.kind)
        assertEquals(3, b.readings.size)
        assertEquals(1, p.newSubjects.size)
        assertEquals("pool", p.newSubjects.single().normName)
        assertEquals(SubjectType.THING, p.newSubjects.single().type)
        assertEquals(3, p.newMetrics.size)
        assertEquals(setOf("free chlorine", "ph", "alkalinity"), p.newMetrics.map { it.key }.toSet())
        assertTrue(p.newMetrics.all { it.subjectId == p.newSubjects.single().id })
        assertEquals(b.record.subjectId, p.newSubjects.single().id)
        assertEquals(listOf("5", "7.2", "70"), b.readings.map { it.valueText })
        assertEquals(listOf(5.0, 7.2, 70.0), b.readings.map { it.valueNum })
        assertTrue(b.readings.all { it.measuredAt == NOON_WED && it.recordId == b.record.id && it.subjectId == b.record.subjectId })
        assertEquals(p.newMetrics.map { it.id }.toSet(), b.readings.map { it.metricId }.toSet())
    }

    @Test fun aSecondPoolNoteReusesTheSubjectAndMetrics() {
        val first = project("Pool test free chlorine 5 pH 7.2 alkalinity 70")
        val second = project(
            "Pool test free chlorine 3 pH 7.4 alkalinity 80",
            ctx(subjects = first.newSubjects, metrics = first.newMetrics), capture("x", id = "c2"),
        )
        assertTrue(second.newSubjects.isEmpty())
        assertTrue(second.newMetrics.isEmpty())
        assertEquals(first.newSubjects.single().id, second.bundles.single().record.subjectId)
        assertEquals(first.newMetrics.map { it.id }.toSet(), second.bundles.single().readings.map { it.metricId }.toSet())
    }

    @Test fun readingMeasuredAtNoonOfTheDayHeSaid() {
        val r = ExtractedRecord(RecordKind.READING, "pH", "pH 7.2 yesterday", subject = "pool",
            readings = listOf(ExtractedReading("pH", "7.2", 7.2)), whenExpr = DateExpr("yesterday", named = "yesterday"))
        val p = Projector.project(capture("pH 7.2 yesterday"), "x", listOf(CheckedRecord(r)), ctx())
        assertEquals("2026-11-10", p.bundles.single().record.happenedOn)
        assertEquals("yesterday", p.bundles.single().record.happenedText)
        assertEquals(Instant.parse("2026-11-10T12:00:00Z").toEpochMilli(), p.bundles.single().readings.single().measuredAt)
    }

    @Test fun tidePriceAtCostcoIsOneReadingWithAPlaceSubject() {
        val text = "The Tide is on sale for 24.99 at Costco"
        val p = project(text)
        val b = p.bundles.single()
        assertEquals(RecordKind.READING, b.record.kind)
        val reading = b.readings.single()
        assertEquals(24.99, reading.valueNum!!, 0.0)
        assertEquals("24.99", reading.valueText)
        assertEquals("price", p.newMetrics.single().key)
        val tide = p.newSubjects.first { it.normName == "tide" }
        val costco = p.newSubjects.first { it.normName == "costco" }
        assertEquals(SubjectType.PLACE, costco.type)
        assertEquals(tide.id, b.record.subjectId)
        assertEquals(tide.id, reading.subjectId)
        assertEquals(costco.id, b.record.placeSubjectId)
        assertEquals("Costco", b.record.placeText)
    }

    @Test fun anItemSubjectIsLinkedForATaskToo() {
        val p = project("I need to remember to buy ground beef at Costco")
        val b = p.bundles.single()
        assertEquals("ground beef", p.newSubjects.first { it.type == SubjectType.ITEM }.normName)
        assertEquals(p.newSubjects.first { it.type == SubjectType.ITEM }.id, b.record.subjectId)
        assertEquals(p.newSubjects.first { it.type == SubjectType.PLACE }.id, b.task!!.atPlaceSubjectId)
        assertEquals("""["shopping"]""", b.record.tags)
    }

    @Test fun subjectsFromASensitiveCaptureAreSensitive() {
        val p = project("Pool test free chlorine 5 pH 7.2", cap = capture("x", sensitive = true))
        assertTrue(p.newSubjects.all { it.sensitive })
    }

    @Test fun nearSubjectNameSetsSameAsAttention() {
        val alice = subject("a1", "Alice", SubjectType.PERSON)
        val r = ExtractedRecord(RecordKind.LOAN, "Drill to Alise", "I lent the drill to Alise", item = "drill", person = "Alise", direction = Direction.LENT)
        val p = Projector.project(capture("I lent the drill to Alise"), "x", listOf(CheckedRecord(r)), ctx(subjects = listOf(alice)))
        assertEquals(Attention.SAME_AS_PREFIX + "a1", p.bundles.single().record.attention)
        assertEquals(1, p.newSubjects.count { it.type == SubjectType.PERSON })
    }

    @Test fun anExistingAttentionReasonIsNotReplacedBySameAs() {
        val r = ExtractedRecord(RecordKind.REFERENCE, "t", "Alise", person = "Alise")
        val p = Projector.project(capture("Alise"), "x", listOf(CheckedRecord(r, Attention.BAD_QUOTE)),
            ctx(subjects = listOf(subject("a1", "Alice", SubjectType.PERSON))))
        assertEquals(Attention.BAD_QUOTE, p.bundles.single().record.attention)
    }

    @Test fun loanFillsWhereaboutsAndTheSaidDate() {
        val p = project("I loaned my guitar to Frank on January fifth")
        val b = p.bundles.single()
        assertEquals(Direction.LENT, b.whereabouts!!.direction)
        assertEquals("Frank", b.whereabouts!!.personText)
        assertEquals("January fifth", b.whereabouts!!.saidDateText)
        assertEquals("2026-01-05", b.record.happenedOn)
        assertNotNull(b.whereabouts!!.personSubjectId)
        assertEquals(SubjectType.PERSON, p.newSubjects.first { it.normName == "frank" }.type)
    }

    @Test fun placedSupersedesTheItemsNewestCurrentRecordAndPinsIt() {
        val glasses = subject("s1", "reading glasses")
        val older = ItemRecordRef("r-old", "c0", "s1", RecordKind.PLACED, Direction.PLACED, null, null, NOON_WED - 1000)
        val oldest = ItemRecordRef("r-oldest", "c-1", "s1", RecordKind.PLACED, Direction.PLACED, null, null, NOON_WED - 5000)
        val other = ItemRecordRef("r-keys", "c9", "s9", RecordKind.PLACED, Direction.PLACED, null, null, NOON_WED - 10)
        val p = project("I'm putting my reading glasses in the kitchen drawer", ctx(subjects = listOf(glasses), items = listOf(oldest, other, older)))
        assertEquals("r-old", p.bundles.single().record.supersedesRecordId)
        assertEquals(listOf("r-old"), p.olderToPin)
        assertTrue(p.newSubjects.isEmpty())
    }

    @Test fun aRecordFromTheSameCaptureOrALaterOneIsNotSuperseded() {
        val glasses = subject("s1", "reading glasses")
        val same = ItemRecordRef("r-same", "c1", "s1", RecordKind.PLACED, Direction.PLACED, null, null, NOON_WED - 1000)
        val later = ItemRecordRef("r-later", "c5", "s1", RecordKind.PLACED, Direction.PLACED, null, null, NOON_WED + 1000)
        val p = project("I'm putting my reading glasses in the kitchen drawer", ctx(subjects = listOf(glasses), items = listOf(same, later)))
        assertNull(p.bundles.single().record.supersedesRecordId)
        assertTrue(p.olderToPin.isEmpty())
    }

    @Test fun aLoanSupersedesAPlacement() {
        val guitar = subject("s1", "guitar")
        val placed = ItemRecordRef("r-placed", "c0", "s1", RecordKind.PLACED, Direction.PLACED, null, null, NOON_WED - 1000)
        val p = project("I loaned my guitar to Frank", ctx(subjects = listOf(guitar), items = listOf(placed)))
        assertEquals("r-placed", p.bundles.single().record.supersedesRecordId)
    }

    @Test fun twoPlacementsOfOneItemInOneCaptureChain() {
        val a = CheckedRecord(ExtractedRecord(RecordKind.PLACED, "a", "a", item = "keys", where = "in the bowl"))
        val b = CheckedRecord(ExtractedRecord(RecordKind.PLACED, "b", "b", item = "keys", where = "on the hook"))
        val p = Projector.project(capture("a b"), "x", listOf(a, b), ctx())
        assertNull(p.bundles[0].record.supersedesRecordId)
        assertEquals(p.bundles[0].record.id, p.bundles[1].record.supersedesRecordId)
        assertTrue(p.olderToPin.isEmpty())
    }

    @Test fun returnedClosesTheOpenLoanInsteadOfCompetingWithIt() {
        val guitar = subject("s1", "guitar")
        val loan = ItemRecordRef("r-loan", "c0", "s1", RecordKind.LOAN, Direction.LENT, null, null, NOON_WED - 1000)
        val p = project("Frank gave my guitar back", ctx(subjects = listOf(guitar), items = listOf(loan)))
        val rec = p.bundles.single()
        assertEquals(Direction.RETURNED, rec.whereabouts!!.direction)
        assertNull(rec.record.supersedesRecordId)
        assertEquals(1, p.returned.size)
        assertEquals("r-loan", p.returned.single().loanRecordId)
        assertEquals(NOON_WED, p.returned.single().returnedAt)
        assertEquals(listOf("r-loan"), p.olderToPin)
    }

    @Test fun returnedWithNoOpenLoanStillMakesTheRecord() {
        val p = project("Frank gave my guitar back", ctx(subjects = listOf(subject("s1", "guitar")),
            items = listOf(ItemRecordRef("r-p", "c0", "s1", RecordKind.PLACED, Direction.PLACED, null, null, 1))))
        assertTrue(p.returned.isEmpty())
        assertEquals(Direction.RETURNED, p.bundles.single().whereabouts!!.direction)
    }

    @Test fun anAlreadyReturnedLoanIsNotReturnedAgain() {
        val loan = ItemRecordRef("r-loan", "c0", "s1", RecordKind.LOAN, Direction.LENT, null, 50L, 1)
        val p = project("Frank gave my guitar back", ctx(subjects = listOf(subject("s1", "guitar")), items = listOf(loan)))
        assertTrue(p.returned.isEmpty())
    }

    @Test fun closesStoresTheLabelledTasksRecordIdAndNothingElse() {
        val e = ExtractedRecord(RecordKind.EVENT, "Cleaned the car", "I cleaned the car", closes = "T1")
        val p = Projector.project(capture("I cleaned the car"), "x", ExtractionChecks.check(listOf(e), "I cleaned the car", setOf("T1")),
            ctx(open = mapOf("T1" to "task-record-9")))
        assertEquals("task-record-9", p.bundles.single().record.claimsToCloseRecordId)
        assertNull(p.bundles.single().task)
    }

    @Test fun photoHintAndSpotAndPlaceReachTheRecord() {
        val text = "It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10"
        val p = project(text)
        assertTrue(p.photoHint)
        assertEquals("Italian section, row 5, bucket 10", p.bundles.single().record.spotText)
        assertNull(p.bundles.single().record.placeText)
        assertFalse(project("I cleaned out the car").photoHint)
    }

    @Test fun titleIsCutToEightyAndNeverBlank() {
        val long = "x".repeat(200)
        val p = Projector.project(capture(long), "x", listOf(CheckedRecord(ExtractedRecord(RecordKind.OTHER, "  ", long))), ctx())
        assertEquals(80, p.bundles.single().record.title.length)
        assertEquals(7L, p.bundles.single().record.createdAt)
        assertEquals(7L, p.bundles.single().record.updatedAt)
    }

    @Test fun aliasesAndTagsAreJsonLists() {
        val r = ExtractedRecord(RecordKind.REFERENCE, "t", "q", aliases = listOf("specs", "reading glasses"), tags = listOf("eyes"))
        val p = Projector.project(capture("q"), "x", listOf(CheckedRecord(r)), ctx())
        assertEquals("""["specs","reading glasses"]""", p.bundles.single().record.aliases)
        assertEquals("""["eyes"]""", p.bundles.single().record.tags)
    }
}
