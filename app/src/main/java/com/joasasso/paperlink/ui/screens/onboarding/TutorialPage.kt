package com.joasasso.paperlink.ui.screens.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Data class representing a single tutorial slide.
 */
data class TutorialPage(
    @DrawableRes val imageRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
)
