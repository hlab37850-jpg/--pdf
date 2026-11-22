package com.jarrah.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            ) { JarrahPDFApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarrahPDFApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var inputFile by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selection by remember { mutableStateOf<RectF?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<Pair<File, String>?>(null) }

    fun snack(msg: String) { scope.launch { snackbarHostState.showSnackbar(msg) } }

    fun loadFile(f: File, page: Int = 0) {
        scope.launch(Dispatchers.IO) {
            try {
                val pages = PdfEngine.pageCount(f)
                val p = page.coerceIn(0, pages - 1)
                val bmp = PdfEngine.renderPage(f, p, 1080)
                withContext(Dispatchers.Main) {
                    inputFile = f
                    pageCount = pages
                    currentPage = p
                    bitmap = bmp
                    selection = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { snack("خطأ: ${e.message}") }
            }
        }
    }

    fun copyUriToCache(uri: Uri, name: String): File? = try {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val f = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
        if (f.exists() && f.length() > 0) f else null
    } catch (e: Exception) { null }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val f = copyUriToCache(uri, "input_${System.currentTimeMillis()}.pdf")
            withContext(Dispatchers.Main) {
                if (f != null) { loadFile(f); tab = 1; snack("تم فتح الملف") }
                else snack("فشل فتح الملف")
            }
        }
    }

    val mergePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val primary = inputFile ?: run { snack("افتح ملفاً أولاً"); return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.IO) {
            try {
                val f2 = copyUriToCache(uri, "input2_${System.currentTimeMillis()}.pdf") ?: throw Exception("فشل قراءة الملف الثاني")
                val temp = File(context.cacheDir, "merged_${System.currentTimeMillis()}.pdf")
                PdfEngine.mergePdfs(listOf(primary, f2), temp)
                withContext(Dispatchers.Main) { loadFile(temp); snack("تم الدمج") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { snack("فشل الدمج: ${e.message}") }
            }
        }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val p = pendingSave
        pendingSave = null
        if (uri == null || p == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    p.first.inputStream().use { inp -> inp.copyTo(out) }
                }
                withContext(Dispatchers.Main) { snack("تم الحفظ: ${p.second}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { snack("فشل الحفظ: ${e.message}") }
            }
        }
    }

    fun saveOutput(tempFile: File, name: String) {
        pendingSave = tempFile to name
        savePdf.launch(name)
    }

    fun doRotate(deg: Int) {
        val f = inputFile ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val temp = File(context.cacheDir, "rot_${System.currentTimeMillis()}.pdf")
                PdfEngine.rotatePage(f, temp, currentPage, deg)
                withContext(Dispatchers.Main) { loadFile(temp, currentPage); snack("تم التدوير") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { snack("فشل: ${e.message}") } }
        }
    }

    fun doDelete() {
        val f = inputFile ?: return
        if (pageCount <= 1) { snack("لا يمكن حذف الصفحة الوحيدة"); return }
        scope.launch(Dispatchers.IO) {
            try {
                val temp = File(context.cacheDir, "del_${System.currentTimeMillis()}.pdf")
                PdfEngine.deletePage(f, temp, currentPage)
                withContext(Dispatchers.Main) {
                    loadFile(temp, currentPage.coerceAtMost(pageCount - 2))
                    snack("تم حذف الصفحة")
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { snack("فشل: ${e.message}") } }
        }
    }

    fun doInsertBlank() {
        val f = inputFile ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val temp = File(context.cacheDir, "ins_${System.currentTimeMillis()}.pdf")
                PdfEngine.insertBlankPage(f, temp, currentPage)
                withContext(Dispatchers.Main) { loadFile(temp, currentPage); snack("تم إدراج صفحة فارغة") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { snack("فشل: ${e.message}") } }
        }
    }

    fun doInsertTable(rows: Int, cols: Int) {
        val f = inputFile ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val temp = File(context.cacheDir, "tbl_${System.currentTimeMillis()}.pdf")
                PdfEngine.insertTablePage(f, temp, currentPage, rows, cols)
                withContext(Dispatchers.Main) {
                    loadFile(temp, currentPage + 1)
                    snack("تم إدراج جدول $rows×$cols")
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { snack("فشل: ${e.message}") } }
        }
    }

    fun doCrop() {
        val f = inputFile; val sel = selection
        if (f == null || sel == null) { snack("ارسم مستطيلاً أولاً"); return }
        if (sel.width() < 0.02f || sel.height() < 0.02f) { snack("المنطقة صغيرة جداً"); return }
        scope.launch(Dispatchers.IO) {
            try {
                val temp = File(context.cacheDir, "cropped_${System.currentTimeMillis()}.pdf")
                PdfEngine.cropPage(f, temp, currentPage, sel)
                withContext(Dispatchers.Main) {
                    loadFile(temp, currentPage)
                    saveOutput(temp, "cropped.pdf")
                    snack("تم القص - اختر مكان الحفظ")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { snack("فشل القص: ${e.message}") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("جرّاح PDF", fontSize = 22.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                listOf("الرئيسية", "عرض", "قص", "أدوات").forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.take(1), fontSize = 18.sp) },
                        label = { Text(t, fontSize = 12.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(
                    inputFile = inputFile,
                    pageCount = pageCount,
                    onPick = { pickPdf.launch(arrayOf("application/pdf")) },
                    onOpenViewer = { tab = 1 }
                )
                1 -> ViewerScreen(
                    bitmap = bitmap,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    onPage = { newPage -> inputFile?.let { loadFile(it, newPage) } }
                )
                2 -> CropScreen(
                    bitmap = bitmap,
                    selection = selection,
                    onSelection = { selection = it },
                    onCrop = { doCrop() }
                )
                3 -> ToolsScreen(
                    hasFile = inputFile != null,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    onRotate = { deg -> doRotate(deg) },
                    onDelete = { doDelete() },
                    onInsertBlank = { doInsertBlank() },
                    onInsertTable = { r, c -> doInsertTable(r, c) },
                    onMerge = { mergePick.launch(arrayOf("application/pdf")) },
                    onSave = { inputFile?.let { saveOutput(it, "output.pdf") } }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(inputFile: File?, pageCount: Int, onPick: () -> Unit, onOpenViewer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("مرحباً بك في جرّاح PDF", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        if (inputFile == null) {
            Text("لم يتم فتح أي ملف بعد", fontSize = 16.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("فتح ملف PDF") }
        } else {
            Text("الملف مفتوح: $pageCount صفحة", fontSize = 16.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenViewer, modifier = Modifier.fillMaxWidth()) { Text("عرض الملف") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("فتح ملف آخر") }
        }
    }
}

@Composable
fun ViewerScreen(bitmap: Bitmap?, currentPage: Int, pageCount: Int, onPage: (Int) -> Unit) {
    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح ملفاً من الرئيسية") }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (currentPage > 0) onPage(currentPage - 1) }) { Text("◀ السابق") }
            Text("${currentPage + 1} / $pageCount", fontSize = 16.sp)
            TextButton(onClick = { if (currentPage < pageCount - 1) onPage(currentPage + 1) }) { Text("التالي ▶") }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFFE0E0E0)).padding(8.dp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun CropScreen(bitmap: Bitmap?, selection: RectF?, onSelection: (RectF?) -> Unit, onCrop: () -> Unit) {
    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح ملفاً أولاً") }
        return
    }
    var imgSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "ارسم مستطيلاً حول المنطقة المراد قصها",
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .onSizeChanged { imgSize = it }
                        .pointerInput(bitmap) {
                            detectDragGestures(
                                onDragStart = { off ->
                                    if (imgSize.width == 0 || imgSize.height == 0) return@detectDragGestures
                                    val x = (off.x / imgSize.width).coerceIn(0f, 1f)
                                    val y = (off.y / imgSize.height).coerceIn(0f, 1f)
                                    dragStart = Offset(x, y)
                                    onSelection(RectF(x, y, x, y))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val s = dragStart ?: return@detectDragGestures
                                    if (imgSize.width == 0 || imgSize.height == 0) return@detectDragGestures
                                    val x = (change.position.x / imgSize.width).coerceIn(0f, 1f)
                                    val y = (change.position.y / imgSize.height).coerceIn(0f, 1f)
                                    onSelection(RectF(minOf(s.x, x), minOf(s.y, y), maxOf(s.x, x), maxOf(s.y, y)))
                                },
                                onDragEnd = { dragStart = null },
                                onDragCancel = { dragStart = null }
                            )
                        }
                )
                Canvas(Modifier.matchParentSize()) {
                    val sel = selection ?: return@Canvas
                    val l = sel.left * size.width
                    val t = sel.top * size.height
                    val r = sel.right * size.width
                    val b = sel.bottom * size.height
                    drawRect(Color(0x44FF0000), Offset(l, t), Size(r - l, b - t))
                    drawRect(Color.Red, Offset(l, t), Size(r - l, b - t), style = Stroke(4f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSelection(null) }, modifier = Modifier.weight(1f)) { Text("مسح") }
            Button(onClick = onCrop, modifier = Modifier.weight(2f)) { Text("قص ثم حفظ") }
        }
    }
}

@Composable
fun ToolsScreen(
    hasFile: Boolean,
    currentPage: Int,
    pageCount: Int,
    onRotate: (Int) -> Unit,
    onDelete: () -> Unit,
    onInsertBlank: () -> Unit,
    onInsertTable: (Int, Int) -> Unit,
    onMerge: () -> Unit,
    onSave: () -> Unit
) {
    if (!hasFile) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("افتح ملفاً أولاً") }
        return
    }
    var showTableDialog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("الأدوات", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Text("الصفحة الحالية: ${currentPage + 1} من $pageCount", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onRotate(90) }, modifier = Modifier.fillMaxWidth()) { Text("تدوير 90°") }
        Button(onClick = { onRotate(180) }, modifier = Modifier.fillMaxWidth()) { Text("تدوير 180°") }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("حذف الصفحة الحالية") }
        OutlinedButton(onClick = onInsertBlank, modifier = Modifier.fillMaxWidth()) { Text("إدراج صفحة فارغة") }
        OutlinedButton(onClick = { showTableDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("إدراج جدول في صفحة جديدة") }
        OutlinedButton(onClick = onMerge, modifier = Modifier.fillMaxWidth()) { Text("دمج مع ملف PDF آخر") }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("حفظ الملف الحالي") }
    }
    if (showTableDialog) {
        InsertTableDialog(
            onDismiss = { showTableDialog = false },
            onConfirm = { r, c -> showTableDialog = false; onInsertTable(r, c) }
        )
    }
}

@Composable
fun InsertTableDialog(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var rows by remember { mutableStateOf("5") }
    var cols by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إدراج جدول") },
        text = {
            Column {
                OutlinedTextField(
                    value = rows,
                    onValueChange = { rows = it.filter { c -> c.isDigit() } },
                    label = { Text("عدد الصفوف") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cols,
                    onValueChange = { cols = it.filter { c -> c.isDigit() } },
                    label = { Text("عدد الأعمدة") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = rows.toIntOrNull()?.coerceIn(1, 50) ?: 5
                val c = cols.toIntOrNull()?.coerceIn(1, 20) ?: 3
                onConfirm(r, c)
            }) { Text("إدراج") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
