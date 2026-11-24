package io.github.mam15mon.dsh.nativeextra

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mam15mon.dsh.BuildConfig
import io.github.mam15mon.dsh.core.capability.Frame
import io.github.mam15mon.dsh.core.capability.StableIds
import io.github.mam15mon.dsh.core.capability.UiElement
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Lives in the privileged process for both Root and Shizuku; nodes never cross a UID boundary. */
class ServiceScreen(private val exec: (String, Long) -> String) {
    private val thread = HandlerThread("dsh-device-ui").apply { start() }
    private var connected: UiAutomation? = null
    private val automation: UiAutomation get() = connected ?: run {
        val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val type = Class.forName("android.app.IUiAutomationConnection")
        val ui = UiAutomation::class.java.getConstructor(Looper::class.java, type).newInstance(thread.looper, connection)
        UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType).invoke(ui, 1) // do not suppress accessibility services
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ui.waitForIdle(100, 5_000)
        connected = ui
        ui
    }
    private val views = object : LinkedHashMap<String, StableIds>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StableIds>?) = size > 64
    }
    private class Read(val frame: Frame, val nodes: List<AccessibilityNodeInfo>)

    private fun read(session: String, display: Int): Read {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val elements = ArrayList<UiElement>()
        val windows = (if (android.os.Build.VERSION.SDK_INT >= 30) automation.windowsOnAllDisplays.get(display).orEmpty()
            else { require(display == 0); automation.windows }).mapNotNull { w -> w.root?.let { w to it } }
        val apps = windows.filter { it.first.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val shown = apps.filter { it.second.packageName?.toString() != BuildConfig.APPLICATION_ID }.ifEmpty { apps }
            .sortedByDescending { it.first.isActive || it.first.isFocused }
        shown.forEach { walk(it.second, 0, false, nodes, elements) }
        windows.filter { it.first.type == AccessibilityWindowInfo.TYPE_SYSTEM }.forEach { walk(it.second, 0, true, nodes, elements) }
        val pkg = shown.firstOrNull()?.second?.packageName?.toString()
        val ids = views.getOrPut("$session|$display") { StableIds() }
        return Read(ids.assign(pkg, elements), nodes)
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, system: Boolean, nodes: MutableList<AccessibilityNodeInfo>, elements: MutableList<UiElement>) {
        if (!n.isVisibleToUser || depth >= 40 || elements.size >= 1500) return
        val text = if (n.isPassword) null else n.text?.toString()?.take(120)
        val desc = n.contentDescription?.toString()?.take(120)
        val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        if (interesting) {
            val bounds = Rect().also(n::getBoundsInScreen)
            val flags = buildList {
                if (n.isClickable) add("click"); if (n.isLongClickable) add("long"); if (n.isEditable) add("edit")
                if (n.isScrollable) add("scroll"); if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (!n.isEnabled) add("disabled"); if (n.isFocused) add("focused")
            }
            nodes += n
            elements += UiElement(n.className?.toString()?.substringAfterLast('.') ?: "View", text, desc,
                n.viewIdResourceName?.substringAfter(":id/"), bounds.centerX(), bounds.centerY(), depth, flags, n.isEditable, system)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, system, nodes, elements) }
    }

    private fun index(session: String, display: Int, args: JSONObject, r: Read): Int? {
        val id = args.optString("node").takeIf { it.isNotBlank() }
        if (id != null) return when (val found = views.getValue("$session|$display").resolve(StableIds.normalize(id), r.frame)) {
            is StableIds.Lookup.Found -> found.index
            is StableIds.Lookup.Relocated -> found.index
            else -> error("Element $id is stale or unknown; call screen_read again")
        }
        val text = args.optString("text").takeIf { it.isNotBlank() } ?: return null
        return r.frame.elements.indexOfFirst { it.text == text || it.desc == text }.takeIf { it >= 0 }
            ?: r.frame.elements.indexOfFirst { it.label?.contains(text, true) == true }.takeIf { it >= 0 }
            ?: error("No element with text '$text'; call screen_read again")
    }

    private fun command(cmd: String) {
        val output = exec(cmd, 15_000)
        check(output.substringBefore('\n') == "0") { output.substringAfter('\n').take(1000) }
    }

    @Synchronized fun invoke(method: String, raw: String, session: String, display: Int): String = try {
        val args = JSONObject(raw)
        var before = read(session, display)
        if (method == "screen_wait") {
            require(args.has("text") || args.has("package")) { "Give text and/or package" }
            val until = SystemClock.elapsedRealtime() + args.optInt("timeoutMs", 10000).coerceIn(500, 30000)
            while (true) {
                val text = args.optString("text")
                val found = before.frame.elements.any { it.label?.contains(text, true) == true }
                val textOk = !args.has("text") || if (args.optBoolean("gone")) !found else found
                if (textOk && (!args.has("package") || before.frame.packageName == args.getString("package"))) break
                if (SystemClock.elapsedRealtime() >= until) return result(before, "Timed out waiting").toString()
                Thread.sleep(500); before = read(session, display)
            }
        }
        if (method == "screen_read" || method == "screen_wait") result(before, "Screen ($display)").toString()
        else {
            when (method) {
                "screen_tap" -> {
                    val i = index(session, display, args, before)
                    val x = i?.let { before.frame.elements[it].x } ?: args.getInt("x")
                    val y = i?.let { before.frame.elements[it].y } ?: args.getInt("y")
                    require(x >= 0 && y >= 0)
                    command(if (args.optString("kind") == "long") "input -d $display swipe $x $y $x $y 700" else "input -d $display tap $x $y")
                }
                "screen_swipe" -> {
                    val xy = listOf("x1", "y1", "x2", "y2").map { args.getInt(it) }
                    require(xy.all { it >= 0 })
                    command("input -d $display swipe ${xy.joinToString(" ")} ${args.optInt("durationMs", 300).coerceIn(50, 5000)}")
                }
                "screen_scroll" -> {
                    val i = index(session, display, args, before)
                    val node = i?.let { before.nodes[it] } ?: before.nodes.firstOrNull { it.isScrollable }
                    val direction = args.getString("direction")
                    require(direction in listOf("down", "up", "left", "right"))
                    check(node != null && node.performAction(if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                        "No scrollable node accepted the action; use screen_swipe with coordinates"
                    }
                }
                "screen_type" -> {
                    val i = index(session, display, JSONObject().apply { if (args.has("node")) put("node", args.getString("node")) }, before)
                    val node = i?.let { before.nodes[it] } ?: before.nodes.firstOrNull { it.isEditable && it.isFocused }
                    check(node != null && node.isEditable) { "No editable field is focused; tap the field first" }
                    val text = args.getString("text")
                    val value = if (args.optBoolean("append")) node.text?.toString().orEmpty() + text else text
                    check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) {
                        "This field does not support setting text through its UI node"
                    }
                    if (args.optBoolean("submit")) command("input -d $display keyevent 66")
                }
                "screen_key" -> {
                    val key = args.getString("key")
                    if (key == "notifications" || key == "quick_settings") {
                        require(display == 0) { "The shade is only available on the main screen" }
                        command("cmd statusbar ${if (key == "notifications") "expand-notifications" else "expand-settings"}")
                    } else {
                        val code = mapOf("back" to 4, "home" to 3, "recents" to 187, "enter" to 66, "search" to 84, "delete" to 67, "lock_screen" to 223)[key] ?: error("Invalid key")
                        command("input -d $display keyevent $code")
                    }
                }
                "screen_set_wheel" -> error("Wheel-specific node operations are not implemented by the privileged screen backend; use screen_swipe or choose accessibility")
                else -> error("Unsupported screen method $method")
            }
            Thread.sleep(400)
            val after = read(session, display)
            result(after, "Action sent; verify the screen below").put("unchanged", before.frame.fingerprint == after.frame.fingerprint).toString()
        }
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("text", e.cause?.message ?: e.message ?: e.javaClass.simpleName).toString()
    }

    private fun result(r: Read, title: String) = JSONObject().put("ok", true).put("fingerprint", r.frame.fingerprint)
        .put("readable", r.frame.readableCount).put("text", "$title · ${r.frame.packageName ?: "unknown app"}\n${r.frame.render().take(24000)}")

    @Synchronized fun capture(): ByteArray {
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable or protected")
        return try { ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Synchronized fun close() { connected?.let { UiAutomation::class.java.getMethod("disconnect").invoke(it) }; connected = null; thread.quitSafely() }
}
