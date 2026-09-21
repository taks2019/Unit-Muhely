@file:OptIn(ExperimentalMaterial3Api::class)

package hu.unitool.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- keret
@Composable
fun App(vm: AppVm, onPick: () -> Unit, onGrant: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        bottomBar = {
            Column {
                StatusStrip(vm) { showLog = true }
                NavigationBar(containerColor = cs.surface) {
                    val items = listOf(
                        "Projekt" to Icons.Default.Home, "Scanner" to Icons.Default.Search,
                        "Csere" to Icons.Default.Edit, "Csomag" to Icons.Default.Build,
                        "Kinézet" to Icons.Default.Settings
                    )
                    items.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = cs.primaryContainer, selectedIconColor = cs.primary,
                                selectedTextColor = cs.primary, unselectedIconColor = cs.onSurfaceVariant,
                                unselectedTextColor = cs.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ProjectScreen(vm, onPick, onGrant)
                1 -> ScanScreen(vm)
                2 -> ExchangeScreen(vm)
                3 -> BuildScreen(vm)
                else -> LookScreen(vm)
            }
        }
    }
    if (showLog) LogSheet(vm) { showLog = false }
}

@Composable
private fun StatusStrip(vm: AppVm, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (vm.busy != null) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = cs.primary)
            Spacer(Modifier.size(10.dp))
        }
        Text(
            vm.log.lastOrNull() ?: "Napló üres", maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun LogSheet(vm: AppVm, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        val state = rememberLazyListState()
        LazyColumn(Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 16.dp), state = state) {
            items(vm.log.toList().asReversed()) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- közös elemek
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surface)
            .border(BorderStroke(1.dp, cs.outline), MaterialTheme.shapes.medium).padding(14.dp)
    ) { content() }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

@Composable
private fun Muted(text: String, mono: Boolean = false) = Text(
    text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
)

@Composable
private fun Page(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

// ---------------------------------------------------------------- Projekt
@Composable
private fun ProjectScreen(vm: AppVm, onPick: () -> Unit, onGrant: () -> Unit) {
    Page {
        Title("Projekt")
        if (!vm.storageOk) {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A munkamappa most az alkalmazás saját tárában van, amit a fájlkezelők nem látnak.")
                    Muted("Engedélyezd a teljes fájlhozzáférést, akkor a Dokumentumok/UnityMuhely mappában szerkesztheted az exportált fájlokat.")
                    FilledTonalButton(onClick = onGrant) { Text("Hozzáférés engedélyezése") }
                }
            }
        }
        Button(onClick = onPick, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth()) {
            Text("APK, OBB vagy ZIP megnyitása")
        }
        vm.current?.let { cur ->
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(cur, fontWeight = FontWeight.SemiBold)
                    Muted(vm.projDir()?.absolutePath ?: "", mono = true)
                    if (vm.unpackNote.isNotBlank()) Text(vm.unpackNote, fontSize = 13.sp)
                    OutlinedButton(onClick = { vm.reUnpack() }, enabled = vm.busy == null) { Text("Kibontás újra") }
                }
            }
        }
        if (vm.projects.isNotEmpty()) {
            Text("Projektek", fontWeight = FontWeight.SemiBold)
            vm.projects.forEach { name ->
                var confirm by remember { mutableStateOf(false) }
                Panel(Modifier.clickable { vm.select(name) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = if (name == vm.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        if (confirm) {
                            TextButton(onClick = { vm.delete(name); confirm = false }) { Text("Biztos törlöd?", color = MaterialTheme.colorScheme.error) }
                        } else {
                            TextButton(onClick = { confirm = true }) { Text("Törlés") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Scanner
@Composable
private fun ScanScreen(vm: AppVm) {
    var query by rememberSaveable { mutableStateOf("") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val data = vm.scan
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title("Scanner")
        Button(onClick = { vm.runScan() }, enabled = vm.busy == null && vm.current != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (data == null) "Szövegek keresése" else "Újra keresés")
        }
        if (data == null) {
            Muted(if (vm.current == null) "Előbb nyiss meg egy csomagot a Projekt lapon." else "Nincs még eredmény. A kereső végigmegy minden Unity fájlon, és megmutatja, hol van szöveg.")
            return@Column
        }
        if (data.il2cpp) Panel { Text("IL2CPP játék: a szövegek egy része a global-metadata.dat-ban lehet, azt ez a verzió még nem kezeli.", fontSize = 13.sp) }
        if (!data.decoder) Panel { Text("Tömörített textúrák (ETC, ASTC) nem dekódolhatók ezen az eszközön. A DXT és a nyers formátumok igen.", fontSize = 13.sp) }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Szűrés névre vagy szövegre") }
        )
        val q = query.trim().lowercase()
        val files = data.files.filter { f ->
            q.isEmpty() || f.file.lowercase().contains(q) || f.texts.any { it.name.lowercase().contains(q) || it.sample.lowercase().contains(q) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Muted("${data.files.size} Unity fájl · ${data.plain.size} sima szövegfájl · ${data.unknown.size} ismeretlen") }
            items(files, key = { it.file }) { f ->
                Panel(Modifier.clickable { open[f.file] = !(open[f.file] ?: false) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(f.file, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Muted(f.types.take(6).joinToString("   ") { "${it.first} ${it.second}" })
                        val extra = buildString {
                            append("${f.texts.size} szöveges elem")
                            if (f.noTypeTree > 0) append(" · ${f.noTypeTree} Mono type tree nélkül")
                        }
                        Text(extra, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        if (f.error.isNotEmpty()) Text(f.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        if (open[f.file] == true) {
                            f.texts.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.sample.lowercase().contains(q) || f.file.lowercase().contains(q) }
                                .take(120).forEach { t ->
                                    Column(Modifier.padding(top = 8.dp)) {
                                        Text("${t.name.ifBlank { "(név nélkül)" }}  ·  ${t.type}  ·  ${t.n}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        if (t.sample.isNotBlank()) Muted(t.sample)
                                    }
                                }
                        }
                    }
                }
            }
            if (data.plain.isNotEmpty()) {
                item { Text("Sima szövegfájlok", fontWeight = FontWeight.SemiBold) }
                items(data.plain.filter { q.isEmpty() || it.file.lowercase().contains(q) || it.sample.lowercase().contains(q) }.take(200), key = { "p" + it.file }) { p ->
                    Panel {
                        Column {
                            Text(p.file, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Muted("${p.size} bájt  ${p.sample}")
                        }
                    }
                }
            }
            if (data.unknown.isNotEmpty()) {
                item { Text("Ismeretlen fejlécű (titkosított?) fájlok", fontWeight = FontWeight.SemiBold) }
                items(data.unknown, key = { "u" + it }) { u -> Muted(u, mono = true) }
            }
        }
    }
}

// ---------------------------------------------------------------- Csere (export / import)
@Composable
private fun ExchangeScreen(vm: AppVm) {
    val labels = listOf(
        "text" to "Szövegek (TextAsset)", "mono" to "MonoBehaviour szövegek (JSON)",
        "image" to "Képek (Texture2D → PNG)", "audio" to "Hangok (AudioClip)", "plain" to "Sima szövegfájlok"
    )
    Page {
        Title("Export és import")
        Panel {
            Column {
                labels.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { vm.opts[k] = !(vm.opts[k] ?: false) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.opts[k] ?: false, onCheckedChange = { vm.opts[k] = it })
                        Text(label)
                    }
                }
            }
        }
        Button(onClick = { vm.runExport() }, enabled = vm.busy == null && vm.current != null, modifier = Modifier.fillMaxWidth()) { Text("Exportálás") }
        if (vm.exportStat.isNotEmpty()) Muted(vm.exportStat)
        vm.exportDir()?.let {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Itt szerkeszd a fájlokat:", fontSize = 13.sp)
                    SelectionContainer { Text(it.absolutePath, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    Muted("A fájlnevek elején lévő azonosítót (pl. a0p12) ne írd át, az alapján kerül vissza a helyére.")
                }
            }
        }
        FilledTonalButton(onClick = { vm.runImport() }, enabled = vm.busy == null && vm.current != null, modifier = Modifier.fillMaxWidth()) {
            Text("Importálás (csak a módosítottak)")
        }
        if (vm.importStat.isNotEmpty()) Muted(vm.importStat)
        Muted("Az import csak a megváltozott fájlokat írja vissza. A hangok importja még nincs kész. Tömörített textúra helyére a kép tömörítetlenül (RGBA32) kerül, így nagyobb lesz.")
    }
}

// ---------------------------------------------------------------- Csomag
@Composable
private fun BuildScreen(vm: AppVm) {
    Page {
        Title("Csomag")
        Text("Az import után innen készül el a telepíthető APK. A motor visszaírja a módosított fájlokat, igazítja a csomagot és aláírja.", fontSize = 14.sp)
        Button(onClick = { vm.runBuild() }, enabled = vm.busy == null && vm.current != null, modifier = Modifier.fillMaxWidth()) {
            Text("Építés és aláírás")
        }
        vm.built?.let { f ->
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(f.absolutePath, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    Muted("${f.length() / 1024 / 1024} MB")
                    if (f.name.endsWith(".apk")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.install(f) }) { Text("Telepítés") }
                            OutlinedButton(onClick = { vm.share(f) }) { Text("Megosztás") }
                        }
                    } else {
                        OutlinedButton(onClick = { vm.share(f) }) { Text("Megosztás") }
                    }
                }
            }
        }
        Panel {
            Text(
                "Az APK saját kulccsal van aláírva. Ha az eredeti játék telepítve van, előbb távolítsd el, mert eltérő aláírású csomag nem telepíthető fölé. Néhány játék ellenőrzi az aláírást vagy a fájlok épségét, azok nem indulnak el módosítva.",
                fontSize = 13.sp
            )
        }
    }
}

// ---------------------------------------------------------------- Kinézet
@Composable
private fun LookScreen(vm: AppVm) {
    val look = vm.looks.look
    val cs = MaterialTheme.colorScheme
    val swatches = listOf(38f to 0.78f, 8f to 0.7f, 330f to 0.55f, 200f to 0.65f, 150f to 0.6f, 265f to 0.5f)
    Page {
        Title("Kinézet")
        Text("Alap", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeNames.forEachIndexed { i, n ->
                FilterChip(selected = look.mode == i, onClick = { vm.looks.set(look.copy(mode = i)) }, label = { Text(n) })
            }
        }
        Text("Kiemelő szín", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            swatches.forEach { (h, s) ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Look(hue = h, sat = s, value = 0.95f).accent)
                        .border(if (Math.abs(look.hue - h) < 1f) BorderStroke(3.dp, cs.onSurface) else BorderStroke(1.dp, cs.outline), CircleShape)
                        .clickable { vm.looks.set(look.copy(hue = h, sat = s, value = 0.95f)) }
                )
            }
        }
        Label("Árnyalat")
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
                    Brush.horizontalGradient((0..12).map { Color.hsv(it * 30f, 1f, 1f) })
                )
            )
            Slider(
                value = look.hue, onValueChange = { vm.looks.set(look.copy(hue = it)) }, valueRange = 0f..360f,
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent)
            )
        }
        Label("Telítettség")
        Slider(value = look.sat, onValueChange = { vm.looks.set(look.copy(sat = it)) }, valueRange = 0f..1f)
        Label("Fényerő")
        Slider(value = look.value, onValueChange = { vm.looks.set(look.copy(value = it)) }, valueRange = 0.4f..1f)
        Label("Sarkok lekerekítése")
        Slider(value = look.corner, onValueChange = { vm.looks.set(look.copy(corner = it)) }, valueRange = 0f..28f)
        Label("Szövegméret")
        Slider(value = look.scale, onValueChange = { vm.looks.set(look.copy(scale = it)) }, valueRange = 0.85f..1.3f)
        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Előnézet", fontWeight = FontWeight.SemiBold)
                Muted("assets/bin/Data/level0", mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}) { Text("Gomb") }
                    OutlinedButton(onClick = {}) { Text("Másik") }
                }
            }
        }
        TextButton(onClick = { vm.looks.set(Look()) }) { Text("Alaphelyzet") }
    }
}

@Composable
private fun Label(text: String) = Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
