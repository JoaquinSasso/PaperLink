package com.joasasso.paperlink.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PermissionState {
    object Initial : PermissionState
    object Rationale : PermissionState
    object Partial : PermissionState
    object FullAccess : PermissionState
    object PermanentlyDenied : PermissionState
}

data class OnboardingUiState(
    val permissionState: PermissionState = PermissionState.Initial
)

class OnboardingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun checkPermissions(context: Context) {
        val hasFullAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val hasPartialAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        val newState = when {
            hasFullAccess -> PermissionState.FullAccess
            hasPartialAccess -> PermissionState.Partial
            else -> PermissionState.Initial
        }

        _uiState.value = _uiState.value.copy(permissionState = newState)
    }

    fun onPermissionResult(
        isFullGranted: Boolean,
        isPartialGranted: Boolean,
        shouldShowRationale: Boolean
    ) {
        val newState = when {
            isFullGranted -> PermissionState.FullAccess
            isPartialGranted -> PermissionState.Partial
            shouldShowRationale -> PermissionState.Rationale
            else -> PermissionState.PermanentlyDenied
        }
        _uiState.value = _uiState.value.copy(permissionState = newState)
    }
}
