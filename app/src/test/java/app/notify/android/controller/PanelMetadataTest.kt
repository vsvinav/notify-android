package app.notify.android.controller

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PanelMetadataTest {
    private fun tab() = JSONObject("""{"id":"notes","label":"Notes","sections":[{"title":"Review","emphasis":"attention","items":[
        {"id":"one","title":"First","log":{"entries":[{"at":"Today","text":"Exact note"}],"truncated":false}},
        {"id":"two","title":"Second"}]}],
        "form":{"version":1,"actionId":"capture","title":"Add a note","submitLabel":"Review note","placeholder":"A short note","minLength":1,"maxLength":300},
        "track":{"version":1,"title":"Progress","stages":[{"id":"start","label":"Start","kind":"normal"},{"id":"review","label":"Review","hint":"Human review","kind":"gate"},
        {"id":"choose","label":"Choose","kind":"decision"}],"placements":[{"itemId":"two","stageId":"review"}]}}""")
    private fun parse(tab: JSONObject) = Snapshots.panel(JSONObject().put("available", true).put("panel",
        JSONObject().put("version", 1).put("tabs", JSONArray().put(tab)))).feed!!.tabs.single()

    @Test fun additiveDisplayAndFormAreTypedAndKeepProviderOrder() {
        val parsed = parse(tab())
        assertEquals("capture", parsed.form!!.actionId)
        assertEquals(listOf("start", "review", "choose"), parsed.track!!.stages.map { it.id })
        assertEquals(listOf(PanelPlacement("two", "review")), parsed.track!!.placements)
        assertEquals(PanelStageKind.Gate, parsed.track!!.stages[1].kind)
        assertTrue(parsed.sections.single().attention)
        assertEquals(listOf(PanelLogEntry("Today", "Exact note")), parsed.sections.single().items.first().log!!.entries)
    }

    @Test fun malformedTrackFallsBackWithoutLosingCardsOrOtherMetadata() {
        for (bad in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("track").put("version", 2) },
            { it.getJSONObject("track").getJSONArray("stages").getJSONObject(1).put("id", "start") },
            { it.getJSONObject("track").getJSONArray("stages").getJSONObject(0).put("kind", "done") },
            { it.getJSONObject("track").getJSONArray("placements").put(JSONObject("""{"itemId":"missing","stageId":"review"}""")) },
            { it.getJSONObject("track").getJSONArray("placements").put(JSONObject("""{"itemId":"two","stageId":"start"}""")) },
            { it.getJSONObject("track").getJSONArray("placements").getJSONObject(0).put("stageId", "missing") },
            { it.getJSONObject("track").getJSONArray("stages").getJSONObject(0).put("hint", "bad\nhint") },
            { it.getJSONArray("sections").put(JSONObject("""{"title":"Again","items":[{"id":"two","title":"Ambiguous"}]}""")) }
        )) {
            val wire = tab().also(bad)
            val parsed = parse(wire)
            assertNull(parsed.track)
            assertEquals(listOf("one", "two"), parsed.sections.first().items.map { it.id })
            assertNotNull(parsed.form)
        }
    }

    @Test fun malformedFormDoesNotHideTheFeedOrTrack() {
        for ((key, value) in listOf("version" to 2, "actionId" to "bad id", "title" to "",
            "submitLabel" to "x".repeat(25), "placeholder" to "bad\u0000", "minLength" to 0,
            "maxLength" to 301, "maxLength" to 1.5)) {
            val wire = tab()
            wire.getJSONObject("form").put(key, value)
            val parsed = parse(wire)
            assertNull("$key=$value", parsed.form)
            assertNotNull(parsed.track)
        }
        assertNull(parse(tab().apply { remove("form"); remove("track") }).form)
    }

    @Test fun logsOmitInvalidEntriesAndShowTruncationWithoutInventingTranscript() {
        val wire = tab()
        val item = wire.getJSONArray("sections").getJSONObject(0).getJSONArray("items").getJSONObject(0)
        item.getJSONObject("log").getJSONArray("entries").put(JSONObject().put("text", "bad\nline"))
            .put(JSONObject().put("text", "Next")).put("invalid")
        val log = parse(wire).sections.single().items.first().log!!
        assertEquals(listOf("Exact note", "Next"), log.entries.map { it.text })
        assertTrue(log.truncated)
        item.put("log", JSONObject().put("entries", JSONArray((1..10).map { JSONObject().put("text", "$it") })).put("truncated", false))
        assertEquals(8, parse(wire).sections.single().items.first().log!!.entries.size)
        assertTrue(parse(wire).sections.single().items.first().log!!.truncated)
        item.getJSONObject("log").put("entries", "not a list")
        assertNull(parse(wire).sections.single().items.first().log)
    }

    @Test fun validUntitledSectionsKeepItemsAndTheirTrackPlacements() {
        val wire = tab()
        wire.getJSONArray("sections").getJSONObject(0).put("title", "")
        val parsed = parse(wire)
        assertEquals(listOf("one", "two"), parsed.sections.flatMap { it.items }.map { it.id })
        assertNotNull(parsed.track)
    }

    @Test fun optionalDisplayTextUsesNonemptyWireRuleWithoutTrimming() {
        val wire = tab()
        wire.getJSONObject("form").put("title", " ").put("placeholder", "")
        wire.getJSONObject("track").getJSONArray("stages").getJSONObject(0).put("label", " ")
        wire.getJSONArray("sections").getJSONObject(0).getJSONArray("items").getJSONObject(0)
            .getJSONObject("log").getJSONArray("entries").getJSONObject(0).put("text", " ")
        val parsed = parse(wire)
        assertEquals(" ", parsed.form?.title)
        assertEquals("", parsed.form?.placeholder)
        assertEquals(" ", parsed.track?.stages?.first()?.label)
        assertEquals(" ", parsed.sections.single().items.first().log?.entries?.first()?.text)
    }
}
