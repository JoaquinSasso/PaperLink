package com.joasasso.paperlink.ui.screens.viewer

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.data.repository.LinkContent
import com.joasasso.paperlink.ui.theme.JetBrainsMono
import com.joasasso.paperlink.util.log
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/**
 * Visor de las fotos de un código, deslizables y con zoom. Arriba siempre el código y la
 * etiqueta (para notar al instante un código mal leído). El resto de los elementos se abre
 * desde la pantalla del código.
 */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val current = state) {
            ViewerViewModel.State.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            ViewerViewModel.State.Missing -> CenteredMessage(stringResource(R.string.error_code_not_found))
            is ViewerViewModel.State.Loaded -> Pages(current.content, viewModel)
        }
        val content = (state as? ViewerViewModel.State.Loaded)?.content
        Header(code = viewModel.code, label = content?.link?.link?.label, onBack = onBack)
    }
}

@Composable
private fun Pages(content: LinkContent, viewModel: ViewerViewModel) {
    val photos = content.link.attachments.filter { it.kind == Kind.IMAGE }
    LaunchedEffect(Unit) { log("Viewer shown") }
    if (photos.isEmpty()) {
        CenteredMessage(stringResource(R.string.viewer_no_photos))
        return
    }
    val startIndex = photos.indexOfFirst { it.id == viewModel.startId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { photos.size }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
            val photo = photos[page]
            if (photo.id in content.unavailableIds) {
                CenteredMessage(stringResource(R.string.file_unavailable), showBrokenIcon = true)
            } else {
                ZoomableAsyncImage(
                    model = viewModel.fileFor(photo),
                    contentDescription = stringResource(R.string.kind_image),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (photos.size > 1) {
            Text(
                text = stringResource(R.string.viewer_position, pagerState.currentPage + 1, photos.size),
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
private fun Header(code: String, label: String?, onBack: () -> Unit) {
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
