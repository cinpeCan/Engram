package io.github.cinpecan.engram.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
val LocalModalBottomSheetState = staticCompositionLocalOf<SheetState?> { null }

@OptIn(ExperimentalMaterial3Api::class)
internal class BottomSheetScene<T : Any>(
    override val key: Any,
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val expandable: Boolean,
    private val onBack: () -> Unit,
) : OverlayScene<T> {

    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable () -> Unit = {
        val lifecycleOwner = rememberLifecycleOwner()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !expandable)
        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = onBack,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = {
                if (expandable) {
                    val toggleLabel = if (sheetState.currentValue == SheetValue.Expanded) {
                        "收起至半屏"
                    } else {
                        "展开至全屏"
                    }
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 72.dp, minHeight = 48.dp)
                            .semantics { contentDescription = toggleLabel }
                            .clickable(
                                role = Role.Button,
                                onClickLabel = toggleLabel,
                            ) {
                                scope.launch {
                                    if (sheetState.currentValue == SheetValue.Expanded) {
                                        sheetState.partialExpand()
                                    } else {
                                        sheetState.expand()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }
                } else {
                    BottomSheetDefaults.DragHandle()
                }
            },
        ) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycleOwner,
                LocalModalBottomSheetState provides sheetState,
            ) {
                entry.Content()
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as BottomSheetScene<*>
        return key == other.key &&
            expandable == other.expandable &&
            previousEntries == other.previousEntries &&
            overlaidEntries == other.overlaidEntries &&
            entry == other.entry
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + expandable.hashCode()
        result = 31 * result + previousEntries.hashCode()
        result = 31 * result + overlaidEntries.hashCode()
        result = 31 * result + entry.hashCode()
        return result
    }
}

/**
 * Navigation 3 [SceneStrategy] rendering entries marked with [bottomSheet] inside an
 * [OverlayScene] backed by Material 3 [ModalBottomSheet], optionally supporting two-stage
 * half/full expansion with drag & single-tap accessible handle.
 */
class BottomSheetSceneStrategy<T : Any> : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull() ?: return null
        if (lastEntry.metadata[BottomSheetKey] != true || entries.size <= 1) return null
        val underlying = entries.dropLast(1)
        val expandable = lastEntry.metadata[ExpandableKey] == true
        return BottomSheetScene(
            key = lastEntry.contentKey,
            entry = lastEntry,
            previousEntries = underlying,
            overlaidEntries = underlying,
            expandable = expandable,
            onBack = onBack,
        )
    }

    companion object {
        object BottomSheetKey : NavMetadataKey<Boolean>
        object ExpandableKey : NavMetadataKey<Boolean>

        fun bottomSheet(expandable: Boolean = false): Map<String, Any> = metadata {
            put(BottomSheetKey, true)
            put(ExpandableKey, expandable)
        }
    }
}
