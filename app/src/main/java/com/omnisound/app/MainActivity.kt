package com.omnisound.app

import android.Manifest
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.abs
import com.omnisound.app.domain.RecommendationEngine
import com.omnisound.app.domain.Track
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { com.omnisound.app.ui.theme.OmniTheme { OmniApp() } }
    }
}

private val starterTracks = listOf(
    Track("1", "afterglow", "slowdive", genre = "Dream pop", bpm = 112, tags = setOf("hazy", "guitar", "shoegaze")),
    Track("2", "the less i know", "tame impala", genre = "Psychedelic", bpm = 117, tags = setOf("indie", "bass", "dreamy")),
    Track("3", "glory box", "portishead", genre = "Trip-hop", bpm = 98, tags = setOf("dark", "downtempo", "electronic")),
    Track("4", "chamber of reflection", "mac demarco", genre = "Indie", bpm = 92, tags = setOf("dreamy", "lo-fi", "guitar")),
    Track("5", "teardrop", "massive attack", genre = "Trip-hop", bpm = 76, tags = setOf("dark", "electronic", "ambient"))
)
private val bg = Color(0xFF080808)
private val panel = Color(0xFF191919)
private val muted = Color(0xFF929292)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OmniApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var player by remember { mutableStateOf<MediaController?>(null) }
    val controllerFuture = remember {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener({
            runCatching { player = controllerFuture.get() }
        }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }
    var tab by remember { mutableStateOf("Home") }
    var tracks by remember { mutableStateOf(starterTracks) }
    var selected by remember { mutableStateOf(starterTracks.first()) }
    var playing by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    val engine = remember { RecommendationEngine() }
    val recommendations = remember(selected, tracks) { engine.rank(tracks, emptyList(), selected).filter { it.id != selected.id } }
    val localTracks = tracks.filter { it.streamUrl?.startsWith("content://") == true }

    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Imported audio"
            val track = Track("local-$uri", name.substringBeforeLast('.').ifBlank { name }, "On this device", streamUrl = uri.toString(), genre = "Local", tags = setOf("local"))
            val updatedTracks = tracks.filterNot { it.id == track.id } + track
            tracks = updatedTracks
            selected = track
            error = null
            val playable = updatedTracks.filter { it.streamUrl?.startsWith("content://") == true || it.streamUrl?.startsWith("https://") == true || it.streamUrl?.startsWith("http://") == true }
            val queueIndex = playable.indexOfFirst { it.id == track.id }
            player?.setMediaItems(playable.map { mediaItem(it, it.streamUrl.orEmpty()) }, queueIndex, 0L)
            player?.prepare()
            player?.play()
            tab = "Player"
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { duration = player?.duration?.takeIf { it > 0 } ?: 0L }
            override fun onPlayerError(exception: PlaybackException) {
                playing = false
                error = "Не удалось открыть этот аудиофайл. Попробуй другой файл."
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = player?.duration?.takeIf { it > 0 } ?: 0L
            delay(400)
        }
    }

    fun selectTrack(track: Track) {
        selected = track
        error = null
        val uri = track.streamUrl
        if (uri?.startsWith("content://") == true || uri?.startsWith("https://") == true || uri?.startsWith("http://") == true) {
            val playable = tracks.filter { it.streamUrl?.let { url -> url.startsWith("content://") || url.startsWith("https://") || url.startsWith("http://") } == true }
            val queue = (playable.filterNot { it.id == track.id } + track)
            val queueIndex = queue.indexOfFirst { it.id == track.id }
            player?.setMediaItems(queue.map { mediaItem(it, it.streamUrl.orEmpty()) }, queueIndex, 0L)
            player?.prepare()
            player?.play()
        } else {
            player?.pause()
            error = "Это карточка каталога, а не аудиопоток. Для неё пока не подключён SoundCloud API."
        }
        tab = "Player"
    }
    fun nextTrack() {
        val list = if (shuffle) tracks.shuffled() else tracks
        val index = list.indexOfFirst { it.id == selected.id }
        selectTrack(list[(index + 1).mod(list.size)])
    }
    fun previousTrack() {
        if (position > 3000L) player?.seekTo(0L) else {
            val index = tracks.indexOfFirst { it.id == selected.id }
            selectTrack(tracks[(index - 1).mod(tracks.size)])
        }
    }

    Scaffold(
        containerColor = bg,
        bottomBar = {
            Column {
                AnimatedVisibility(tab != "Player", enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                    MiniPlayer(selected, playing, if (duration > 0) position.toFloat() / duration else 0f,
                        onToggle = { if (selected.streamUrl?.startsWith("content://") == true) { if (player?.isPlaying == true) player?.pause() else player?.play() } else error = "Импортируй аудио с телефона, чтобы проверить воспроизведение." },
                        onOpen = { tab = "Player" })
                }
                NavigationBar(containerColor = Color(0xFF0D0D0D), tonalElevation = 0.dp) {
                    listOf("Home" to Icons.Default.Home, "Radio" to Icons.Default.GraphicEq, "Search" to Icons.Default.Search, "Library" to Icons.Default.LibraryMusic).forEach { (name, icon) ->
                        NavigationBarItem(selected = tab == name, onClick = { tab = name }, icon = { Icon(icon, null) }, label = { Text(name, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color(0xFF292929), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray))
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(targetState = tab, modifier = Modifier.padding(padding), transitionSpec = { (slideInVertically(tween(260)) { it / 14 } + fadeIn(tween(220))) togetherWith (slideOutVertically(tween(180)) { -it / 20 } + fadeOut(tween(140))) }, label = "screen") { destination ->
            when (destination) {
                "Player" -> PlayerScreen(selected, playing, position, duration, selected.id in liked, error,
                    onToggle = { if (selected.streamUrl?.startsWith("content://") == true) { if (player?.isPlaying == true) player?.pause() else player?.play() } else error = "Этот трек пока демонстрационный. Импортируй аудио через Library." },
                    onLike = { liked = if (selected.id in liked) liked - selected.id else liked + selected.id },
                    onSeek = { player?.seekTo(it) }, onNext = ::nextTrack, onPrevious = ::previousTrack,
                    onShuffle = { shuffle = !shuffle }, onQueue = { queueOpen = true }, onBack = { tab = "Home" })
                "Search" -> SearchScreen(query, { query = it }, tracks.filter { (it.title + it.artist + it.genre).contains(query, true) }, ::selectTrack)
                "Radio" -> RadioScreen(selected, recommendations, ::selectTrack)
                "Library" -> LibraryScreen(liked.mapNotNull { id -> tracks.find { it.id == id } }, localTracks, ::selectTrack) { importAudio.launch(arrayOf("audio/*")) }
                else -> HomeScreen(recommendations, ::selectTrack, { tab = "Radio" }) { importAudio.launch(arrayOf("audio/*")) }
            }
        }
    }
    if (queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false }, containerColor = panel) {
        Text("UP NEXT", color = muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 22.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp)) { items(tracks, key = { it.id }) { TrackRow(it) { selectTrack(it); queueOpen = false } } }
    }
}

@Composable
private fun HomeScreen(recommendations: List<Track>, onTrack: (Track) -> Unit, onRadio: () -> Unit, onImport: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(23.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("omnisound", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp); Text("YOUR SOUND, YOUR UNIVERSE", color = muted, fontSize = 9.sp, letterSpacing = 2.sp) }
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF202020)), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null) }
            }
        }
        item {
            Text("YOUR NEXT OBSESSION", color = muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp)).background(Brush.linearGradient(listOf(Color(0xFF383838), Color(0xFF111111)))).clickable(onClick = onRadio).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A little closer\nto your taste.", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                    Text("A mix that learns what moves you", color = Color.LightGray, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("START RADIO", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold); Icon(Icons.Default.ArrowForward, null, Modifier.padding(start = 7.dp).size(15.dp)) }
                }
                Equalizer(Modifier.size(96.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xFF252525)))
            }
        }
        item { SectionTitle("Made for you", "BASED ON YOUR TASTE") }
        items(recommendations, key = { it.id }) { TrackRow(it) { onTrack(it) } }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(panel).clickable(onClick = onImport).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Play music from this device", fontWeight = FontWeight.SemiBold); Text("Import an audio file to test playback", color = muted, fontSize = 11.sp) }; Icon(Icons.Default.ChevronRight, null, tint = muted)
            }
        }
        item { Text("SoundCloud tracks are discovery placeholders at this stage. Live search and streaming require an approved API integration.", color = muted, fontSize = 10.sp, lineHeight = 15.sp) }
    }
}

@Composable
private fun RadioScreen(seed: Track, tracks: List<Track>, onTrack: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp)) {
        Text("RADIO", color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Keep discovering.", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(seed, 62); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("RADIO STARTED FROM", color = muted, fontSize = 9.sp, letterSpacing = 1.sp); Text(seed.title, fontWeight = FontWeight.Bold); Text(seed.artist, color = muted, fontSize = 12.sp) }; Equalizer(Modifier.size(38.dp))
        }
        Spacer(Modifier.height(25.dp)); Text("UP NEXT · YOUR LOCAL MIX", color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
        LazyColumn { items(tracks, key = { it.id }) { TrackRow(it) { onTrack(it) } } }
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, tracks: List<Track>, onTrack: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Search", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text("FIND SOMETHING THAT FEELS RIGHT", color = muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("Tracks, artists, moods...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.DarkGray, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White))
        Spacer(Modifier.height(17.dp)); Text("${tracks.size} RESULTS", color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
        LazyColumn { items(tracks, key = { it.id }) { TrackRow(it) { onTrack(it) } } }
    }
}

@Composable
private fun LibraryScreen(liked: List<Track>, imported: List<Track>, onTrack: (Track) -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Your library", fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Text("YOUR MUSIC, IN ONE PLACE", color = muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(22.dp)); Text("Liked tracks · ${liked.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (liked.isEmpty()) Text("Tap the heart on a track to save it here.", color = muted, modifier = Modifier.padding(top = 12.dp)) else LazyColumn(Modifier.weight(1f, false)) { items(liked, key = { it.id }) { TrackRow(it) { onTrack(it) } } }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("On this device", fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick = onImport) { Icon(Icons.Default.Add, null); Text("Import") } }
        if (imported.isEmpty()) Text("Add audio files from your phone. Playback works offline.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        else LazyColumn { items(imported, key = { it.id }) { TrackRow(it) { onTrack(it) } } }
    }
}

@Composable
private fun PlayerScreen(track: Track, playing: Boolean, position: Long, duration: Long, liked: Boolean, error: String?, onToggle: () -> Unit, onLike: () -> Unit, onSeek: (Long) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onShuffle: () -> Unit, onQueue: () -> Unit, onBack: () -> Unit) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().padding(horizontal = 25.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, null, tint = muted) }; Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("NOW PLAYING", fontSize = 9.sp, letterSpacing = 2.sp, color = muted); Text(if (track.streamUrl?.startsWith("content://") == true) "ON THIS DEVICE" else "SOUNDCLOUD DISCOVERY", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.weight(1f)); IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = muted) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val artworkPulse by rememberInfiniteTransition(label = "artwork-pulse").animateFloat(
                initialValue = 1f, targetValue = 1.025f,
                animationSpec = infiniteRepeatable(tween(1500), repeatMode = RepeatMode.Reverse),
                label = "artwork-scale"
            )
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
                    val scale = if (playing) artworkPulse else 1f
                    scaleX = scale; scaleY = scale
                }.clip(RoundedCornerShape(29.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF555555), Color(0xFF141414))))
                    .pointerInput(track.id) {
                        detectDragGestures(
                            onDragEnd = {
                                when {
                                    abs(dragY) > 75f && dragY > 0f -> onBack()
                                    abs(dragX) > 75f && dragX < 0f -> onNext()
                                    abs(dragX) > 75f && dragX > 0f -> onPrevious()
                                }
                                dragX = 0f; dragY = 0f
                            },
                            onDragCancel = { dragX = 0f; dragY = 0f }
                        ) { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y }
                    },
                contentAlignment = Alignment.Center
            ) { Equalizer(Modifier.size(132.dp), playing) }
            Spacer(Modifier.height(25.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, color = muted, fontSize = 14.sp) }
                IconButton(onClick = onLike) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (liked) Color.White else muted) }
            }
            AnimatedVisibility(error != null) { Text(error.orEmpty(), color = Color(0xFFBDBDBD), fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        Column(Modifier.fillMaxWidth()) {
            val safeDuration = duration.coerceAtLeast(1L)
            Slider(value = (position.toFloat() / safeDuration).coerceIn(0f, 1f), onValueChange = { onSeek((it * safeDuration).toLong()) }, enabled = duration > 0, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(position), color = muted, fontSize = 10.sp); Text(time(duration), color = muted, fontSize = 10.sp) }
            Spacer(Modifier.height(17.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = muted) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = onToggle, Modifier.size(70.dp).clip(CircleShape).background(Color.White)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = muted) }
            }
        }
    }
}

@Composable
private fun MiniPlayer(track: Track, playing: Boolean, progress: Float, onToggle: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color(0xFF202020)).animateContentSize().clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, 40); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, color = muted, fontSize = 10.sp) }
            IconButton(onClick = onToggle, Modifier.size(38.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp).clip(CircleShape).background(Color(0xFF343434))) { Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White)) }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).animateContentSize().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, 52); Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1) }; Icon(Icons.Default.MoreHoriz, null, tint = muted)
    }
}

@Composable
private fun Artwork(track: Track, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Color(0xFF606060), Color(0xFF171717)))), contentAlignment = Alignment.Center) { Text(track.title.take(1).uppercase(), color = Color.White.copy(alpha = .9f), fontSize = (size / 2).sp, fontWeight = FontWeight.Black) }
}

@Composable
private fun Equalizer(modifier: Modifier = Modifier, active: Boolean = true) {
    val heights = listOf(18, 34, 52, 27, 43, 22)
    val transition = rememberInfiniteTransition(label = "equalizer-loop")
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { index, height ->
            val animated by transition.animateFloat(
                initialValue = .38f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420 + index * 95, delayMillis = index * 65),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "equalizer-$index"
            )
            val scale = if (active) animated else .52f
            Box(Modifier.padding(horizontal = 2.dp).width(4.dp).height((height * scale).dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun SectionTitle(title: String, eyebrow: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(eyebrow, color = muted, fontSize = 9.sp, letterSpacing = 1.7.sp); Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
}
private fun time(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private fun mediaItem(track: Track, uri: String): MediaItem = MediaItem.Builder()
    .setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build())
    .build()
