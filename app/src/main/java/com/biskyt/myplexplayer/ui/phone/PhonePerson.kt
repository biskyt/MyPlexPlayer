package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.PersonCredit
import com.biskyt.myplexplayer.data.LibraryTitle
import com.biskyt.myplexplayer.data.PersonPage
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.CirclePhoto
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.vod.BIO_LINES
import com.biskyt.myplexplayer.ui.vod.PersonViewModel
import com.biskyt.myplexplayer.ui.vod.aboutLine
import com.biskyt.myplexplayer.ui.vod.cardLine
import com.biskyt.myplexplayer.ui.vod.creditKind

// A person's page on a phone or an unfolded foldable (the owner, 2026-11-25): the TV page's parts (PersonScreen.kt),
// drawn with the phone parts. Back from it goes where it does on a TV (to the player, which plays on with Cast and
// crew open on the same person).

/**
 * The person's page on a phone: Back, their photo, name and the line about them, the biography cut to 3 lines (with
 * Full biography, [showBiography], when it is cut), then "In your library" (a tap opens a title's page, [open]), "Also appears in"
 * (with a TMDB key, a tap opens its TMDB page, [openTmdb]) and "All credits". A part with no data is left out.
 */
@Composable
fun PhonePersonPage(
    screen: Screen.Person,
    vm: PersonViewModel,
    open: (LibraryTitle) -> Unit,
    openTmdb: ((PersonCredit) -> Unit)?,
    showBiography: () -> Unit
) {
    val page = vm.page
    val error = vm.error
    // Unfolded, the photo is larger.
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    LazyColumn(
        Modifier.fillMaxSize().background(Palette.background),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PersonHeader(screen, page, wide, showBiography) }
        when {
            error != null -> item {
                Column(Modifier.padding(horizontal = PhoneGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhoneSmallText(error, maxLines = 3)
                    PhonePill(stringResource(R.string.common_retry)) { vm.load() }
                }
            }
            page == null -> item { PhoneSmallText(stringResource(R.string.common_loading), Modifier.padding(horizontal = PhoneGutter)) }
            else -> personSections(page, open, openTmdb)
        }
    }
}

/** Back, then the photo with the name and the line about them beside it, then the biography and Full biography. */
@Composable
private fun PersonHeader(screen: Screen.Person, page: PersonPage?, wide: Boolean, showBiography: () -> Unit) {
    val nav = LocalNavigator.current
    val name = page?.name ?: screen.name
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PhoneIconButton(
            AppIcons.Back,
            stringResource(R.string.player_back),
            { nav.back() },
            Modifier.padding(start = 14.dp, top = 14.dp),
            fill = Color.Black.copy(alpha = 0.45f),
            iconSize = 22.dp
        )
        Row(
            Modifier.padding(horizontal = PhoneGutter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The cast photo already shown, if there is one: a new address would show the initials while it loads.
            CirclePhoto(screen.photoUrl ?: page?.photoUrl, name, if (wide) 140.dp else PHOTO)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    color = Palette.text,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                aboutLine(page, screen.role, screen.title)?.let { PhoneSmallText(it, maxLines = 4) }
            }
        }
        page?.biography?.let { bio -> Biography(bio, showBiography) }
    }
}

/** The biography cut to [BIO_LINES] lines, and Full biography ([showBiography]) only when it is cut, as PhonePlot does. */
@Composable
private fun Biography(bio: String, showBiography: () -> Unit) {
    var cut by remember(bio) { mutableStateOf(false) }
    Text(
        bio,
        color = Palette.textMuted,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        maxLines = BIO_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { cut = it.hasVisualOverflow },
        modifier = Modifier.padding(horizontal = PhoneGutter)
    )
    if (cut) {
        PhonePill(
            stringResource(R.string.vod_person_full_biography),
            Modifier.padding(horizontal = PhoneGutter),
            onClick = showBiography
        )
    }
}

/** "In your library", "Also appears in" and "All credits", each only when it has something. */
private fun LazyListScope.personSections(page: PersonPage, open: (LibraryTitle) -> Unit, openTmdb: ((PersonCredit) -> Unit)?) {
    if (page.library.isNotEmpty()) {
        item(key = "library") {
            PosterSection(stringResource(R.string.vod_person_library), page.library) { t ->
                PhonePoster(t.posterUrl, t.title, cardLine(t.show, t.year, t.role, t.kind).orEmpty(), onLongClick = { open(t) }) { open(t) }
            }
        }
    }
    if (page.elsewhere.isNotEmpty()) {
        item(key = "elsewhere") {
            PosterSection(stringResource(R.string.vod_person_elsewhere), page.elsewhere) { c ->
                // Not on the server: without a TMDB key, nothing to open.
                val tap = { openTmdb?.invoke(c) ?: Unit }
                PhonePoster(c.thumb, c.title, cardLine(c.show, c.year, c.role, c.kind).orEmpty(), onLongClick = tap, onClick = tap)
            }
        }
    }
    if (page.credits.isNotEmpty()) {
        item(key = "credits") { PhoneSectionTitle(stringResource(R.string.vod_person_credits)) }
        items(page.credits) { PhoneCreditRow(it) }
    }
}

/** A section's [title] over a row of posters ([poster] for each of [items]) that scrolls sideways. */
@Composable
private fun <T> PosterSection(title: String, items: List<T>, poster: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneSectionTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items) { poster(it) }
        }
    }
}

/** A credit: its year, its title (a show's with " (TV show)"), and under it the part or job, with a line below. */
@Composable
private fun PhoneCreditRow(c: PersonCredit) {
    Column(Modifier.padding(horizontal = PhoneGutter)) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(c.year?.toString().orEmpty(), color = Palette.textDim, fontSize = 14.sp, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (c.show) stringResource(R.string.vod_person_show_title, c.title) else c.title,
                    color = Palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    c.role ?: creditKind(c.kind),
                    color = Palette.textDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.surfaceHigh))
    }
}

private val PHOTO = 104.dp
