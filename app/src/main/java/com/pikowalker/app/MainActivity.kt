package com.pikowalker.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.pikowalker.app.health.HealthConnectHelper
import com.pikowalker.app.ui.navigation.PikoWalkerNavGraph
import com.pikowalker.app.ui.theme.PikoWalkerTheme
import com.pikowalker.app.viewmodel.WalkViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: WalkViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    // Health Connect permission launcher
    private val hcPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val hasAll = granted.containsAll(HealthConnectHelper.PERMISSIONS)
        (application as PikStepApp).walkRepository.setHealthConnect(
            available = true,
            hasPermission = hasAll
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        checkHealthConnect()
        setContent {
            PikoWalkerTheme {
                if (permissionsGranted) {
                    PikoWalkerNavGraph(
                        viewModel,
                        onRequestHcPermission = { requestHcPermission() }
                    )
                } else {
                    PermissionScreen(onRequest = { permissionLauncher.launch(requiredPermissions) })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkHealthConnect()
    }

    private fun checkPermissions() {
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun checkHealthConnect() {
        val available = HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        if (!available) {
            (application as PikStepApp).walkRepository.setHealthConnect(available = false, hasPermission = false)
            return
        }
        lifecycleScope.launch {
            val helper = HealthConnectHelper(this@MainActivity)
            val has = helper.hasPermissions()
            (application as PikStepApp).walkRepository.setHealthConnect(available = true, hasPermission = has)
        }
    }

    fun requestHcPermission() {
        hcPermissionLauncher.launch(HealthConnectHelper.PERMISSIONS)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("PikoWalker 需要以下權限才能運作：",
                style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• 位置權限（模擬 GPS 位置）", style = MaterialTheme.typography.bodyMedium)
            Text("• 通知權限（背景執行狀態列）", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("授予權限") }
        }
    }
}
