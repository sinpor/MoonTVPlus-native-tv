package com.moontvplus.nativetv

import android.view.KeyEvent
import android.view.ViewGroup
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class DetailTab { EPISODES, SOURCES }
private enum class FullPanel { NONE, EPISODES, SOURCES }
private val actions = listOf("暂停/播放", "上一集", "下一集", "选集", "换源")
private const val GRID_COLUMNS = 4
private enum class PlaybackGlyph { PLAY, PAUSE, PREVIOUS, NEXT }

@Composable
fun DetailPlaybackScreen(
    api: MoonApi,
    store: WatchStore,
    item: VideoItem,
    fullScreen: Boolean,
    onFullScreen: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(api, item.source, item.id) {
        val http = OkHttpDataSource.Factory(api.mediaClient())
            .setDefaultRequestProperties(mapOf("User-Agent" to "MoonTVPlus App NativeTV/0.1"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
    }
    var active by remember(item.source, item.id) { mutableStateOf(item) }
    var sources by remember(item.source, item.id) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var episode by remember(item.source, item.id) { mutableIntStateOf(0) }
    var startTime by remember(item.source, item.id) { mutableStateOf(0L) }
    var playVersion by remember(item.source, item.id) { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var playWhenReady by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var resolvingMedia by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }
    var detailTab by remember { mutableStateOf(DetailTab.EPISODES) }
    var menuVisible by remember { mutableStateOf(false) }
    var menuIndex by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(FullPanel.NONE) }
    var panelIndex by remember { mutableIntStateOf(0) }
    var previewFocused by remember { mutableStateOf(false) }
    val previewFocus = remember { FocusRequester() }
    val favoriteFocus = remember { FocusRequester() }
    val episodesTabFocus = remember { FocusRequester() }
    val fullFocus = remember { FocusRequester() }
    var progressVisible by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seekSelection by remember(player) { mutableStateOf<SeekSelection?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    val hostView = LocalView.current
    var pausedByBackground by remember(player) { mutableStateOf(false) }
    val seekController = remember(player) {
        FullscreenSeekController(object : SeekPlayback {
            override val position get() = player.currentPosition
            override val duration get() = player.duration
            override val canSeek get() = player.isCurrentMediaItemSeekable && !resolvingMedia &&
                !loading && error.isBlank() && !pausedByBackground
            override val playing get() = player.playWhenReady
            override fun seekTo(position: Long) { player.seekTo(position) }
            override fun setPlayWhenReady(playing: Boolean) {
                player.playWhenReady = playing && !pausedByBackground
            }
        }) {
            seekSelection = it
            lastInteraction = SystemClock.uptimeMillis()
        }
    }
    val recording = remember(player) { mutableStateOf<Pair<VideoItem, Int>?>(null) }
    val recordWrites = remember(player) { Channel<Pair<String, JSONObject>>(Channel.UNLIMITED) }

    fun queueProgressSave() {
        val currentPosition = player.currentPosition
        val (video, recordedEpisode) = recording.value ?: return
        if (currentPosition <= 1000 || video.source.isBlank() || video.id.isBlank()) return
        recordWrites.trySend("${video.source}+${video.id}" to JSONObject()
            .put("title", video.title).put("source_name", video.sourceName)
            .put("year", video.year).put("cover", video.poster)
            .put("index", recordedEpisode + 1).put("total_episodes", video.episodes.size)
            .put("play_time", currentPosition / 1000)
            .put("total_time", player.duration.coerceAtLeast(0) / 1000)
            .put("save_time", System.currentTimeMillis()))
    }

    fun showProgress() {
        lastInteraction = SystemClock.uptimeMillis()
        progressVisible = true
        positionMs = player.currentPosition.coerceAtLeast(0)
        durationMs = player.duration
    }

    fun stopSeeking(restorePlayback: Boolean = true) {
        seekJob?.cancel()
        seekJob = null
        seekController.cancel(restorePlayback)
        lastInteraction = SystemClock.uptimeMillis()
    }

    fun startSeeking(direction: Int) {
        seekJob?.cancel()
        seekJob = null
        seekController.press(direction, SystemClock.uptimeMillis())
        showProgress()
        if (seekController.selection == null) return
        seekJob = scope.launch {
            delay(500)
            while (seekController.selection != null) {
                seekController.advance(SystemClock.uptimeMillis())
                delay(250)
            }
        }
    }

    DisposableEffect(player, hostView) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pausedByBackground = true
                player.pause()
                queueProgressSave()
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) stopSeeking(restorePlayback = false)
        }
        val windowListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) stopSeeking() else seekController.resetInput()
        }
        owner?.lifecycle?.addObserver(observer)
        hostView.viewTreeObserver.addOnWindowFocusChangeListener(windowListener)
        onDispose {
            stopSeeking(restorePlayback = false)
            owner?.lifecycle?.removeObserver(observer)
            if (hostView.viewTreeObserver.isAlive) hostView.viewTreeObserver.removeOnWindowFocusChangeListener(windowListener)
        }
    }

    val panelGridState = rememberLazyGridState()
    val detailGridState = rememberLazyGridState()

    DisposableEffect(player) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for ((key, record) in recordWrites) {
                try { store.saveRecord(key, record) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Progress saves must not block leaving playback. */ }
            }
        }
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { playWhenReady = ready }
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
            override fun onPlayerError(exception: androidx.media3.common.PlaybackException) {
                error = exception.message ?: "播放失败"
            }
        }
        player.addListener(listener)
        onDispose {
            queueProgressSave()
            recordWrites.close()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(item.source, item.id) {
        loading = true
        try {
            val allowed = api.sources()
            val matching = api.search(item.title, allowed).filter { sameWork(item, it) }
            val initial = if (item.source.isNotBlank() && item.id.isNotBlank()) api.detail(item.source, item.id)
                else matching.firstOrNull()?.let { api.detail(it.source, it.id) }
                    ?: error("没有找到可播放的普通点播源")
            val record = store.records().optJSONObject("${initial.source}+${initial.id}")
            val resumeEpisode = ((record?.optInt("index", 1) ?: 1) - 1).coerceAtLeast(0)
            sources = (matching + initial).distinctBy { it.source to it.id }.filter { sameWork(initial, it) }
            val selected = SourceSelector(api).fastest(sources, resumeEpisode) ?: initial
            active = if (selected.episodes.isNotEmpty()) selected else api.detail(selected.source, selected.id)
            episode = resumeEpisode.coerceAtMost((active.episodes.size - 1).coerceAtLeast(0))
            startTime = if (active.source == initial.source && active.id == initial.id) {
                (record?.optLong("play_time", 0) ?: 0) * 1000L
            } else 0L
            favorite = store.favorites().has("${active.source}+${active.id}")
            playVersion++
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "详情加载失败" }
        finally { loading = false }
    }

    LaunchedEffect(active.source, active.id, episode, playVersion) {
        if (playVersion == 0 || active.episodes.isEmpty()) return@LaunchedEffect
        error = ""
        resolvingMedia = true
        try {
            val url = api.resolveEpisode(active.episodes[episode], active.source, active.proxyMode)
            player.setMediaItem(MediaItem.fromUri(url))
            recording.value = active to episode
            player.prepare()
            if (startTime > 0) player.seekTo(startTime)
            if (!pausedByBackground) player.playWhenReady = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法播放" }
        finally { resolvingMedia = false }
    }

    LaunchedEffect(active.source, active.id, episode) {
        while (true) {
            delay(10000)
            queueProgressSave()
        }
    }

    fun chooseEpisode(index: Int) {
        stopSeeking(restorePlayback = false)
        if (index !in active.episodes.indices) return
        queueProgressSave()
        pausedByBackground = false
        episode = index
        startTime = 0L
        playVersion++
        panel = FullPanel.NONE
        menuVisible = false
    }

    fun chooseSource(index: Int) {
        stopSeeking(restorePlayback = false)
        val candidate = sources.getOrNull(index) ?: return
        queueProgressSave()
        pausedByBackground = false
        scope.launch {
            loading = true
            try {
                val next = api.detail(candidate.source, candidate.id)
                require(sameWork(active, next) && next.episodes.isNotEmpty()) { "此播放源没有可用选集" }
                active = next
                episode = episode.coerceAtMost(next.episodes.lastIndex)
                startTime = 0L
                playVersion++
                favorite = store.favorites().has("${next.source}+${next.id}")
                error = ""
                panel = FullPanel.NONE
                menuVisible = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "换源失败" }
            finally { loading = false }
        }
    }

    fun runAction(index: Int) {
        when (index) {
            0 -> if (player.playWhenReady) player.pause() else player.play()
            1 -> if (episode > 0) chooseEpisode(episode - 1)
            2 -> if (episode + 1 < active.episodes.size) chooseEpisode(episode + 1)
            3 -> { panel = FullPanel.EPISODES; panelIndex = episode }
            4 -> { panel = FullPanel.SOURCES; panelIndex = sources.indexOfFirst { it.source == active.source && it.id == active.id }.coerceAtLeast(0) }
        }
    }

    fun actionEnabled(index: Int): Boolean = when (index) {
        1 -> episode > 0
        2 -> episode + 1 < active.episodes.size
        else -> true
    }

    LaunchedEffect(episode, active.episodes.size) {
        if (!actionEnabled(menuIndex)) menuIndex = 0
    }

    LaunchedEffect(fullScreen, active.id) {
        stopSeeking()
        if (fullScreen) {
            showProgress()
            fullFocus.requestFocus()
        }
        else {
            menuVisible = false
            panel = FullPanel.NONE
            detailGridState.scrollToItem(0)
            previewFocus.requestFocus()
        }
    }
    val pausedForProgress = !playWhenReady && playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING) && error.isBlank()
    val keepProgress = pausedForProgress || menuVisible || panel != FullPanel.NONE || seekSelection != null
    LaunchedEffect(fullScreen, keepProgress, lastInteraction) {
        if (!fullScreen) {
            progressVisible = false
        } else if (keepProgress) {
            progressVisible = true
        } else {
            delay((3000L - (SystemClock.uptimeMillis() - lastInteraction)).coerceAtLeast(0L))
            progressVisible = false
        }
    }
    LaunchedEffect(fullScreen, progressVisible) {
        while (fullScreen && progressVisible) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration
            delay(200)
        }
    }
    LaunchedEffect(menuVisible, panel, playWhenReady) {
        if (menuVisible || panel != FullPanel.NONE) stopSeeking()
        if (fullScreen) showProgress()
    }
    LaunchedEffect(loading, resolvingMedia, error, playbackState) {
        if (seekController.selection == null ||
            (!loading && !resolvingMedia && error.isBlank() && player.isCurrentMediaItemSeekable && player.duration > 0)) return@LaunchedEffect
        stopSeeking()
    }

    BackHandler(fullScreen) {
        val wasSelecting = seekController.selection != null
        stopSeeking()
        showProgress()
        when {
            wasSelecting -> Unit
            panel != FullPanel.NONE -> panel = FullPanel.NONE
            menuVisible -> menuVisible = false
            else -> onBack()
        }
    }
    LaunchedEffect(panel, panelIndex) {
        if (panel != FullPanel.NONE && panelIndex >= 0) {
            val layout = panelGridState.layoutInfo
            val selected = layout.visibleItemsInfo.firstOrNull { it.index == panelIndex }
            if (selected == null || selected.offset.y < layout.viewportStartOffset ||
                selected.offset.y + selected.size.height > layout.viewportEndOffset) {
                panelGridState.scrollToItem(panelIndex)
            }
        }
    }

    if (fullScreen) {
        Box(Modifier.fillMaxSize().focusRequester(fullFocus)
            .onFocusChanged { if (!it.isFocused) stopSeeking() else seekController.resetInput() }
            .onPreviewKeyEvent { key ->
                val native = key.nativeKeyEvent
                val isSeekKey = native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                val seekDirection = if (native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (native.action == KeyEvent.ACTION_UP) {
                    if (isSeekKey && seekController.isHeld(seekDirection)) {
                        seekJob?.cancel()
                        seekJob = null
                        if (native.isCanceled) seekController.cancel()
                        else if (seekController.selection?.longPress == false) {
                            // A release at the threshold can beat the coroutine's first tick.
                            seekController.advance(SystemClock.uptimeMillis())
                        }
                        seekController.release(seekDirection)
                        showProgress()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                showProgress()
                if (isSeekKey && (seekController.isHeld(seekDirection) || native.repeatCount > 0) &&
                    (!menuVisible && panel == FullPanel.NONE || seekController.isHeld(seekDirection))) return@onPreviewKeyEvent true
                if (!isSeekKey && native.keyCode != KeyEvent.KEYCODE_BACK) stopSeeking()
                when (key.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (panel != FullPanel.NONE) {
                            if (panel == FullPanel.EPISODES) chooseEpisode(panelIndex) else chooseSource(panelIndex)
                        } else if (menuVisible) runAction(menuIndex)
                        else if (player.playWhenReady) player.pause() else player.play()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (panel != FullPanel.NONE) panelIndex = (panelIndex + GRID_COLUMNS).coerceAtMost(
                            if (panel == FullPanel.EPISODES) active.episodes.lastIndex else sources.lastIndex
                        ) else if (!menuVisible) menuVisible = true
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (panel != FullPanel.NONE) panelIndex = (panelIndex - GRID_COLUMNS).coerceAtLeast(0)
                        else if (menuVisible) menuVisible = false
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val step = if (key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        if (panel != FullPanel.NONE) {
                            val count = if (panel == FullPanel.EPISODES) active.episodes.size else sources.size
                            panelIndex = (panelIndex + step)
                                .coerceIn(0, (count - 1).coerceAtLeast(0))
                        } else if (menuVisible) {
                            var candidate = menuIndex + step
                            while (candidate in actions.indices && !actionEnabled(candidate)) candidate += step
                            if (candidate in actions.indices) menuIndex = candidate
                        } else {
                            if (native.repeatCount == 0) {
                                startSeeking(step)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }.focusable()) {
            PlaybackView(player, Modifier.fillMaxSize())
            val paused = !playWhenReady && playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING) && error.isBlank()
            val buffering = error.isBlank() && (resolvingMedia || loading || playWhenReady && playbackState == Player.STATE_BUFFERING)
            if (paused && seekSelection?.longPress != true) {
                Box(Modifier.align(Alignment.Center).size(88.dp)
                    .background(Color(0xB8000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    PlaybackIcon(PlaybackGlyph.PAUSE, Modifier.size(42.dp))
                }
            }
            if (buffering && !paused) {
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color(0xB8000000), RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    Text("正在加载…", color = Color.White, fontSize = 16.sp)
                }
            }
            if (menuVisible || panel != FullPanel.NONE) {
                Text("${active.title}  ·  第 ${episode + 1} 集", color = Color.White, fontSize = 22.sp,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000)).padding(16.dp))
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9999),
                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(16.dp))
            if (progressVisible && !menuVisible && panel == FullPanel.NONE) {
                PlaybackProgress(seekSelection?.target ?: positionMs, durationMs, Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().background(Color(0xCC10131D)).padding(24.dp),
                    offset = seekSelection?.let { it.target - it.origin })
            }
            if (menuVisible || panel != FullPanel.NONE) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD10131D)).padding(16.dp)) {
                    PlaybackProgress(positionMs, durationMs, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    if (panel == FullPanel.NONE) {
                        Text("←/→ 选择操作 · 确定执行 · 返回隐藏菜单", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.forEachIndexed { index, label ->
                                if (index < 3) {
                                    PlaybackActionChip(index, index == 0 && playWhenReady, menuIndex == index,
                                        actionEnabled(index), label) { menuIndex = index; runAction(index) }
                                } else {
                                    ActionChip(label, menuIndex == index) { menuIndex = index; runAction(index) }
                                }
                            }
                        }
                    } else {
                        val labels = if (panel == FullPanel.EPISODES) active.episodes.indices.map { i ->
                            "${i + 1}. ${active.episodeTitles.getOrNull(i) ?: "第 ${i + 1} 集"}"
                        } else sources.map { "${it.sourceName.ifBlank { it.source }} · ${it.source}" }
                        Text(if (panel == FullPanel.EPISODES) "选集 · 方向键选择 · 确定播放 · 返回上级菜单" else "换源 · 方向键选择 · 确定播放 · 返回上级菜单", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS), state = panelGridState,
                            modifier = Modifier.fillMaxWidth().height(210.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(labels) { index, label ->
                                val current = if (panel == FullPanel.EPISODES) episode == index
                                    else sources[index].let { it.source == active.source && it.id == active.id }
                                ActionChip(label, panelIndex == index, current = current,
                                    marker = if (panel == FullPanel.EPISODES) CurrentMarker.EPISODE else CurrentMarker.SOURCE,
                                    modifier = Modifier.fillMaxWidth()) {
                                    panelIndex = index
                                    if (panel == FullPanel.EPISODES) chooseEpisode(index) else chooseSource(index)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS), state = detailGridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(active.title, color = Color.White, fontSize = 28.sp)
                    Text(listOf(active.year, active.sourceName).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFFADB8CD))
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(270.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(7f).fillMaxSize().focusRequester(previewFocus)
                            .focusProperties { right = favoriteFocus; down = episodesTabFocus }
                            .onFocusChanged { previewFocused = it.isFocused }
                            .onPreviewKeyEvent { key ->
                                if (key.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    (key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                                    onFullScreen(); true
                                } else false
                            }.focusable()
                            .tvFocusBorder(previewFocused, RoundedCornerShape(10.dp))
                            .padding(TvStyle.focusWidth).clip(RoundedCornerShape(7.dp))) {
                            PlaybackView(player, Modifier.fillMaxSize())
                            Text("确定 · 全屏播放", color = Color.White, modifier = Modifier.align(Alignment.BottomStart)
                                .background(if (previewFocused) TvStyle.focusedSurface else Color(0x99000000)).padding(10.dp))
                            if (loading) Text("正在测速并加载预览…", color = Color.White,
                                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(12.dp))
                            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9999),
                                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(12.dp))
                        }
                        Column(Modifier.weight(3f).fillMaxSize()
                            .background(Color(0xFF1B2030), RoundedCornerShape(10.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("影片信息", color = Color.White, fontSize = 20.sp)
                                val facts = listOfNotNull(
                                    active.year.takeIf { it.isNotBlank() && it != "unknown" },
                                    active.category.takeIf { it.isNotBlank() },
                                    active.episodes.size.takeIf { it > 0 }?.let { "$it 集" }
                                )
                                Text(facts.joinToString(" · "), color = Color(0xFFADB8CD), fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (active.remarks.isNotBlank()) {
                                    Text("更新：${active.remarks}", color = Color(0xFFADB8CD), fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (active.description.isNotBlank()) {
                                    Text(active.description, color = Color(0xFFD5DBE7), fontSize = 14.sp,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TvButton(
                                modifier = Modifier.focusRequester(favoriteFocus)
                                    .focusProperties { left = previewFocus; down = episodesTabFocus }
                                .width(148.dp).height(48.dp),
                                selected = favorite,
                                onClick = {
                                    scope.launch {
                                        try {
                                            val key = "${active.source}+${active.id}"
                                            if (favorite) { store.deleteFavorite(key); favorite = false }
                                            else {
                                                store.saveFavorite(key, JSONObject().put("title", active.title)
                                                    .put("source_name", active.sourceName).put("year", active.year)
                                                    .put("cover", active.poster).put("total_episodes", active.episodes.size)
                                                    .put("save_time", System.currentTimeMillis()))
                                                favorite = true
                                            }
                                        } catch (e: Exception) { error = e.message ?: "收藏失败" }
                                    }
                                }) {
                                Text(if (favorite) "取消收藏" else "收藏", fontSize = 16.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(onClick = { detailTab = DetailTab.EPISODES }, modifier = Modifier.focusRequester(episodesTabFocus),
                            selected = detailTab == DetailTab.EPISODES) { Text("选集") }
                        TvButton(onClick = { detailTab = DetailTab.SOURCES }, selected = detailTab == DetailTab.SOURCES) { Text("选源") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (detailTab == DetailTab.EPISODES) {
                itemsIndexed(active.episodes) { index, _ ->
                    TvButton(onClick = { chooseEpisode(index) },
                        modifier = Modifier.fillMaxWidth().height(68.dp)
                            .semantics { if (episode == index) stateDescription = "当前播放集" },
                        selected = episode == index, marker = CurrentMarker.EPISODE) {
                        Text("${index + 1}. ${active.episodeTitles.getOrNull(index) ?: "第 ${index + 1} 集"}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                itemsIndexed(sources) { index, source ->
                    TvButton(onClick = { chooseSource(index) },
                        modifier = Modifier.fillMaxWidth().height(68.dp)
                            .semantics { if (source.source == active.source && source.id == active.id) stateDescription = "当前播放源" },
                        selected = source.source == active.source && source.id == active.id,
                        marker = CurrentMarker.SOURCE) {
                        Text("${source.sourceName.ifBlank { source.source }} · ${source.source}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun playbackTime(milliseconds: Long): String {
    if (milliseconds < 0) return "--:--"
    val seconds = milliseconds / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun PlaybackProgress(position: Long, duration: Long, modifier: Modifier = Modifier, offset: Long? = null) {
    val time = "${playbackTime(position)} / ${playbackTime(if (duration > 0) duration else -1)}"
    val displacement = offset?.let { (if (it < 0) "−" else "+") + playbackTime(kotlin.math.abs(it)) }
    Column(modifier.semantics {
        contentDescription = if (displacement == null) "播放进度 $time" else "定位目标 $time，偏移 $displacement"
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(time, color = Color.White, fontSize = 18.sp)
            if (displacement != null) Text(displacement, color = Color(0xFFADB8CD), fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            drawRect(Color(0xFF566077))
            if (duration > 0) {
                val fraction = (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
                drawRect(TvStyle.selectedSurface, size = Size(size.width * fraction, size.height))
            }
        }
    }
}

@Composable
private fun PlaybackView(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply {
            useController = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        } },
        update = { it.player = player }, modifier = modifier
    )
}

@Composable
private fun ActionChip(
    label: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    marker: CurrentMarker? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.background(TvStyle.container(focused, current), shape)
        .tvFocusBorder(focused, shape)
        // The full-screen parent owns D-pad navigation; children remain clickable by touch.
        .focusProperties { canFocus = false }
        .semantics {
            selected = current
            if (current) stateDescription = if (marker == CurrentMarker.EPISODE) "当前播放集" else "当前播放源"
        }
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current && marker != null) CurrentStateMarker(marker)
        Text(label, color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaybackActionChip(
    index: Int,
    isPlaying: Boolean,
    focused: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val glyph = when (index) {
        0 -> if (isPlaying) PlaybackGlyph.PAUSE else PlaybackGlyph.PLAY
        1 -> PlaybackGlyph.PREVIOUS
        else -> PlaybackGlyph.NEXT
    }
    Box(Modifier.size(58.dp)
        .background(TvStyle.container(focused, enabled = enabled), RoundedCornerShape(8.dp))
        .tvFocusBorder(focused && enabled, RoundedCornerShape(8.dp))
        .focusProperties { canFocus = false }
        .semantics {
            contentDescription = if (index == 0) (if (isPlaying) "暂停" else "播放") else label
            if (!enabled) disabled()
        }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        PlaybackIcon(glyph, Modifier.size(30.dp), if (enabled) Color.White else Color(0xFF777E8D))
    }
}

@Composable
private fun PlaybackIcon(glyph: PlaybackGlyph, modifier: Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun triangle(left: Float, right: Float, pointsRight: Boolean) {
            val path = Path().apply {
                if (pointsRight) {
                    moveTo(left, h * .16f)
                    lineTo(right, h * .5f)
                    lineTo(left, h * .84f)
                } else {
                    moveTo(right, h * .16f)
                    lineTo(left, h * .5f)
                    lineTo(right, h * .84f)
                }
                close()
            }
            drawPath(path, color)
        }
        when (glyph) {
            PlaybackGlyph.PLAY -> triangle(w * .22f, w * .82f, true)
            PlaybackGlyph.PAUSE -> {
                drawRoundRect(color, Offset(w * .22f, h * .16f), Size(w * .18f, h * .68f))
                drawRoundRect(color, Offset(w * .60f, h * .16f), Size(w * .18f, h * .68f))
            }
            PlaybackGlyph.PREVIOUS -> {
                drawRoundRect(color, Offset(w * .14f, h * .16f), Size(w * .13f, h * .68f))
                triangle(w * .28f, w * .84f, false)
            }
            PlaybackGlyph.NEXT -> {
                triangle(w * .16f, w * .72f, true)
                drawRoundRect(color, Offset(w * .73f, h * .16f), Size(w * .13f, h * .68f))
            }
        }
    }
}
