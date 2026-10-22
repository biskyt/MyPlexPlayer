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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.groupLabel
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
 * Entries for [categories], named as the playlist names them; a name shared by categories of different groups ("News"
 * from two countries' channels) gets its group's label too, so they can be told apart.
 */
fun <F> categoryEntries(categories: List<CategoryEntity>, filter: (CategoryEntity) -> F): List<CategoryEntry<F>> {
    val shared = categories.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    return categories.map {
        val label = if (it.name in shared && it.lang != null) "${it.name} · ${groupLabel(it.lang)}" else it.name
        CategoryEntry(filter(it), label, it.channelCount, it)
    }
}

/**
 * The column of categories on the left of Live TV, Movies and Series. Moving up or down to an entry calls [onFocus]
 * (screens switch the list after a short pause); OK calls [onSelect]. Holding OK (or Menu) on a provider category
 * offers to hide it ([onHide]).
 *
 * The list follows the remote's up/down keys, not focus: focus arriving any other way (e.g. thrown out of a list that
 * changed under it) never switches it. Coming into the column, focus goes to the selected entry. [right]: where right
 * goes from it, when the list beside it isn't level with it (left to find, right could land on the top bar).
 */
@Composable
fun <F> CategoryColumn(
    entries: List<CategoryEntry<F>>,
    selected: F,
    onFocus: (F) -> Unit,
    onSelect: (F) -> Unit,
    onHide: ((CategoryEntity) -> Unit)? = null,
    right: FocusRequester = FocusRequester.Default
) {
    val selectedEntry = remember { FocusRequester() }
    var options by remember { mutableStateOf<CategoryEntity?>(null) }
    val state = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.filter == selected }
    val currentIndex by rememberUpdatedState(selectedIndex)
    fun selectedShown() = state.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
    // Picked some other way (remembered, or the one shown was hidden): the column shows it, ready for focus.
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0 && !selectedShown()) state.scrollToItem(selectedIndex) }
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        // A slow box may take a few frames to lay out the entry it scrolls to: focus goes there once it's shown.
        repeat(REFOCUS_FRAMES) {
            val index = currentIndex
            if (index >= 0 && !selectedShown()) state.scrollToItem(index)
            withFrameNanos { }
            if (selectedShown() && runCatching { selectedEntry.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LazyColumn(
        Modifier.width(250.dp)
            // Coming in, focus goes to the selected entry; if it isn't in view yet (a slow box still scrolling to it),
            // once the column has scrolled there.
            .focusProperties {
                onEnter = {
                    when {
                        selectedShown() -> runCatching { selectedEntry.requestFocus(requestedFocusDirection) }
                        selectedIndex >= 0 -> {
                            cancelFocusChange()
                            refocus++
                        }
                    }
                }
            }
            .focusGroup(),
        state = state,
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
                    .rightTo(right)
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
        val hide = ItemAction.Run(stringResource(R.string.common_hide_category), stringResource(R.string.common_hide_category_help)) {
            close()
            onHide?.invoke(cat)
            refocus++ // its entry goes, and focus with it
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
    filterModifier: Modifier = Modifier,
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
            modifier = filterModifier.weight(0.55f),
            leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
        )
        trailing()
    }
}

/** How many frames focus waits for the selected entry to be laid out after scrolling to it. */
private const val REFOCUS_FRAMES = 10

/** Right from here goes to [target]; unchanged (the usual search) when it's the default. */
private fun Modifier.rightTo(target: FocusRequester): Modifier =
    if (target == FocusRequester.Default) this else focusProperties { right = target }
