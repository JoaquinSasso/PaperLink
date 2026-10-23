package com.joasasso.paperlink.ui.screens.viewer

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.data.repository.LinkContent
import com.joasasso.paperlink.ui.common.AttachmentThumbnail
import com.joasasso.paperlink.ui.common.labelRes
import com.joasasso.paperlink.ui.common.openWebLink
import com.joasasso.paperlink.ui.common.openWithExternalApp
import com.joasasso.paperlink.ui.theme.JetBrainsMono
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/**
 * Visor: arriba siempre el código y la etiqueta (para notar al instante un código mal leído),
 * y los elementos deslizables. Imágenes con zoom; el resto se abre con otras apps.
 */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onOpenDetail: (code: String) -> Unit,
    viewModel: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val current = state) {
            ViewerViewModel.State.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            ViewerViewModel.State.Missing -> CenteredMessage(stringResource(R.string.error_code_not_found))
            is ViewerViewModel.State.Loaded -> Pages(current.content, viewModel, snackbar, onOpenDetail)
        }
        val content = (state as? ViewerViewModel.State.Loaded)?.content
        Header(
            code = viewModel.code,
            label = content?.link?.link?.label,
            onBack = onBack,
            onOpenDetail = { onOpenDetail(viewModel.code) },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Pages(
    content: LinkContent,
    viewModel: ViewerViewModel,
    snackbar: SnackbarHostState,
    onOpenDetail: (String) -> Unit,
) {
    val attachments = content.link.attachments
    LaunchedEffect(Unit) { log("Viewer shown") }
    if (attachments.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.viewer_empty), color = Color.White, textAlign = TextAlign.Center)
            Button(onClick = { onOpenDetail(viewModel.code) }) { Text(stringResource(R.string.viewer_detail)) }
        }
        return
    }
    val startIndex = attachments.indexOfFirst { it.id == viewModel.startId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { attachments.size }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { attachments[it].id }) { page ->
            val attachment = attachments[page]
            if (attachment.id in content.unavailableIds) {
                CenteredMessage(stringResource(R.string.file_unavailable), showBrokenIcon = true)
            } else {
                AttachmentPage(attachment, viewModel, snackbar)
            }
        }
        if (attachments.size > 1) {
            Text(
                text = stringResource(R.string.viewer_position, pagerState.currentPage + 1, attachments.size),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AttachmentPage(attachment: Attachment, viewModel: ViewerViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.viewer_no_app)
    fun reportIfFailed(opened: Boolean) {
        if (!opened) scope.launch { snackbar.showSnackbar(noApp) }
    }

    when (attachment.kind) {
        Kind.IMAGE -> ZoomableAsyncImage(
            model = viewModel.fileFor(attachment),
            contentDescription = stringResource(R.string.kind_image),
            modifier = Modifier.fillMaxSize(),
        )
        Kind.NOTE -> SelectionContainer(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 72.dp, start = 24.dp, end = 24.dp, bottom = 48.dp),
        ) {
            Text(attachment.text.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
        Kind.URL -> OpenCard(
            attachment = attachment,
            viewModel = viewModel,
            title = attachment.url.orEmpty(),
            action = stringResource(R.string.viewer_open_link),
            onAction = { attachment.url?.let { reportIfFailed(context.openWebLink(it)) } },
        )
        else -> OpenCard(
            attachment = attachment,
            viewModel = viewModel,
            title = buildString {
                append(stringResource(attachment.kind.labelRes()))
                attachment.sizeBytes?.let { append(" · ").append(Formatter.formatShortFileSize(context, it)) }
            },
            action = stringResource(R.string.viewer_open_with),
            onAction = {
                val uri = viewModel.uriFor(attachment)
                reportIfFailed(uri != null && context.openWithExternalApp(uri, attachment.mimeType))
            },
        )
    }
}

/** Elementos que no se ven dentro de la app: vista previa y botón para abrirlos afuera. */
@Composable
private fun OpenCard(
    attachment: Attachment,
    viewModel: ViewerViewModel,
    title: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AttachmentThumbnail(
            attachment = attachment,
            file = viewModel.fileFor(attachment),
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)),
        )
        Text(title, color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Header(code: String, label: String?, onBack: () -> Unit, onOpenDetail: () -> Unit) {
    val spoken = stringResource(R.string.code_spoken, code.toList().joinToString(" "))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = code,
                color = Color.White,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            if (!label.isNullOrBlank()) {
                Text(label, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onOpenDetail) {
            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.viewer_detail), tint = Color.White)
        }
    }
}

@Composable
private fun CenteredMessage(text: String, showBrokenIcon: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showBrokenIcon) Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Text(text, color = Color.White, textAlign = TextAlign.Center)
    }
}
