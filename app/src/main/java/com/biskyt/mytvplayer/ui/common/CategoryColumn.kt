package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The selected entry of a [CategoryColumn]. Focusing an entry switches after a short pause, so moving through
 * categories stays smooth; selecting switches at once. [onChange] runs when the selection changes.
 */
class CategoryFilter<F>(private val scope: CoroutineScope, initial: F, private val onChange: (F) -> Unit = {}) {
    private val state = MutableStateFlow(initial)
    val value: StateFlow<F> = state
    private var job: Job? = null

    fun focus(f: F) {
        job?.cancel()
        if (f == state.value) return
        job = scope.launch {
            delay(FOCUS_DELAY_MS)
            set(f)
        }
    }

    fun select(f: F) {
        job?.cancel()
        set(f)
    }

    private fun set(f: F) {
        if (f == state.value) return
        state.value = f
        onChange(f)
    }

    private companion object {
        const val FOCUS_DELAY_MS = 300L
    }
}

/**
 * One entry in a category column: a fixed list (Favourites, All…), or a provider [category] (which can be hidden) with
 * its size.
 */
data class CategoryEntry<F>(val filter: F, val label: String, val count: Int? = null, val category: CategoryEntity? = null)

/**
 * The column of categories on the left of Live TV, Movies and Series. Moving up or down to an entry calls [onFocus]
 * (screens switch the list after a short pause); OK calls [onSelect]. Holding OK (or Menu) on a provider category
 * offers to hide it ([onHide]).
 *
 * The list follows the remote's up/down keys, not focus: focus arriving any other way (e.g. thrown out of a list that
 * changed under it) never switches it. Coming into the column, focus goes to the selected entry.
 */
@Composable
fun <F> CategoryColumn(
    entries: List<CategoryEntry<F>>,
    selected: F,
    onFocus: (F) -> Unit,
    onSelect: (F) -> Unit,
    onHide: ((CategoryEntity) -> Unit)? = null
) {
    val selectedEntry = remember { FocusRequester() }
    var options by remember { mutableStateOf<CategoryEntity?>(null) }
    LazyColumn(
        Modifier.width(250.dp)
            .focusProperties { onEnter = { selectedEntry.requestFocus(requestedFocusDirection) } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.label + e.filter.hashCode() }) { i, entry ->
            val (f, label, count) = entry
            val category = entry.category
            val isSelected = f == selected
            TvCard(
                onClick = { onSelect(f) },
                onLongClick = if (onHide != null && category != null) ({ options = category }) else null,
                selected = isSelected,
                color = Palette.background,
                modifier = Modifier.fillMaxWidth()
                    .then(if (isSelected) Modifier.focusRequester(selectedEntry) else Modifier)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) {
                            val next = when (e.key) {
                                Key.DirectionUp -> i - 1
                                Key.DirectionDown -> i + 1
                                else -> -1
                            }
                            entries.getOrNull(next)?.let { onFocus(it.filter) }
                        }
                        false // focus moves as usual
                    }
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) LocalAccent.current.color else Palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (count != null) Text(formatCount(count), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                }
            }
        }
    }
    options?.let { cat ->
        val close = { options = null }
        val hide = ItemAction.Run("Hide category", "Show it again in Settings › Languages & categories") {
            close()
            onHide?.invoke(cat)
        }
        ItemActionsPanel(cat.name, listOf(hide), close)
    }
}

/** Above a list or grid: its title and size, a filter box, and anything screen-specific ([trailing]). */
@Composable
fun ListHeader(
    title: String,
    count: Int?,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f, fill = false)
        )
        if (count != null) Muted("· ${formatCount(count)}")
        TvTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = placeholder,
            modifier = Modifier.weight(0.55f),
            leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
        )
        trailing()
    }
}
