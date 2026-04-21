package com.tezas.safestnotes.export

import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.security.SecurityManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds/parses the plaintext JSON blob that gets encrypted into the MP3.
 *
 * For [Note.isSecure] notes, the [Note.content] is *ciphertext* in the DB. We
 * decrypt it with the unlocked [SecurityManager] before serialising, so the
 * export holds the **plaintext** of every note. The export password is then
 * the only thing protecting those plaintexts.
 *
 * Format:
 *
 *   {
 *     "version": 1,
 *     "exportedAt": 1700000000000,
 *     "folders": [ {id, name, parentFolderId, accentColor, isSecure}, ... ],
 *     "notes":   [ {id, title, content, timestamp, createdTimestamp,
 *                   isFavorite, folderId, noteColor, isPinned}, ... ]
 *   }
 *
 * Fields irrelevant after restore (`isDeleted`, `deletedAt`, `secureMetadata`)
 * are intentionally dropped.
 */
object ExportPayload {

    const val VERSION = 1

    class BadPayloadException(msg: String) : Exception(msg)

    data class Parsed(val folders: List<Folder>, val notes: List<Note>)

    fun encode(folders: List<Folder>, notes: List<Note>, now: Long = System.currentTimeMillis()): ByteArray {
        val sm = SecurityManager.get()
        val foldersJson = JSONArray()
        folders.forEach { f ->
            foldersJson.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("parentFolderId", f.parentFolderId ?: JSONObject.NULL)
                    .put("accentColor", f.accentColor)
                    .put("isSecure", f.isSecure)
            )
        }
        val notesJson = JSONArray()
        notes.forEach { n ->
            val plaintext = if (n.isSecure && n.secureMetadata != null) {
                runCatching { sm.decryptNote(n.content, n.secureMetadata!!) }
                    .getOrElse { return@forEach }
            } else n.content
            notesJson.put(
                JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("content", plaintext)
                    .put("timestamp", n.timestamp)
                    .put("createdTimestamp", n.createdTimestamp)
                    .put("isFavorite", n.isFavorite)
                    .put("folderId", n.folderId ?: JSONObject.NULL)
                    .put("noteColor", n.noteColor)
                    .put("isPinned", n.isPinned)
                    .put("isSecure", n.isSecure)
            )
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("exportedAt", now)
            .put("folders", foldersJson)
            .put("notes", notesJson)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Parsed {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BadPayloadException("Malformed JSON: ${e.message}")
        }
        val version = root.optInt("version", 0)
        if (version != VERSION) throw BadPayloadException("Unsupported payload version $version")

        val fArr = root.optJSONArray("folders") ?: JSONArray()
        val folders = ArrayList<Folder>(fArr.length())
        for (i in 0 until fArr.length()) {
            val o = fArr.getJSONObject(i)
            folders.add(
                Folder(
                    id = o.optInt("id", 0),
                    name = o.optString("name", ""),
                    parentFolderId = if (o.isNull("parentFolderId")) null else o.optInt("parentFolderId"),
                    accentColor = o.optInt("accentColor", 0),
                    isSecure = o.optBoolean("isSecure", false)
                )
            )
        }
        val nArr = root.optJSONArray("notes") ?: JSONArray()
        val notes = ArrayList<Note>(nArr.length())
        for (i in 0 until nArr.length()) {
            val o = nArr.getJSONObject(i)
            val wasSecure = o.optBoolean("isSecure", false)
            val sm = SecurityManager.get()
            val plaintext = o.optString("content", "")
            val (storedContent, metadata, keepSecure) =
                if (wasSecure && sm.isUnlocked()) {
                    val (ct, meta) = sm.encryptNote(plaintext)
                    Triple(ct, meta, true)
                } else {
                    Triple(plaintext, null as String?, false)
                }
            notes.add(
                Note(
                    id = 0, // Let Room auto-assign; avoids collisions with existing DB rows.
                    title = o.optString("title", ""),
                    content = storedContent,
                    timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                    createdTimestamp = o.optLong("createdTimestamp", System.currentTimeMillis()),
                    isFavorite = o.optBoolean("isFavorite", false),
                    folderId = if (o.isNull("folderId")) null else o.optInt("folderId"),
                    isSecure = keepSecure,
                    secureMetadata = metadata,
                    noteColor = o.optInt("noteColor", 0),
                    isPinned = o.optBoolean("isPinned", false)
                )
            )
        }
        return Parsed(folders, notes)
    }
}
