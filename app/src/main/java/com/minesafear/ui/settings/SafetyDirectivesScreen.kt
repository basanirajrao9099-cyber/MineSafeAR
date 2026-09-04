package com.minesafear.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minesafear.R

data class SafetyDirective(
    val id: String,
    val code: String,
    val title: String,
    val content: String,
    val effectiveDate: String,
    val category: String,
)

val sampleDirectives = listOf(
    SafetyDirective(
        id = "dir_402",
        code = "DGMS Directive #402",
        title = "Underground Shaft 3 Ventilation Protocol",
        content = "Mandatory continuous Methane & CO gas monitoring required before entering Shaft 3. Auxiliary fan inspection logs must be recorded daily.",
        effectiveDate = "Effective: Immediate",
        category = "Ventilation",
    ),
    SafetyDirective(
        id = "dir_108",
        code = "Statutory Directive #108",
        title = "Self-Rescuer Apparatus Inspection",
        content = "All miners operating below level 2 must verify oxygen self-rescuer seal pressure gauge before boarding cage hoist.",
        effectiveDate = "Effective: Statutory Requirement",
        category = "DGMS Rules",
    ),
    SafetyDirective(
        id = "dir_215",
        code = "Emergency Circular #215",
        title = "Electrical Starter Panel Isolation",
        content = "Live conveyor motor starter panels must be isolated by certified electrician prior to water hose cleaning in surrounding areas.",
        effectiveDate = "Effective: Standard Operating Procedure",
        category = "Electrical",
    ),
    SafetyDirective(
        id = "dir_309",
        code = "DGMS Directive #309",
        title = "Strata Rock Bolt Anchorage Check",
        content = "Weekly torque testing of mechanical rock bolts required in all freshly blasted heading gallery sections.",
        effectiveDate = "Effective: Immediate",
        category = "Strata Control",
    ),
)

/**
 * Screen displaying official mine safety directives and statutory regulations with search and category filtering.
 */
@Composable
fun SafetyDirectivesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences("directives_pref", Context.MODE_PRIVATE) }
    val acknowledgedMap = remember { mutableStateMapOf<String, Boolean>() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf("All", "DGMS Rules", "Electrical", "Ventilation", "Strata Control")

    // Read acknowledged states
    sampleDirectives.forEach { directive ->
        if (!acknowledgedMap.containsKey(directive.id)) {
            acknowledgedMap[directive.id] = pref.getBoolean(directive.id, false)
        }
    }

    val filteredDirectives = remember(searchQuery, selectedCategory) {
        sampleDirectives.filter { directive ->
            val matchesCategory = (selectedCategory == "All") || (directive.category == selectedCategory)
            val matchesQuery = searchQuery.isBlank() ||
                directive.title.contains(searchQuery, ignoreCase = true) ||
                directive.content.contains(searchQuery, ignoreCase = true) ||
                directive.code.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.directives_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(R.string.directives_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(text = stringResource(R.string.directives_search_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(categories) { category ->
                val isSelected = category == selectedCategory
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedCategory = category },
                    label = { Text(category) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filteredDirectives, key = { it.id }) { directive ->
                val isAck = acknowledgedMap[directive.id] == true

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = directive.code,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )

                            if (isAck) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = stringResource(R.string.directives_acknowledged),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }

                        Text(
                            text = directive.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Text(
                            text = directive.content,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Text(
                            text = directive.effectiveDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (!isAck) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    acknowledgedMap[directive.id] = true
                                    pref.edit().putBoolean(directive.id, true).apply()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.directives_mark_read))
                            }
                        }
                    }
                }
            }
        }
    }
}
