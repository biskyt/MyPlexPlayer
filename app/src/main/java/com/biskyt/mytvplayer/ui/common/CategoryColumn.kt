package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

/** One entry in a category column: a fixed list (Favourites, All…) or a provider category with its size. */
data class CategoryEntry<F>(val filter: F, val label: String, val count: Int? = null)

/**
 * The column of categories on the left of Live TV, Movies and Series. Moving focus onto an entry calls [onFocus]
 * (screens switch the list after a short pause); OK calls [onSelect].
 */
@Composable
fun <F> CategoryColumn(entries: List<CategoryEntry<F>>, selected: F, onFocus: (F) -> Unit, onSelect: (F) -> Unit) {
    LazyColumn(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(entries, key = { it.label + it.filter.hashCode() }) { (f, label, count) ->
            val isSelected = f == selected
            TvCard(
                onClick = { onSelect(f) },
                selected = isSelected,
                color = Palette.background,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus(f) }
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
}
