package com.devil.phoenixproject

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.csv.AndroidCsvImportBridge
import com.devil.phoenixproject.data.csv.CsvImportDeliveryResult
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.domain.csv.CsvImportDeliveryPolicy
import com.devil.phoenixproject.domain.csv.CsvImportIntentClassifier
import com.devil.phoenixproject.domain.csv.CsvImportIntentDescriptor
import com.devil.phoenixproject.domain.csv.CsvImportPayload
import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import com.devil.phoenixproject.domain.csv.isHandledImport
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.NightSample
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.nightSampleFromMask
import com.devil.phoenixproject.ui.theme.resolveSystemDark
import com.devil.phoenixproject.ui.theme.resolveUseDarkColors
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * CSV import intake (#1242): a handled delivery is recorded in memory at acceptance and
     * restored through [onSaveInstanceState], so recreation after acceptance never imports
     * twice. This is at-most-once for supported saved-state cases, not a durable ledger.
     */
    private var csvImportHandled = false
    private var csvImportReadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply stored locale BEFORE composition to prevent first-frame flicker.
        // This reads directly from SharedPreferences instead of waiting for the
        // Compose ViewModel pipeline (which fires after the first frame).
        applyStoredLocaleBeforeComposition()

        volumeControlStream = AudioManager.STREAM_MUSIC

        val systemBarStyle = applyPersistedThemeWindow()
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
        csvImportHandled = savedInstanceState?.getBoolean(KEY_CSV_IMPORT_HANDLED, false) ?: false
        acceptCsvImportIntent(intent, coldStart = true)
        setContent {
            AndroidAppHost()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptCsvImportIntent(intent, coldStart = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CSV_IMPORT_HANDLED, csvImportHandled)
    }

    /**
     * One intake step per delivered intent. Ignored intents never touch the stored intent;
     * a handled import is accepted (unless saved state already records it), the activity
     * intent is sanitized to a plain launcher intent, and only then is the bounded read
     * started. Everything after this point is per-delivery, never per-content.
     */
    private fun acceptCsvImportIntent(intent: Intent, coldStart: Boolean) {
        val payload = CsvImportIntentClassifier.classify(intent.toCsvImportDescriptor())
        if (!payload.isHandledImport) return

        val accept = if (coldStart) {
            CsvImportDeliveryPolicy.acceptOnCreate(restoredHandled = csvImportHandled)
        } else {
            CsvImportDeliveryPolicy.acceptOnNewIntent(payload)
        }
        if (CsvImportDeliveryPolicy.shouldSanitizeActivityIntent(payload)) {
            setIntent(plainLauncherIntent())
        }
        if (!accept) {
            Logger.d(tag = "MainActivity") { "csv_import_intake outcome=skipped_restored" }
            return
        }

        csvImportHandled = true
        val deliveryId = "csv-import-${deliveryCounter.incrementAndGet()}-${generateUUID()}"
        when (payload) {
            is CsvImportPayload.InlineText -> AndroidCsvImportBridge.offer(
                deliveryId,
                CsvImportDeliveryResult.Read(payload.text),
            )

            is CsvImportPayload.StreamUri -> {
                csvImportReadJob?.cancel()
                csvImportReadJob = lifecycleScope.launch {
                    AndroidCsvImportBridge.offer(
                        deliveryId,
                        AndroidCsvImportBridge.readCsvImportText(payload.uri, RoutineCsvFormat.MAX_BYTES),
                    )
                }
            }

            CsvImportPayload.TooLarge -> AndroidCsvImportBridge.offer(deliveryId, CsvImportDeliveryResult.TooLarge)
            CsvImportPayload.Unreadable -> AndroidCsvImportBridge.offer(deliveryId, CsvImportDeliveryResult.Unreadable)
            CsvImportPayload.Ignore -> Unit
        }
    }

    private fun Intent.toCsvImportDescriptor(): CsvImportIntentDescriptor {
        // Extra keys are matched case-insensitively: the wire key real senders write has been
        // observed in a different case than the SDK constant on some platform versions, and a
        // case-sensitive miss would silently drop the whole delivery into "Unreadable".
        val bundle = extras
        fun extraValue(name: String): Any? {
            val b = bundle ?: return null
            val key = b.keySet().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
            return b.get(key)
        }
        val streamExtra = extraValue(Intent.EXTRA_STREAM)
        val streamUri = (
            streamExtra as? Uri
                ?: (streamExtra as? CharSequence)?.toString()?.let(Uri::parse)
            )?.toString()
        val clipDataUri = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.toString()
        val inlineText = (extraValue(Intent.EXTRA_TEXT) as? CharSequence)?.toString()
        Logger.d(tag = "MainActivity") {
            "csv_import_intent action=$action type=$type " +
                "stream=${streamUri != null} clip=${clipDataUri != null} text=${inlineText != null} hasData=${data != null}"
        }
        return CsvImportIntentDescriptor(
            action = action,
            mimeType = type,
            scheme = data?.scheme,
            host = data?.host,
            streamUri = streamUri,
            clipDataUri = clipDataUri,
            inlineText = inlineText,
            data = data?.toString(),
        )
    }

    /** What a handled import leaves behind: a launcher intent with no import payload. */
    private fun plainLauncherIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)

    private companion object {
        const val KEY_CSV_IMPORT_HANDLED = "csv_import_handled"

        /** Per-delivery identity is never per-content, so a re-share imports again. */
        val deliveryCounter = AtomicInteger(0)
    }

    private fun applyPersistedThemeWindow(): SystemBarStyle {
        val prefs = getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
        val themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(ThemeViewModel.THEME_MODE_KEY, "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(ThemeMode.SYSTEM)
        val applicationNight = nightSampleFromMask(
            applicationContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK,
        )
        // Cold-start seed prefers dark so a missing/undefined night sample cannot flash light.
        val systemDark = resolveSystemDark(
            previous = true,
            applicationNight = applicationNight,
            activityNight = NightSample.UNDEFINED,
            composeNight = applicationNight == NightSample.YES,
        )
        val useDark = resolveUseDarkColors(themeMode, systemDark)
        val windowColor = getColor(
            if (useDark) R.color.phoenix_window_background_dark else R.color.phoenix_window_background_light,
        )
        window.setBackgroundDrawable(ColorDrawable(windowColor))
        return if (useDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            )
        }
    }

    /**
     * Reads the persisted language preference and applies it to the platform locale
     * before setContent{} is called. This prevents the brief English flash on non-EN
     * locales during cold start.
     */
    private fun applyStoredLocaleBeforeComposition() {
        try {
            val prefs = getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
            val langCode = prefs.getString(SettingsPreferencesManager.KEY_LANGUAGE, null)
            if (!langCode.isNullOrBlank()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val localeManager = getSystemService(android.app.LocaleManager::class.java)
                    localeManager.applicationLocales = LocaleList.forLanguageTags(langCode)
                } else {
                    val locale = Locale.forLanguageTag(langCode)
                    val config = resources.configuration
                    config.setLocale(locale)
                    config.setLocales(LocaleList(locale))
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(config, resources.displayMetrics)
                }
                Logger.d(tag = "MainActivity") { "Applied locale '$langCode' before composition" }
            }
        } catch (e: Exception) {
            Logger.w(tag = "MainActivity") { "Failed to apply locale before composition: ${e.message}" }
        }
    }
}
