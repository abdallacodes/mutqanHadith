package com.mutqan.hadith.domain.backup

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A backup that parsed and is structurally sound, but has not been checked against the library or applied. */
data class ParsedBackup(val formatVersion: Int, val exportedAt: Long, val data: UserData)

/**
 * The backup file format, version [VERSION]:
 *
 * ```
 * { "format": "mutqan-hadith-backup", "version": 1, "exportedAt": <epoch ms>,
 *   "masteryRecords": [ { "hadithId", "matnStability", "sanadStability",
 *                         "matnLastReviewedAt" | null, "sanadLastReviewedAt" | null,
 *                         "nextReviewDue", "repetitionCount" } ],
 *   "narratorReviewEvents": [ { "id", "hadithId", "narratorId", "reviewedAt", "succeeded" } ] }
 * ```
 *
 * Timestamps and ids are written as JSON integers and stabilities as doubles holding the exact
 * float value, so a round trip reproduces every field bit for bit. Decoding is strict: wrong types
 * (a number as a string, a fraction as an id), negative values and duplicate keys are rejected
 * instead of coerced, because a restore replaces the user's data.
 */
object BackupCodec {
    const val FORMAT = "mutqan-hadith-backup"
    const val VERSION = 1

    fun encode(data: UserData, exportedAt: Long): String {
        val mastery = JSONArray()
        data.mastery.sortedBy { it.hadithId }.forEach { r ->
            mastery.put(
                JSONObject()
                    .put("hadithId", r.hadithId)
                    .put("matnStability", r.matnStability.toDouble())
                    .put("sanadStability", r.sanadStability.toDouble())
                    .put("matnLastReviewedAt", r.matnLastReviewedAt ?: JSONObject.NULL)
                    .put("sanadLastReviewedAt", r.sanadLastReviewedAt ?: JSONObject.NULL)
                    .put("nextReviewDue", r.nextReviewDue)
                    .put("repetitionCount", r.repetitionCount),
            )
        }
        val events = JSONArray()
        data.events.sortedBy { it.id }.forEach { e ->
            events.put(
                JSONObject()
                    .put("id", e.id)
                    .put("hadithId", e.hadithId)
                    .put("narratorId", e.narratorId)
                    .put("reviewedAt", e.reviewedAt)
                    .put("succeeded", e.succeeded),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("masteryRecords", mastery)
            .put("narratorReviewEvents", events)
            .toString()
    }

    /** @throws BackupException when [text] is not a valid backup of a version this app understands. */
    fun decode(text: String): ParsedBackup {
        if (text.isBlank()) throw BackupException.NotABackup("empty file")
        val root = try {
            JSONObject(text.removePrefix("﻿"))
        } catch (e: JSONException) {
            throw BackupException.NotABackup("not a JSON object")
        }
        if (root.opt("format") != FORMAT) throw BackupException.NotABackup("missing or foreign format marker")

        try {
            val version = root.intField("version")
            if (version > VERSION) throw BackupException.UnsupportedVersion(version, VERSION)
            if (version < 1) throw BackupException.Corrupt("version $version")

            val mastery = root.arrayField("masteryRecords").objects().mapIndexed { i, o -> o.mastery("masteryRecords[$i]") }
            val events = root.arrayField("narratorReviewEvents").objects().mapIndexed { i, o -> o.event("narratorReviewEvents[$i]") }

            requireUnique(mastery.map { it.hadithId }, "masteryRecords", "hadithId")
            requireUnique(events.map { it.id }.filter { it != 0L }, "narratorReviewEvents", "id")

            return ParsedBackup(version, root.timestamp("exportedAt", "backup"), UserData(mastery, events))
        } catch (e: BackupException) {
            throw e
        } catch (e: JSONException) {
            throw BackupException.Corrupt(e.message.orEmpty(), e)
        }
    }

    private fun JSONObject.mastery(path: String): MasteryRecordEntity {
        val matnStability = floatField("matnStability", path)
        val sanadStability = floatField("sanadStability", path)
        val repetitions = longField("repetitionCount", path)
        if (repetitions !in 0..Int.MAX_VALUE) corrupt(path, "repetitionCount out of range")
        return MasteryRecordEntity(
            hadithId = longField("hadithId", path),
            matnStability = matnStability,
            sanadStability = sanadStability,
            matnLastReviewedAt = nullableTimestamp("matnLastReviewedAt", path),
            sanadLastReviewedAt = nullableTimestamp("sanadLastReviewedAt", path),
            nextReviewDue = timestamp("nextReviewDue", path),
            repetitionCount = repetitions.toInt(),
        )
    }

    private fun JSONObject.event(path: String) = NarratorReviewEventEntity(
        id = longField("id", path).also { if (it < 0) corrupt(path, "id is negative") },
        hadithId = longField("hadithId", path),
        narratorId = longField("narratorId", path),
        reviewedAt = timestamp("reviewedAt", path),
        succeeded = opt("succeeded") as? Boolean ?: corrupt(path, "succeeded is not true or false"),
    )

    // -- Strict field access ---------------------------------------------------------------------

    private fun corrupt(path: String, problem: String): Nothing = throw BackupException.Corrupt("$path: $problem")

    private fun JSONObject.arrayField(name: String): JSONArray =
        opt(name) as? JSONArray ?: throw BackupException.Corrupt("$name is missing or not a list")

    private fun JSONArray.objects(): List<JSONObject> =
        List(length()) { i -> opt(i) as? JSONObject ?: throw BackupException.Corrupt("list entry $i is not an object") }

    private fun JSONObject.intField(name: String): Int {
        val value = longField(name)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw BackupException.Corrupt("$name out of range")
        return value.toInt()
    }

    /** An integer: JSON numbers without a fraction or exponent, never strings or decimals. */
    private fun JSONObject.longField(name: String, path: String = "backup"): Long {
        val value = opt(name)
        return if (value is Int) value.toLong() else value as? Long ?: corrupt(path, "$name is missing or not an integer")
    }

    private fun JSONObject.timestamp(name: String, path: String): Long =
        longField(name, path).also { if (it < 0) corrupt(path, "$name is negative") }

    private fun JSONObject.nullableTimestamp(name: String, path: String): Long? {
        if (!has(name)) corrupt(path, "$name is missing")
        return if (isNull(name)) null else timestamp(name, path)
    }

    private fun JSONObject.floatField(name: String, path: String): Float {
        val value = opt(name) as? Number ?: corrupt(path, "$name is missing or not a number")
        val result = value.toDouble().toFloat()
        if (!result.isFinite() || result < 0f) corrupt(path, "$name must be a finite number of days, zero or more")
        return result
    }

    private fun requireUnique(keys: List<Long>, list: String, field: String) {
        val seen = HashSet<Long>(keys.size)
        for (key in keys) if (!seen.add(key)) throw BackupException.Corrupt("$list: duplicate $field $key")
    }
}
