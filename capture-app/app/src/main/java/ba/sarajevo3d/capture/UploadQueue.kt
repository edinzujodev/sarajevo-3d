package ba.sarajevo3d.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Waiting list of photos to send to the server.
 *
 * Every photo is added here. A background job (WorkManager) sends the list
 * whenever the phone is on Wi-Fi, also when the app is closed, and tries again
 * later if the server can't be reached (for example on another Wi-Fi).
 */
object UploadQueue {
    private const val PREFS = "queue"
    private const val KEY = "pending"
    private const val WORK_NAME = "upload-photos"

    @Synchronized
    fun add(context: Context, uri: String) {
        val p = prefs(context)
        val set = p.getStringSet(KEY, emptySet())!!.toMutableSet()
        set.add(uri)
        p.edit().putStringSet(KEY, set).commit()
        schedule(context)
    }

    @Synchronized
    fun remove(context: Context, uri: String) {
        val p = prefs(context)
        val set = p.getStringSet(KEY, emptySet())!!.toMutableSet()
        set.remove(uri)
        p.edit().putStringSet(KEY, set).commit()
    }

    @Synchronized
    fun pending(context: Context): List<String> =
        prefs(context).getStringSet(KEY, emptySet())!!.sorted()

    fun count(context: Context): Int = pending(context).size

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts (or restarts) the background sending job: runs when on Wi-Fi. */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val server = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("server", "")!!.trimEnd('/')
        if (server.isEmpty()) return Result.success() // nothing to send to; photos stay waiting

        // Keep going until the list is empty (photos taken meanwhile are included).
        while (true) {
            val next = UploadQueue.pending(ctx).firstOrNull() ?: break
            when (send(server, next)) {
                Outcome.SENT, Outcome.DROP -> UploadQueue.remove(ctx, next)
                Outcome.RETRY -> return Result.retry()
            }
            setLastResult(ctx)
        }
        return Result.success()
    }

    private enum class Outcome { SENT, DROP, RETRY }

    private fun setLastResult(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong("last_upload", System.currentTimeMillis()).apply()
    }

    private fun send(server: String, uriString: String): Outcome {
        val ctx = applicationContext
        val uri = android.net.Uri.parse(uriString)
        val input = try {
            ctx.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return Outcome.DROP // photo was deleted from the phone

        return try {
            val boundary = "----sarajevo3d" + System.currentTimeMillis()
            val conn = URL("$server/upload").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(64 * 1024)
            val name = "IMG_" + uri.lastPathSegment + ".jpg"
            conn.outputStream.use { out ->
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; " +
                    "filename=\"$name\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray())
                input.use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            when {
                code in 200..299 -> {
                    val json = JSONObject(body)
                    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                        .putString("last_place", "Last sent: place has ${json.optInt("photos")} photos" +
                            (json.optInt("needed_for_3d").let { if (it > 0) ", $it more for 3D" else ", 3D is being built" }))
                        .apply()
                    Outcome.SENT
                }
                code in 400..499 -> Outcome.DROP // e.g. no GPS in the photo: server will never accept it
                else -> Outcome.RETRY
            }
        } catch (e: Exception) {
            Outcome.RETRY // server not reachable (other Wi-Fi, PC off): try again later
        }
    }
}
