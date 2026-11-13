package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** The letter grid's columns: A-Z in 4 rows of 7. */
private const val KEY_COLUMNS = 7

/**
 * The DEX list's NAME search, in the left column while it's open (the companion can't host a keyboard): [query] over
 * a grid of [keys] (A-Z and the seen names' other characters, `dexNameKeys`), with DEL and OK under it (a word is too
 * wide for a letter's cell). A key in [live] still finds a seen name; the rest are dimmed and do nothing, so a name
 * typed here never runs into an empty list. The list on the right filters as each key goes in. OK, the title's back
 * arrow and BACK close it, keeping [query].
 */
@Composable
fun DexNameSearch(
    query: String,
    keys: List<Char>,
    live: Set<Char>,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val u = m.u
    CompanionBackHandler(onBack = onClose)
    // The D-pad starts on the first key that still finds something.
    val first = keys.firstOrNull { it in live }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(u * 4)) {
        OptionTitleWindow(tk("NAME"), m, onBack = onClose)
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = u * 8, vertical = u * 4), verticalAlignment = Alignment.CenterVertically) {
                // The typed name with the naming screen's underscore for where the next letter goes.
                GbaText("${query}_", OptionColors.value, OptionColors.valueShadow, m, Modifier.weight(1f))
                Spacer(Modifier.width(u * 4))
                // Always enabled: a button greyed out under the D-pad drops its focus. Empty, it does nothing.
                OptionButton(tk("CLEAR"), small, onClick = { if (query.isNotEmpty()) onQuery("") })
            }
        }
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(u * 3)) {
                keys.chunked(KEY_COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { c ->
                            KeyCell(c.toString(), c in live, start = c == first, m = m, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (c in live) onQuery(query + c)
                            }
                        }
                        // The last row's empty cells keep the columns lined up.
                        repeat(KEY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 4)) {
            // Both always enabled (a button greyed out under the D-pad drops its focus): DEL on an empty name does nothing.
            OptionButton(
                tk("DEL"), small, onClick = { if (query.isNotEmpty()) onQuery(query.dropLast(1)) },
                modifier = Modifier.weight(1f), contentDescription = tr("Delete a letter"),
            )
            OptionButton(tk("OK"), small, onClick = onClose, modifier = Modifier.weight(1f), emphasis = true)
        }
    }
}

/**
 * A key: the OPTION list's white cursor under the D-pad, a dimmed label when it would find nothing. Dead keys stay
 * focusable (a disabled clickable would drop the focus the moment a letter makes it dead) and simply do nothing.
 */
@Composable
private fun KeyCell(
    label: String,
    live: Boolean,
    start: Boolean,
    m: GbaTextMetrics,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val u = m.u
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val (fg, shadow) = if (live) OptionColors.label to OptionColors.labelShadow else OptionColors.muted to OptionColors.mutedShadow
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .focusStart(start)
            .clip(PixelRoundedShape(u * 2))
            .background(if (focused) OptionColors.rowSelected else Color.Transparent)
            .soundClickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        GbaText(label, fg, shadow, m, bold = gameBoldLabels())
    }
}
