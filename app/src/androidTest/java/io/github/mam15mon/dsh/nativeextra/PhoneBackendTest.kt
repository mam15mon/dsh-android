package io.github.mam15mon.dsh.nativeextra

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import io.github.mam15mon.dsh.core.platform.ShellAccess
import io.github.mam15mon.dsh.core.platform.ShizukuShell
import io.github.mam15mon.dsh.core.capability.CallContext
import io.github.mam15mon.dsh.core.capability.ScreenOcr
import io.github.mam15mon.dsh.core.capability.Content
import kotlinx.serialization.json.JsonObject

/** Separate test APK: exercises the real Root Binder service without calling a model. */
class PhoneBackendTest : Instrumentation() {
    private var filesOnly = false
    private var mountOnly = false
    private var appOnly = false
    private var ballPersistence = ""
    private var ballOnly = false
    private var engineOnly = false
    private var stopOnly = false
    override fun onCreate(arguments: Bundle?) { stopOnly = arguments?.getString("stop_only") == "true"; ballPersistence = arguments?.getString("ball_persistence").orEmpty(); ballOnly = arguments?.getString("ball_only") == "true"; engineOnly = arguments?.getString("engine_only") == "true"; filesOnly = arguments?.getString("files_only") == "true"; mountOnly = arguments?.getString("mount_only") == "true"; appOnly = arguments?.getString("app_only") == "true"; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        if (stopOnly) {
            try { finish(Activity.RESULT_OK, runBlocking { StopChecks.run(targetContext) }) }
            catch (e: Throwable) { result.putString("failure", android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result) }
            return
        }
        if (ballPersistence.isNotEmpty()) {
            try { finish(Activity.RESULT_OK, BallPersistenceChecks.run(targetContext, ballPersistence)) }
            catch (e: Throwable) { result.putString("failure", android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result) }
            return
        }
        if (ballOnly) {
            try { finish(Activity.RESULT_OK, runBlocking { BallBackendChecks.run(targetContext, this@PhoneBackendTest) }) }
            catch (e: Throwable) { result.putString("failure", android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result) }
            return
        }
        if (engineOnly) {
            try {
                runBlocking {
                    RootGrant.setAllowed(targetContext, true)
                    val engine = dagger.hilt.android.EntryPointAccessors.fromApplication(targetContext.applicationContext, EngineIdentityEntry::class.java).engine()
                    withTimeout(90000) { engine.control().request("account/state") }
                    result.putString("engine_sdk", "PASS: account/state answered without a model call")
                    check(engine.isRootEngine) { "Expected authorized Root engine" }
                    val privateTemp = java.io.File(targetContext.cacheDir, "tmp/root-engine")
                    val spillCheck = NativeRoot.exec(targetContext, "find ${NativeRoot.quote(privateTemp.path)} -maxdepth 1 -name 'dsh-spill-*' -exec stat -c '%u %a' {} +")
                    check(spillCheck.code == 0 && spillCheck.output.trim().isNotEmpty() && spillCheck.output.trim().lines().all { it == "0 700" }) { "Root spill ownership is unsafe: $spillCheck" }
                    check(!java.io.File(targetContext.filesDir, "logs/engine.log").readText().contains("skipped unsafe root"))
                    result.putString("root_spill", "PASS: real DSH spill directories retain uid 0 and mode 0700")
                    repeat(2) {
                        val gc = async(Dispatchers.Default) { repeat(60) { System.gc(); delay(100) } }
                        try { withTimeout(20000) { engine.stop() } } finally { gc.cancel() }
                        check(engine.state.value !is io.github.mam15mon.dsh.core.engine.EngineState.Failed) { "Engine stop failed under GC" }
                        withTimeout(90000) { engine.control().request("account/state") }
                        check(engine.isRootEngine)
                    }
                    result.putString("engine_gc_restart", "PASS: two real Root engine stop/restart cycles under forced GC")
                    val selected = NativeRoot.selected(targetContext)
                    var generation = engine.generation
                    val capabilities = dagger.hilt.android.EntryPointAccessors.fromApplication(targetContext.applicationContext, EngineIdentityEntry::class.java).capabilities()
                    val work = async(Dispatchers.Default) {
                        capabilities.invoke(buildJsonObject {
                            put("tool", "phone_sh"); put("callId", "root-switch-sleep-test")
                            putJsonObject("args") { put("cmd", "sleep 2"); put("timeout_s", 5) }
                        }, viaEngine = true)
                    }
                    withTimeout(3000) { while (capabilities.liveCalls.value.isEmpty() && !work.isCompleted) delay(20) }
                    check(!work.isCompleted) { "Test tool did not enter execution" }
                    delay(200)
                    RootGrant.setAllowed(targetContext, false)
                    delay(500)
                    check(engine.generation == generation && engine.isRootEngine) { "Root was switched while a device tool was still running" }
                    check(work.await().ok) { "In-flight tool failed during Root disable" }
                    result.putString("deferred_switch", "PASS: waited for real in-flight phone command")
                    withTimeout(30000) { while (engine.generation <= generation || engine.state.value != io.github.mam15mon.dsh.core.engine.EngineState.Ready || engine.isRootEngine) delay(100) }
                    withTimeout(10000) { engine.control().request("account/state") }
                    check(NativeRoot.selected(targetContext) == selected)
                    check(!RootGrant.check(targetContext)) { "Manual Root disable was not respected" }
                    result.putString("root_to_app", "PASS: SDK ready, project preserved, explicit disable respected")
                    generation = engine.generation
                    val page = startActivitySync(Intent(targetContext, NativeRootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    runOnMainSync { page.moveTaskToBack(true) }
                    delay(1200)
                    // Simulate permission becoming eligible while backgrounded, without calling the switch API.
                    targetContext.getSharedPreferences("native_root", 0).edit().putBoolean("disabled_by_user", false).commit()
                    targetContext.startActivity(Intent(targetContext, NativeRootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    try {
                        withTimeout(30000) { while (engine.generation <= generation || !engine.isRootEngine) delay(100) }
                    } finally { runOnMainSync { page.finish() } }
                    result.putString("foreground_recheck", "PASS: background-to-foreground checked real su and switched without a manual restart")
                    withTimeout(10000) { engine.control().request("account/state") }
                    check(NativeRoot.selected(targetContext) == selected)
                    result.putString("app_to_root", "PASS: SDK ready, original project preserved")
                    val file = java.io.File(targetContext.filesDir, "home/root-ownership-test-${System.nanoTime()}")
                    try {
                        val proc = ProcessBuilder("su", "--mount-master", "-c", "umask 077; printf test > ${NativeRoot.quote(file.path)}").start()
                        check(proc.waitFor() == 0)
                        withTimeout(5000) { while (android.system.Os.stat(file.path).st_uid != android.os.Process.myUid()) delay(50) }
                        check(file.readText() == "test")
                        result.putString("state_ownership", "PASS: Root-created private file returned to App uid")
                    } finally { file.delete() }
                }
                finish(Activity.RESULT_OK, result)
            } catch (e: Throwable) { result.putString("failure", android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result) }
            return
        }
        if (filesOnly) {
            System.setProperty("java.io.tmpdir", targetContext.cacheDir.path)
            val tests = org.junit.runner.JUnitCore.runClasses(io.github.mam15mon.dsh.core.platform.ShellFilesTest::class.java)
            result.putString("files_tests", "${tests.runCount} tests; ${tests.failureCount} failures; ${tests.failures}")
            finish(if (tests.wasSuccessful()) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        val wasEnabled = NativeRoot.enabled(targetContext)
        var code = Activity.RESULT_CANCELED
        try {
            NativeRoot.setEnabled(targetContext, true)
            runBlocking {
                val service = RootConnection.service(targetContext)
                check(service.exec("id -u", 5000).trim() == "0\n0") { "service uid is not root" }
                result.putString("identity", "root")
                if (appOnly) {
                    val activity = startActivitySync(Intent(targetContext, NativeRootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    try {
                        fun invoke(method: String, args: JSONObject): String {
                            val r = JSONObject(service.screen(method, args.toString(), "app-picker-test", 0))
                            check(r.getBoolean("ok")) { r.toString() }
                            return r.getString("text")
                        }
                        fun waitFor(text: String): String = invoke("screen_wait", JSONObject().put("text", text).put("timeoutMs", 5000)).also { check(!it.contains("Timed out")) { it } }
                        fun tap(text: String) { waitFor(text); invoke("screen_tap", JSONObject().put("text", text)) }
                        tap("添加项目"); tap("所有应用数据")
                        val read = waitFor("搜索应用名或包名")
                        val id = Regex("\\[(e\\d+)\\] EditText").find(read)!!.groupValues[1]
                        invoke("screen_type", JSONObject().put("node", id).put("text", "DSH"))
                        waitFor("com.dshmobile.nativeexperiment")
                        result.putString("name_search", "PASS")
                        tap("取消"); tap("所有应用数据")
                        val current = waitFor("搜索应用名或包名")
                        val search = Regex("\\[(e\\d+)\\] EditText").find(current)!!.groupValues[1]
                        invoke("screen_type", JSONObject().put("node", search).put("text", "com.dshmobile.nativeexperiment"))
                        val matches = targetContext.packageManager.getInstalledApplications(0).count { it.packageName.contains("com.dshmobile.nativeexperiment") }
                        waitFor("$matches 个应用"); result.putString("package_search", "PASS")
                        tap("系统应用"); waitFor("没有匹配的应用")
                        tap("用户应用"); waitFor("$matches 个应用")
                        // Tap the labelled application row, not the matching text inside the search box.
                        tap("DSH 安卓·原生实验")
                        tap("内部数据 · 配置、数据库、私有文件")
                        waitFor(targetContext.applicationInfo.dataDir)
                        result.putString("filters_and_app_directory", "PASS")
                    } finally { runOnMainSync { activity.finish() } }
                    return@runBlocking
                }
                val rootPath = "/data/user/${android.os.Process.myUid() / 100000}"
                val expected = NativeRoot.exec(targetContext, "find $rootPath -mindepth 1 -maxdepth 1 -type d | wc -l")
                check(expected.code == 0)
                val visible = service.fsList(rootPath, false, 2000).lineSequence().count { it.startsWith("d\t") }
                check(visible == expected.output.trim().toInt()) { "Root service sees $visible app directories; global root sees ${expected.output.trim()}" }
                result.putString("global_root_view", "PASS ($visible app directories)")
                if (mountOnly) {
                    val raw = context.cacheDir.path + "/root-workspace-test-${System.nanoTime()}"
                    val canonical = NativeRoot.exec(targetContext, "mkdir -p ${NativeRoot.quote(raw)} && realpath -- ${NativeRoot.quote(raw)}")
                    check(canonical.code == 0)
                    val source = canonical.output.trim()
                    val target = java.io.File(targetContext.cacheDir.canonicalFile, "mount-test-${System.nanoTime()}")
                    check(target.mkdir())
                    val helper = java.io.File(targetContext.applicationInfo.nativeLibraryDir, "libdshworkspace.so")
                    var process: Process? = null
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(service.fsOpen("$source/sentinel", "w")).use { it.write("before".toByteArray()) }
                        process = ProcessBuilder("su", "--mount-master", "-c", "exec timeout 15 ${NativeRoot.quote(helper.path)} ${android.os.Process.myPid()} ${android.os.Process.myUid()} ${NativeRoot.quote(source)} ${NativeRoot.quote(target.path)}").redirectErrorStream(true).start()
                        check(process.inputStream.bufferedReader().readLine() == "READY") { "临时直连挂载失败" }
                        val file = java.io.File(target, "sentinel")
                        check(file.readText() == "before")
                        file.writeText("after")
                        check(ParcelFileDescriptor.AutoCloseInputStream(service.fsOpen("$source/sentinel", "r")).use { it.readBytes().decodeToString() } == "after")
                        result.putString("isolated_app_workspace", "PASS (read/write original test-app cache through FUSE)")
                    } finally {
                        process?.outputStream?.close()
                        process?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        check(target.delete()) { "临时挂载未正常卸载" }
                        service.fsDelete(source)
                    }
                    return@runBlocking
                }
                val activity = startActivitySync(Intent(targetContext, NativeRootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                try {
                    for (text in listOf("添加项目", "手动输入路径")) {
                        val waiting = JSONObject(service.screen("screen_wait", JSONObject().put("text", text).put("timeoutMs", 5000).toString(), "backend-test", 0))
                        check(waiting.getBoolean("ok") && !waiting.getString("text").contains("Timed out")) { waiting.toString() }
                        val tapped = JSONObject(service.screen("screen_tap", JSONObject().put("text", text).toString(), "backend-test", 0))
                        check(tapped.getBoolean("ok")) { tapped.toString() }
                    }
                    val read = JSONObject(service.screen("screen_wait", "{\"text\":\"绝对路径\",\"timeoutMs\":5000}", "backend-test", 0))
                    check(read.getBoolean("ok")) { read.toString() }
                    check(read.getString("text").contains("EditText")) { read.toString() }
                    result.putString("tree", "PASS")
                    val shell = PhoneShell(targetContext, ShizukuShell(targetContext, ShellAccess(targetContext)))
                    check(shell.available && shell.rootMode && shell.label == "Root")
                    check(shell.exec("id -u", 5000).output.trim() == "0")
                    val screen = PrivilegedScreen(targetContext, shell, ScreenOcr())
                    val call = CallContext("backend-route-test", "test", false)
                    val routed = screen.invoke("screen_read", JsonObject(emptyMap()), call, 0)
                    check(routed.ok && routed.content.filterIsInstance<Content.Text>().any { it.text.contains("EditText") })
                    check(screen.invoke("screen_capture", JsonObject(emptyMap()), call, 0).content.any { it is Content.Image })
                    result.putString("unified_tool_route", "PASS")
                    val fieldId = Regex("\\[(e\\d+)\\] EditText").find(read.getString("text"))!!.groupValues[1]
                    val tap = JSONObject(service.screen("screen_tap", JSONObject().put("node", fieldId).toString(), "backend-test", 0))
                    check(tap.getBoolean("ok")) { tap.toString() }
                    val type = JSONObject(service.screen("screen_type", JSONObject().put("node", fieldId).put("text", "统一后端测试123").toString(), "backend-test", 0))
                    check(type.getBoolean("ok") && type.getString("text").contains("统一后端测试123")) { type.toString() }
                    result.putString("tap_type_readback", "PASS")
                    val wait = JSONObject(service.screen("screen_wait", "{\"text\":\"统一后端测试123\",\"timeoutMs\":500}", "backend-test", 0))
                    check(wait.getBoolean("ok") && !wait.getString("text").contains("Timed out"))
                    val key = JSONObject(service.screen("screen_key", "{\"key\":\"delete\"}", "backend-test", 0))
                    check(key.getBoolean("ok") && key.getString("text").contains("统一后端测试12"))
                    result.putString("wait_key", "PASS")
                    val image = ParcelFileDescriptor.AutoCloseInputStream(service.captureMain()).use { it.readBytes() }
                    check(image.take(4).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
                    result.putString("screenshot", "PASS (${image.size} bytes)")
                    val file = "/data/local/tmp/dsh-backend-test-${System.nanoTime()}"
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(service.fsOpen(file, "w")).use { it.write("root-file-test".toByteArray()) }
                        check(ParcelFileDescriptor.AutoCloseInputStream(service.fsOpen(file, "r")).use { it.readBytes().decodeToString() } == "root-file-test")
                        result.putString("root_file", "PASS")
                    } finally { service.fsDelete(file) }
                    val display = service.createDisplay(480, 800, 160)
                    check(display >= 0) { service.lastDisplayError() ?: "display creation failed" }
                    try { check(service.displayFlags(display) >= 0); result.putString("virtual_display", "PASS ($display)") }
                    finally { service.releaseDisplay(display) }
                } finally { runOnMainSync { activity.finish() } }
            }
            code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("failure", android.util.Log.getStackTraceString(e))
        } finally {
            runBlocking { RootConnection.disconnect() }
            NativeRoot.setEnabled(targetContext, wasEnabled)
        }
        finish(code, result)
    }
}
