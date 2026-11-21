package io.github.cinpecan.engram.ui.answer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.domain.rag.Citation
import io.github.cinpecan.engram.domain.rag.RetrievalResult
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import io.github.cinpecan.engram.ui.graph.ReasoningGraphCanvas
import io.github.cinpecan.engram.ui.navigation.EngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalEngramNavigator
import io.github.cinpecan.engram.ui.viewmodel.AnswerIntent
import io.github.cinpecan.engram.ui.viewmodel.AnswerStatus
import io.github.cinpecan.engram.ui.viewmodel.AnswerViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AnswerSheetRoute(
    question: String,
    modifier: Modifier = Modifier,
    viewModel: AnswerViewModel = koinViewModel(key = "answer:$question") { parametersOf(question) },
    navigator: EngramNavigator = LocalEngramNavigator.current,
) {
    val retrieval by viewModel.retrieval.collectAsStateWithLifecycle()
    val answerText by viewModel.answerText.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    AnswerSheetContent(
        question = question,
        retrieval = retrieval,
        answerText = answerText,
        status = status,
        onOpenSource = navigator::openRawMemory,
        onRetry = { viewModel.onIntent(AnswerIntent.Retry) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerSheetContent(
    question: String,
    retrieval: RetrievalResult?,
    answerText: String,
    status: AnswerStatus,
    onOpenSource: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val citations = retrieval?.citations.orEmpty()
    val traceEdges = retrieval?.traceEdges.orEmpty()
    var selectedEntity by remember(question, traceEdges) { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Text(
                text = question,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (answerText.isNotBlank()) {
            CitedAnswerText(
                text = answerText,
                citations = citations,
                onOpenSource = onOpenSource,
            )
        }

        StatusLine(status = status, onRetry = onRetry)

        AnimatedVisibility(visible = citations.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Sources")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    citations.forEach { citation ->
                        SourceChip(citation = citation, onClick = { onOpenSource(citation.memoryId) })
                    }
                }
            }
        }

        AnimatedVisibility(visible = traceEdges.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    SectionLabel("Reasoning graph · how these memories connect")
                }
                ReasoningGraphCanvas(
                    edges = traceEdges,
                    seedEntities = retrieval?.seedEntities.orEmpty(),
                    selectedEntity = selectedEntity,
                    onEntityClick = { entity ->
                        selectedEntity = if (selectedEntity.equals(entity, ignoreCase = true)) {
                            null
                        } else {
                            entity
                        }
                    },
                )

                val entity = selectedEntity
                if (!entity.isNullOrBlank()) {
                    val linkedMemoryIds = traceEdges
                        .filter { edge ->
                            edge.source.equals(entity, ignoreCase = true) ||
                                edge.target.equals(entity, ignoreCase = true)
                        }
                        .mapTo(HashSet()) { it.memoryId }
                    val matchingCitations = citations.filter { citation ->
                        citation.memoryId in linkedMemoryIds ||
                            citation.headline.contains(entity, ignoreCase = true) ||
                            citation.contextText.contains(entity, ignoreCase = true)
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Linked sources · $entity (${matchingCitations.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ExpressivePillSurface(
                                    onClick = { selectedEntity = null },
                                    modifier = Modifier.size(28.dp),
                                    restCorner = 14.dp,
                                    pressedCorner = 8.dp,
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    contentDescription = "Clear selected entity",
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            if (matchingCitations.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    matchingCitations.forEach { citation ->
                                        ExpressivePillSurface(
                                            onClick = { onOpenSource(citation.memoryId) },
                                            modifier = Modifier.widthIn(max = 280.dp),
                                            restCorner = 12.dp,
                                            pressedCorner = 8.dp,
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            contentDescription = "Open source ${citation.index}",
                                        ) {
                                            Text(
                                                text = "${citation.index} · ${citation.headline}",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StatusLine(status: AnswerStatus, onRetry: () -> Unit) {
    when {
        status.isWorking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.75.dp,
            )
            Text(
                text = status.stage.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        !status.badge.isNullOrBlank() -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        ) {
            Text(
                text = status.badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        !status.notice.isNullOrBlank() -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = status.notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            ExpressivePillSurface(
                onClick = onRetry,
                restCorner = 16.dp,
                pressedCorner = 10.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                pressedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = "Retry",
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(16.dp),
                )
            }
        }
    }
}

/** Renders `[n]` markers as tappable tonal chips that open the cited memory. */
@Composable
private fun CitedAnswerText(
    text: String,
    citations: List<Citation>,
    onOpenSource: (String) -> Unit,
) {
    val chipBackground = MaterialTheme.colorScheme.primaryContainer
    val chipForeground = MaterialTheme.colorScheme.onPrimaryContainer
    val annotated: AnnotatedString = remember(text, citations, chipBackground, chipForeground) {
        val byIndex = citations.associateBy { it.index }
        buildAnnotatedString {
            var cursor = 0
            CITATION_REGEX.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                val index = match.groupValues[1].toIntOrNull()
                val citation = index?.let { byIndex[it] }
                if (citation == null) {
                    append(match.value)
                } else {
                    val chipStyle = SpanStyle(
                        background = chipBackground,
                        color = chipForeground,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "cite-${citation.index}",
                            styles = TextLinkStyles(style = chipStyle),
                        ) { onOpenSource(citation.memoryId) },
                    ) {
                        withStyle(chipStyle) { append("\u2009${citation.index}\u2009") }
                    }
                }
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SourceChip(citation: Citation, onClick: () -> Unit) {
    ExpressivePillSurface(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 300.dp),
        restCorner = 14.dp,
        pressedCorner = 9.dp,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        pressedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentDescription = "Open source ${citation.index}",
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = "${citation.index} · ${citation.headline}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = matchReason(citation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun matchReason(citation: Citation): String {
    val parts = buildList {
        if (citation.lexicalRank != null) add("Keyword match")
        citation.graphHops?.let { hops -> add(if (hops <= 1) "Direct link" else "$hops-hop link") }
    }
    return parts.joinToString(" · ").ifEmpty { "Related" }
}

private val CITATION_REGEX = Regex("\\[(\\d{1,2})]")
