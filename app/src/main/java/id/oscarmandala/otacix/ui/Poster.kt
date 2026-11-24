package id.oscarmandala.otacix.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.oscarmandala.otacix.OtacixApp
import id.oscarmandala.otacix.data.Track
import id.oscarmandala.otacix.util.PosterRenderer
import id.oscarmandala.otacix.util.Tier
import kotlinx.coroutines.launch

/** Poster Studio: playlist -> poster liquid glass (simpan ke galeri / bagikan). */
@Composable
fun PosterScreen(ui: UiState) {
    val ctx = LocalContext.current
    val tier = LocalTier.current
    val scope = rememberCoroutineScope()
    val db by OtacixApp.store.db.collectAsStateWithLifecycle()

    val sources = listOf("liked" to "Disukai") + db.playlists.map { it.id to it.name } + ("history" to "Riwayat")
    var sel by remember { mutableStateOf(ui.posterFor ?: "liked") }
    var style by remember { mutableIntStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var bg by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? -> if (u != null) bg = u }

    val tracks: List<Track> = when (sel) {
        "liked" -> db.liked
        "history" -> db.history.map { it.track }
        else -> db.playlists.firstOrNull { it.id == sel }?.tracks.orEmpty()
    }
    val title = sources.firstOrNull { it.first == sel }?.second ?: "Playlist"

    LaunchedEffect(sel, style, tracks.size, bg) {
        bmp = null
        if (tracks.isEmpty()) return@LaunchedEffect
        busy = true
        bmp = try { PosterRenderer.render(ctx, title, tracks, style, tier == Tier.LITE, bg) } catch (e: Throwable) { null }
        busy = false
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBtn(Ic.Close, 44.dp, 22.dp, glass = true) { ui.poster = false; ui.posterFor = null }
            Txt("Poster Studio", 22.sp, FontWeight.ExtraBold)
        }
        Txt("Sumber", 13.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources) { (id, name) -> Chip(name, sel == id) { sel = id } }
        }
        Txt("Gaya", 13.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Kartu", style == 0) { style = 0 }
            Chip("Grid 3×3", style == 1) { style = 1 }
            Chip("Tumpuk", style == 2) { style = 2 }
        }
        if (style == 0) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Foto latar", Ic.Image) { picker.launch("image/*") }
                if (bg != null) PillButton("Hapus foto", Ic.Close) { bg = null }
            }
        }

        Box(
            Modifier.padding(top = 20.dp).widthIn(max = 340.dp).fillMaxWidth(0.8f).aspectRatio(2f / 3f)
                .glass(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            when {
                b != null -> Image(b.asImageBitmap(), "Poster", Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Fit)
                tracks.isEmpty() -> Txt("Sumber ini masih kosong.\nSukai lagu atau isi playlist dulu.", 13.sp, color = Color.White.copy(alpha = 0.7f), align = TextAlign.Center, modifier = Modifier.padding(20.dp))
                else -> Txt(if (busy) "Membuat poster…" else "Gagal membuat poster", 14.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }

        Row(Modifier.padding(top = 20.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Simpan", Ic.Download, primary = true) {
                val b = bmp
                when {
                    b == null -> toast(ctx, "Poster belum siap")
                    PosterRenderer.saveToGallery(ctx, b) -> toast(ctx, "Tersimpan di Galeri › Pictures/Otacix")
                    else -> toast(ctx, "Simpan langsung butuh Android 10+. Gunakan Bagikan.")
                }
            }
            PillButton("Bagikan", Ic.Share) {
                val b = bmp
                if (b == null) toast(ctx, "Poster belum siap") else PosterRenderer.share(ctx, b)
            }
        }
    }
}
