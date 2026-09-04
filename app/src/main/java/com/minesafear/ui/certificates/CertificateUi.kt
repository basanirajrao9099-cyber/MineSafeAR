package com.minesafear.ui.certificates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minesafear.R
import com.minesafear.certificate.CertificatePolicy
import com.minesafear.data.entity.CertificateEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Formatting and chrome shared by the three certificate screens. Internal on
 * purpose: nothing outside this package should depend on how a certificate is laid
 * out.
 */

/**
 * A medium-style date in the current locale. Keyed on the locale so a language
 * change in Settings reformats rather than keeping the old locale's month names.
 */
@Composable
internal fun rememberFormattedDate(epochMillis: Long): String {
    val locale = Locale.getDefault()
    return remember(epochMillis, locale) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(epochMillis))
    }
}

internal fun CertificateEntity.isExpiredAt(nowMillis: Long): Boolean =
    CertificatePolicy.isExpiredAt(expiryDate, nowMillis)

/**
 * Back affordance plus title. The app has no top app bar, so pushed screens carry
 * their own — and this stays a plain [Row] rather than a `TopAppBar` so it needs no
 * experimental Material 3 opt-in.
 */
@Composable
internal fun CertificateScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.certificate_back),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Label on the left, value on the right, wrapping rather than truncating. */
@Composable
internal fun CertificateDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            // Weighted so a 36-character certificate id wraps instead of squeezing
            // the label out of the row.
            modifier = Modifier.weight(1f),
        )
    }
}
