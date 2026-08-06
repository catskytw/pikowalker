package com.pikowalker.app.update

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(val version: String, val notes: String, val assetApiUrl: String, val assetName: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data object UpToDate : UpdateUiState()
    data class Available(val info: UpdateCheckResult.Available) : UpdateUiState()
    data class Downloading(val info: UpdateCheckResult.Available, val progress: Float) : UpdateUiState()
    data class ReadyToInstall(val apkPath: String) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}
