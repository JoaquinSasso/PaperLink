package com.joasasso.paperlink.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Onboarding screen.
 */
class OnboardingViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val isFirstLaunch: StateFlow<Boolean> = userPreferencesRepository.isFirstLaunch
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val pages = listOf(
        TutorialPage(
            imageRes = R.drawable.paso1,
            titleRes = R.string.onboarding_title_page1,
            descriptionRes = R.string.onboarding_desc_page1
        ),
        TutorialPage(
            imageRes = R.drawable.paso2,
            titleRes = R.string.onboarding_title_page2,
            descriptionRes = R.string.onboarding_desc_page2
        ),
        TutorialPage(
            imageRes = R.drawable.paso3,
            titleRes = R.string.onboarding_title_page3,
            descriptionRes = R.string.onboarding_desc_page3
        ),
        TutorialPage(
            imageRes = R.drawable.paso4,
            titleRes = R.string.onboarding_title_page4,
            descriptionRes = R.string.onboarding_desc_page4
        )
    )

    fun completeOnboarding(onNavigateToHome: () -> Unit) {
        viewModelScope.launch {
            userPreferencesRepository.setFirstLaunchCompleted()
            onNavigateToHome()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp)
                OnboardingViewModel(application.container.userPreferencesRepository)
            }
        }
    }
}
