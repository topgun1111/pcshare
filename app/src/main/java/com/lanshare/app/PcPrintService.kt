package com.lanshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.io.File
import java.io.IOException

/**
 * Makes LANShare show up as a printer ("LANShare - print on PC") in Android's own print dialog, so
 * "Print" in ANY app / file manager can pick it. The finished PDF is copied to <storage>/LANShare Shared/
 * and the native FilesActivity opens LANShare's own print dialog (PC picker, layout options, preview) for it.
 *
 * The user has to switch the service on once: Settings > Connected devices > Printing > LANShare.
 */
class PcPrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: List<PrinterId>) { addPrinters(listOf(printer())) }
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: List<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) { addPrinters(listOf(printer())) }
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }

    private fun printer(): PrinterInfo {
        val id = generatePrinterId("lanshare-pc")
        val caps = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A3, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
            .addResolution(PrintAttributes.Resolution("r300", "300 dpi", 300, 300), true)
            .setColorModes(PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        return PrinterInfo.Builder(id, "LANShare - print on PC", PrinterInfo.STATUS_IDLE)
            .setDescription("Choose the PC and options in LANShare")
            .setCapabilities(caps)
            .build()
    }

    override fun onRequestCancelPrintJob(job: PrintJob) { job.cancel() }

    override fun onPrintJobQueued(job: PrintJob) {
        Thread {
            try {
                job.start()
                val dir = File(Environment.getExternalStorageDirectory(), "LANShare Shared")
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No storage access - allow \"All files access\" for LANShare")
                var base = (job.info.label ?: "document").replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(80).ifEmpty { "document" }
                if (!base.lowercase().endsWith(".pdf")) base += ".pdf"
                var f = File(dir, base); var k = 1
                while (f.exists()) { f = File(dir, "${base.removeSuffix(".pdf")} ($k).pdf"); k++ }
                val pfd: ParcelFileDescriptor = job.document.data ?: throw IOException("No document data")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins -> f.outputStream().use { ins.copyTo(it) } }
                job.complete()
                openInApp(f.name)
            } catch (e: Exception) {
                try { job.fail(e.message ?: "LANShare could not read the document") } catch (_: Exception) {}
            }
        }.start()
    }

    /** Open LANShare's print dialog for the file. Android 10+ may block a background start, so a tap-to-open notification is posted too. */
    private fun openInApp(name: String) {
        val nid = 7000 + (name.hashCode() and 0xFFF)
        val i = Intent(this, FilesActivity::class.java)
            .setAction(FilesActivity.ACTION_PRINT_SHARED)
            .putExtra("names", arrayOf(name)).putExtra("nid", nid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(this, nid, i, flags)
            val b = if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("lanshare_print", "Print requests", NotificationManager.IMPORTANCE_HIGH))
                Notification.Builder(this, "lanshare_print")
            } else @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
            nm.notify(nid, b.setSmallIcon(R.drawable.ic_notification).setContentTitle("Print with LANShare")
                .setContentText(name).setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Exception) {}
        try { startActivity(i) } catch (_: Exception) {}
    }
}
