// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rostamvpn.android.ui.screens.WelcomeScreen
import com.rostamvpn.android.ui.screens.LaunchOverlay
import com.rostamvpn.android.ui.screens.LaunchTouch
import com.rostamvpn.android.ui.screens.LaunchGeometry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rostamvpn.android.ui.screens.MainScreen
import com.rostamvpn.android.ui.theme.RostamTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val loc = uiLocale(newBase)
        if (loc != null) {
            java.util.Locale.setDefault(loc)
            val cfg = android.content.res.Configuration(newBase.resources.configuration)
            cfg.setLocale(loc)

            applyOverrideConfiguration(cfg)
        }
        super.attachBaseContext(newBase)
    }

    /**
     * UI locale precedence: explicit user choice (Settings → Language) > the
     * device's own language (null = leave the system locale untouched).
     */
    private fun uiLocale(ctx: android.content.Context): java.util.Locale? {
        val pref = ctx.getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ui_lang", "auto") ?: "auto"
        return when (pref) {
            "", "auto" -> null
            "fa" -> java.util.Locale("fa", "IR")
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> java.util.Locale(pref)
        }
    }

    /** Welcome → app, with the launch animation OVER it rather than before it. */
    @Composable
    private fun EntryGate(vm: VpnViewModel) {
        val prefs = remember { getSharedPreferences("rostam_prefs", android.content.Context.MODE_PRIVATE) }

        var stage by remember {
            mutableStateOf(
                if (prefs.getBoolean("entry_done", ranBefore)) EntryStage.App else EntryStage.Welcome
            )
        }

        var launching by remember { mutableStateOf(freshStart) }

        fun finish() {
            prefs.edit().putBoolean("entry_done", true).apply()
            stage = EntryStage.App
        }

        Box(Modifier.fillMaxSize()) {
            when (stage) {
                EntryStage.Welcome -> WelcomeScreen(

                    showPlans = vm.premiumAvailable.collectAsState().value && !vm.paidOnly,
                    onContinue = { finish() },
                )
                EntryStage.App -> MainScreen(vm)
            }
            if (launching) LaunchOverlay(onFinish = { launching = false })
            vm.pendingCode.collectAsState().value?.let { code ->
                AlertDialog(
                    onDismissRequest = { vm.answerAccountCode(false) },
                    title = { Text(stringResource(R.string.restore)) },
                    text = { Text(code) },
                    confirmButton = {
                        TextButton(onClick = { vm.answerAccountCode(true) }) { Text(stringResource(R.string.restore)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { vm.answerAccountCode(false) }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }

    private enum class EntryStage { Welcome, App }

    companion object {
        const val TAG = "RostamMain"
        @JvmField
        var instance: MainActivity? = null

        /**
         * A shared config is a few KB of text; anything bigger is not a config. Bounds
         * the import so a mis-targeted "open with" cannot OOM the app.
         */
        private const val MAX_CONFIG_CHARS = 256 * 1024

        @JvmField
        var viewModelRef: VpnViewModel? = null

        @JvmStatic
        fun getInstance(): MainActivity? = instance
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted")
            viewModelRef?.onVpnPermissionGranted()
        } else {
            Log.e(TAG, "VPN permission denied")
            viewModelRef?.onVpnPermissionDenied()
        }
    }

    fun requestVpnPermission(intent: android.content.Intent) {
        vpnPermissionLauncher.launch(intent)
    }

    /** The consent dialog on its own, answered by a callback. */
    private var pendingConsentResult: ((Boolean) -> Unit)? = null

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        val cb = pendingConsentResult
        pendingConsentResult = null
        cb?.invoke(result.resultCode == Activity.RESULT_OK)
    }

    fun requestVpnConsent(onResult: (Boolean) -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            onResult(true)
            return
        }
        pendingConsentResult = onResult
        vpnConsentLauncher.launch(intent)
    }

    /** Whether this install had already run before THIS launch. */
    private var ranBefore: Boolean = false

    /**
     * Whether this onCreate is a cold start rather than a restore. Gates the
     * launch overlay.
     */
    private var freshStart: Boolean = true

    /** A tap on the way past ends the launch overlay. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            LaunchTouch.onTouch?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ranBefore = getSharedPreferences("redsocket", android.content.Context.MODE_PRIVATE)
            .contains("redsocket_cid")
        freshStart = savedInstanceState == null

        installSplashScreen().setOnExitAnimationListener { provider ->

            provider.iconView.let { icon ->
                val want = (LaunchGeometry.SLOT.value * LaunchGeometry.KEYLINE *
                    resources.displayMetrics.density).toInt()
                Log.i(TAG, "splash icon keyline: ${icon.width}x${icon.height}px (expected ${want}px)")
            }
            provider.remove()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        instance = this
        ActivityHolder.currentActivity = this

        com.rostamvpn.android.billing.CashierIdentity.ensure(applicationContext)

        if (BuildConfig.DEBUG) {
            applicationContext.getSharedPreferences("rostam_prefs", MODE_PRIVATE)
                .getString("dev_region", "")?.ifEmpty { null }
                ?.let { com.rostamvpn.android.billing.RegionDetector.devOverride = it }
        }
        Log.i(TAG, "MainActivity created")

        setContent {
            val vpnViewModel: VpnViewModel = viewModel()
            viewModelRef = vpnViewModel
            val themeMode by vpnViewModel.themeSetting.collectAsState()
            RostamTheme(themeMode = themeMode) {
                EntryGate(vpnViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleDeepLink(intent)

        viewModelRef?.syncEntitlement()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val action = intent.action
        val uri = intent.data

        if (uri != null && uri.scheme == "rostam" && uri.host == "c") {
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrBlank()) {
                Log.i(TAG, "Deep link account code received")
                viewModelRef?.offerAccountCode(code)
                intent.data = null
                return
            }
            val path = uri.path ?: return
            val payload = path.removePrefix("/v1/").removePrefix("/")
            if (payload.isNotEmpty()) {
                Log.i(TAG, "Deep link config received (${payload.length} chars)")
                viewModelRef?.applyQrConfig(payload)
                intent.data = null
            }
            return
        }

        if (action == Intent.ACTION_VIEW && uri != null) {
            handleRostamFileUri(uri)
            intent.data = null
            return
        }

        if (action == Intent.ACTION_SEND) {
            val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) {
                handleRostamFileUri(sharedUri)
                intent.action = null
            }
        }
    }

    private fun handleRostamFileUri(uri: Uri) {
        if (uri.scheme != "content" || uri.authority.orEmpty().startsWith("$packageName.")) {
            Log.w(TAG, "Config import refused: not another app's content URI")
            viewModelRef?.showToast("Invalid config file")
            return
        }
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                Log.e(TAG, "Failed to open config file: null InputStream for $uri")
                viewModelRef?.showToast("Failed to open config file")
                return
            }

            val content = inputStream.use { stream ->
                val buf = CharArray(MAX_CONFIG_CHARS)
                val reader = stream.bufferedReader()
                val n = reader.read(buf, 0, MAX_CONFIG_CHARS)
                if (n <= 0) "" else String(buf, 0, n)
            }.trim()

            if (content.isEmpty()) {
                Log.e(TAG, "Config file is empty: $uri")
                viewModelRef?.showToast("Config file is empty")
                return
            }

            val payload = if (content.startsWith("ROSTAM-CONFIG-V1")) {
                content.substringAfter("\n").trim()
            } else {
                content
            }

            if (payload.isNotEmpty()) {
                Log.i(TAG, "Config file imported (${payload.length} chars)")
                viewModelRef?.applyQrConfig(payload)
            } else {
                viewModelRef?.showToast("Invalid config file")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read config file: ${e.message}", e)
            viewModelRef?.showToast("Failed to read config file")
        }
    }

    override fun onDestroy() {
        instance = null
        ActivityHolder.currentActivity = null
        super.onDestroy()
    }

    fun stopVpnService() {
        Log.i(TAG, "stopVpnService called")

        runOnUiThread {
            try {
                val intent = Intent(this, TunnelVpnService::class.java)
                stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TunnelVpnService: ${e.message}")
            }
        }
    }
}
