package com.minesafear.ui.assessment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minesafear.R
import com.minesafear.assessment.AnswerOption
import com.minesafear.assessment.AssessmentQuestion
import com.minesafear.assessment.AssessmentScore
import com.minesafear.assessment.AssessmentSubmission
import com.minesafear.assessment.ScoringEngine
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.entity.AssessmentResultEntity
import com.minesafear.data.repository.TrainingRepository
import kotlinx.coroutines.launch
import java.util.UUID

/** Sample safety assessment questions for underground mining operations. */
val sampleQuestions = listOf(
    AssessmentQuestion(
        id = "q1",
        prompt = "Which type of fire extinguisher is appropriate for a live electrical motor fire?",
        options = listOf(
            AnswerOption("q1_a", "Water extinguisher (Red body)"),
            AnswerOption("q1_b", "CO₂ extinguisher (Black body)"),
            AnswerOption("q1_c", "Foam extinguisher (Cream body)"),
            AnswerOption("q1_d", "Wet Chemical extinguisher"),
        ),
        correctOptionId = "q1_b",
        hazardTag = "Electrical & Fire Safety",
    ),
    AssessmentQuestion(
        id = "q2",
        prompt = "What is the primary action when toxic methane or gas leak is detected underground?",
        options = listOf(
            AnswerOption("q2_a", "Attempt to extinguish the source with water"),
            AnswerOption("q2_b", "Evacuate immediately following emergency exit signs and notify control"),
            AnswerOption("q2_c", "Wait in the shaft for supervisor instructions"),
            AnswerOption("q2_d", "Turn on high-power electrical lights to locate the leak"),
        ),
        correctOptionId = "q2_b",
        hazardTag = "Gas Leak & Evacuation Protocol",
    ),
    AssessmentQuestion(
        id = "q3",
        prompt = "Why should water NEVER be sprayed on a live electrical equipment fire?",
        options = listOf(
            AnswerOption("q3_a", "Water cools the fire too quickly"),
            AnswerOption("q3_b", "Water conducts electricity and creates lethal shock hazards"),
            AnswerOption("q3_c", "Water turns into flammable gas instantly"),
            AnswerOption("q3_d", "Water damages nearby non-electrical machinery"),
        ),
        correctOptionId = "q3_b",
        hazardTag = "Electrical & Shock Hazard",
    ),
    AssessmentQuestion(
        id = "q4",
        prompt = "What does a green exit arrow sign indicate during underground mine evacuation?",
        options = listOf(
            AnswerOption("q4_a", "Direction to the nearest safe escape opening/shaft"),
            AnswerOption("q4_b", "Location of fresh water supplies"),
            AnswerOption("q4_c", "Refuge chamber storage location only"),
            AnswerOption("q4_d", "Explosives storage area"),
        ),
        correctOptionId = "q4_a",
        hazardTag = "Egress & Directional Signage",
    ),
    AssessmentQuestion(
        id = "q5",
        prompt = "What is the mandatory pass percentage for statutory safety training assessments?",
        options = listOf(
            AnswerOption("q5_a", "50%"),
            AnswerOption("q5_b", "60%"),
            AnswerOption("q5_c", "80%"),
            AnswerOption("q5_d", "100%"),
        ),
        correctOptionId = "q5_c",
        hazardTag = "Safety Certification Rules",
    ),
)

private enum class AssessmentScreenMode {
    INTRO,
    IN_PROGRESS,
    COMPLETED
}

/**
 * Interactive written safety assessment screen.
 * Displays questions, evaluates answers on-device using [ScoringEngine],
 * shows score feedback with missed hazard topics, and persists results to Room.
 */
@Composable
fun AssessmentScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }
    val scope = rememberCoroutineScope()
    val userId = remember(context) { com.minesafear.data.ActiveWorkerPreference.getActiveWorkerId(context) }

    var mode by remember { mutableStateOf(AssessmentScreenMode.INTRO) }
    var currentQuestionIndex by remember { mutableIntStateOf(0) }
    val selectedAnswers = remember { mutableStateMapOf<String, String>() }
    var startTimeMillis by remember { mutableLongStateOf(0L) }
    var scoreResult by remember { mutableStateOf<AssessmentScore?>(null) }
    var missedTags by remember { mutableStateOf<List<String>>(emptyList()) }

    fun startAssessment() {
        selectedAnswers.clear()
        currentQuestionIndex = 0
        startTimeMillis = System.currentTimeMillis()
        mode = AssessmentScreenMode.IN_PROGRESS
    }

    fun submitAssessment() {
        val duration = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt().coerceAtLeast(1)
        val submission = AssessmentSubmission(
            workerId = userId,
            moduleId = "fire_explosion_response",
            answers = selectedAnswers.toMap(),
            durationSeconds = duration,
        )

        val score = ScoringEngine.score(sampleQuestions, submission)
        scoreResult = score
        missedTags = ScoringEngine.missedHazardTags(sampleQuestions, submission)
        mode = AssessmentScreenMode.COMPLETED

        scope.launch {
            runCatching {
                val attemptNum = repository.nextAttemptNumber(userId, "fire_explosion_response")
                val entity = AssessmentResultEntity(
                    id = UUID.randomUUID().toString(),
                    workerId = userId,
                    moduleId = "fire_explosion_response",
                    attemptNumber = attemptNum,
                    scorePercent = score.scorePercent,
                    correctAnswers = score.correctAnswers,
                    totalQuestions = score.totalQuestions,
                    passed = score.passed,
                    durationSeconds = duration,
                    submittedAt = System.currentTimeMillis(),
                    pendingSync = true,
                )
                repository.saveResult(entity)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.title_assessment),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (mode) {
            AssessmentScreenMode.INTRO -> IntroView(onStart = ::startAssessment)

            AssessmentScreenMode.IN_PROGRESS -> {
                val currentQuestion = sampleQuestions[currentQuestionIndex]
                val answeredCount = selectedAnswers.size

                QuestionView(
                    question = currentQuestion,
                    questionIndex = currentQuestionIndex,
                    totalQuestions = sampleQuestions.size,
                    selectedOptionId = selectedAnswers[currentQuestion.id],
                    onOptionSelected = { optionId -> selectedAnswers[currentQuestion.id] = optionId },
                    onPrevious = { if (currentQuestionIndex > 0) currentQuestionIndex-- },
                    onNext = { if (currentQuestionIndex < (sampleQuestions.size - 1)) currentQuestionIndex++ },
                    onSubmit = ::submitAssessment,
                    unansweredCount = sampleQuestions.size - answeredCount,
                )
            }

            AssessmentScreenMode.COMPLETED -> {
                scoreResult?.let { score ->
                    ResultView(
                        score = score,
                        missedTags = missedTags,
                        onRetake = ::startAssessment,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroView(onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.assessment_start_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.assessment_start_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.assessment_start_button))
            }
        }
    }
}

@Composable
private fun QuestionView(
    question: AssessmentQuestion,
    questionIndex: Int,
    totalQuestions: Int,
    selectedOptionId: String?,
    onOptionSelected: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
    unansweredCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.assessment_question_counter, questionIndex + 1, totalQuestions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LinearProgressIndicator(
            progress = { (questionIndex + 1).toFloat() / totalQuestions },
            modifier = Modifier.fillMaxWidth(),
        )

        // Question Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = question.prompt,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                HorizontalDivider()

                Column(modifier = Modifier.selectableGroup()) {
                    question.options.forEach { option ->
                        val isSelected = option.id == selectedOptionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                ) { onOptionSelected(option.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = option.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        if ((unansweredCount > 0) && (questionIndex == (totalQuestions - 1))) {
            Text(
                text = stringResource(R.string.assessment_unanswered_warning, unansweredCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Navigation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = questionIndex > 0,
            ) {
                Text(stringResource(R.string.assessment_prev_button))
            }

            if (questionIndex < (totalQuestions - 1)) {
                Button(onClick = onNext) {
                    Text(stringResource(R.string.assessment_next_button))
                }
            } else {
                Button(
                    onClick = onSubmit,
                    enabled = unansweredCount == 0,
                ) {
                    Text(stringResource(R.string.assessment_submit_button))
                }
            }
        }
    }
}

@Composable
private fun ResultView(
    score: AssessmentScore,
    missedTags: List<String>,
    onRetake: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status Chip
            Surface(
                color = if (score.passed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(if (score.passed) R.string.assessment_passed else R.string.assessment_failed),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (score.passed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            Text(
                text = stringResource(
                    R.string.assessment_score_summary,
                    score.scorePercent,
                    score.correctAnswers,
                    score.totalQuestions,
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(if (score.passed) R.string.assessment_passed_desc else R.string.assessment_failed_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (missedTags.isNotEmpty()) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assessment_missed_hazards),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    missedTags.forEach { tag ->
                        Text(
                            text = "• $tag",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRetake,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.assessment_retry_button))
            }
        }
    }
}
