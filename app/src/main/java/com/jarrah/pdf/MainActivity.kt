package com.jarrah.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0B3D2E),
                    secondary = Color(0xFF1E88E5),
                    surface = Color(0xFFF5F7FA)
                )
            ) { App() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val rendererMutex = remember { Mutex() }

    var inputFile by remember { mutableStateOf<File?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var thumbs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var selection by remember { mutableStateOf<RectF?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingText by remember { mutableStateOf("") }
    var nightMode by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<Pair<File, String>?>(null) }

    // Password flow
    var pendingEncryptedFile by remember { mutableStateOf<File?>(null) }
    var showPasswordPrompt by remember { mutableStateOf(false) }

    fun snack(msg: String) { scope.launch { snackbarHostState.showSnackbar(msg) } }

    fun closeRenderer() {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null; pfd = null
    }

    suspend fun openFileInternal(f: File, targetPage: Int = 0, password: String? = null) {
        // التحقق من التشفير
        val encrypted = withContext(Dispatchers.IO) { PdfEngine.isEncrypted(f) }
        if (encrypted && password == null) {
            pendingEncryptedFile = f
            showPasswordPrompt = true
            return
        }
        var workingFile = f
        if (encrypted && password != null) {
            workingFile = withContext(Dispatchers.IO) {
                try {
                    val temp = File(context.cacheDir, "dec_${System.currentTimeMillis()}.pdf")
                    PdfEngine.decryptToTemp(f, password, temp)
                    temp
                } catch (e: Exception) {
                    throw Exception("كلمة المرور خاطئة")
                }
            }
        }

        rendererMutex.withLock {
            val oldFile = inputFile
            withContext(Dispatchers.Main) {
                isLoading = true; loadingText = "جاري فتح الملف..."
                closeRenderer()
                bitmap?.recycle(); bitmap = null
                thumbs.forEach { try { it.recycle() } catch (_: Exception) {} }; thumbs = emptyList()
            }
            try {
                val newPfd = ParcelFileDescriptor.open(workingFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val newRenderer = try { PdfRenderer(newPfd) } catch (e: Exception) { newPfd.close(); throw e }
                val pages = newRenderer.pageCount
                val p = targetPage.coerceIn(0, pages - 1)
                val bmp = try { PdfEngine.renderPage(newRenderer, p, 1080) } catch (e: Exception) { newRenderer.close(); newPfd.close(); throw e }
                withContext(Dispatchers.Main) {
                    inputFile = workingFile; pfd = newPfd; renderer = newRenderer
                    pageCount = pages; currentPage = p; bitmap = bmp; selection = null
                    loadingText = "جاري تجهيز المصغرات..."
                }
                val newThumbs = mutableListOf<Bitmap>()
                val limit = minOf(pages, 100)
                for (i in 0 until limit) {
                    try { newThumbs.add(PdfEngine.renderThumb(newRenderer, i, 160)) } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    if (inputFile == workingFile) {
                        thumbs.forEach { try { it.recycle() } catch (_: Exception) {} }
                        thumbs = newThumbs
                    } else newThumbs.forEach { try { it.recycle() } catch (_: Exception) {} }
                    isLoading = false; loadingText = ""
                    if (oldFile != null && oldFile != workingFile && oldFile.parentFile == context.cacheDir) {
                        try { oldFile.delete() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false; loadingText = ""
                    snack("خطأ في الفتح: ${e.message}")
                }
            }
        }
    }

    fun goToPage(p: Int) {
        val r = renderer ?: return
        if (p < 0 || p >= pageCount || p == currentPage) return
        scope.launch(Dispatchers.IO) {
            val bmp = rendererMutex.withLock {
                try { PdfEngine.renderPage(r, p, 1080) } catch (e: Exception) { null }
            }
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    val old = bitmap; bitmap = bmp; currentPage = p; old?.recycle()
                }
            }
        }
    }

    fun performOp(label: String, switchToViewer: Boolean = true, op: (File, File) -> Unit) {
        val f = inputFile ?: return
        val page = currentPage
        scope.launch {
            withContext(Dispatchers.Main) { isLoading = true; loadingText = "$label..." }
            try {
                val temp = File(context.cacheDir, "${label}_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.IO) { op(f, temp) }
                openFileInternal(temp, page)
                if (switchToViewer) tab = 1
                snack("$label: نجح")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false; loadingText = ""; snack("$label: ${e.message}") }
            }
        }
    }

    fun copyUri(uri: Uri, name: String): File? = try {
        try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val f = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
        if (f.exists() && f.length() > 0) f else null
    } catch (e: Exception) { null }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val f = copyUri(uri, "in_${System.currentTimeMillis()}.pdf")
            withContext(Dispatchers.Main) {
                if (f != null) { tab = 1; scope.launch { openFileInternal(f) } }
                else snack("فشل فتح الملف")
            }
        }
    }

    val mergePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val primary = inputFile ?: run { snack("افتح ملفاً أولاً"); return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.IO) {
            try {
                val f2 = copyUri(uri, "in2_${System.currentTimeMillis()}.pdf") ?: throw Exception("فشل قراءة الملف الثاني")
                val temp = File(context.cacheDir, "merged_${System.currentTimeMillis()}.pdf")
                PdfEngine.mergePdfs(listOf(primary, f2), temp)
                openFileInternal(temp, 0)
                withContext(Dispatchers.Main) { tab = 1; snack("تم الدمج") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { snack("فشل الدمج: ${e.message}") }
            }
        }
    }

    val imagePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val sel = selection ?: run { snack("ارسم مستطيلاً أولاً"); return@rememberLauncherForActivityResult }
        val page = currentPage
        val f = inputFile ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val imgFile = copyUri(uri, "img_${System.currentTimeMillis()}") ?: throw Exception("فشل قراءة الصورة")
                val temp = File(context.cacheDir, "withimg_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.Main) { isLoading = true; loadingText = "إدراج صورة..." }
                PdfEngine.addImageFileOnPage(f, temp, page, sel, imgFile)
                openFileInternal(temp, page)
                withContext(Dispatchers.Main) { tab = 1; snack("تم إدراج الصورة") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false; loadingText = ""; snack("فشل: ${e.message}") }
            }
        }
    }

    val imageToPdfPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val imgFile = copyUri(uri, "conv_${System.currentTimeMillis()}") ?: throw Exception("فشل قراءة الصورة")
                val temp = File(context.cacheDir, "fromimg_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.Main) { isLoading = true; loadingText = "تحويل صورة..." }
                PdfEngine.imageToPdf(imgFile, temp)
                openFileInternal(temp, 0)
                withContext(Dispatchers.Main) { tab = 1; snack("تم تحويل الصورة إلى PDF") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false; loadingText = ""; snack("فشل: ${e.message}") }
            }
        }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val p = pendingSave; pendingSave = null
        if (uri == null || p == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    p.first.inputStream().use { inp -> inp.copyTo(out) }
                }
                withContext(Dispatchers.Main) { snack("تم الحفظ: ${p.second}") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { snack("فشل الحفظ: ${e.message}") } }
        }
    }

    fun saveOutput(tempFile: File, name: String) { pendingSave = tempFile to name; savePdf.launch(name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("جرّاح PDF", fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                listOf("الرئيسية", "عرض", "تحرير", "مستند").forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.take(1), fontSize = 16.sp) },
                        label = { Text(t, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(
                    inputFile = inputFile, pageCount = pageCount,
                    onPick = { pickPdf.launch(arrayOf("application/pdf")) },
                    onOpenViewer = { tab = 1 },
                    onImageToPdf = { imageToPdfPick.launch("image/*") }
                )
                1 -> ViewerScreen(
                    bitmap = bitmap, currentPage = currentPage, pageCount = pageCount,
                    nightMode = nightMode, onNightModeChange = { nightMode = it },
                    thumbs = thumbs,
                    onPage = { goToPage(it) },
                    onSearch = { q -> scope.launch {
                        val f = inputFile ?: return@launch
                        snack("جاري البحث...")
                        val r = withContext(Dispatchers.IO) { try { PdfEngine.searchPages(f, q) } catch (e: Exception) { emptyList() } }
                        if (r.isEmpty()) snack("لا نتائج")
                        else { snack("وجد في ${r.size} صفحة"); goToPage(r[0]) }
                    } }
                )
                2 -> EditScreen(
                    bitmap = bitmap, selection = selection, onSelection = { selection = it },
                    hasFile = inputFile != null, currentPage = currentPage, pageCount = pageCount,
                    onCrop = {
                        val sel = selection ?: return@EditScreen snack("ارسم مستطيلاً أولاً")
                        if (sel.width() < 0.02f || sel.height() < 0.02f) snack("المنطقة صغيرة")
                        else performOp("قص") { i, o -> PdfEngine.cropPage(i, o, currentPage, sel) }
                    },
                    onClearSelection = { selection = null },
                    onRotate = { deg -> performOp("تدوير") { i, o -> PdfEngine.rotatePage(i, o, currentPage, deg) } },
                    onDelete = { performOp("حذف") { i, o -> PdfEngine.deletePage(i, o, currentPage) } },
                    onInsertBlank = { performOp("إدراج") { i, o -> PdfEngine.insertBlankPage(i, o, currentPage) } },
                    onInsertTable = { r, c -> performOp("جدول") { i, o -> PdfEngine.insertTablePage(i, o, currentPage, r, c) } },
                    onReplaceText = { text, size ->
                        val sel = selection ?: return@EditScreen snack("ارسم مستطيلاً أولاً")
                        performOp("استبدال نص") { i, o -> PdfEngine.replaceTextRegion(i, o, currentPage, sel, text, size) }
                    },
                    onSignature = { sigBmp ->
                        val sel = selection ?: return@EditScreen snack("ارسم مستطيلاً أولاً")
                        val bmpCopy = sigBmp.copy(Bitmap.Config.ARGB_8888, false)
                        performOp("توقيع") { i, o -> PdfEngine.addBitmapOnPage(i, o, currentPage, sel, bmpCopy) }
                    },
                    onInsertImage = { imagePick.launch("image/*") },
                    onMerge = { mergePick.launch(arrayOf("application/pdf")) },
                    onSave = { inputFile?.let { saveOutput(it, "output.pdf") } }
                )
                3 -> DocumentScreen(
                    hasFile = inputFile != null, pageCount = pageCount, thumbs = thumbs,
                    onSplit = { from, to -> performOp("تقسيم") { i, o -> PdfEngine.splitPdf(i, o, from, to) } },
                    onExtract = { pages -> performOp("استخراج") { i, o -> PdfEngine.extractPages(i, o, pages) } },
                    onReorder = { order -> performOp("ترتيب") { i, o -> PdfEngine.reorderPages(i, o, order) } },
                    onCompress = { q -> performOp("ضغط") { i, o -> PdfEngine.compressPdf(i, o, q) } },
                    onEncrypt = { pwd ->
                        val f = inputFile ?: return@DocumentScreen
                        scope.launch {
                            withContext(Dispatchers.Main) { isLoading = true; loadingText = "تشفير..." }
                            try {
                                val temp = File(context.cacheDir, "enc_${System.currentTimeMillis()}.pdf")
                                withContext(Dispatchers.IO) { PdfEngine.encryptPdf(f, temp, pwd) }
                                saveOutput(temp, "protected.pdf")
                                withContext(Dispatchers.Main) { isLoading = false; loadingText = ""; snack("تم التشفير - اختر مكان الحفظ") }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { isLoading = false; loadingText = ""; snack("فشل: ${e.message}") }
                            }
                        }
                    },
                    onSave = { inputFile?.let { saveOutput(it, "output.pdf") } }
                )
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(loadingText.ifBlank { "جاري المعالجة..." }, color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            if (showPasswordPrompt) {
                PasswordDialog(
                    onDismiss = { showPasswordPrompt = false; pendingEncryptedFile = null },
                    onConfirm = { pwd ->
                        val f = pendingEncryptedFile
                        showPasswordPrompt = false
                        pendingEncryptedFile = null
                        if (f != null) scope.launch { openFileInternal(f, 0, pwd) }
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(inputFile: File?, pageCount: Int, onPick: () -> Unit, onOpenViewer: () -> Unit, onImageToPdf: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("جرّاح PDF", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("تحرير · قص · دمج · ترتيب · حماية", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(32.dp))
        if (inputFile == null) {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("فتح ملف PDF", fontSize = 16.sp) }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("ملف مفتوح", fontSize = 14.sp, color = Color.Gray)
                    Text("$pageCount صفحة", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenViewer, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("عرض") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("فتح ملف آخر") }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onImageToPdf, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("تحويل صورة إلى PDF", fontSize = 13.sp)
        }
    }
}

@Composable
fun ViewerScreen(
    bitmap: Bitmap?, currentPage: Int, pageCount: Int,
    nightMode: Boolean, onNightModeChange: (Boolean) -> Unit,
    thumbs: List<Bitmap>, onPage: (Int) -> Unit, onSearch: (String) -> Unit
) {
    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح ملفاً أولاً") }
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var searchText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchText, onValueChange = { searchText = it },
                placeholder = { Text("بحث...", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(52.dp)
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onSearch(searchText) }) { Text("🔍", fontSize = 18.sp) }
            IconButton(onClick = { onNightModeChange(!nightMode) }) { Text(if (nightMode) "☀" else "🌙", fontSize = 18.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (currentPage > 0) onPage(currentPage - 1) }) { Text("◀") }
            Text("${currentPage + 1} / $pageCount", fontSize = 14.sp)
            TextButton(onClick = { if (currentPage < pageCount - 1) onPage(currentPage + 1) }) { Text("▶") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF303030)).clip(RoundedCornerShape(4.dp))) {
            val invertFilter = if (nightMode)
                ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                ))) else null
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = "Page",
                contentScale = ContentScale.Fit, colorFilter = invertFilter,
                modifier = Modifier.fillMaxSize().padding(8.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
            )
        }
        if (thumbs.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().height(80.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(thumbs.size) { i ->
                    Box(Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(4.dp))
                        .border(if (i == currentPage) 2.dp else 0.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp))
                        .clickable { onPage(i) }) {
                        Image(thumbs[i].asImageBitmap(), null, contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}

@Composable
fun EditScreen(
    bitmap: Bitmap?, selection: RectF?, onSelection: (RectF?) -> Unit,
    hasFile: Boolean, currentPage: Int, pageCount: Int,
    onCrop: () -> Unit, onClearSelection: () -> Unit,
    onRotate: (Int) -> Unit, onDelete: () -> Unit, onInsertBlank: () -> Unit,
    onInsertTable: (Int, Int) -> Unit,
    onReplaceText: (String, Float) -> Unit,
    onSignature: (Bitmap) -> Unit,
    onInsertImage: () -> Unit,
    onMerge: () -> Unit, onSave: () -> Unit
) {
    if (!hasFile || bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح ملفاً أولاً") }
        return
    }
    var showTableDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showSigDialog by remember { mutableStateOf(false) }
    var imgSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
        Text("ارسم مستطيلاً لتحديد منطقة", fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth()) {
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    .onSizeChanged { imgSize = it }
                    .pointerInput(bitmap) {
                        detectDragGestures(
                            onDragStart = { off ->
                                if (imgSize.width == 0) return@detectDragGestures
                                val x = (off.x / imgSize.width).coerceIn(0f, 1f)
                                val y = (off.y / imgSize.height).coerceIn(0f, 1f)
                                dragStart = Offset(x, y); onSelection(RectF(x, y, x, y))
                            },
                            onDrag = { ch, _ ->
                                ch.consume()
                                val s = dragStart ?: return@detectDragGestures
                                if (imgSize.width == 0) return@detectDragGestures
                                val x = (ch.position.x / imgSize.width).coerceIn(0f, 1f)
                                val y = (ch.position.y / imgSize.height).coerceIn(0f, 1f)
                                onSelection(RectF(minOf(s.x, x), minOf(s.y, y), maxOf(s.x, x), maxOf(s.y, y)))
                            },
                            onDragEnd = { dragStart = null }, onDragCancel = { dragStart = null }
                        )
                    }
            )
            Canvas(Modifier.matchParentSize()) {
                val sel = selection ?: return@Canvas
                val l = sel.left * size.width; val t = sel.top * size.height
                val r = sel.right * size.width; val b = sel.bottom * size.height
                drawRect(Color(0x44FF0000), Offset(l, t), Size(r - l, b - t))
                drawRect(Color.Red, Offset(l, t), Size(r - l, b - t), style = Stroke(3f))
            }
        }

        if (selection != null) {
            Spacer(Modifier.height(6.dp))
            Text("تحديد: ${(selection.left * 100).toInt()}%,${(selection.top * 100).toInt()} → ${(selection.right * 100).toInt()}%,${(selection.bottom * 100).toInt()}",
                fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onCrop, modifier = Modifier.weight(1f)) { Text("قص", fontSize = 12.sp) }
                OutlinedButton(onClick = { showTextDialog = true }, modifier = Modifier.weight(1f)) { Text("نص", fontSize = 12.sp) }
                OutlinedButton(onClick = { showSigDialog = true }, modifier = Modifier.weight(1f)) { Text("توقيع", fontSize = 12.sp) }
                OutlinedButton(onClick = onInsertImage, modifier = Modifier.weight(1f)) { Text("صورة", fontSize = 12.sp) }
            }
        }

        Divider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onRotate(90) }, modifier = Modifier.weight(1f)) { Text("↻90", fontSize = 12.sp) }
            OutlinedButton(onClick = { onRotate(180) }, modifier = Modifier.weight(1f)) { Text("↻180", fontSize = 12.sp) }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("حذف", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onInsertBlank, modifier = Modifier.fillMaxWidth()) { Text("إدراج صفحة فارغة", fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { showTableDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("إدراج جدول", fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onMerge, modifier = Modifier.fillMaxWidth()) { Text("دمج مع ملف", fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("حفظ الملف", fontSize = 13.sp) }
    }
    if (showTableDialog) InsertTableDialog(onDismiss = { showTableDialog = false }, onConfirm = { r, c -> showTableDialog = false; onInsertTable(r, c) })
    if (showTextDialog) TextReplaceDialog(onDismiss = { showTextDialog = false }, onConfirm = { t, s -> showTextDialog = false; onReplaceText(t, s) })
    if (showSigDialog) SignatureDialog(onDismiss = { showSigDialog = false }, onConfirm = { b -> showSigDialog = false; onSignature(b) })
}

@Composable
fun DocumentScreen(
    hasFile: Boolean, pageCount: Int, thumbs: List<Bitmap>,
    onSplit: (Int, Int) -> Unit, onExtract: (List<Int>) -> Unit,
    onReorder: (List<Int>) -> Unit, onCompress: (Float) -> Unit,
    onEncrypt: (String) -> Unit,
    onSave: () -> Unit
) {
    if (!hasFile) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح ملفاً أولاً") }; return }
    var showSplit by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }
    var showReorder by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var showEncrypt by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("إدارة المستند", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Text("$pageCount صفحة", fontSize = 13.sp, color = Color.Gray)
        Divider(Modifier.padding(vertical = 4.dp))
        OutlinedButton(onClick = { showSplit = true }, modifier = Modifier.fillMaxWidth()) { Text("تقسيم بنطاق") }
        OutlinedButton(onClick = { showExtract = true }, modifier = Modifier.fillMaxWidth()) { Text("استخراج صفحات") }
        OutlinedButton(onClick = { showReorder = true }, modifier = Modifier.fillMaxWidth()) { Text("إعادة ترتيب") }
        OutlinedButton(onClick = { showCompress = true }, modifier = Modifier.fillMaxWidth()) { Text("ضغط") }
        OutlinedButton(onClick = { showEncrypt = true }, modifier = Modifier.fillMaxWidth()) { Text("حماية بكلمة مرور") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("حفظ الملف الحالي") }
    }

    if (showSplit) SplitDialog(pageCount, { showSplit = false }, { a, b -> showSplit = false; onSplit(a, b) })
    if (showExtract) ExtractDialog(pageCount, { showExtract = false }, { l -> showExtract = false; onExtract(l) })
    if (showReorder) ReorderDialog(pageCount, thumbs, { showReorder = false }, { o -> showReorder = false; onReorder(o) })
    if (showCompress) CompressDialog({ showCompress = false }, { q -> showCompress = false; onCompress(q) })
    if (showEncrypt) EncryptDialog({ showEncrypt = false }, { pwd -> showEncrypt = false; onEncrypt(pwd) })
}

@Composable
fun InsertTableDialog(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var rows by remember { mutableStateOf("5") }; var cols by remember { mutableStateOf("3") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("إدراج جدول") }, text = {
        Column {
            OutlinedTextField(value = rows, onValueChange = { rows = it.filter { c -> c.isDigit() } }, label = { Text("الصفوف") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = cols, onValueChange = { cols = it.filter { c -> c.isDigit() } }, label = { Text("الأعمدة") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = { onConfirm(rows.toIntOrNull()?.coerceIn(1, 50) ?: 5, cols.toIntOrNull()?.coerceIn(1, 20) ?: 3) }) { Text("إدراج") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun TextReplaceDialog(onDismiss: () -> Unit, onConfirm: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(14f) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("استبدال منطقة بنص") }, text = {
        Column {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("النص الجديد") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("حجم الخط: ${size.toInt()}", fontSize = 12.sp)
            Slider(value = size, onValueChange = { size = it }, valueRange = 8f..32f)
            Text("يدعم العربية تلقائياً", fontSize = 11.sp, color = Color.Gray)
        }
    }, confirmButton = {
        TextButton(onClick = { if (text.isNotBlank()) onConfirm(text, size) }, enabled = text.isNotBlank()) { Text("تطبيق") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun SignatureDialog(onDismiss: () -> Unit, onConfirm: (Bitmap) -> Unit) {
    var allStrokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ارسم توقيعك") },
        text = {
            Column {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.White)
                        .border(1.dp, Color.Gray)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off -> currentStroke = listOf(off) },
                                onDrag = { change, _ -> change.consume(); currentStroke = currentStroke + change.position },
                                onDragEnd = { allStrokes = allStrokes + listOf(currentStroke); currentStroke = emptyList() },
                                onDragCancel = { currentStroke = emptyList() }
                            )
                        }
                ) {
                    allStrokes.forEach { s ->
                        if (s.size > 1) for (i in 0 until s.size - 1) drawLine(Color.Black, s[i], s[i + 1], strokeWidth = 4f)
                    }
                    if (currentStroke.size > 1) for (i in 0 until currentStroke.size - 1) drawLine(Color.Black, currentStroke[i], currentStroke[i + 1], strokeWidth = 4f)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { allStrokes = emptyList(); currentStroke = emptyList() }) { Text("مسح") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (allStrokes.isEmpty() || canvasSize.width == 0) return@TextButton
                val bmp = strokesToBitmap(allStrokes, canvasSize.width, canvasSize.height)
                onConfirm(bmp)
            }) { Text("تطبيق") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

fun strokesToBitmap(strokes: List<List<Offset>>, viewW: Int, viewH: Int): Bitmap {
    val scale = 2
    val bmp = Bitmap.createBitmap(viewW * scale, viewH * scale, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.TRANSPARENT)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 6f
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        for (i in 0 until stroke.size - 1) {
            canvas.drawLine(stroke[i].x * scale, stroke[i].y * scale, stroke[i + 1].x * scale, stroke[i + 1].y * scale, paint)
        }
    }
    return bmp
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("الملف محمي") }, text = {
        Column {
            Text("أدخل كلمة المرور:", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pwd, onValueChange = { pwd = it }, label = { Text("كلمة المرور") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = { if (pwd.isNotBlank()) onConfirm(pwd) }, enabled = pwd.isNotBlank()) { Text("فتح") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun EncryptDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("حماية بكلمة مرور") }, text = {
        Column {
            Text("كلمة المرور لفتح الملف (AES-256):", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pwd, onValueChange = { pwd = it }, label = { Text("كلمة المرور") }, singleLine = true)
            Text("احفظها في مكان آمن - لا يمكن استرجاعها", fontSize = 11.sp, color = Color.Gray)
        }
    }, confirmButton = {
        TextButton(onClick = { if (pwd.length >= 4) onConfirm(pwd) }, enabled = pwd.length >= 4) { Text("تشفير") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun SplitDialog(pageCount: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var from by remember { mutableStateOf("1") }; var to by remember { mutableStateOf("$pageCount") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("تقسيم") }, text = {
        Column {
            OutlinedTextField(value = from, onValueChange = { from = it.filter { c -> c.isDigit() } }, label = { Text("من") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = to, onValueChange = { to = it.filter { c -> c.isDigit() } }, label = { Text("إلى") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val a = (from.toIntOrNull() ?: 1).coerceIn(1, pageCount)
            val b = (to.toIntOrNull() ?: pageCount).coerceIn(a, pageCount)
            onConfirm(a - 1, b - 1)
        }) { Text("تقسيم") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun ExtractDialog(pageCount: Int, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("استخراج صفحات") }, text = {
        Column {
            Text("مثال: 1,3,5-7 (المجموع $pageCount)", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("الصفحات") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val result = mutableSetOf<Int>()
            input.split(',').forEach { part ->
                val p = part.trim()
                when {
                    p.isEmpty() -> {}
                    p.contains('-') -> {
                        val ab = p.split('-')
                        val a = ab.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                        val b = ab.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                        for (i in a..b) if (i in 1..pageCount) result.add(i - 1)
                    }
                    else -> p.toIntOrNull()?.let { if (it in 1..pageCount) result.add(it - 1) }
                }
            }
            if (result.isNotEmpty()) onConfirm(result.toList())
        }) { Text("استخراج") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun ReorderDialog(pageCount: Int, thumbs: List<Bitmap>, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
    var order by remember { mutableStateOf((0 until pageCount).toList()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("إعادة ترتيب") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(order.size) { idx ->
                val pageIdx = order[idx]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${idx + 1}", fontSize = 12.sp, modifier = Modifier.width(24.dp))
                    Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(2.dp)).background(Color.LightGray)) {
                        thumbs.getOrNull(pageIdx)?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit) }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("صفحة ${pageIdx + 1}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (idx > 0) order = order.toMutableList().also { val t = it[idx]; it[idx] = it[idx - 1]; it[idx - 1] = t } }, modifier = Modifier.size(32.dp)) { Text("▲", fontSize = 12.sp) }
                    IconButton(onClick = { if (idx < order.size - 1) order = order.toMutableList().also { val t = it[idx]; it[idx] = it[idx + 1]; it[idx + 1] = t } }, modifier = Modifier.size(32.dp)) { Text("▼", fontSize = 12.sp) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(order) }) { Text("تطبيق") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
fun CompressDialog(onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var quality by remember { mutableFloatStateOf(0.5f) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("ضغط") }, text = {
        Column {
            Text("الجودة: ${(quality * 100).toInt()}%", fontSize = 13.sp)
            Slider(value = quality, onValueChange = { quality = it }, valueRange = 0.1f..0.9f)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(quality) }) { Text("ضغط") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
