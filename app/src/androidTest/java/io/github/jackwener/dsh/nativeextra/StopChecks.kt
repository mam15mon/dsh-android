package io.github.jackwener.dsh.nativeextra

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import dagger.hilt.android.EntryPointAccessors
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.ModelLimits
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real SDK turns against a local, indefinitely thinking SSE endpoint. No paid API calls. */
object StopChecks {
    suspend fun run(context: Context): Bundle = coroutineScope {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, EngineIdentityEntry::class.java)
        val engine = entry.engine(); val store = entry.sessions(); val settings = entry.settings()
        val trace = java.io.File(context.cacheDir, "stop-check-progress.txt")
        fun progress(value: String) { trace.appendText("$value\n") }
        progress("begin")
        settings.current().providers.filter { it.name == "Stop regression" }.forEach { settings.deleteProvider(it.id) }
        if (NativeRoot.selected(context).contains("/stop-workspace-")) NativeRoot.clearDirectory(context)
        val original = settings.current()
        val selected = NativeRoot.selected(context)
        val workspace = java.io.File(context.filesDir, "home/stop-workspace-${UUID.randomUUID()}").apply { mkdirs() }
        val rootDisabled = context.getSharedPreferences("native_root", 0).getBoolean("disabled_by_user", false)
        val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val requests = AtomicInteger()
        val sessions = mutableListOf<String>()
        val route = "stop-test-${UUID.randomUUID()}"
        val result = Bundle()
        val screenshot = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
        screenshot.eraseColor(android.graphics.Color.BLUE)
        val screenshotBytes = java.io.ByteArrayOutputStream().apply { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        screenshot.recycle()
        val image = "image/png" to android.util.Base64.encodeToString(screenshotBytes, android.util.Base64.NO_WRAP)
        val accept = launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                sockets += socket
                launch(Dispatchers.IO) {
                    try {
                        val input = socket.getInputStream()
                        fun line(): String = buildString {
                            while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) append(b.toChar()) }
                        }
                        val first = line()
                        var size = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                        }
                        val bodyBytes = ByteArray(size)
                        var received = 0
                        while (received < size) { val count = input.read(bodyBytes, received, size - received); check(count > 0); received += count }
                        val out = socket.getOutputStream()
                        if (first.contains("/chat/completions")) {
                            check(bodyBytes.toString(Charsets.UTF_8).contains("image_url")) { "Screenshot did not reach the model request" }
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write("data: {\"id\":\"stop-test\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"Still thinking\"},\"finish_reason\":null}]}\n\n".toByteArray())
                            out.flush(); requests.incrementAndGet()
                            while (isActive) { delay(200); out.write(": thinking\n\n".toByteArray()); out.flush() }
                        } else {
                            val body = "{\"data\":[{\"id\":\"stop-test\"}]}"
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()); out.flush()
                        }
                    } catch (_: java.io.IOException) { }
                    finally { socket.close() }
                }
            }
        }
        try {
            settings.setEngineKeepAlive(false)
            NativeRoot.saveDirectory(context, workspace.path)
            settings.saveProvider(ProviderConfig(route, "custom", "Stop regression", "http://127.0.0.1:${server.localPort}", listOf("stop-test"), limits = mapOf("stop-test" to ModelLimits(images = true))))
            settings.setModel(route, "stop-test")
            for (root in listOf(true, false)) {
                progress("request identity root=$root")
                RootGrant.setAllowed(context, root)
                if (root && !RootGrant.check(context)) { result.putString("root_status", "NOT_RUN: su is unavailable to the test package"); break }
                withTimeout(90000) { while (true) { engine.control(); if (engine.isRootEngine == root) break; delay(100) } }
                progress("identity ready root=$root")
                check(engine.isRootEngine == root)
                repeat(2) { attempt ->
                    val id = if (attempt == 0) UUID.randomUUID().toString().also { sessions += it } else sessions.last()
                    val count = requests.get()
                    val prompt = async(Dispatchers.Default) { store.prompt(id, "Describe this screenshot after you have finished thinking", images = listOf(image)) }
                    progress("prompt root=$root attempt=$attempt")
                    withTimeout(90000) {
                        while (requests.get() <= count) {
                            if (prompt.isCancelled) prompt.await()
                            val failed = store.state(id).value.events.lastOrNull { it.type == "app/error" }
                            check(failed == null) { "Screenshot prompt failed before reaching endpoint: ${failed?.data}" }
                            delay(50)
                        }
                    }
                    check(store.state(id).value.running)
                    val cwd = engine.control().request("session/working-directory/get", buildJsonObject { put("sessionId", id) }).jsonObject["cwd"]!!.jsonPrimitive.content
                    val expectedCwd = if (root) workspace else java.io.File(context.filesDir, "home")
                    check(java.io.File(cwd).canonicalFile == expectedCwd.canonicalFile) { "Unexpected resumed workspace: $cwd" }
                    val began = SystemClock.elapsedRealtime()
                    progress("stop root=$root attempt=$attempt")
                    withTimeout(5000) { store.stop() }
                    progress("stopped root=$root attempt=$attempt")
                    val elapsed = SystemClock.elapsedRealtime() - began
                    check(!store.state(id).value.running && !store.hasRunning())
                    withTimeout(5000) { prompt.join() }
                    progress("prompt joined root=$root attempt=$attempt")
                    check(store.state(id).value.events.any { it.type == "app/stopped" })
                    check(store.state(id).value.events.none { it.type == "app/error" }) { "Intentional stop was reported as an error" }
                    progress("restart root=$root attempt=$attempt")
                    withTimeout(90000) { engine.control() }
                    progress("restarted root=$root attempt=$attempt")
                    check(engine.isRootEngine == root)
                    check(NativeRoot.selected(context) == workspace.path)
                    result.putString("${if (root) "root" else "app"}_stop_$attempt", "PASS: real PNG reached model endpoint; thinking stopped in ${elapsed}ms; engine restarted")
                }
            }
            result.putString("workspace_resume", "PASS: same conversation retained saved Root workspace after restart; App identity used private home without clearing the selection")
        } finally {
            progress("cleanup")
            store.stop()
            settings.setModel(original.provider, original.model)
            settings.deleteProvider(route)
            RootGrant.setAllowed(context, !rootDisabled)
            if (selected.isEmpty()) NativeRoot.clearDirectory(context) else NativeRoot.saveDirectory(context, selected)
            settings.setEngineKeepAlive(original.engineKeepAlive)
            sessions.forEach { store.delete(it) }
            workspace.delete()
            server.close(); sockets.forEach { runCatching { it.close() } }; accept.cancelAndJoin()
            progress("finished")
        }
        result
    }
}
