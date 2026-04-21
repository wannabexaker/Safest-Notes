package com.tezas.safestnotes.export

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Glue between a [Fragment] and the [Mp3ExportCodec]. Handles SAF pickers,
 * the "what to export" selection UI, password prompts, and coroutine dispatch.
 *
 * Usage inside a Fragment:
 *
 *     private val exportFlow = Mp3ExportFlow(this).also { it.register() }
 *     ...
 *     exportFlow.startExport()   // user tapped Export
 *     exportFlow.startImport()   // user tapped Import
 */
class Mp3ExportFlow(private val fragment: Fragment) {

    private lateinit var exportPicker: ActivityResultLauncher<String>
    private lateinit var importPicker: ActivityResultLauncher<Array<String>>

    /** Set by the selection dialog before the SAF picker opens. */
    private var pendingSelection: Selection? = null

    private data class Selection(val folders: List<Folder>, val notes: List<Note>)

    fun register() {
        exportPicker = fragment.registerForActivityResult(
            ActivityResultContracts.CreateDocument("audio/mpeg")
        ) { uri ->
            val sel = pendingSelection
            pendingSelection = null
            if (uri == null || sel == null) return@registerForActivityResult
            promptPassword(confirm = true, title = "Set export password") { pw ->
                runExport(uri, pw, sel)
            }
        }
        importPicker = fragment.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri ?: return@registerForActivityResult
            promptPassword(confirm = false, title = "Enter export password") { pw ->
                runImport(uri, pw)
            }
        }
    }

    fun startExport() {
        val ctx = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val (folders, notes) = withContext(Dispatchers.IO) {
                val db = NotesDatabase.getDatabase(ctx)
                val repo = NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao())
                repo.getAllFoldersOnce() to repo.getAllNotesOnce().filter { !it.isDeleted }
            }
            if (notes.isEmpty()) {
                toast("Nothing to export")
                return@launch
            }
            showWhatToExportDialog(folders, notes)
        }
    }

    fun startImport() {
        importPicker.launch(arrayOf("audio/mpeg", "application/octet-stream", "*/*"))
    }

    // ─── Selection UI ─────────────────────────────────────────────────────────

    private fun showWhatToExportDialog(folders: List<Folder>, notes: List<Note>) {
        val ctx = fragment.requireContext()
        val options = arrayOf(
            "Export all (${notes.size} notes, ${folders.size} folders)",
            "Select notes…",
            "Select folders…"
        )
        MaterialAlertDialogBuilder(ctx, R.style.SafestNotes_AlertDialog)
            .setTitle("What to export?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchSavePicker(Selection(folders, notes))
                    1 -> pickNotes(folders, notes)
                    2 -> pickFolders(folders, notes)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickNotes(allFolders: List<Folder>, allNotes: List<Note>) {
        val ctx = fragment.requireContext()
        val labels = allNotes.map { n ->
            val t = if (n.title.isBlank()) "(untitled)" else n.title
            val lock = if (n.isSecure) " \uD83D\uDD12" else ""
            "$t$lock"
        }.toTypedArray()
        val checked = BooleanArray(allNotes.size) { true }
        MaterialAlertDialogBuilder(ctx, R.style.SafestNotes_AlertDialog)
            .setTitle("Select notes (${allNotes.size})")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Next") { _, _ ->
                val picked = allNotes.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) {
                    toast("Select at least one note")
                    return@setPositiveButton
                }
                val neededFolderIds = picked.mapNotNull { it.folderId }.toSet()
                val foldersSubset = expandParents(allFolders, neededFolderIds)
                launchSavePicker(Selection(foldersSubset, picked))
            }
            .setNeutralButton("Select all") { _, _ ->
                launchSavePicker(Selection(allFolders, allNotes))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickFolders(allFolders: List<Folder>, allNotes: List<Note>) {
        val ctx = fragment.requireContext()
        if (allFolders.isEmpty()) {
            toast("No folders exist")
            return
        }
        val labels = allFolders.map { it.name.ifBlank { "(unnamed)" } }.toTypedArray()
        val checked = BooleanArray(allFolders.size) { false }
        MaterialAlertDialogBuilder(ctx, R.style.SafestNotes_AlertDialog)
            .setTitle("Select folders")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Next") { _, _ ->
                val pickedFolders = allFolders.filterIndexed { i, _ -> checked[i] }
                if (pickedFolders.isEmpty()) {
                    toast("Select at least one folder")
                    return@setPositiveButton
                }
                val ids = pickedFolders.map { it.id }.toSet()
                val notes = allNotes.filter { it.folderId in ids }
                val withParents = expandParents(allFolders, ids)
                launchSavePicker(Selection(withParents, notes))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun expandParents(all: List<Folder>, ids: Set<Int>): List<Folder> {
        val byId = all.associateBy { it.id }
        val result = LinkedHashSet<Folder>()
        ids.forEach { id ->
            var cur: Folder? = byId[id]
            while (cur != null && result.add(cur)) {
                cur = cur.parentFolderId?.let { byId[it] }
            }
        }
        return result.toList()
    }

    private fun launchSavePicker(selection: Selection) {
        pendingSelection = selection
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportPicker.launch("safestnotes-$ts.mp3")
    }

    // ─── Run ──────────────────────────────────────────────────────────────────

    private fun runExport(uri: android.net.Uri, password: CharArray, selection: Selection) {
        val ctx = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val payload = ExportPayload.encode(selection.folders, selection.notes)
                    ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
                        Mp3ExportCodec.pack(ctx, password, payload, out)
                    } ?: throw IllegalStateException("Cannot open output stream")
                    selection.notes.size
                }
                toast("Exported $count notes")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun runImport(uri: android.net.Uri, password: CharArray) {
        val ctx = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            try {
                val (folderCount, noteCount) = withContext(Dispatchers.IO) {
                    val decrypted = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        Mp3ExportCodec.unpack(input, password)
                    } ?: throw IllegalStateException("Cannot open input stream")
                    val parsed = ExportPayload.decode(decrypted)
                    val db = NotesDatabase.getDatabase(ctx)
                    val repo = NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao())
                    val folderIdMap = HashMap<Int, Int>()
                    parsed.folders.forEach { f ->
                        val newId = repo.insertFolderAndGetId(f.copy(id = 0))
                        folderIdMap[f.id] = newId
                    }
                    parsed.notes.forEach { n ->
                        val remapped = n.copy(folderId = n.folderId?.let { folderIdMap[it] })
                        repo.insert(remapped)
                    }
                    parsed.folders.size to parsed.notes.size
                }
                toast("Imported $folderCount folders, $noteCount notes")
            } catch (e: Mp3ExportCodec.WrongPasswordException) {
                toast("Incorrect password")
            } catch (e: Mp3ExportCodec.BadFileException) {
                toast("Not a SafestNotes backup: ${e.message}")
            } catch (e: ExportPayload.BadPayloadException) {
                toast("Malformed backup: ${e.message}")
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            } finally {
                password.fill('\u0000')
            }
        }
    }

    // ─── Password dialog with show-password eye ───────────────────────────────

    private fun promptPassword(
        confirm: Boolean,
        title: String,
        onOk: (CharArray) -> Unit
    ) {
        val ctx = fragment.requireContext()
        val pad = (ctx.resources.displayMetrics.density * 20).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val til1 = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Password (min 8 chars)"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val pw1 = TextInputEditText(til1.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        til1.addView(pw1)

        val til2 = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Confirm password"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            visibility = if (confirm) View.VISIBLE else View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
            layoutParams = lp
        }
        val pw2 = TextInputEditText(til2.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        til2.addView(pw2)

        val warning = TextView(ctx).apply {
            text = if (confirm)
                "Losing this password makes the backup unrecoverable."
            else
                "Existing notes in this vault will stay; imported items are added."
            alpha = 0.7f
            textSize = 12f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
            layoutParams = lp
        }
        container.addView(til1)
        container.addView(til2)
        container.addView(warning)

        MaterialAlertDialogBuilder(ctx, R.style.SafestNotes_AlertDialog)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val p1 = pw1.text?.toString().orEmpty()
                val p2 = pw2.text?.toString().orEmpty()
                when {
                    p1.length < 8 -> toast("Password must be at least 8 characters")
                    confirm && p1 != p2 -> toast("Passwords do not match")
                    else -> onOk(p1.toCharArray())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        val ctx = fragment.context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
