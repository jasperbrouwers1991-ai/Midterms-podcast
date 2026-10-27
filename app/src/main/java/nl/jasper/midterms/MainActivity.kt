package nl.jasper.midterms

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var controller by mutableStateOf<MediaController?>(null)
    private var tts: TextToSpeech? = null
    private var ttsSpeakingDate by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFE63946),
                secondary = Color(0xFF4F8EF7),
                background = Color(0xFF101827),
                surface = Color(0xFF172133),
                surfaceVariant = Color(0xFF1F2B42),
            )) {
                App(
                    controller = controller,
                    ttsSpeakingDate = ttsSpeakingDate,
                    onPlay = ::play,
                    onSpeak = ::speak,
                    onStopSpeak = ::stopSpeaking,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, mainExecutor)
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun play(ep: Episode) {
        val c = controller ?: return
        stopSpeaking()
        val url = ep.audioUrl ?: return
        if (c.currentMediaItem?.mediaId == ep.date) {
            if (c.isPlaying) c.pause() else c.play()
            return
        }
        val item = MediaItem.Builder()
            .setMediaId(ep.date)
            .setUri(url.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setArtist("AI Dagelijks")
                    .setIsPlayable(true)
                    .build()
            ).build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    /** Fallback for days when ElevenLabs produced no audio: the phone's own voice reads the script. */
    private fun speak(ep: Episode) {
        controller?.pause()
        val start = {
            tts?.setLanguage(Locale("nl", "NL"))
            val chunks = ep.text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            chunks.forEachIndexed { i, s ->
                tts?.speak(s, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "c$i")
            }
            ttsSpeakingDate = ep.date
        }
        if (tts == null) {
            tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) start() }
        } else start()
    }

    private fun stopSpeaking() {
        tts?.stop()
        ttsSpeakingDate = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    controller: MediaController?,
    ttsSpeakingDate: String?,
    onPlay: (Episode) -> Unit,
    onSpeak: (Episode) -> Unit,
    onStopSpeak: () -> Unit,
) {
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf<Episode?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() = scope.launch {
        loading = true
        error = null
        runCatching { Feed.load() }
            .onSuccess { episodes = it }
            .onFailure { error = "Kon afleveringen niet laden: ${it.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    // Poll the controller so the play button and progress stay in sync.
    var playingId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                playingId = it.currentMediaItem?.mediaId
                isPlaying = it.isPlaying
                position = it.currentPosition
                duration = it.duration.coerceAtLeast(0)
            }
            delay(500)
        }
    }

    val current = open
    if (current != null) {
        androidx.activity.compose.BackHandler { open = null }
        EpisodeDetail(
            ep = current,
            isThis = playingId == current.date,
            isPlaying = isPlaying && playingId == current.date,
            position = position,
            duration = duration,
            speaking = ttsSpeakingDate == current.date,
            onBack = { open = null },
            onPlay = { onPlay(current) },
            onSeek = { controller?.seekTo(it) },
            onRewind = { controller?.seekBack() },
            onForward = { controller?.seekForward() },
            onSpeak = { onSpeak(current) },
            onStopSpeak = onStopSpeak,
        )
        return
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("AI Dagelijks", fontWeight = FontWeight.Bold)
                        Text(
                            "Elke dag 5 minuten over kunstmatige intelligentie",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Vernieuwen") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading && episodes.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && episodes.isEmpty() -> Text(error!!, Modifier.padding(24.dp).align(Alignment.Center))
                episodes.isEmpty() -> Text(
                    "Nog geen afleveringen. De eerste verschijnt om 7:00.",
                    Modifier.padding(24.dp).align(Alignment.Center)
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(episodes, key = { it.date }) { ep ->
                        EpisodeCard(
                            ep = ep,
                            latest = ep == episodes.first(),
                            isPlaying = isPlaying && playingId == ep.date,
                            speaking = ttsSpeakingDate == ep.date,
                            onOpen = { open = ep },
                            onPlay = { if (ep.audioUrl != null) onPlay(ep) else if (ttsSpeakingDate == ep.date) onStopSpeak() else onSpeak(ep) },
                        )
                    }
                }
            }
        }
    }
}

private val dutchDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl", "NL"))
private fun prettyDate(d: String) =
    runCatching { LocalDate.parse(d).format(dutchDate).replaceFirstChar { it.uppercase() } }.getOrDefault(d)

@Composable
private fun EpisodeCard(
    ep: Episode, latest: Boolean, isPlaying: Boolean, speaking: Boolean,
    onOpen: () -> Unit, onPlay: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (latest) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (latest) "NIEUWSTE · " else "") + prettyDate(ep.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (latest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(ep.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (ep.summary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ep.summary, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ep.audioUrl == null) "Alleen tekst · voorgelezen door je telefoon"
                    else "${(ep.durationSeconds + 30) / 60} min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(52.dp)) {
                Icon(
                    when {
                        isPlaying || speaking -> Icons.Default.Pause
                        ep.audioUrl == null -> Icons.Default.RecordVoiceOver
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = "Afspelen"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeDetail(
    ep: Episode, isThis: Boolean, isPlaying: Boolean, position: Long, duration: Long, speaking: Boolean,
    onBack: () -> Unit, onPlay: () -> Unit, onSeek: (Long) -> Unit,
    onRewind: () -> Unit, onForward: () -> Unit, onSpeak: () -> Unit, onStopSpeak: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(prettyDate(ep.date)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 20.dp).fillMaxSize()) {
            Text(ep.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (ep.audioUrl != null) {
                val dur = if (isThis && duration > 0) duration else ep.durationSeconds * 1000L
                val pos = if (isThis) position else 0L
                Slider(
                    value = if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { if (isThis && dur > 0) onSeek((it * dur).toLong()) },
                    enabled = isThis
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(pos), style = MaterialTheme.typography.labelSmall)
                    Text(fmt(dur), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRewind, enabled = isThis) { Icon(Icons.Default.Replay, "15 s terug") }
                    Spacer(Modifier.width(16.dp))
                    FilledIconButton(onClick = onPlay, modifier = Modifier.size(64.dp)) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Afspelen",
                            Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onForward, enabled = isThis) { Icon(Icons.Default.Forward30, "30 s vooruit") }
                }
            } else {
                Button(onClick = { if (speaking) onStopSpeak() else onSpeak() }) {
                    Icon(if (speaking) Icons.Default.Stop else Icons.Default.RecordVoiceOver, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (speaking) "Stop voorlezen" else "Laat voorlezen")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Uitgeschreven tekst", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                ep.text,
                Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
