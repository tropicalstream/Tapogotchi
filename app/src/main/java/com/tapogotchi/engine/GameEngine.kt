package com.tapogotchi.engine

import android.os.SystemClock
import com.tapogotchi.SettingsStore
import com.tapogotchi.audio.Audio
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** MainActivity implements this so the engine stays Android-light. */
interface Host {
    fun sound(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun applySettings()
}

enum class ScreenId { PET, FEED, STATS, SETTINGS, GAME, REPORT, GRAVE }
enum class IconAction(val label: String) {
    FEED("FEED"), PLAY("PLAY"), CLEAN("CLEAN"), LIGHTS("LIGHTS"),
    MEDICINE("MEDICINE"), DISCIPLINE("SCOLD"), STATS("STATS"), SETTINGS("SETTINGS")
}
enum class Anim { NONE, EAT, CLEAN, HAPPY, EVOLVE, HEAL, REFUSE, HATCH }

/**
 * Screens, input routing, the dance mini-game, walks, and the save cadence
 * (suite convention: persist after every interaction + on pause).
 */
class GameEngine(
    private val store: SettingsStore,
    private val host: Host
) {

    var pet: Pet = Pet.fromJson(store.petJson) ?: Pet()
        private set

    var screen = ScreenId.PET
        private set
    var iconIndex = 0
    var feedIndex = 0            // 0 meal · 1 snack
    var settingsIndex = 0
    var resetArmed = false       // confirm-twice for Reset Settings
    var graveArmed = false       // confirm-twice for the next egg
    var report: AwayReport? = null

    // animation channel the renderer reads
    var anim = Anim.NONE
        private set
    var animStartMs = 0L
        private set

    // dance-off mini-game
    var gameRound = 0
    var gameScore = 0
    var gameGuess = -1           // 2 = left, 3 = right
    var gameActual = -1
    var gameCueUntilMs = 0L
    var gameInputAtMs = 0L
    var gameInputDeadlineMs = 0L
    var gameRevealAtMs = 0L
    var gameDoneAtMs = 0L
    private val rng = Random(System.nanoTime())

    private var lastChirpMs = 0L
    private var lastPeriodicSaveMs = 0L
    private var deathSeen = !pet.alive

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Away catch-up already narrated these; don't replay them as live FX. */
    private fun clearEventFlags() {
        pet.justEvolved = false
        pet.justPooped = false
        pet.justGotSick = false
        pet.justCalled = false
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    fun boot() {
        val away = AwayReport()
        pet.simulateTo(System.currentTimeMillis(), live = false, report = away)
        clearEventFlags()
        save()
        when {
            !pet.alive && !deathSeen -> {
                recordLineage()
                deathSeen = true
                screen = ScreenId.GRAVE
                host.sound(Audio.DEATH, 1f, 0.8f)
            }
            !pet.alive -> screen = ScreenId.GRAVE
            away.any -> {
                report = away
                screen = ScreenId.REPORT
            }
        }
    }

    fun onAppPause() {
        save()
    }

    fun update(dt: Float) {
        val nowWall = System.currentTimeMillis()
        val now = SystemClock.uptimeMillis()
        pet.simulateTo(nowWall, live = true)

        // one-frame sim events -> sound & animation
        if (pet.justEvolved) {
            pet.justEvolved = false
            if (pet.stage == Stage.BABY) {
                host.sound(Audio.HATCH)
                startAnim(Anim.HATCH)
            } else {
                host.sound(Audio.EVOLVE)
                startAnim(Anim.EVOLVE)
            }
            save()
        }
        if (pet.justPooped) {
            pet.justPooped = false
            host.sound(Audio.POOP, 1f, 0.7f)
        }
        if (pet.justGotSick) {
            pet.justGotSick = false
            host.sound(Audio.CALL_SICK)
        }
        if (pet.justCalled) {
            pet.justCalled = false
            chirp()
            lastChirpMs = now
        }
        // the attention call renews until answered or given up on
        if (pet.alive && pet.activeNeed != null && !pet.asleep && now - lastChirpMs > 45_000L) {
            chirp()
            lastChirpMs = now
        }
        // a death observed live
        if (!pet.alive && !deathSeen) {
            deathSeen = true
            recordLineage()
            host.sound(Audio.DEATH, 1f, 0.9f)
            screen = ScreenId.GRAVE
            save()
        }
        // dance-off reveal timing
        if (screen == ScreenId.GAME) updateGame(now)
        // gentle autosave (a pet is state; never lose an hour of care)
        if (now - lastPeriodicSaveMs > 30_000L) {
            lastPeriodicSaveMs = now
            save()
        }
    }

    private fun chirp() {
        when (pet.activeNeed) {
            Need.HUNGRY -> host.sound(Audio.CALL_HUNGRY)
            Need.UNHAPPY -> host.sound(Audio.CALL_UNHAPPY)
            Need.SICK -> host.sound(Audio.CALL_SICK)
            Need.MESS -> host.sound(Audio.CALL_MESS)
            Need.FALSE_ALARM -> host.sound(Audio.CALL_FALSE)
            null -> Unit
        }
    }

    private fun startAnim(a: Anim) {
        anim = a
        animStartMs = SystemClock.uptimeMillis()
    }

    fun animAgeMs(): Long = SystemClock.uptimeMillis() - animStartMs

    fun save() {
        store.petJson = pet.toJson()
    }

    // ------------------------------------------------------------------
    //  Input (suite conventions: one swipe = one step, tap = select,
    //  double-tap = back)
    // ------------------------------------------------------------------

    /** 0 up · 1 down · 2 left · 3 right */
    fun swipeDir(dir: Int) {
        when (screen) {
            ScreenId.PET -> {
                val n = IconAction.entries.size
                when (dir) {
                    2 -> iconIndex = (iconIndex + n - 1) % n
                    3 -> iconIndex = (iconIndex + 1) % n
                    else -> return
                }
                host.sound(Audio.SELECT, 1.2f, 0.4f)
            }
            ScreenId.FEED -> {
                feedIndex = 1 - feedIndex
                host.sound(Audio.SELECT, 1.2f, 0.4f)
            }
            ScreenId.SETTINGS -> when (dir) {
                0 -> { settingsIndex = (settingsIndex + settingsRows().size - 1) % settingsRows().size; resetArmed = false; host.sound(Audio.SELECT, 1.2f, 0.4f) }
                1 -> { settingsIndex = (settingsIndex + 1) % settingsRows().size; resetArmed = false; host.sound(Audio.SELECT, 1.2f, 0.4f) }
                2 -> adjustSetting(-1)
                3 -> adjustSetting(+1)
            }
            ScreenId.GAME -> {
                val now = SystemClock.uptimeMillis()
                if (gameGuess < 0 && gameRound in 1..5 && now >= gameInputAtMs &&
                    now < gameInputDeadlineMs && (dir == 2 || dir == 3)
                ) {
                    gameGuess = dir
                    gameRevealAtMs = now + GAME_RESULT_MS
                    host.sound(Audio.SELECT, 1f, 0.5f)
                }
            }
            else -> Unit
        }
    }

    fun click() {
        when (screen) {
            ScreenId.PET -> activateIcon()
            ScreenId.FEED -> {
                val ok = if (feedIndex == 0) pet.feedMeal() else pet.feedSnack()
                if (ok) {
                    host.sound(if (feedIndex == 0) Audio.EAT else Audio.SNACK)
                    startAnim(Anim.EAT)
                } else {
                    host.sound(Audio.SAD, 1f, 0.6f)
                    startAnim(Anim.REFUSE)
                }
                screen = ScreenId.PET
                save()
            }
            ScreenId.STATS -> {
                screen = ScreenId.PET
                host.sound(Audio.BACK, 1f, 0.5f)
            }
            ScreenId.SETTINGS -> adjustSetting(+1)
            ScreenId.GAME -> if (gameRound > 5) endGame()
            ScreenId.REPORT -> {
                report = null
                screen = if (pet.alive) ScreenId.PET else ScreenId.GRAVE
                host.sound(Audio.SELECT, 1f, 0.5f)
            }
            ScreenId.GRAVE -> {
                // confirm-twice: a new life should never start by accident
                if (!graveArmed) {
                    graveArmed = true
                    host.sound(Audio.SELECT, 0.9f, 0.5f)
                } else {
                    newEgg()
                }
            }
        }
    }

    fun doubleTap() {
        when (screen) {
            ScreenId.PET -> host.sound(Audio.BACK, 0.8f, 0.3f)   // nothing to leave
            // Leaving early gives no mood/weight reward. Energy was already
            // spent on entry, closing the old abort-to-farm exploit.
            ScreenId.GAME -> endGameEarly()
            ScreenId.GRAVE -> graveArmed = false
            else -> {
                screen = ScreenId.PET
                resetArmed = false
                host.sound(Audio.BACK, 1f, 0.5f)
            }
        }
    }

    fun onBack(): Boolean {
        if (screen != ScreenId.PET) {
            doubleTap()
            return true
        }
        return false
    }

    private fun activateIcon() {
        if (!pet.alive) return
        when (IconAction.entries[iconIndex]) {
            IconAction.FEED -> {
                if (pet.stage == Stage.EGG || pet.asleep) refuse() else {
                    feedIndex = 0
                    screen = ScreenId.FEED
                    host.sound(Audio.SELECT)
                }
            }
            IconAction.PLAY -> {
                if (!pet.beginPlay()) refuse() else {
                    startGame()
                    save()
                }
            }
            IconAction.CLEAN -> {
                if (pet.clean()) {
                    host.sound(Audio.CLEAN)
                    startAnim(Anim.CLEAN)
                } else refuse()
                save()
            }
            IconAction.LIGHTS -> {
                pet.toggleLights()
                host.sound(if (pet.lightsOff) Audio.SLEEP else Audio.WAKE, 1f, 0.6f)
                save()
            }
            IconAction.MEDICINE -> {
                if (pet.medicine()) {
                    host.sound(Audio.MEDICINE)
                    startAnim(Anim.HEAL)
                } else refuse()
                save()
            }
            IconAction.DISCIPLINE -> {
                if (pet.stage == Stage.EGG) { refuse(); return }
                if (pet.disciplineNow()) {
                    host.sound(Audio.DISCIPLINE)
                } else {
                    host.sound(Audio.SAD, 0.9f, 0.6f)
                    startAnim(Anim.REFUSE)
                }
                save()
            }
            IconAction.STATS -> {
                screen = ScreenId.STATS
                host.sound(Audio.SELECT)
            }
            IconAction.SETTINGS -> {
                settingsIndex = 0
                resetArmed = false
                screen = ScreenId.SETTINGS
                host.sound(Audio.SELECT)
            }
        }
    }

    private fun refuse() {
        host.sound(Audio.SAD, 1.1f, 0.5f)
        startAnim(Anim.REFUSE)
    }

    // ------------------------------------------------------------------
    //  Dance-off (5 rounds; guess which way the little one hops)
    // ------------------------------------------------------------------

    private fun startGame() {
        gameRound = 1
        gameScore = 0
        screen = ScreenId.GAME
        host.sound(Audio.HAPPY)
        prepareDanceRound(SystemClock.uptimeMillis())
    }

    private fun updateGame(now: Long) {
        if (gameRound in 1..5 && gameGuess < 0 && now >= gameInputDeadlineMs) {
            gameGuess = 0 // explicit timeout/miss sentinel
            gameRevealAtMs = now + GAME_RESULT_MS
        }
        if (gameGuess >= 0 && gameActual >= 0 && now >= gameRevealAtMs && gameRound in 1..5) {
            if (gameGuess == gameActual) {
                gameScore++
                host.sound(Audio.WIN, 1.4f, 0.5f)
            } else {
                host.sound(Audio.LOSE, 1.4f, 0.4f)
            }
            gameRound++
            if (gameRound > 5) {
                val won = gameScore >= 3
                pet.playResult(won)
                host.sound(if (won) Audio.WIN else Audio.LOSE)
                gameDoneAtMs = now + 2_600L
                save()
            } else prepareDanceRound(now)
        }
        if (gameRound > 5 && now >= gameDoneAtMs) endGame()
    }

    private fun endGame() {
        screen = ScreenId.PET
        startAnim(if (gameScore >= 3) Anim.HAPPY else Anim.NONE)
    }

    private fun endGameEarly() {
        screen = ScreenId.PET
        host.sound(Audio.BACK, 1f, 0.5f)
        save()
    }

    /** Each round previews a hop, briefly hides it, then asks the player to copy. */
    private fun prepareDanceRound(now: Long) {
        gameGuess = -1
        gameActual = if (rng.nextBoolean()) 2 else 3
        val cueMs = (GAME_CUE_START_MS - (gameRound - 1) * GAME_CUE_STEP_MS)
            .coerceAtLeast(GAME_CUE_MIN_MS)
        gameCueUntilMs = now + cueMs
        gameInputAtMs = gameCueUntilMs + GAME_MEMORY_GAP_MS
        gameInputDeadlineMs = gameInputAtMs + GAME_INPUT_WINDOW_MS
        gameRevealAtMs = 0L
    }

    // ------------------------------------------------------------------
    //  Walks (the X3 exclusive: your steps are its steps)
    // ------------------------------------------------------------------

    private var lastWalkHundred = 0L

    fun onSteps(delta: Long) {
        if (!pet.alive) return
        val goal = pet.onSteps(delta, todayKey())
        val hundreds = pet.stepsToday / 100
        if (hundreds != lastWalkHundred) {
            lastWalkHundred = hundreds
            if (screen == ScreenId.PET) host.sound(Audio.WALK_TICK, 1f, 0.5f)
        }
        if (goal) {
            host.sound(Audio.WIN)
            startAnim(Anim.HAPPY)
            save()
        }
    }

    // ------------------------------------------------------------------
    //  Lineage & the next egg
    // ------------------------------------------------------------------

    private fun recordLineage() {
        runCatching {
            val arr = JSONArray(store.lineageJson)
            arr.put(
                JSONObject()
                    .put("gen", pet.generation)
                    .put("name", pet.name)
                    .put("form", if (pet.stage >= Stage.ADULT) pet.form.label else pet.stage.label)
                    .put("days", pet.ageDays)
                    .put("peaceful", pet.passedPeacefully)
            )
            store.lineageJson = arr.toString()
        }
    }

    fun lineage(): List<String> = runCatching {
        val arr = JSONArray(store.lineageJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            "G${o.optInt("gen")} ${o.optString("name")} · ${o.optString("form")} · ${o.optInt("days")}d" +
                if (o.optBoolean("peaceful")) " ✦" else ""
        }
    }.getOrDefault(emptyList())

    private fun newEgg() {
        val nextGen = pet.generation + 1
        pet = Pet().apply {
            generation = nextGen
            // lineage wisdom: the third-and-later keepers start slightly ahead
            if (nextGen >= 3) careScore = 55f
        }
        deathSeen = false
        graveArmed = false
        screen = ScreenId.PET
        iconIndex = 0
        host.sound(Audio.HATCH)
        save()
    }

    // ------------------------------------------------------------------
    //  Settings (Reset Settings LAST, confirm-twice — suite convention)
    // ------------------------------------------------------------------

    fun settingsRows(): List<Pair<String, String>> = listOf(
        "Sound" to "${store.soundVolume}",
        "Swipe feel" to String.format(Locale.US, "%.1f", store.swipeSens),
        "Safe tap" to if (store.safeTap) "ON" else "OFF",
        "Room drift (parallax)" to if (store.parallax) "ON" else "OFF",
        "30 fps cap" to if (store.frameCap30) "ON" else "OFF",
        "Flip swipe ⇄" to if (store.flipHorizontal) "ON" else "OFF",
        "Flip swipe ⇅" to if (store.flipVertical) "ON" else "OFF",
        "Reset settings" to if (resetArmed) "TAP AGAIN" else "—"
    )

    private fun adjustSetting(dir: Int) {
        when (settingsIndex) {
            0 -> store.soundVolume += dir
            1 -> store.swipeSens += dir * 0.2f
            2 -> store.safeTap = !store.safeTap
            3 -> store.parallax = !store.parallax
            4 -> store.frameCap30 = !store.frameCap30
            5 -> store.flipHorizontal = !store.flipHorizontal
            6 -> store.flipVertical = !store.flipVertical
            7 -> {
                if (resetArmed) {
                    store.resetSettings()
                    resetArmed = false
                } else resetArmed = true
                host.applySettings()
                host.sound(Audio.SELECT)
                return
            }
        }
        host.applySettings()
        host.sound(Audio.SELECT, 1.1f, 0.5f)
    }

    companion object {
        private const val GAME_CUE_START_MS = 950L
        private const val GAME_CUE_STEP_MS = 90L
        private const val GAME_CUE_MIN_MS = 590L
        private const val GAME_MEMORY_GAP_MS = 260L
        private const val GAME_INPUT_WINDOW_MS = 3_200L
        private const val GAME_RESULT_MS = 700L
    }
}
