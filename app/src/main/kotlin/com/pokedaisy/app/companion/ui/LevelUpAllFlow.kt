package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.data.LevelUpChoice
import com.pokedaisy.app.companion.data.LevelUpOffer
import com.pokedaisy.app.companion.data.LevelUpResult
import com.pokedaisy.app.companion.data.LevelUpSession
import com.pokedaisy.app.companion.data.MoveQuestion
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.i18n.trGuide

/** Where LEVEL UP ALL's pop-ups are: pick a target, pick what new moves do, confirm, answer the moves, see what happened. */
internal sealed class LevelUpStep {
    class Target(val offer: LevelUpOffer) : LevelUpStep()
    class Moves(val offer: LevelUpOffer, val choice: LevelUpChoice) : LevelUpStep()
    class Confirm(val offer: LevelUpOffer, val choice: LevelUpChoice, val ask: Boolean) : LevelUpStep()
    class Ask(val offer: LevelUpOffer, val session: LevelUpSession, val question: MoveQuestion) : LevelUpStep()
    class Notice(val message: String) : LevelUpStep()
}

private const val DONT_LEARN = -1
private const val SKIP_REST = -2

/** The row's tap: the first pop-up, or why not now. */
internal fun startLevelUp(s: CompanionSettings, go: (LevelUpStep) -> Unit) {
    s.levelUpOffer { offer -> go(if (offer == null) LevelUpStep.Notice(tr("Finish what's on the screen and try again from the field.")) else LevelUpStep.Target(offer)) }
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
        is LevelUpStep.Target -> {
            val choices = listOfNotNull(step.offer.highest, step.offer.nextBoss)
            val highestLabel = tr("HIGHEST · LV {0}", step.offer.highest.level)
            val bossLabel = step.offer.nextBoss?.let { tr("NEXT BOSS · LV {0}", it.level) } ?: tr("NEXT BOSS")
            val labels = listOf(highestLabel, bossLabel)
            OptionSelector(
                tk("LEVEL UP ALL"), labels, highestLabel, { it }, m,
                onPick = { l ->
                    if (l == highestLabel) go(afterTarget(step.offer, choices[0]))
                    else step.offer.nextBoss?.let { go(afterTarget(step.offer, it)) }
                },
                onDismiss = close,
                badge = { if (it == bossLabel && step.offer.nextBoss == null) tk("N/A") else null },
                note = step.offer.bossName?.let { tr("NEXT: {0}", trGuide(it)) } ?: tr("Every boss is beaten"),
            )
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
