package com.jarrah.pdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    var tab by remember { mutableIntStateOf(0) }
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
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Text("الرئيسية") }, label = { Text("الرئيسية") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Text("تحرير") }, label = { Text("تحرير") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Text("دمج") }, label = { Text("دمج") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Text("قص") }, label = { Text("قص") })
            }
        }
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen()
                1 -> EditScreen()
                2 -> MergeScreen()
                3 -> CropScreen()
            }
        }
    }
}

@Composable fun HomeScreen() {
    Column(Modifier.padding(24.dp)) {
        Text("مرحبًا بك في جرّاح PDF", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("اختر ملف PDF للبدء.", fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {}, Modifier.fillMaxWidth()) { Text("فتح ملف PDF") }
    }
}

@Composable fun EditScreen() {
    Column(Modifier.padding(24.dp)) {
        Text("أدوات التحرير", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        ToolButton("إدراج عمود / جدول")
        ToolButton("استبدال عمود")
        ToolButton("حذف عمود / جدول")
        ToolButton("تركيب عمود على آخر")
    }
}

@Composable fun MergeScreen() {
    Column(Modifier.padding(24.dp)) {
        Text("دمج المستندات", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        ToolButton("دمج ملفين PDF")
        ToolButton("إدراج صفحات من ملف آخر")
    }
}

@Composable fun CropScreen() {
    Column(Modifier.padding(24.dp)) {
        Text("قص الأعمدة والجداول", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        ToolButton("تحديد منطقة القص (CropBox)")
        ToolButton("قص جدول وتصديره")
    }
}

@Composable fun ToolButton(text: String) {
    OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text)
    }
}
