package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import java.util.concurrent.Executors

/** Android owns the modern delete confirmation; older releases use an explicit app confirmation. */
class PhotoDeletion(private val activity: Activity, private val finished: (Int) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private var remaining = mutableListOf<Uri>()
    private var deleted = 0
    private var batch = 0
    private var working = false
    val inProgress: Boolean get() = remaining.isNotEmpty() || working

    fun restore(state: Bundle?) {
        remaining = state?.getStringArrayList("deleteUris")?.map(Uri::parse)?.toMutableList() ?: mutableListOf()
        deleted = state?.getInt("deletedCount") ?: 0
        batch = state?.getInt("deleteBatch") ?: 0
    }
    fun resume() {
        if (remaining.isNotEmpty() && batch == 0 && !working) advance()
    }
    fun save(state: Bundle) {
        state.putStringArrayList("deleteUris", ArrayList(remaining.map { it.toString() }))
        state.putInt("deletedCount", deleted); state.putInt("deleteBatch", batch)
    }
    fun delete(uris: List<Uri>) {
        if (remaining.isNotEmpty() || working || uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            remaining = uris.distinct().toMutableList(); deleted = 0; advance()
        } else AlertDialog.Builder(activity).setTitle("Delete ${uris.size} photo(s)?")
            .setMessage("These original files will be permanently deleted from this device.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                remaining = uris.distinct().toMutableList(); deleted = 0
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                if (Build.VERSION.SDK_INT <= 28 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION)
                else advance()
            }.show()
    }
    private fun advance() {
        if (activity.isDestroyed) return
        if (remaining.isEmpty()) { complete(); return }
        if (Build.VERSION.SDK_INT >= 30) {
            // API 36 limits each request to 2000 URIs.
            batch = minOf(remaining.size, 2000)
            runCatching {
                val request = MediaStore.createDeleteRequest(activity.contentResolver, remaining.take(batch))
                activity.startIntentSenderForResult(request.intentSender, REQUEST, null, 0, 0, 0)
            }.onFailure { complete("Could not request deletion. Refresh the gallery and try again.") }
        } else {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            working = true
            val uri = remaining.first()
            worker.execute {
                val result = runCatching { activity.contentResolver.delete(uri, null, null) }
                activity.runOnUiThread {
                    working = false
                    if (activity.isDestroyed) return@runOnUiThread
                    result.fold(onSuccess = { count ->
                        if (count > 0) deleted++
                        remaining.removeAt(0); advance()
                    }, onFailure = { error ->
                        if (Build.VERSION.SDK_INT == 29 && error is RecoverableSecurityException) {
                            batch = 1
                            runCatching { activity.startIntentSenderForResult(error.userAction.actionIntent.intentSender,
                                REQUEST, null, 0, 0, 0) }.onFailure { complete("Deletion was not permitted.") }
                        } else complete("Could not delete this photo. It may no longer be accessible.")
                    })
                }
            }
        }
    }
    fun onActivityResult(code: Int, result: Int): Boolean {
        if (code != REQUEST) return false
        if (result != Activity.RESULT_OK) { complete("Deletion canceled."); return true }
        if (Build.VERSION.SDK_INT >= 30) {
            deleted += batch; remaining = remaining.drop(batch).toMutableList()
        } // Android 10 grants access, then we must retry delete ourselves.
        batch = 0
        advance(); return true
    }
    fun onPermissionsResult(code: Int, results: IntArray): Boolean {
        if (code != PERMISSION) return false
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) advance() else complete("Deletion permission was not granted.")
        return true
    }
    private fun complete(message: String? = null) {
        val count = deleted
        if (Build.VERSION.SDK_INT <= 29) activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        remaining.clear(); batch = 0; deleted = 0
        Toast.makeText(activity, message ?: "Deleted $count photo(s)", Toast.LENGTH_SHORT).show()
        finished(count)
    }
    fun close() { worker.shutdownNow() }
    companion object { private const val REQUEST = 3001; private const val PERMISSION = 3002 }
}
