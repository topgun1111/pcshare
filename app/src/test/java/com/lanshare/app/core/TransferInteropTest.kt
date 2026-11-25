package com.lanshare.app.core

import android.app.Application
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransferInteropTest {
    @Test fun uploadsAcrossImplementations() {
        val ctx = RuntimeEnvironment.getApplication()
        val shared = File(ctx.cacheDir,"storage").apply { mkdirs() }
        Core.appCtx = ctx
        Core.local = LocalFs(shared.path)
        Cfg.load(File(ctx.filesDir,"interop-config.json"),"test phone","")
        val input = File(shared,"source.bin")
        val block = ByteArray(262144) { (it*29).toByte() }
        input.outputStream().use { out -> repeat(65) { out.write(block) }; out.write(127) }
        val port = ServerSocket(0).use { it.localPort }
        val server = MiniHttp(port,Routes::handle).bind()
        server.start()
        try {
            val windowsPort = System.getenv("PCSHARE_WINDOWS_PORT")?.toInt()
            val id = if (windowsPort == null) Cfg.id else "pc:interop-windows"
            val peer = Peer(id,"127.0.0.1",windowsPort ?: port,"test",0,listOf("127.0.0.1"),true)
            val rel = "Folder/ö sample.bin"
            val items = JSONArray().put(PendingUpload.item(input.path,rel,input.length()))
            val outgoing = PcTransferNotice.beginOutgoing("send:test",peer.name,rel,input.length(),1)
            try {
                val upload = TransferUpload(ctx,peer,outgoing,PendingUpload(ctx,peer,items))
                assertTrue(upload.directory("Empty folder"))
                var acknowledged = 0L
                assertTrue(upload.send(rel,input.path,input.length(),{input.inputStream()}) { acknowledged += it })
                assertEquals(input.length(),acknowledged)
                upload.batchDone()
            } finally { PcTransferNotice.endOutgoing(outgoing,true) }
            fun digest(f: File) = f.inputStream().use { TransferV2.hash(it,f.length()) }
            val destination = System.getenv("PCSHARE_WINDOWS_ROOT")?.let { File(it) }
                ?: File(shared,"Download/pcshare")
            assertTrue(File(destination,"Empty folder").isDirectory)
            assertEquals(digest(input),digest(File(destination,rel)))
            val dll = System.getenv("PCSHARE_WINDOWS_CLIENT")
            if (dll != null) {
                val client = ProcessBuilder("dotnet",dll,Cfg.id,port.toString(),input.path).inheritIO().start()
                try {
                    assertTrue("Windows sender timed out",client.waitFor(120,TimeUnit.SECONDS))
                    assertEquals(0,client.exitValue())
                    assertEquals(digest(input),digest(File(shared,"Download/pcshare/Windows/ö sample.bin")))
                } finally { client.destroyForcibly() }
            }
        } finally { server.stop() }
    }
}
