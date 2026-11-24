package io.github.mam15mon.dsh.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.designsystem.BtnVariant
import io.github.mam15mon.dsh.core.designsystem.DButton
import io.github.mam15mon.dsh.core.designsystem.DIcon
import io.github.mam15mon.dsh.core.designsystem.DTopBar
import io.github.mam15mon.dsh.core.designsystem.Dsh
import io.github.mam15mon.dsh.core.designsystem.DshMarkdown
import io.github.mam15mon.dsh.core.designsystem.DshType
import io.github.mam15mon.dsh.core.designsystem.HDivider
import io.github.mam15mon.dsh.core.designsystem.IconBtn
import io.github.mam15mon.dsh.core.designsystem.Radius
import io.github.mam15mon.dsh.core.designsystem.Segmented
import io.github.mam15mon.dsh.core.designsystem.Tag
import io.github.mam15mon.dsh.core.designsystem.Tone
import io.github.mam15mon.dsh.core.designsystem.copyText
import io.github.mam15mon.dsh.core.designsystem.pressable
import io.github.mam15mon.dsh.core.platform.ShellFiles
import io.github.mam15mon.dsh.nativeextra.PhoneShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** The folder relative links in a Markdown text start from; null = the conversation's workspace ([FileLinks.workspace]). */
val LocalLinkBase = staticCompositionLocalOf<String?> { null }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FileViewerEntryPoint {
    fun phoneShell(): PhoneShell
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Text(val text: String, val size: Long?, val truncated: Boolean) : Loaded {
        val lines: List<String> by lazy { text.lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } }
    }
    data class Picture(val bitmap: ImageBitmap, val size: Long?, val width: Int, val height: Int) : Loaded
    data class Binary(val size: Long?) : Loaded
    data class Failed(val message: String) : Loaded
}

private const val TEXT_CAP = 256 * 1024
private const val MARKDOWN_CHARS = 120_000
private const val IMAGE_CAP = 24 * 1024 * 1024
private const val OPEN_CAP = 200L * 1024 * 1024
private const val MAX_IMAGE_SIDE = 4096
private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
private val MARKDOWN = setOf("md", "markdown", "mdx")
private val CODE = setOf(
    "py", "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json", "jsonl", "yml", "yaml", "toml", "xml", "html", "css",
    "sh", "bash", "c", "h", "cpp", "hpp", "cc", "rs", "go", "rb", "php", "lua", "sql", "gradle", "ini", "cfg", "conf", "properties", "swift",
)

private class Unreadable(message: String) : Exception(message)
private class Source(val input: InputStream, val size: Long?)

private fun extOf(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

/**
 * Opens [path] for reading: directly when the app may (shared storage with All files access, its own
 * files), else through Root or Shizuku (Android/data, a Root project) like the file_* tools.
 */
private suspend fun openFile(context: Context, path: String): Source {
    val f = File(path)
    if (f.isFile && f.canRead()) return Source(f.inputStream(), f.length())
    val shell = EntryPointAccessors.fromApplication(context.applicationContext, FileViewerEntryPoint::class.java).phoneShell()
    if (!shell.available) throw Unreadable(context.getString(if (f.isDirectory) R.string.viewer_is_folder else R.string.viewer_no_access))
    return try {
        val svc = shell.service()
        val row = svc.fsStat(path)?.let { ShellFiles.parseRow(it) } ?: throw Unreadable(context.getString(R.string.viewer_not_found))
        if (row.dir) throw Unreadable(context.getString(R.string.viewer_is_folder))
        Source(ParcelFileDescriptor.AutoCloseInputStream(svc.fsOpen(path, "r")), row.size)
    } catch (e: CancellationException) { throw e } catch (e: Unreadable) { throw e } catch (e: Exception) {
        throw Unreadable(context.getString(R.string.viewer_failed, e.message ?: e.javaClass.simpleName))
    }
}

private suspend fun load(context: Context, path: String): Loaded = try {
    val src = openFile(context, path)
    if (extOf(path) in IMAGES) {
        val (bytes, more) = ViewerBytes.readCapped(src.input, IMAGE_CAP)
        (if (more) null else decodeImage(bytes, src.size)) ?: Loaded.Binary(src.size)
    } else {
        val (bytes, more) = ViewerBytes.readCapped(src.input, TEXT_CAP)
        if (ViewerBytes.looksBinary(bytes)) Loaded.Binary(src.size)
        else Loaded.Text(ViewerBytes.decode(bytes, more).first, src.size, more)
    }
} catch (e: CancellationException) { throw e } catch (e: Unreadable) {
    Loaded.Failed(e.message.orEmpty())
} catch (e: Exception) {
    Loaded.Failed(context.getString(R.string.viewer_failed, e.message ?: e.javaClass.simpleName))
}

private fun decodeImage(bytes: ByteArray, size: Long?): Loaded.Picture? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return Loaded.Picture(bitmap.asImageBitmap(), size, bounds.outWidth, bounds.outHeight)
}

/** Copies [path] to the cache and offers it to other apps (the copy keeps Android/data and Root files shareable). */
private suspend fun openWithOtherApp(context: Context, path: String) {
    val name = path.substringAfterLast('/').ifEmpty { "file" }
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "open-with").apply { deleteRecursively(); mkdirs() }
            val copy = File(dir, name)
            var total = 0L
            openFile(context, path).input.use { input ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n = input.read(buf)
                    while (n >= 0) {
                        total += n
                        if (total > OPEN_CAP) throw Unreadable(context.getString(R.string.viewer_too_large))
                        out.write(buf, 0, n); n = input.read(buf)
                    }
                }
            }
            copy
        }
    }
    val copy = result.getOrElse { e ->
        if (e is CancellationException) throw e
        Toast.makeText(context, e.message ?: context.getString(R.string.viewer_no_app), Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(copy.extension.lowercase()) ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.viewer_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun human(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.1f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}

@DrawableRes private fun iconOf(ext: String): Int = when (ext) {
    in MARKDOWN -> R.drawable.dsw_deliver_doc
    in CODE -> R.drawable.dsw_code_outline
    in IMAGES -> R.drawable.dsw_fullscreen_outline
    "txt", "log", "csv", "tsv", "" -> R.drawable.dsw_list_pen_outline
    else -> R.drawable.dsw_paperclip_outline
}

@Composable
private fun kindOf(ext: String): String = when (ext) {
    in MARKDOWN -> "Markdown"
    "" -> stringResource(R.string.viewer_kind_text)
    else -> ext.uppercase()
}

/** Full-screen read-only viewer for a file a reply links to: text, Markdown, images; other types open elsewhere. */
@Composable
fun FileViewerDialog(path: String, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(shown, enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 16 }) {
            ViewerPage(path, onDismiss)
        }
    }
}

@Composable
private fun ViewerPage(path: String, onDismiss: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = remember(path) { extOf(path) }
    val markdown = ext in MARKDOWN
    var state by remember(path) { mutableStateOf<Loaded>(Loaded.Loading) }
    var mode by remember { mutableIntStateOf(0) } // Markdown: 0 preview, 1 source
    LaunchedEffect(path) { state = withContext(Dispatchers.IO) { load(context, path) } }
    val openWith = { scope.launch { openWithOtherApp(context, path) }; Unit }

    Column(Modifier.fillMaxSize().background(c.bgBase).systemBarsPadding()) {
        DTopBar(path.substringAfterLast('/'), onDismiss) {
            (state as? Loaded.Text)?.let { t ->
                IconBtn(R.drawable.dsw_copy_outline, { copyText(context, t.text) }, iconSize = 16.dp, contentDescription = stringResource(R.string.viewer_copy_content))
            }
            IconBtn(R.drawable.dsw_right_up_outline, openWith, iconSize = 16.dp, contentDescription = stringResource(R.string.viewer_open_with))
        }
        InfoStrip(path, ext, state, markdown, mode) { mode = it }
        HDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Crossfade(state, label = "viewer") { s ->
                when (s) {
                    Loaded.Loading -> Skeleton()
                    is Loaded.Failed -> Placeholder(R.drawable.dsw_warning_outline, stringResource(R.string.viewer_failed_title), s.message, null)
                    is Loaded.Binary -> Placeholder(R.drawable.dsw_paperclip_outline, stringResource(R.string.viewer_binary_title),
                        stringResource(R.string.viewer_binary), stringResource(R.string.viewer_open_with) to openWith)
                    is Loaded.Picture -> ZoomableImage(s.bitmap, path.substringAfterLast('/'))
                    is Loaded.Text -> when {
                        s.text.isEmpty() -> Placeholder(iconOf(ext), stringResource(R.string.viewer_empty), "", null)
                        markdown && mode == 0 && s.text.length <= MARKDOWN_CHARS -> MarkdownPreview(s.text, path)
                        else -> LineView(s.lines)
                    }
                }
            }
        }
    }
}

/** File-type badge, path (tap to copy) and facts; the Markdown preview/source switch. */
@Composable
private fun InfoStrip(path: String, ext: String, state: Loaded, markdown: Boolean, mode: Int, onMode: (Int) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(Radius.tile).background(c.selector), contentAlignment = Alignment.Center) {
            DIcon(iconOf(ext), size = 18.dp, tint = c.labelSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(path, Modifier.clip(Radius.xs).pressable({ copyText(context, path) }, Radius.xs), style = DshType.mono11, color = c.labelTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(kindOf(ext))
                when (state) {
                    is Loaded.Text -> {
                        state.size?.let { Tag(human(it), mono = true) }
                        Tag(stringResource(R.string.viewer_lines, state.lines.size))
                        if (state.truncated) Tag(stringResource(R.string.viewer_partial, TEXT_CAP / 1024), Tone.Warn)
                    }
                    is Loaded.Picture -> {
                        Tag("${state.width}×${state.height}", mono = true)
                        state.size?.let { Tag(human(it), mono = true) }
                    }
                    is Loaded.Binary -> state.size?.let { Tag(human(it), mono = true) }
                    else -> {}
                }
            }
        }
        if (markdown && state is Loaded.Text && state.text.isNotEmpty() && state.text.length <= MARKDOWN_CHARS) {
            Spacer(Modifier.width(8.dp))
            Segmented(listOf(stringResource(R.string.viewer_preview), stringResource(R.string.viewer_source)), mode, onMode, compact = true)
        }
    }
}

@Composable
private fun MarkdownPreview(text: String, path: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        // Links inside the file are relative to the file's own folder.
        CompositionLocalProvider(LocalLinkBase provides path.substringBeforeLast('/', "/")) {
            SelectionContainer { DshMarkdown(text) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Source text with a line-number gutter; lines wrap, numbers stay on the first row of each line. */
@Composable
private fun LineView(lines: List<String>) {
    val c = Dsh.colors
    val gutter: Dp = remember(lines.size) { (lines.size.toString().length * 7 + 12).dp }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = gutter + 8.dp).width(1.dp).fillMaxHeight().background(c.borderL1))
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp)) {
                itemsIndexed(lines) { i, line ->
                    Row(Modifier.fillMaxWidth().padding(end = 16.dp)) {
                        Text("${i + 1}", Modifier.width(gutter), style = DshType.mono12, color = c.labelCaption, textAlign = TextAlign.End, maxLines = 1)
                        Spacer(Modifier.width(17.dp))
                        Text(line.ifEmpty { " " }, Modifier.weight(1f), style = DshType.mono12, color = c.labelPrimary)
                    }
                }
            }
        }
    }
}

/** Pinch to zoom, drag to pan, double-tap to reset. */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String) {
    val c = Dsh.colors
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { z, p, _ -> scale = (scale * z).coerceIn(1f, 8f); offset = if (scale == 1f) Offset.Zero else offset + p }
    Box(Modifier.fillMaxSize().background(c.bgLayer1)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }) }
        .transformable(zoom), contentAlignment = Alignment.Center) {
        Image(bitmap, description, Modifier.fillMaxSize().padding(12.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
    }
}

/** Grey bars standing in for text while the file is read. */
@Composable
private fun Skeleton() {
    val c = Dsh.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(0.55f, 0.92f, 0.8f, 0.97f, 0.62f, 0f, 0.86f, 0.74f, 0.9f, 0.4f).forEach { w ->
            if (w == 0f) Spacer(Modifier.height(6.dp))
            else Box(Modifier.fillMaxWidth(w).height(12.dp).clip(CircleShape).background(c.bgSkeleton))
        }
    }
}

@Composable
private fun Placeholder(@DrawableRes icon: Int, title: String, body: String, action: Pair<String, () -> Unit>?) {
    val c = Dsh.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.selector), contentAlignment = Alignment.Center) {
            DIcon(icon, size = 24.dp, tint = c.labelSecondary)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = DshType.base16m, color = c.labelPrimary, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = DshType.xs13, color = c.labelTertiary, textAlign = TextAlign.Center)
        }
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(20.dp))
            DButton(label, onClick, variant = BtnVariant.Outline, icon = R.drawable.dsw_right_up_outline)
        }
        Spacer(Modifier.height(48.dp))
    }
}
