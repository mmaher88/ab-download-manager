package com.abdownloadmanager.desktop.pages.addDownload.single

import com.abdownloadmanager.shared.util.ui.WithContentAlpha
import com.abdownloadmanager.shared.util.ui.WithContentColor
import com.abdownloadmanager.desktop.window.custom.BaseOptionDialog
import com.abdownloadmanager.shared.util.ui.widget.MyIcon
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import com.abdownloadmanager.shared.util.ui.myColors
import com.abdownloadmanager.shared.util.ui.theme.myTextSizes
import com.abdownloadmanager.desktop.window.moveSafe
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import arrow.core.Some
import com.abdownloadmanager.shared.ui.widget.*
import com.abdownloadmanager.desktop.pages.addDownload.shared.*
import com.abdownloadmanager.shared.util.mvi.HandleEffects
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.downloaderinui.add.CanAddResult
import com.abdownloadmanager.shared.pages.adddownload.single.BaseAddSingleDownloadComponent
import com.abdownloadmanager.shared.util.ClipboardUtil
import com.abdownloadmanager.shared.util.div
import com.abdownloadmanager.shared.util.ui.theme.myShapes
import ir.amirab.util.compose.resources.myStringResource
import ir.amirab.downloader.utils.OnDuplicateStrategy
import ir.amirab.util.compose.asStringSource
import java.awt.MouseInfo
import com.abdownloadmanager.desktop.pages.youtube.YouTubeUrlDetector
import com.abdownloadmanager.desktop.pages.youtube.YtDlpService
import com.abdownloadmanager.desktop.pages.youtube.YouTubeFormat
import com.abdownloadmanager.desktop.pages.youtube.YouTubeVideoInfo
import com.abdownloadmanager.desktop.AppComponent
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.abdownloadmanager.shared.util.ui.LocalContentColor
import androidx.compose.ui.window.Popup

@Composable
fun AddDownloadPage(
    component: BaseAddSingleDownloadComponent,
) {
    val onDuplicateStrategy by component.onDuplicateStrategy.collectAsState()
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        val credentials by component.credentials.collectAsState()
        fun setLink(link: String) {
            component.setCredentials(
                credentials.copy(link = Some(link))
            )
        }

        HandleEffects(component) {
            when (it) {
                is BaseAddSingleDownloadComponent.Effects.Common -> {
                    when (it) {
                        is BaseAddSingleDownloadComponent.Effects.Common.SuggestUrl -> {
                            setLink(it.link)
                        }
                    }
                }

                is BaseAddSingleDownloadComponent.Effects.Platform -> {
                    // support platform effects if any
                }
            }
        }
        UrlTextField(
            text = credentials.link,
            setText = {
                setLink(it)
            },
            modifier = Modifier
        )
        Row(
        ) {
            val canAddResult by component.canAddResult.collectAsState()
            Column(Modifier.weight(1f)) {
                val useCategory by component.useCategory.collectAsState()
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.height(IntrinsicSize.Max),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .onClick {
                                component.setUseCategory(!useCategory)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        CheckBox(
                            size = 16.dp,
                            value = useCategory,
                            onValueChange = { component.setUseCategory(it) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(myStringResource(Res.string.use_category))
                    }
                    Spacer(Modifier.width(8.dp))
                    CategorySelect(
                        modifier = Modifier.weight(1f),
                        enabled = useCategory,
                        categories = component.categories.collectAsState().value,
                        selectedCategory = component.selectedCategory.collectAsState().value,
                        onCategorySelected = {
                            component.setSelectedCategory(it)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    CategoryAddButton(
                        enabled = useCategory,
                        modifier = Modifier.fillMaxHeight(),
                        onClick = {
                            component.addNewCategory()
                        },
                    )
                }
                Spacer(Modifier.size(8.dp))
                LocationTextField(
                    modifier = Modifier.fillMaxWidth(),
                    text = component.folder.collectAsState().value,
                    setText = {
                        component.setFolder(it)
                    },
                    errorText = when (canAddResult) {
                        CanAddResult.CantWriteInThisFolder -> myStringResource(Res.string.cant_write_to_this_folder)
                        else -> null
                    },
                    lastUsedLocations = component.lastUsedLocations.collectAsState().value,
                    onRequestRemoveSaveLocation = component::removeFromLastDownloadLocation,
                )
                val name by component.name.collectAsState()
                Spacer(Modifier.size(8.dp))
                NameTextField(
                    text = name,
                    setText = {
                        component.setName(it)
                    },
                    errorText = when (canAddResult) {
                        is CanAddResult.DownloadAlreadyExists -> {
                            if (onDuplicateStrategy == null) {
                                myStringResource(Res.string.download_already_exists)
                            } else {
                                null
                            }
                        }

                        CanAddResult.InvalidFileName -> myStringResource(Res.string.invalid_file_name)
                        else -> null
                    }.takeIf { name.isNotEmpty() }
                )
                // YouTube format selector (only shown for YouTube URLs)
                YouTubeFormatSelector(
                    currentUrl = credentials.link,
                    setLink = ::setLink,
                    component = component,
                    formatHint = component.integrationDescription,
                )
            }
            Spacer(Modifier.size(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Top)
                    .width(IntrinsicSize.Max)
            ) {
                RenderFileTypeAndSize(component)
                RenderResumeSupport(component)
                ConfigActionsButtons(component)
            }
        }
        Spacer(Modifier.weight(1f))
        MainActionButtons(component)
        if (component.showSolutionsOnDuplicateDownloadUi) {
            ShowSolutionsOnDuplicateDownload(component)
        }
        if (component.shouldShowAddToQueue) {
            ShowAddToQueueDialog(
                queueList = component.queues.collectAsState().value,
                onClose = { component.shouldShowAddToQueue = false },
                onQueueSelected = { queue, startQueue ->
                    component.onRequestAddToQueue(queue, startQueue)
                }
            )
        }
        if (component.showMoreSettings) {
            ExtraConfig(
                onDismiss = { component.showMoreSettings = false },
                configurables = component.configurables,
            )
        }
    }
}

@Composable
private fun ShowSolutionsOnDuplicateDownload(component: BaseAddSingleDownloadComponent) {
    val h = 250
    val w = 300
    val state = rememberDialogState(
        size = DpSize(
            height = Dp.Unspecified,
            width = Dp.Unspecified,
        ),
    )
    val close = {
        component.showSolutionsOnDuplicateDownloadUi = false
    }
    val onDuplicateStrategy by component.onDuplicateStrategy.collectAsState()
    BaseOptionDialog(
        onCloseRequest = close,
        state = state,
        resizeable = false,
    ) {
        LaunchedEffect(window) {
            window.moveSafe(
                MouseInfo.getPointerInfo().location.run {
                    DpOffset(
                        x = x.dp,
                        y = y.dp
                    )
                }
            )
        }


        val shape = myShapes.defaultRounded
        Column(
            Modifier
                .clip(shape)
                .border(2.dp, myColors.onBackground / 10, shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            myColors.surface,
                            myColors.background,
                        )
                    )
                )
        ) {
            WithContentColor(myColors.onBackground) {
                Column(
                    Modifier.widthIn(max = 300.dp)
                ) {
                    WindowDraggableArea(Modifier) {
                        Column(
                            Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                        ) {
                            Text(
                                myStringResource(Res.string.select_a_solution),
                                Modifier,
                                fontSize = myTextSizes.base
                            )
                            Spacer(Modifier.height(8.dp))
                            WithContentAlpha(0.75f) {
                                Text(
                                    myStringResource(Res.string.select_download_strategy_description),
                                    Modifier,
                                    fontSize = myTextSizes.sm,
                                )
                            }
                        }
                    }
                    Column(
                        Modifier
                            .padding(horizontal = 8.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Divider()
                        Spacer(Modifier.height(4.dp))
                        Column {
                            OnDuplicateStrategySolutionItem(
                                isSelected = onDuplicateStrategy == OnDuplicateStrategy.AddNumbered,
                                title = myStringResource(Res.string.download_strategy_add_a_numbered_file),
                                description = myStringResource(Res.string.download_strategy_add_a_numbered_file_description),
                            ) {
                                component.setOnDuplicateStrategy(OnDuplicateStrategy.AddNumbered)
                                close()
                            }
                            OnDuplicateStrategySolutionItem(
                                isSelected = onDuplicateStrategy == OnDuplicateStrategy.OverrideDownload,
                                title = myStringResource(Res.string.download_strategy_override_existing_file),
                                description = myStringResource(Res.string.download_strategy_override_existing_file_description),
                            ) {
                                component.setOnDuplicateStrategy(OnDuplicateStrategy.OverrideDownload)
                                close()
                            }
                            OnDuplicateStrategySolutionItem(
                                isSelected = null,
                                title = myStringResource(Res.string.download_strategy_update_download_link),
                                description = myStringResource(Res.string.download_strategy_update_download_link_description),
                            ) {
                                component.updateDownloadCredentialsOfOriginalDownload()
                                close()
                            }
                            OnDuplicateStrategySolutionItem(
                                isSelected = null,
                                title = myStringResource(Res.string.download_strategy_show_downloaded_file),
                                description = myStringResource(Res.string.download_strategy_show_downloaded_file_description),
                            ) {
                                component.openDownloadFileForCurrentLink()
                                close()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnDuplicateStrategySolutionItem(
    title: String,
    description: String,
    isSelected: Boolean?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        isSelected?.let {
            CheckBox(isSelected, { onClick() }, size = 12.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                title,
                fontSize = myTextSizes.base,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            WithContentAlpha(0.7f) {
                Text(
                    text = description,
                    fontSize = myTextSizes.sm,
                    modifier = Modifier
                )
            }
        }

    }
}


@Composable
private fun Divider() {
    Spacer(
        Modifier.fillMaxWidth()
            .height(1.dp)
            .background(myColors.onBackground / 10),
    )
}


@Composable
fun RenderResumeSupport(component: BaseAddSingleDownloadComponent) {
    val fileInfo by component.linkResponseInfo.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(16.dp)

    ) {
        val lineModifier = Modifier.weight(1f)
            .height(1.dp)
            .background(myColors.onBackground / 10)
        Box(lineModifier)
        val canAddToDownloads by component.canAddToDownloads.collectAsState()
        AnimatedVisibility(
            visible = canAddToDownloads && fileInfo != null,
        ) {
            fileInfo?.let { fileInfo ->
                if (fileInfo.resumeSupport) {
                    val iconModifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(10.dp)
                    if (fileInfo.resumeSupport) {
                        MyIcon(
                            icon = MyIcons.check,
                            contentDescription = null,
                            modifier = iconModifier,
                            tint = myColors.success
                        )
                    } else {
                        MyIcon(
                            icon = MyIcons.clear,
                            contentDescription = null,
                            modifier = iconModifier,
                            tint = myColors.error,
                        )
                    }
                }
            }
        }
        Box(lineModifier)


    }
}

@Composable
private fun MainConfigActionButton(
    text: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ActionButton(text, modifier, enabled, onClick)
}


@Composable
fun ConfigActionsButtons(component: BaseAddSingleDownloadComponent) {
    val responseInfo by component.linkResponseInfo.collectAsState()
    Row {
        IconActionButton(MyIcons.refresh, Res.string.refresh.asStringSource()) {
            component.refresh()
        }
        Spacer(Modifier.width(6.dp))
        IconActionButton(
            MyIcons.settings,
            Res.string.settings.asStringSource(),
            indicateActive = component.showMoreSettings,
            requiresAttention = responseInfo?.requireBasicAuth ?: false
        ) {
            component.showMoreSettings = true
        }
    }
}

@Composable
private fun MainActionButtons(component: BaseAddSingleDownloadComponent) {
    Row {
        val onDuplicateStrategy by component.onDuplicateStrategy.collectAsState()
        val canAddResult by component.canAddResult.collectAsState()
        if (canAddResult is CanAddResult.DownloadAlreadyExists && onDuplicateStrategy == null) {
            MainConfigActionButton(
                text = myStringResource(Res.string.show_solutions),
                modifier = Modifier,
                onClick = { component.showSolutionsOnDuplicateDownloadUi = true },
            )
            if (component.shouldShowOpenFile.collectAsState().value) {
                Spacer(Modifier.width(8.dp))
                MainConfigActionButton(
                    text = myStringResource(Res.string.open_file),
                    modifier = Modifier,
                    onClick = { component.openExistingFile() },
                )
            }
        } else {
            val canAddToDownloads by component.canAddToDownloads.collectAsState()
            MainConfigActionButton(
                text = myStringResource(Res.string.add),
                modifier = Modifier,
                enabled = canAddToDownloads,
                onClick = {
                    component.shouldShowAddToQueue = true
                },
            )
            Spacer(Modifier.width(8.dp))
            PrimaryMainActionButton(
                text = myStringResource(Res.string.download),
                modifier = Modifier,
                enabled = canAddToDownloads,
                onClick = {
                    component.onRequestDownload()
                },
            )
            if (onDuplicateStrategy != null) {
                Spacer(Modifier.width(8.dp))
                MainConfigActionButton(
                    text = myStringResource(Res.string.change_solution),
                    modifier = Modifier,
                    onClick = { component.showSolutionsOnDuplicateDownloadUi = true },
                )
            }

        }
        //        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))

        MainConfigActionButton(
            text = myStringResource(Res.string.cancel),
            modifier = Modifier,
            onClick = {
                component.onRequestClose()
            },
        )
    }
}

@Composable
fun RenderFileTypeAndSize(
    component: BaseAddSingleDownloadComponent,
) {
    val isLinkLoading by component.isLinkLoading.collectAsState()
    val fileInfo by component.linkResponseInfo.collectAsState()
    val fileIconProvider = component.iconProvider
    val iconModifier = Modifier.size(16.dp)
    Box(Modifier.padding(top = 16.dp)) {
        AnimatedContent(
            targetState = isLinkLoading,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }
        ) { loading ->
            if (loading) {
                LoadingIndicator(iconModifier)
            } else {
//                val extension = getExtension(fileInfo?.fileName ?: usersSetFileName) ?: "unknown"
                val downloadItem by component.downloadItem.collectAsState()
                val icon = fileIconProvider.rememberIcon(downloadItem.name)

//                val bitmap = FileIconProvider.getIconOfFileExtension(extension)

                AnimatedContent(
                    fileInfo,
                ) { fileInfo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WithContentAlpha(1f) {
                            if (fileInfo != null) {
                                if (fileInfo.requiresAuth) {
                                    MyIcon(
                                        MyIcons.lock,
                                        null,
                                        iconModifier,
                                        tint = myColors.error
                                    )
                                }
                                MyIcon(
                                    icon,
                                    null,
                                    iconModifier
                                )
                                val size = component.getLengthString()
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    size.rememberString(),
                                    fontSize = myTextSizes.sm,
                                )
                            } else {
                                MyIcon(
                                    icon = MyIcons.question,
                                    contentDescription = null,
                                    modifier = iconModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getExtension(s: String): String? {
    if (s.isBlank()) return null
    return s.substringAfterLast(".", "")
        .takeIf { it.isNotBlank() }
}


@Composable
private fun UrlTextField(
    text: String,
    setText: (String) -> Unit,
    errorText: String? = null,
    modifier: Modifier = Modifier,
) {
    MyTextFieldWithIcons(
        text,
        setText,
        myStringResource(Res.string.download_link),
        modifier = modifier.fillMaxWidth(),
        end = {
            MyTextFieldIcon(MyIcons.paste) {
                setText(
                    ClipboardUtil.read()
                        .orEmpty()
                )
            }
        },
        errorText = errorText
    )
}

@Composable
private fun NameTextField(
    text: String,
    setText: (String) -> Unit,
    errorText: String? = null,
) {
    MyTextFieldWithIcons(
        text,
        setText,
        myStringResource(Res.string.name),
        modifier = Modifier.fillMaxWidth(),
        errorText = errorText,
    )
}

@Composable
private fun YouTubeFormatSelector(
    currentUrl: String,
    setLink: (String) -> Unit,
    component: BaseAddSingleDownloadComponent,
    formatHint: String? = null,
) {
    // Remember the original YouTube URL separately from the current link
    val youtubeUrl = remember { currentUrl }
    val isYouTube = remember(youtubeUrl) { YouTubeUrlDetector.isYouTubeUrl(youtubeUrl) }
    if (!isYouTube) return

    // Parse format hint from extension: "youtube:format=137"
    val hintedFormatId = remember(formatHint) {
        formatHint?.let {
            Regex("""youtube:format=(\d+)""").find(it)?.groupValues?.get(1)
        }
    }

    val ytDlpService = remember { YtDlpService() }
    var videoInfo by remember { mutableStateOf<YouTubeVideoInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFormat by remember { mutableStateOf<YouTubeFormat?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var autoExtractDone by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Extract URLs for a given format
    fun extractFormat(format: YouTubeFormat) {
        selectedFormat = format
        extracting = true
        error = null
        coroutineScope.launch {
            val selector = buildFormatSelector(format)
            ytDlpService.extractUrls(
                url = youtubeUrl,
                videoFormatId = selector,
                needsMerge = format.isVideoOnly,
                rawFormatId = format.formatId,
            ).fold(
                onSuccess = { extracted ->
                    setLink(extracted.videoUrl)
                    component.setName(extracted.filename)
                    if (extracted.audioUrl != null) {
                        val appComponent = org.koin.java.KoinJavaComponent.getKoin()
                            .get<AppComponent>()
                        appComponent.registerYouTubeAudioUrl(
                            extracted.videoUrl, extracted.audioUrl
                        )
                    }
                    extracting = false
                },
                onFailure = {
                    error = it.message
                    extracting = false
                }
            )
        }
    }

    LaunchedEffect(youtubeUrl) {
        loading = true
        error = null
        // Clear the link so Download button is disabled while loading
        setLink("")
        component.setName("")
        ytDlpService.fetchVideoInfo(youtubeUrl).fold(
            onSuccess = { info ->
                videoInfo = info
                // Set video title as name, keep link empty until format selected
                component.setName(info.title)
                loading = false
            },
            onFailure = {
                error = it.message
                loading = false
            }
        )
    }

    // Auto-extract when format hint is present and formats are loaded
    LaunchedEffect(videoInfo, hintedFormatId, autoExtractDone) {
        val info = videoInfo ?: return@LaunchedEffect
        val hintId = hintedFormatId ?: return@LaunchedEffect
        if (autoExtractDone) return@LaunchedEffect
        autoExtractDone = true

        val matchedFormat = info.formats.firstOrNull { it.formatId == hintId }
            ?: info.formats.filter { it.hasVideo }
                .sortedByDescending { it.height }
                .firstOrNull()
        if (matchedFormat != null) {
            extractFormat(matchedFormat)
        }
    }

    Spacer(Modifier.size(8.dp))

    if (loading) {
        BasicText(
            text = "Loading YouTube formats...",
            style = androidx.compose.ui.text.TextStyle(
                color = LocalContentColor.current.copy(alpha = 0.6f),
                fontSize = myTextSizes.sm,
            )
        )
        return
    }

    val info = videoInfo
    if (info == null) {
        if (error != null) {
            BasicText(
                text = "YouTube: $error",
                style = androidx.compose.ui.text.TextStyle(
                    color = myColors.error,
                    fontSize = myTextSizes.sm,
                )
            )
        }
        return
    }
    val formats = remember(info) {
        info.formats.filter { it.hasVideo }
            .sortedByDescending { it.height }
            .distinctBy { "${it.displayResolution}_${it.ext}" }
    }

    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BasicText(
            text = "Quality",
            style = androidx.compose.ui.text.TextStyle(
                color = LocalContentColor.current.copy(alpha = 0.6f),
                fontSize = myTextSizes.sm,
            )
        )
        Spacer(Modifier.width(8.dp))
        Box {
            ActionButton(
                text = if (extracting) "Extracting..."
                       else selectedFormat?.let { "${it.displayResolution} ${it.ext.uppercase()}" }
                       ?: "Select quality",
                onClick = { if (!extracting) expanded = true },
                enabled = !extracting && formats.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            )
            if (expanded) {
                Popup(
                    alignment = Alignment.TopStart,
                    onDismissRequest = { expanded = false },
                ) {
                    Column(
                        modifier = Modifier
                            .width(350.dp)
                            .heightIn(max = 300.dp)
                            .background(myColors.surface, RoundedCornerShape(8.dp))
                            .border(1.dp, myColors.onBackground.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(4.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        formats.forEach { format ->
                            val isSelected = selectedFormat == format
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) myColors.primary.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        expanded = false
                                        if (extracting) return@clickable
                                        extractFormat(format)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                YouTubeFormatRow(format)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        // Refresh formats button
        ActionButton(
            text = "\u21BB",  // ↻ refresh symbol
            onClick = {
                loading = true
                error = null
                selectedFormat = null
                setLink("")
                coroutineScope.launch {
                    ytDlpService.fetchVideoInfo(youtubeUrl).fold(
                        onSuccess = { info ->
                            videoInfo = info
                            component.setName(info.title)
                            loading = false
                        },
                        onFailure = {
                            error = it.message
                            loading = false
                        }
                    )
                }
            },
            enabled = !loading && !extracting,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        )
        if (selectedFormat == null && !extracting) {
            Spacer(Modifier.width(4.dp))
            BasicText(
                text = "Select a quality to download",
                style = androidx.compose.ui.text.TextStyle(
                    color = myColors.error.copy(alpha = 0.7f),
                    fontSize = myTextSizes.xs,
                )
            )
        }
    }
    if (error != null) {
        BasicText(
            text = "$error",
            style = androidx.compose.ui.text.TextStyle(
                color = myColors.error,
                fontSize = myTextSizes.xs,
            ),
            maxLines = 2,
        )
    }
}

@Composable
private fun YouTubeFormatRow(format: YouTubeFormat) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Quality label (bold)
        BasicText(
            text = format.displayResolution,
            style = androidx.compose.ui.text.TextStyle(
                color = LocalContentColor.current,
                fontSize = myTextSizes.base,
                fontWeight = FontWeight.Bold,
            )
        )
        // Codec badge
        FormatBadge(
            text = format.displayCodec.split(" + ").first(),
            bgColor = codecBadgeColor(format.vcodec),
            textColor = codecTextColor(format.vcodec),
        )
        // Container badge
        FormatBadge(
            text = format.ext.uppercase(),
            bgColor = Color(0xFF1A3D1A),
            textColor = Color(0xFF6ABA6A),
        )
        // FPS badge (only for high framerate)
        if (format.hasVideo && format.fps > 30) {
            FormatBadge(
                text = "${format.fps}fps",
                bgColor = Color(0xFF3D3318),
                textColor = Color(0xFFD4A040),
            )
        }
        Spacer(Modifier.weight(1f))
        // File size
        BasicText(
            text = format.displaySize,
            style = androidx.compose.ui.text.TextStyle(
                color = LocalContentColor.current.copy(alpha = 0.5f),
                fontSize = myTextSizes.sm,
            )
        )
    }
}

@Composable
private fun FormatBadge(
    text: String,
    bgColor: Color,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        BasicText(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        )
    }
}

private fun codecBadgeColor(vcodec: String): Color = when {
    vcodec.startsWith("avc1") || vcodec.contains("h264", true) -> Color(0xFF1A1A3D)
    vcodec.startsWith("av01") || vcodec.contains("av1", true) -> Color(0xFF2D1A3D)
    vcodec.contains("vp9", true) -> Color(0xFF1A2D3D)
    else -> Color(0xFF1A1A3D)
}

private fun codecTextColor(vcodec: String): Color = when {
    vcodec.startsWith("avc1") || vcodec.contains("h264", true) -> Color(0xFF8888FF)
    vcodec.startsWith("av01") || vcodec.contains("av1", true) -> Color(0xFFBB77FF)
    vcodec.contains("vp9", true) -> Color(0xFF66AADD)
    else -> Color(0xFF8888FF)
}

private fun buildFormatSelector(format: YouTubeFormat): String {
    if (format.hasVideo) {
        val parts = mutableListOf<String>()
        if (format.height > 0) parts.add("height<=${format.height}")
        if (format.ext.isNotBlank()) parts.add("ext=${format.ext}")
        if (format.vcodec != "none") parts.add("vcodec^=${format.vcodec.substringBefore(".")}")
        return if (parts.isNotEmpty()) "bestvideo[${parts.joinToString("][")}]" else format.formatId
    }
    return format.formatId
}

