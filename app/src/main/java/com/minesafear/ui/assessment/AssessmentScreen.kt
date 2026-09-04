package com.minesafear.ui.assessment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.minesafear.R
import com.minesafear.ui.components.PlaceholderScreen

/**
 * Empty destination. Will render questions from [com.minesafear.assessment]
 * and persist results through Room.
 */
@Composable
fun AssessmentScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.title_assessment),
        message = stringResource(R.string.placeholder_assessment),
        modifier = modifier,
    )
}
