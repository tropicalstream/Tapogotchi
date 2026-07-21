package com.tapogotchi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import com.tapogotchi.engine.Anim
import com.tapogotchi.engine.GameEngine
import com.tapogotchi.engine.IconAction
import com.tapogotchi.engine.Need
import com.tapogotchi.engine.ScreenId
import com.tapogotchi.engine.Sprites
import com.tapogotchi.engine.Stage
import com.tapogotchi.engine.AdultForm
import com.tapogotchi.platform.YawTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sin

/**
 * The whole picture: chunky LCD pixels floating on the waveguide void.
 * Designed at 640-wide logical units and scaled to whatever the eye gets.
 */
class Renderer(
    private val engine: GameEngine,
    private val store: SettingsStore
) {

    var yawTracker: YawTracker? = null

    companion object {
        const val LCD = 0xFF9FE87A.toInt()
        const val DIM = 0xFF3E7A33.toInt()
        const val WHITE = 0xFFF2F2E9.toInt()
        const val ALERT = 0xFFFF6B6B.toInt()
        const val GOLD = 0xFFFFD75E.toInt()
    }

    private val px = Paint().apply { color = LCD }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = LCD
    }
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    private var u = 1f   // 640-logical -> real px

    fun draw(canvas: Canvas, w: Int, h: Int) {
        canvas.drawColor(Color.BLACK)
        u = w / 640f
        when (engine.screen) {
            ScreenId.PET -> drawPetScreen(canvas)
            ScreenId.FEED -> drawFeed(canvas)
            ScreenId.STATS -> drawStats(canvas)
            ScreenId.SETTINGS -> drawSettings(canvas)
            ScreenId.GAME -> drawGame(canvas)
            ScreenId.REPORT -> drawReport(canvas)
            ScreenId.GRAVE -> drawGrave(canvas)
        }
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private fun grid(canvas: Canvas, rows: Array<String>, leftU: Float, topU: Float, cell: Float, paint: Paint = px) {
        val c = cell * u
        for ((r, row) in rows.withIndex()) {
            val y0 = (topU * u) + r * c
            for ((i, ch) in row.withIndex()) {
                if (ch == '#') {
                    val x0 = (leftU * u) + i * c
                    canvas.drawRect(x0, y0, x0 + c - u * 0.5f, y0 + c - u * 0.5f, paint)
                }
            }
        }
    }

    private fun label(
        canvas: Canvas, s: String, xU: Float, yU: Float, sizeU: Float, color: Int,
        center: Boolean = false, right: Boolean = false
    ) {
        text.textSize = sizeU * u
        text.color = color
        text.textAlign = when {
            center -> Paint.Align.CENTER
            right -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        canvas.drawText(s, xU * u, yU * u, text)
    }

    private fun hearts(canvas: Canvas, filled: Int, xU: Float, yU: Float) {
        for (i in 0 until 4) {
            px.color = if (i < filled) LCD else DIM
            grid(canvas, if (i < filled) Sprites.HEART else Sprites.HEART_EMPTY, xU + i * 26f, yU, 3f)
        }
        px.color = LCD
    }

    // ------------------------------------------------------------------
    //  PET — the home screen
    // ------------------------------------------------------------------

    private fun stageFrames(): Array<Array<String>> = when (engine.pet.stage) {
        Stage.EGG -> Sprites.EGG
        Stage.BABY -> Sprites.BABY
        Stage.CHILD -> Sprites.CHILD
        Stage.TEEN -> Sprites.TEEN
        Stage.ADULT -> when (engine.pet.form) {
            AdultForm.ROYAL -> Sprites.ADULT_ROYAL
            AdultForm.SPRITE -> Sprites.ADULT_SPRITE
            AdultForm.SCRAPPY -> Sprites.ADULT_SCRAPPY
            AdultForm.GLOOM -> Sprites.ADULT_GLOOM
        }
        Stage.ELDER -> Sprites.ELDER
    }

    private fun drawPetScreen(canvas: Canvas) {
        val pet = engine.pet
        val now = SystemClock.uptimeMillis()

        // header
        label(canvas, "${pet.name} · ${pet.stage.label} · G${pet.generation}", 24f, 34f, 16f, LCD)
        label(canvas, clock.format(Date()), 616f, 34f, 16f, DIM, right = true)

        // attention bell (blinks; color by need)
        pet.activeNeed?.let { need ->
            if ((now / 400) % 2 == 0L) {
                px.color = if (need == Need.SICK) ALERT else GOLD
                grid(canvas, Sprites.ICON_BELL, 500f, 16f, 3f)
                px.color = LCD
            }
        }

        // hearts
        label(canvas, "FOOD", 24f, 66f, 11f, DIM)
        hearts(canvas, ceil(pet.hunger.toDouble()).toInt(), 76f, 54f)
        label(canvas, "MOOD", 24f, 96f, 11f, DIM)
        hearts(canvas, ceil(pet.happy.toDouble()).toInt(), 76f, 84f)
        label(canvas, "POWER", 24f, 126f, 11f, DIM)
        hearts(canvas, ceil(pet.energy.toDouble()).toInt(), 76f, 114f)

        // the pet, floating in the room
        val cell = 16f
        val parallax = if (store.parallax) (yawTracker?.parallaxPx(70f) ?: 0f) else 0f
        val bounce = if (!pet.asleep && pet.happy > 1f) (sin(now / 280.0) * 4).toFloat() else 0f
        val frame = stageFrames()[((now / 550) % 2).toInt()]
        val petLeft = 320f - (14 * cell) / 2f + parallax
        val petTop = 150f + bounce
        px.color = if (pet.asleep && pet.lightsOff) DIM else LCD
        val animAge = engine.animAgeMs()
        when {
            engine.anim == Anim.HATCH && animAge < 1200 -> {
                if ((now / 120) % 2 == 0L) grid(canvas, frame, petLeft, petTop, cell)
            }
            engine.anim == Anim.EVOLVE && animAge < 1600 -> {
                if ((now / 100) % 3 != 0L) grid(canvas, frame, petLeft, petTop, cell)
                label(canvas, "✦ EVOLVED ✦", 320f, 140f, 16f, GOLD, center = true)
            }
            else -> grid(canvas, frame, petLeft, petTop, cell)
        }
        px.color = LCD

        // sleep, sickness, mess
        if (pet.asleep) grid(canvas, Sprites.ZZZ, petLeft + 15 * cell, petTop - 10f, 4f)
        if (pet.sick) {
            px.color = ALERT
            grid(canvas, Sprites.SKULL, petLeft - 44f, petTop + 20f, 4f)
            px.color = LCD
        }
        for (i in 0 until pet.poops.coerceAtMost(4)) {
            px.color = DIM
            grid(canvas, Sprites.POOP[((now / 500) % 2).toInt()], 500f - i * 44f, 300f, 5f)
            px.color = LCD
        }

        // animation garnish
        when (engine.anim) {
            Anim.EAT -> if (animAge < 900) {
                grid(canvas, if (engine.feedIndex == 0) Sprites.FOOD_MEAL else Sprites.FOOD_SNACK,
                    petLeft - 56f, petTop + 60f - animAge / 25f, 5f)
            }
            Anim.CLEAN -> if (animAge < 700) {
                px.color = WHITE
                canvas.drawRect((560f - animAge / 1.4f) * u, 290f * u, (600f - animAge / 1.4f) * u, 350f * u, px)
                px.color = LCD
            }
            Anim.HAPPY -> if (animAge < 1200) {
                px.color = GOLD
                grid(canvas, Sprites.HEART, petLeft + 40f, petTop - 26f, 4f)
                grid(canvas, Sprites.HEART, petLeft + 130f, petTop - 34f, 3f)
                px.color = LCD
            }
            Anim.HEAL -> if (animAge < 1000) label(canvas, "+", petLeft + 220f, petTop + 40f, 30f, WHITE)
            Anim.REFUSE -> if (animAge < 800) label(canvas, "...", petLeft + 15 * cell, petTop + 60f, 20f, DIM)
            else -> Unit
        }

        // egg coaching (the only stage with a hint)
        if (pet.stage == Stage.EGG) {
            label(canvas, "keep watching the egg...", 320f, 340f, 13f, DIM, center = true)
        }

        // walk meter + lights state
        label(canvas, "WALK ${pet.stepsToday}/${com.tapogotchi.engine.Pet.DAILY_STEP_GOAL}" +
            if (pet.dailyGoalHit) " ✦" else "", 24f, 372f, 12f, if (pet.dailyGoalHit) GOLD else DIM)
        if (pet.lightsOff) label(canvas, "lights off", 616f, 372f, 12f, DIM, right = true)

        drawIconBar(canvas)
    }

    private fun iconGrid(action: IconAction): Array<String> = when (action) {
        IconAction.FEED -> Sprites.ICON_FEED
        IconAction.PLAY -> Sprites.ICON_PLAY
        IconAction.CLEAN -> Sprites.ICON_CLEAN
        IconAction.LIGHTS -> Sprites.ICON_LIGHTS
        IconAction.MEDICINE -> Sprites.ICON_MEDICINE
        IconAction.DISCIPLINE -> Sprites.ICON_DISCIPLINE
        IconAction.STATS -> Sprites.ICON_STATS
        IconAction.SETTINGS -> Sprites.ICON_SETTINGS
    }

    private fun drawIconBar(canvas: Canvas) {
        val actions = IconAction.entries
        val step = 74f
        val start = 320f - step * (actions.size - 1) / 2f
        // selected label above the bar
        label(canvas, actions[engine.iconIndex].label, 320f, 408f, 13f, LCD, center = true)
        for ((i, a) in actions.withIndex()) {
            val cx = start + i * step
            val selected = i == engine.iconIndex
            px.color = if (selected) LCD else DIM
            grid(canvas, iconGrid(a), cx - 13f, 424f, 3f)
            if (selected) {
                px.color = LCD
                canvas.drawRect((cx - 20f) * u, 420f * u, (cx + 20f) * u, 422f * u, px)
                canvas.drawRect((cx - 20f) * u, 456f * u, (cx + 20f) * u, 458f * u, px)
            }
        }
        px.color = LCD
    }

    // ------------------------------------------------------------------
    //  sub-screens
    // ------------------------------------------------------------------

    private fun drawFeed(canvas: Canvas) {
        label(canvas, "FEED ${engine.pet.name}", 320f, 60f, 20f, LCD, center = true)
        val rows = listOf("MEAL  (fills a food heart)", "SNACK (joy now, weight later)")
        for ((i, r) in rows.withIndex()) {
            val y = 160f + i * 90f
            val selected = i == engine.feedIndex
            grid(canvas, if (i == 0) Sprites.FOOD_MEAL else Sprites.FOOD_SNACK, 140f, y - 24f, 5f)
            label(canvas, (if (selected) "▶ " else "  ") + r, 200f, y, 16f, if (selected) LCD else DIM)
        }
        label(canvas, "swipe = choose · tap = feed · double-tap = back", 320f, 400f, 12f, DIM, center = true)
    }

    private fun drawStats(canvas: Canvas) {
        val pet = engine.pet
        label(canvas, "${pet.name} — THE CHRONICLE", 320f, 48f, 18f, LCD, center = true)
        var y = 92f
        fun row(k: String, v: String) {
            label(canvas, k, 60f, y, 14f, DIM)
            text.textAlign = Paint.Align.RIGHT
            label(canvas, v, 580f, y, 14f, LCD)
            text.textAlign = Paint.Align.LEFT
            y += 30f
        }
        row("Age", "${pet.ageDays} days")
        row("Stage", pet.stage.label + if (pet.stage >= Stage.ADULT) " (${pet.form.label})" else "")
        row("Weight", "${pet.weight}" + if (pet.overweight) "  (roly-poly!)" else "")
        row("Energy", "●".repeat(ceil(pet.energy.toDouble()).toInt()).padEnd(4, '○'))
        row("Discipline", "●".repeat(ceil(pet.discipline.toDouble()).toInt()).padEnd(4, '○'))
        row("Care", "✦".repeat((pet.careScore / 20f).toInt().coerceIn(0, 5)).ifEmpty { "—" })
        row("Steps today", "${pet.stepsToday}")
        row("Meals · snacks", "${pet.totalMeals} · ${pet.totalSnacks}")
        row("Dance-offs won", "${pet.gamesWon}/${pet.gamesPlayed}")
        row("Calls missed", "${pet.missedCalls}")
        val lin = engine.lineage()
        if (lin.isNotEmpty()) {
            label(canvas, "ancestors: " + lin.takeLast(2).joinToString("  "), 320f, y + 8f, 11f, DIM, center = true)
        }
        label(canvas, "tap or double-tap = back", 320f, 452f, 12f, DIM, center = true)
    }

    private fun drawSettings(canvas: Canvas) {
        label(canvas, "SETTINGS", 320f, 48f, 18f, LCD, center = true)
        for ((i, row) in engine.settingsRows().withIndex()) {
            val y = 96f + i * 34f
            val selected = i == engine.settingsIndex
            val color = if (selected) LCD else DIM
            label(canvas, (if (selected) "▶ " else "  ") + row.first, 90f, y, 14f, color)
            text.textAlign = Paint.Align.RIGHT
            label(canvas, row.second, 550f, y, 14f, color)
            text.textAlign = Paint.Align.LEFT
        }
        label(canvas, "swipe ⇅ = row · swipe ⇄ / tap = change · double-tap = back", 320f, 430f, 11f, DIM, center = true)
    }

    private fun drawGame(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        label(canvas, "DANCE-OFF  ${engine.gameRound.coerceAtMost(5)}/5", 320f, 52f, 18f, LCD, center = true)
        label(canvas, "score ${engine.gameScore}", 320f, 80f, 14f, DIM, center = true)
        val frame = stageFrames()[((now / 300) % 2).toInt()]
        val showingCue = engine.gameRound <= 5 && engine.gameGuess < 0 && now < engine.gameCueUntilMs
        val showingResult = engine.gameGuess >= 0
        val offset = when {
            (showingCue || showingResult) && engine.gameActual == 2 -> -90f
            (showingCue || showingResult) && engine.gameActual == 3 -> 90f
            else -> 0f
        }
        grid(canvas, frame, 320f - 112f + offset, 160f, 16f)
        if (engine.gameRound <= 5) {
            when {
                showingCue -> label(canvas, "WATCH ${engine.pet.name}'S HOP", 320f, 400f, 15f, GOLD, center = true)
                engine.gameGuess < 0 && now < engine.gameInputAtMs ->
                    label(canvas, "...remember...", 320f, 400f, 15f, DIM, center = true)
                engine.gameGuess < 0 ->
                    label(canvas, "COPY IT — SWIPE LEFT OR RIGHT", 320f, 400f, 15f, LCD, center = true)
                engine.gameGuess == 0 ->
                    label(canvas, "TOO SLOW — ${if (engine.gameActual == 2) "LEFT" else "RIGHT"}", 320f, 400f, 15f, ALERT, center = true)
                engine.gameGuess == engine.gameActual ->
                    label(canvas, "MATCH!", 320f, 400f, 17f, GOLD, center = true)
                else -> label(canvas,
                    "MISS — ${if (engine.gameActual == 2) "LEFT" else "RIGHT"}",
                    320f, 400f, 15f, ALERT, center = true)
            }
        } else {
            label(canvas, if (engine.gameScore >= 3) "✦ ${engine.pet.name} WINS ✦" else "better luck on the road...",
                320f, 400f, 16f, if (engine.gameScore >= 3) GOLD else DIM, center = true)
        }
    }

    private fun drawReport(canvas: Canvas) {
        label(canvas, "WHILE YOU WERE AWAY", 320f, 60f, 18f, LCD, center = true)
        val lines = engine.report?.lines ?: emptyList()
        for ((i, l) in lines.withIndex()) {
            label(canvas, "· $l", 90f, 110f + i * 32f, 14f, if (l.contains("didn't")) ALERT else LCD)
        }
        if (lines.isEmpty()) label(canvas, "· all was calm", 90f, 110f, 14f, DIM)
        label(canvas, "tap = continue", 320f, 430f, 12f, DIM, center = true)
    }

    private fun drawGrave(canvas: Canvas) {
        val pet = engine.pet
        val now = SystemClock.uptimeMillis()
        if (pet.passedPeacefully) {
            label(canvas, "✦ A LONG LIFE, WELL KEPT ✦", 320f, 70f, 18f, GOLD, center = true)
        } else {
            label(canvas, "THE LITTLE LIGHT WENT OUT", 320f, 70f, 18f, ALERT, center = true)
        }
        px.color = DIM
        grid(canvas, Sprites.GRAVE, 320f - 112f, 140f, 16f)
        px.color = LCD
        // the ghost drifts
        val gx = 320f + (sin(now / 900.0) * 60).toFloat()
        px.color = WHITE
        grid(canvas, Sprites.GHOST, gx, 110f + (sin(now / 1300.0) * 10).toFloat(), 6f)
        px.color = LCD
        label(canvas, "${pet.name} · generation ${pet.generation} · ${pet.ageDays} days", 320f, 344f, 14f, LCD, center = true)
        val lin = engine.lineage()
        if (lin.isNotEmpty()) {
            label(canvas, lin.takeLast(3).joinToString("   "), 320f, 372f, 11f, DIM, center = true)
        }
        label(
            canvas,
            if (engine.graveArmed) "tap AGAIN to hatch generation ${pet.generation + 1}"
            else "tap twice to hatch a new egg",
            320f, 424f, 14f, if (engine.graveArmed) GOLD else DIM, center = true
        )
    }
}
