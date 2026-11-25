package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.VOD_TYPES
import com.biskyt.myplexplayer.data.db.VodCard
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.AmbientSource
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.LocalAmbience
import com.biskyt.myplexplayer.ui.common.NowNextLoader
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.liveTvShown
import com.biskyt.myplexplayer.ui.common.rememberMinuteClock
import com.biskyt.myplexplayer.ui.common.vodDetails
import com.biskyt.myplexplayer.ui.home.HomeTile
import com.biskyt.myplexplayer.ui.home.HomeViewModel
import com.biskyt.myplexplayer.ui.home.TileOptions
import com.biskyt.myplexplayer.ui.home.carryOnLeft
import com.biskyt.myplexplayer.ui.home.carryOnProgress
import com.biskyt.myplexplayer.ui.home.favouriteRowTitle
import com.biskyt.myplexplayer.ui.home.newestRowTitle
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette

// Home on a phone or an unfolded foldable (the owner, 2026-11-25): the TV's rows, in the TV's order, sized for touch. A
// tap plays or opens a card, and a long press opens its options, as holding OK does on a TV. No picture goes behind it:
// a touch moves no focus, so there is no card for the TV's title art to follow.

/**
 * Home, from the TV's [HomeViewModel]: Continue watching, recently added series and movies, favourite series and
 * movies, then Live TV last (the favourite channels and those watched lately).
 */
@Composable
fun PhoneHome(vm: HomeViewModel) {
    val nav = LocalNavigator.current
    val favourites by vm.favourites.collectAsState()
    val recent = vm.recent.collectAsState().value.orEmpty()
    val carryOn = vm.carryOn.collectAsState().value.orEmpty()
    val newestVod = VOD_TYPES.associateWith { vm.newestVod.getValue(it).collectAsState().value.orEmpty() }
    val favouriteVod = VOD_TYPES.associateWith { vm.favouriteVod.getValue(it).collectAsState().value.orEmpty() }
    val pid by vm.playlistId.collectAsState()
    val liveTv = liveTvShown()
    var options by remember { mutableStateOf<HomeTile?>(null) }
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    val list = rememberLazyListState()
    // The rows are read at different times (Continue watching last). A row that comes above the one at the top would
    // keep that one in place and start out of view, so until the page is dragged it goes back to its top, as Home on
    // a TV does until the remote is used.
    var dragged by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
    val rows = listOf(carryOn.isEmpty()) + (newestVod.values + favouriteVod.values).map { it.isEmpty() }
    LaunchedEffect(rows) { if (!dragged) list.scrollToItem(0) }
    PhoneScaffold(Tab.HOME, stringResource(R.string.app_name), actions = {
        // The rail has Settings of its own.
        if (!wide) PhoneIconButton(AppIcons.Settings, stringResource(R.string.common_settings), { nav.open(Screen.Settings) })
    }) {
        // Cards 10 dp apart; each section title has 12 dp more above it.
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (carryOn.isNotEmpty()) {
                item(key = "carry-on") {
                    PhoneSection(stringResource(R.string.home_carry_on)) {
                        PosterRow(carryOn, { "${it.kind}${it.itemId}" }) { p ->
                            PhonePoster(
                                p.imageUrl,
                                p.title,
                                carryOnLeft(p),
                                progress = p.carryOnProgress,
                                onLongClick = { options = HomeTile.CarryOn(p) }
                            ) {
                                // (vm.returnTo is not set: it moves a TV's focus back to the card, and a touch screen has none.)
                                nav.push(Screen.VodPlayer(pid, p.itemId, p.seriesId, continueSeries = true))
                            }
                        }
                    }
                }
            }
            vodRows("new", newestVod, ::newestRowTitle, { type, card -> options = HomeTile.New(type, card) }) { type, card ->
                nav.push(vodDetails(pid, type, card.id))
            }
            vodRows("favourites", favouriteVod, ::favouriteRowTitle, { type, card -> options = HomeTile.Vod(type, card) }) { type, card ->
                nav.push(vodDetails(pid, type, card.id))
            }
            val favs = favourites
            val nothingElse = carryOn.isEmpty() && (newestVod.values + favouriteVod.values).all { it.isEmpty() } && recent.isEmpty()
            when {
                favs == null -> Unit
                // With nothing else on Home, a hint says how to add channels: not with no Live TV to add them from.
                favs.isEmpty() -> if (liveTv && nothingElse) {
                    item(key = "on-now") { PhoneSectionTitle(stringResource(R.string.home_on_now)) }
                    item(key = "no-favourites") { EmptyFavourites { nav.reset(Screen.Live()) } }
                }
                else -> {
                    item(key = "on-now") { PhoneSectionTitle(stringResource(R.string.home_on_now)) }
                    channelCards("fav", favs, wide, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Favourites) }) {
                        nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Favourites))
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent") { PhoneSectionTitle(stringResource(R.string.common_recent)) }
                channelCards("recent", recent, wide, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Recent) }) {
                    nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Recent))
                }
            }
        }
    }
    options?.let { tile -> TileOptions(tile, vm, pid) { options = null } }
}

/** A row of posters for each type of [rows] that has any ([key] tells the rows apart), titled by [title]. */
private fun LazyListScope.vodRows(
    key: String,
    rows: Map<String, List<VodCard>>,
    title: (String) -> Int,
    onOptions: (String, VodCard) -> Unit,
    onOpen: (String, VodCard) -> Unit
) {
    rows.forEach { (type, cards) ->
        if (cards.isEmpty()) return@forEach
        item(key = "$key-$type") {
            PhoneSection(stringResource(title(type))) {
                PosterRow(cards, VodCard::id) { card ->
                    PhonePoster(card.imageUrl, card.title, card.year?.toString().orEmpty(), onLongClick = {
                        onOptions(type, card)
                    }) { onOpen(type, card) }
                }
            }
        }
    }
}

/** A row's title over [content]. */
@Composable
private fun PhoneSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneSectionTitle(title)
        content()
    }
}

@Composable
internal fun PhoneSectionTitle(title: String, modifier: Modifier = Modifier, gutter: Boolean = true) {
    val side = if (gutter) PhoneGutter else 0.dp
    Text(
        title,
        color = Palette.text,
        fontSize = 18.sp,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = side, end = side, top = 12.dp)
    )
}

/** A row of posters that scrolls sideways. */
@Composable
private fun <T : Any> PosterRow(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = key) { card(it) }
    }
}

/** A poster for a phone: the poster, then its title and [detail] under it, with how far it got ([progress]) if given. */
@Composable
internal fun PhonePoster(
    imageUrl: String?,
    title: String,
    detail: String,
    width: Dp = PHONE_POSTER_WIDTH,
    progress: Float? = null,
    favourite: Boolean = false,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        Modifier.width(
            width
        ).clip(RoundedCornerShape(10.dp)).touchCard(LocalAmbience.current, AmbientSource.picture(imageUrl), onLongClick, onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            // (Its initials, shown until the picture loads, are not read: the text under it is.)
            Poster(imageUrl, title, width, Modifier.clearAndSetSemantics {})
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp), height = 4.dp)
            if (favourite) FavouriteMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            title,
            color = Palette.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(detail.ifEmpty { " " }, color = Palette.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A favourite's heart, on the corner of its poster. */
@Composable
private fun FavouriteMark(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(Palette.background).padding(4.dp)) {
        androidx.tv.material3.Icon(
            AppIcons.Heart,
            stringResource(R.string.common_favourite_mark),
            tint = LocalAccent.current.color,
            modifier = Modifier.width(13.dp)
        )
    }
}

/** Channel cards one under the other; on an unfolded screen, two side by side. */
@Suppress("LongParameterList") // the row's key, its channels and layout, and what a card does
private fun LazyListScope.channelCards(
    /** Which row ("fav", "recent"): a channel can be in both, and keys must differ. */
    row: String,
    channels: List<ChannelEntity>,
    wide: Boolean,
    loader: NowNextLoader,
    onOptions: (ChannelEntity) -> Unit,
    onPlay: (ChannelEntity) -> Unit
) {
    val columns = if (wide) 2 else 1
    items(channels.chunked(columns), key = { cards -> "$row-" + cards.joinToString { it.streamId.toString() } }) { cards ->
        val now by rememberMinuteClock()
        Row(Modifier.padding(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { c -> PhoneChannelCard(c, loader, now, { onOptions(c) }, Modifier.weight(1f)) { onPlay(c) } }
            repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun EmptyFavourites(onBrowse: () -> Unit) {
    Column(Modifier.padding(horizontal = PhoneGutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.home_no_favourites), color = Palette.textDim, fontSize = 14.sp)
        PhonePill(stringResource(R.string.home_browse_channels), primary = true, onClick = onBrowse)
    }
}

/** A poster's width in rows on a phone. */
internal val PHONE_POSTER_WIDTH = 116.dp
