package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.data.LevelUpChoice
import com.pokedaisy.app.companion.data.LevelUpOffer
import com.pokedaisy.app.companion.data.LevelUpResult
import com.pokedaisy.app.companion.data.LevelUpScope
import com.pokedaisy.app.companion.data.LevelUpSession
import com.pokedaisy.app.companion.data.MoveQuestion
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.i18n.trGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where LEVEL UP ALL's pop-ups are: pick whose Pokémon, pick a target (or a level of the player's own), pick what new moves
 * do, confirm, answer the moves, see what happened.
 */
internal sealed class LevelUpStep {
    class Who(val offer: LevelUpOffer) : LevelUpStep()
    class Target(val offer: LevelUpOffer, val scope: LevelUpScope) : LevelUpStep()
    class PickLevel(val offer: LevelUpOffer, val scope: LevelUpScope, val range: IntRange) : LevelUpStep()
    /** A picked level being planned off the UI thread: nothing drawn for that moment. */
    class Planning(val offer: LevelUpOffer, val scope: LevelUpScope, val level: Int) : LevelUpStep()
    class Moves(val offer: LevelUpOffer, val choice: LevelUpChoice) : LevelUpStep()
    class Confirm(val offer: LevelUpOffer, val choice: LevelUpChoice, val ask: Boolean) : LevelUpStep()
    class Ask(val offer: LevelUpOffer, val session: LevelUpSession, val question: MoveQuestion) : LevelUpStep()
    class Notice(val message: String) : LevelUpStep()
}

private const val DONT_LEARN = -1
private const val SKIP_REST = -2

/** The row's tap: the first pop-up, or why not now. */
internal fun startLevelUp(s: CompanionSettings, go: (LevelUpStep) -> Unit) {
    s.levelUpOffer { offer -> go(if (offer == null) LevelUpStep.Notice(tr("Finish what's on the screen and try again from the field.")) else LevelUpStep.Who(offer)) }
}

/** The next question of [session], or the write when none are left. */
private fun nextStep(s: CompanionSettings, offer: LevelUpOffer, session: LevelUpSession, go: (LevelUpStep?) -> Unit) {
    val q = session.next()
    if (q != null) go(LevelUpStep.Ask(offer, session, q)) else write(s, offer, session, go)
}

private fun write(s: CompanionSettings, offer: LevelUpOffer, session: LevelUpSession, go: (LevelUpStep?) -> Unit) {
    val planned = session.planned()
    // The pop-up goes first: a second tap on RAISE would write again, find its own records changed and say nothing happened.
    go(null)
    s.levelUpApply(planned, offer.rules) { r ->
        go(LevelUpStep.Notice(when (r) {
            LevelUpResult.DONE -> tr("{0} POKéMON RAISED.", planned.size) + "\n" + tr("Save in the game to keep it.")
            LevelUpResult.NOT_NOW -> tr("Finish what's on the screen and try again from the field.")
            LevelUpResult.CHANGED -> tr("Your party or PC changed. Nothing was raised.")
            LevelUpResult.FAILED -> tr("Something went wrong. Nothing was raised.")
        }))
    }
}

@Composable
internal fun LevelUpAllFlow(s: CompanionSettings, step: LevelUpStep, m: GbaTextMetrics, go: (LevelUpStep?) -> Unit) {
    val close = { go(null) }
    when (step) {
        is LevelUpStep.Who -> OptionSelector(
            tk("LEVEL UP ALL"), LevelUpScope.entries, LevelUpScope.ALL, { tr(it.label) }, m,
            onPick = { if (step.offer.lowest(it) != null) go(LevelUpStep.Target(step.offer, it)) },
            onDismiss = close,
            badge = { if (step.offer.lowest(it) == null) tk("N/A") else null },
        )
        is LevelUpStep.Target -> {
            val offer = step.offer
            val highestLabel = tr("HIGHEST · LV {0}", offer.highest.level)
            val bossLabel = offer.nextBoss?.let { tr("NEXT BOSS · LV {0}", it.level) } ?: tr("NEXT BOSS")
            val pickLabel = tr("PICK A LEVEL")
            val range = offer.pickable(step.scope)
            OptionSelector(
                tk("LEVEL UP ALL"), listOf(highestLabel, bossLabel, pickLabel), highestLabel, { it }, m,
                onPick = { l ->
                    when (l) {
                        highestLabel -> go(afterTarget(offer, offer.highest.within(step.scope)))
                        bossLabel -> offer.nextBoss?.let { go(afterTarget(offer, it.within(step.scope))) }
                        else -> range?.let { go(LevelUpStep.PickLevel(offer, step.scope, it)) }
                    }
                },
                onDismiss = close,
                badge = { if ((it == bossLabel && offer.nextBoss == null) || (it == pickLabel && range == null)) tk("N/A") else null },
                note = offer.bossName?.let { tr("NEXT: {0}", trGuide(it)) } ?: tr("Every boss is beaten"),
            )
        }
        is LevelUpStep.PickLevel -> {
            // Starts at the top: the highest it may go, which is HIGHEST's or NEXT BOSS's level.
            var level by remember(step) { mutableIntStateOf(step.range.last) }
            OptionSliderPopup(
                tr("PICK A LEVEL"), tr("LV {0}", level), SettingSlider(level, step.range, 1) { level = it }, m,
                onDismiss = close,
                onDone = { go(LevelUpStep.Planning(step.offer, step.scope, level)) },
            )
        }
        is LevelUpStep.Planning -> LaunchedEffect(step) {
            val choice = withContext(Dispatchers.Default) { runCatching { step.offer.choiceAt(step.level, step.scope) }.getOrNull() }
            go(if (choice == null) LevelUpStep.Notice(tr("Something went wrong. Nothing was raised.")) else afterTarget(step.offer, choice))
        }
        is LevelUpStep.Moves -> {
            val skip = tk("SKIP THEM ALL")
            OptionSelector(
                tk("NEW MOVES"), listOf(skip, tk("ASK FOR EACH")), skip, { tr(it) }, m,
                onPick = { go(LevelUpStep.Confirm(step.offer, step.choice, ask = it != skip)) },
                onDismiss = close,
            )
        }
        is LevelUpStep.Confirm -> {
            val evolving = step.choice.planned.count { it.plan.species != it.plan.oldSpecies }
            OptionConfirm(
                tk("LEVEL UP ALL"),
                listOfNotNull(
                    tr("{0} POKéMON GO UP TO LV {1}.", step.choice.planned.size, step.choice.level),
                    if (evolving > 0) tr("{0} WILL EVOLVE.", evolving) else null,
                    tr("Save in the game to keep it."),
                ).joinToString("\n"),
                tk("RAISE"), m,
                onConfirm = {
                    val session = LevelUpSession(step.choice.planned, step.offer.rules)
                    if (step.ask) nextStep(s, step.offer, session, go)
                    else { session.skipRest(); write(s, step.offer, session, go) }
                },
                onDismiss = close,
            )
        }
        is LevelUpStep.Ask -> {
            val q = step.question
            // Each question is its own list: the D-pad's row starts over.
            key(q.addr, q.move) { OptionSelector(
                tr("{0} CAN LEARN {1}", q.name, lookupMove(q.move).name),
                q.current.indices.toList() + DONT_LEARN + SKIP_REST, DONT_LEARN,
                { slot ->
                    when (slot) {
                        DONT_LEARN -> tr("DON'T LEARN IT")
                        SKIP_REST -> tr("SKIP THE REST")
                        else -> tr("FORGET {0}", lookupMove(q.current[slot]).name)
                    }
                },
                m,
                onPick = { slot ->
                    when (slot) {
                        SKIP_REST -> { step.session.skipRest(); write(s, step.offer, step.session, go) }
                        else -> { step.session.answer(slot.takeIf { it != DONT_LEARN }); nextStep(s, step.offer, step.session, go) }
                    }
                },
                onDismiss = close,
            ) }
        }
        is LevelUpStep.Notice -> OptionConfirm(tk("LEVEL UP ALL"), step.message, tk("OK"), m, onConfirm = close, onDismiss = close, cancelLabel = null)
    }
}

/** A target nobody is below gets the notice; otherwise on to the moves. */
private fun afterTarget(offer: LevelUpOffer, choice: LevelUpChoice): LevelUpStep =
    if (choice.planned.isEmpty()) LevelUpStep.Notice(tr("Everyone is at that level already.")) else LevelUpStep.Moves(offer, choice)
