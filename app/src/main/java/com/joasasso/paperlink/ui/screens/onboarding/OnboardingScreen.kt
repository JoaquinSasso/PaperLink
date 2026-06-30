package com.joasasso.paperlink.ui.screens.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)
) {
    val pages = viewModel.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, end = 16.dp, start = 16.dp)
            ) {
                TextButton(
                    onClick = { viewModel.completeOnboarding(onFinish) },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = stringResource(id = R.string.onboarding_btn_skip),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { index ->
                OnboardingPage(page = pages[index])
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Indicators
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(pages.size) { iteration ->
                    val color = if (pagerState.currentPage == iteration) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    }
                    val width = animateDpAsState(
                        targetValue = if (pagerState.currentPage == iteration) 24.dp else 8.dp,
                        label = "indicatorWidth"
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(color)
                            .width(width.value)
                            .height(8.dp)
                    )
                }
            }

            // Main Action Button
            Button(
                onClick = {
                    if (pagerState.currentPage < pages.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        viewModel.completeOnboarding(onFinish)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp)
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                val buttonTextId = if (pagerState.currentPage == pages.size - 1) {
                    R.string.onboarding_btn_finish
                } else {
                    R.string.onboarding_btn_next
                }
                Text(
                    text = stringResource(id = buttonTextId),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp)
                )
            }
        }
    }
}

@Composable
fun OnboardingPage(page: TutorialPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = page.imageRes),
            contentDescription = null,
            modifier = Modifier
                .size(280.dp)
                .padding(bottom = 40.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = stringResource(id = page.titleRes),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = page.descriptionRes),
            style = MaterialTheme.typography.bodyLarge.copy(
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}
