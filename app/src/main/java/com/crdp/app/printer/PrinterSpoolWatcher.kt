package com.crdp.app.printer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.crdp.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches Context.getExternalFilesDir("printer_spool") for completed print
 * jobs spooled by the native printer-android backend. When a file's CLOSE_WRITE
 * fires the watcher posts a notification with a "View" PendingIntent backed by
 * a FileProvider URI, letting the user route the spool to any XPS-capable
 * viewer or onward to Android's print framework via share-sheet.
 *
 * Lifecycle:
 *   - One instance per Application (Hilt-scoped, see [PrinterSpoolModule]).
 *   - Started lazily on first use (init from [com.crdp.app.CrdpApplication]).
 *   - Survives process death gracefully — FileObserver is GC-anchored by the
 *     singleton reference, and the spool dir is the FreeRDP worker's only
 *     producer so no race with another writer is possible.
 *
 * The watcher does *not* convert XPS to PDF. Producing a print-ready PDF from
 * a Microsoft XPS package requires either a server-side change (install a PDF
 * pseudo-printer, redirect that instead) or a heavyweight Apache POI / FOP
 * port we don't ship. For now the file lands in app-private external storage
 * and the share-sheet path is good enough for the typical "send to printer"
 * use case.
 */
@Singleton
class PrinterSpoolWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var observer: FileObserver? = null

    @Synchronized
    fun start() {
        if (observer != null) return
        val dir = context.getExternalFilesDir(SPOOL_DIR_NAME)
            ?: File(context.filesDir, SPOOL_DIR_NAME).apply { mkdirs() }
        if (!dir.exists()) dir.mkdirs()

        ensureNotificationChannel()

        observer = makeObserver(dir).also { it.startWatching() }
    }

    @Synchronized
    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun makeObserver(dir: File): FileObserver {
        // CLOSE_WRITE fires when the backend's fclose() returns — the job is
        // complete and the file is safe to hand off. CREATE alone would race
        // with the streaming write path.
        val mask = FileObserver.CLOSE_WRITE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and CLOSE_WRITE != 0 && path != null) {
                        notifySpooled(File(dir, path))
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and CLOSE_WRITE != 0 && path != null) {
                        notifySpooled(File(dir, path))
                    }
                }
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Print jobs",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Spooled print jobs from remote sessions"
            },
        )
    }

    private fun notifySpooled(spooled: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        // The native backend always writes job-*.xps but the real payload
        // depends on which driver Windows used. Sniff the leading magic and
        // rename to a matching extension so external viewers / Android's
        // print framework pick the right handler.
        val (file, mime) = renameForPayload(spooled)

        // FileProvider for app-private external files. The grant flag on the
        // PendingIntent's wrapped Intent gives the receiving app read access.
        val authority = "${context.packageName}.printer.fileprovider"
        val uri: Uri = runCatching { FileProvider.getUriForFile(context, authority, file) }
            .getOrNull() ?: return

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Use Intent.createChooser so the user can pick a viewer or share
        // target without us guessing what's installed.
        val chooser = Intent.createChooser(view, "Open print job").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(share))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            file.name.hashCode(),
            chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Print job spooled: ${file.name}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "A remote print job was spooled to ${file.parentFile?.name}/${file.name}. Tap to open.",
            ))
            .setAutoCancel(true)
            .setContentIntent(pi)

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(file.name.hashCode(), builder.build())
        }
    }

    /**
     * Detect the actual payload format by reading the file's leading bytes
     * and rename to a matching extension. The native printer backend always
     * writes `.xps` but the real bytes depend on which Windows driver
     * rendered the job: with the `Microsoft Print to PDF` driver hint we now
     * use, payloads arrive as PDF; with Easy Print they're XPS; older
     * Windows builds occasionally hand us PostScript.
     */
    private fun renameForPayload(file: File): Pair<File, String> {
        val magic = runCatching {
            file.inputStream().use { stream ->
                ByteArray(5).also { stream.read(it) }
            }
        }.getOrElse { return file to "application/octet-stream" }

        val (ext, mime) = when {
            magic.size >= 4 && magic[0] == '%'.code.toByte()
                && magic[1] == 'P'.code.toByte()
                && magic[2] == 'D'.code.toByte()
                && magic[3] == 'F'.code.toByte() -> "pdf" to "application/pdf"
            magic.size >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() ->
                "xps" to "application/oxps"  // ZIP magic — XPS package
            magic.size >= 2 && magic[0] == '%'.code.toByte() && magic[1] == '!'.code.toByte() ->
                "ps" to "application/postscript"
            else -> "bin" to "application/octet-stream"
        }
        if (file.extension.equals(ext, ignoreCase = true)) return file to mime
        val renamed = File(file.parentFile, file.nameWithoutExtension + "." + ext)
        return if (file.renameTo(renamed)) renamed to mime else file to mime
    }

    private companion object {
        const val SPOOL_DIR_NAME = "printer_spool"
        const val CHANNEL_ID = "crdp_printer_spool"
    }
}
