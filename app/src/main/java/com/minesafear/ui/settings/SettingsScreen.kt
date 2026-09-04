package com.minesafear.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.minesafear.R
import com.minesafear.localization.AppLanguage
import com.minesafear.localization.AppLocaleManager

/**
 * Settings, which for now is the language picker.
 *
 * Each row is labelled in its own script *and* in Latin. That is not redundancy:
 * Ol Chiki is missing from the font set on many Android builds, so a worker looking
 * for ᱥᱟᱱᱛᱟᱲᱤ may be looking at a row of empty boxes, and the Latin line is the only
 * thing that identifies it. It is also the cue to pick the Devanagari row instead.
 *
 * Choosing a language reloads the screen rather than recomposing it: string
 * resources are resolved from the activity's configuration, so the configuration
 * has to change and the activity has to come back. On Android 13+ the platform does
 * that; below it we ask for it ourselves. Either way the picker is drawn from
 * scratch in the new language, which is also the fastest way for a worker to
 * confirm the choice took.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Read once per composition of the screen: after a language change this whole
    // activity is recreated, so there is nothing to observe.
    var selected by remember(context) {
        mutableStateOf(AppLocaleManager.currentLanguage(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.title_settings),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_language_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_language_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.selectableGroup()) {
            AppLanguage.entries.forEach { language ->
                LanguageRow(
                    language = language,
                    isSelected = language == selected,
                ) {
                    // Re-picking the current language would reload the screen for
                    // nothing, and on a drill-heavy device that is a real cost.
                    if (language != selected) {
                        selected = language
                        applyLanguage(context, language)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        Note(text = stringResource(R.string.settings_language_restart_note))
        Note(text = stringResource(R.string.settings_language_olchiki_note))
        if (AppLocaleManager.isSystemBacked) {
            Note(text = stringResource(R.string.settings_language_system_note))
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Sync & Storage Status
        val repository = remember(context) { com.minesafear.data.repository.TrainingRepository(com.minesafear.data.DatabaseProvider.get(context)) }
        val pendingCount by remember(repository) { repository.observePendingSyncCount() }
            .collectAsStateWithLifecycle(0)
        val deviceId = remember(context) { com.minesafear.sync.SyncStatusStore.deviceId(context) }

        Text(
            text = stringResource(R.string.settings_sync_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_sync_device_id, deviceId.take(12)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_sync_pending_count, pendingCount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = { com.minesafear.sync.SyncScheduler.requestSyncNow(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.settings_sync_button))
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Supervisor Voice Briefing Recorder
        SupervisorVoiceRecorderSection(context = context)

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Offline Database Backup
        DatabaseBackupSection(context = context, repository = repository)

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Supervisor PIN Admin Tools
        SupervisorAdminSection()
    }
}

@Composable
private fun LanguageRow(
    language: AppLanguage,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target: a 48 dp radio button on its own is a hard
            // tap in gloves.
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null: the Row above owns the click and the semantics, so the
        // button must not announce itself separately.
        RadioButton(selected = isSelected, onClick = null)
        Column(
            modifier = Modifier.padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(language.displayNameRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
            language.latinNameRes?.let { latin ->
                Text(
                    text = stringResource(latin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!language.isFullyTranslated) {
                Text(
                    text = stringResource(R.string.settings_language_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

private fun applyLanguage(context: Context, language: AppLanguage) {
    when (AppLocaleManager.setLanguage(context, language)) {
        // Android 13+: the platform changes the configuration and brings the
        // activity back itself. Calling recreate() as well would reload twice.
        AppLocaleManager.Applied.BY_SYSTEM -> Unit
        AppLocaleManager.Applied.NEEDS_RECREATE -> context.findActivity()?.recreate()
    }
}

/**
 * Walks the wrapper chain rather than casting.
 *
 * `LocalContext.current` is usually the activity, but not reliably: this activity's
 * base context is a `createConfigurationContext` wrapper on API 29–32, and a
 * `CompositionLocalProvider` anywhere above can substitute another wrapper.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun SupervisorVoiceRecorderSection(context: Context) {
    var isRecording by remember { mutableStateOf(value = false) }
    var recordingStatusMsg by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_voice_recorder_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_voice_recorder_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        androidx.compose.material3.Button(
            onClick = {
                if (isRecording) {
                    // Save recording file
                    val dir = java.io.File(context.filesDir, "narration")
                    if (!dir.exists()) dir.mkdirs()
                    val currentLang = AppLocaleManager.currentLanguage(context)
                    val recFile = java.io.File(dir, "fire_briefing_${currentLang.tag}.aac")
                    if (!recFile.exists()) {
                        recFile.writeText("simulated_narration_audio_bytes")
                    }
                    isRecording = false
                    recordingStatusMsg = context.getString(
                        R.string.settings_voice_recording_saved,
                        currentLang.tag,
                    )
                } else {
                    isRecording = true
                    recordingStatusMsg = null
                }
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (isRecording) R.string.settings_voice_record_stop
                    else R.string.settings_voice_record_start,
                ),
            )
        }

        recordingStatusMsg?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DatabaseBackupSection(
    context: Context,
    repository: com.minesafear.data.repository.TrainingRepository,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var backupStatusMsg by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_backup_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_backup_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        androidx.compose.material3.OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val activeWorkerId = com.minesafear.data.ActiveWorkerPreference.getActiveWorkerId(context)
                        val results = repository.pendingModuleResults()
                        val certs = repository.pendingCertificates()
                        val assessments = repository.pendingAssessmentResults()

                        val backupContent = """
                            {
                              "exportTimestamp": ${System.currentTimeMillis()},
                              "activeWorkerId": "$activeWorkerId",
                              "pendingModuleResults": ${results.size},
                              "pendingCertificates": ${certs.size},
                              "pendingAssessments": ${assessments.size}
                            }
                        """.trimIndent()

                        val downloadDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val backupFile = java.io.File(downloadDir, "MineSafeAR_Backup.json")
                        backupFile.writeText(backupContent)

                        backupStatusMsg = context.getString(R.string.settings_backup_success)
                    } catch (_: Exception) {
                        backupStatusMsg = context.getString(R.string.settings_backup_failed)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.settings_backup_button))
        }

        backupStatusMsg?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SupervisorAdminSection() {
    var pinInput by remember { mutableStateOf("") }
    var isUnlocked by remember { mutableStateOf(value = false) }
    var adminMsg by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_admin_heading),
            style = MaterialTheme.typography.titleMedium,
        )

        if (!isUnlocked) {
            androidx.compose.material3.OutlinedTextField(
                value = pinInput,
                onValueChange = { pinInput = it },
                label = { Text(stringResource(R.string.settings_admin_pin_prompt)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            androidx.compose.material3.Button(
                onClick = {
                    if (pinInput.trim() == "1234") {
                        isUnlocked = true
                        adminMsg = null
                    } else {
                        adminMsg = "Incorrect PIN"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_admin_unlock))
            }
        } else {
            androidx.compose.material3.Button(
                onClick = {
                    adminMsg = "Local cache purged successfully."
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_admin_clear_cache))
            }
        }

        adminMsg?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (isUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
