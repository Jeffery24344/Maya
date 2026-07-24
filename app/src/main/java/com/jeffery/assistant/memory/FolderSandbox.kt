package com.jeffery.assistant.memory

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/**
 * Nicknamed folders the user has explicitly granted access to via Android's Storage
 * Access Framework — ported from Elara's ALLOWED_EXTRA_FOLDERS concept. Nova can
 * only ever touch a folder that's been granted here by name; there's no path-based
 * access to arbitrary locations on the device.
 */
class FolderSandbox(private val context: Context) {
    private val prefs = context.getSharedPreferences("nova_folders", Context.MODE_PRIVATE)

    fun addFolder(nickname: String, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val current = loadMap()
        current[nickname.trim().lowercase()] = treeUri.toString()
        save(current)
    }

    fun removeFolder(nickname: String) {
        val current = loadMap()
        current.remove(nickname.trim().lowercase())
        save(current)
    }

    fun nicknames(): List<String> = loadMap().keys.toList()

    private fun documentFor(nickname: String): DocumentFile? {
        val uriString = loadMap()[nickname.trim().lowercase()] ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uriString))
    }

    fun listFiles(nickname: String): List<String>? {
        val dir = documentFor(nickname) ?: return null
        return dir.listFiles().map { it.name ?: "unnamed" }
    }

    /** Returns up to ~4000 chars of a text file's content. */
    fun readFile(nickname: String, filename: String): String? {
        val dir = documentFor(nickname) ?: return null
        val file = dir.listFiles().firstOrNull { it.name.equals(filename, ignoreCase = true) } ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                stream.bufferedReader().readText().take(4000)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Creates (or overwrites, if it already exists) a text file with the given content. */
    fun writeFile(nickname: String, filename: String, content: String): Boolean {
        val dir = documentFor(nickname) ?: return false
        return try {
            val existing = dir.listFiles().firstOrNull { it.name.equals(filename, ignoreCase = true) }
            val target = existing ?: dir.createFile("text/plain", filename) ?: return false
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream ->
                stream.write(content.toByteArray())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadMap(): MutableMap<String, String> {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return mutableMapOf()
        val json = JSONObject(raw)
        val map = mutableMapOf<String, String>()
        json.keys().forEach { key -> map[key] = json.getString(key) }
        return map
    }

    private fun save(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_FOLDERS, json.toString()).apply()
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
    }
}
