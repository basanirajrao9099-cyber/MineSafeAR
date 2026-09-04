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
                    onSelect = {
                        // Re-picking the current language would reload the screen for
                        // nothing, and on a drill-heavy device that is a real cost.
                        if (language != selected) {
                            selected = language
                            applyLanguage(context, language)
                        }
                    },
                )
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
