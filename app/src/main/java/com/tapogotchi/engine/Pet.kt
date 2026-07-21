package com.tapogotchi.engine

import org.json.JSONObject
import java.util.Calendar
import kotlin.math.min
import kotlin.random.Random

enum class Stage(val label: String) {
    EGG("EGG"), BABY("BABY"), CHILD("CHILD"), TEEN("TEEN"), ADULT("ADULT"), ELDER("ELDER")
}
enum class AdultForm(val label: String) {
    ROYAL("ROYAL"), SPRITE("SPRITE"), SCRAPPY("SCRAPPY"), GLOOM("GLOOM")
}
enum class Need { HUNGRY, UNHAPPY, SICK, MESS, FALSE_ALARM }

/** What happened while the glasses were off — read to the keeper on return. */
class AwayReport {
    val lines = ArrayList<String>()
    fun add(s: String) { if (lines.size < 8 && !lines.contains(s)) lines.add(s) }
    val any get() = lines.isNotEmpty()
}

/**
 * The pet — a continuous real-time life. Time passes whether or not the
 * app is open: on every launch the elapsed wall-clock is simulated in
 * one-minute steps (capped at a week), and the differences become the
 * "while you were away" report. Classic rules, honestly enforced:
 * hunger, happiness, energy, mess, sickness, discipline, weight, sleep by
 * the real clock, evolution shaped by the care you actually gave.
 */
class Pet {

    companion object {
        val NAMES = listOf("PIP", "MIMO", "TARO", "LUNE", "KOBI", "ZUZU", "NIMB", "ECHO")

        // Stage thresholds, cumulative age in ms.
        const val EGG_END = 30 * 60_000L                  // hatches sooner if watched
        const val BABY_END = EGG_END + 24 * 3600_000L
        const val CHILD_END = BABY_END + 48 * 3600_000L
        const val TEEN_END = CHILD_END + 72 * 3600_000L
        const val ADULT_END = TEEN_END + 168 * 3600_000L
        const val ELDER_END = ADULT_END + 96 * 3600_000L  // then a peaceful passing

        const val CALL_TIMEOUT_MIN = 30
        const val SICK_DEATH_MIN = 480
        const val MISERY_DEATH_MIN = 720
        const val MAX_CATCHUP_MIN = 7 * 24 * 60
        const val DAILY_STEP_GOAL = 1500L
        const val MIN_PLAY_ENERGY = 1f
        const val PLAY_ENERGY_COST = 0.65f

        fun fromJson(raw: String?): Pet? {
            raw ?: return null
            return runCatching {
                val o = JSONObject(raw)
                Pet().apply {
                    generation = o.optInt("generation", 1)
                    bornMs = o.optLong("bornMs", System.currentTimeMillis())
                    lastSimMs = o.optLong("lastSimMs", bornMs)
                    stage = runCatching { Stage.valueOf(o.optString("stage", "EGG")) }
                        .getOrDefault(Stage.EGG)
                    form = runCatching { AdultForm.valueOf(o.optString("form", "SCRAPPY")) }
                        .getOrDefault(AdultForm.SCRAPPY)
                    hunger = o.optDouble("hunger", 4.0).toFloat().coerceIn(0f, 4f)
                    happy = o.optDouble("happy", 4.0).toFloat().coerceIn(0f, 4f)
                    energy = o.optDouble("energy", 4.0).toFloat().coerceIn(0f, 4f)
                    discipline = o.optDouble("discipline", 0.0).toFloat().coerceIn(0f, 4f)
                    weight = o.optInt("weight", 5).coerceIn(1, 15)
                    poops = o.optInt("poops", 0).coerceIn(0, 4)
                    sick = o.optBoolean("sick", false)
                    sickMin = o.optInt("sickMin", 0)
                    miseryMin = o.optInt("miseryMin", 0)
                    hungerZeroMin = o.optInt("hungerZeroMin", 0)
                    asleep = o.optBoolean("asleep", false)
                    lightsOff = o.optBoolean("lightsOff", false)
                    alive = o.optBoolean("alive", true)
                    passedPeacefully = o.optBoolean("passed", false)
                    careScore = o.optDouble("careScore", 50.0).toFloat().coerceIn(0f, 100f)
                    missedCalls = o.optInt("missedCalls", 0)
                    activeNeed = o.optString("activeNeed", "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { saved -> runCatching { Need.valueOf(saved) }.getOrNull() }
                    needSinceMin = o.optInt("needSinceMin", 0)
                    callCooldownMin = o.optInt("callCooldownMin", 0)
                    poopTimerMin = o.optInt("poopTimerMin", 0)
                    eggWatchMs = o.optLong("eggWatchMs", 0)
                    stepsToday = o.optLong("stepsToday", 0)
                    stepDayKey = o.optString("stepDayKey", "")
                    dailyGoalHit = o.optBoolean("dailyGoalHit", false)
                    totalMeals = o.optInt("totalMeals", 0)
                    totalSnacks = o.optInt("totalSnacks", 0)
                    gamesWon = o.optInt("gamesWon", 0)
                    gamesPlayed = o.optInt("gamesPlayed", 0)
                }
            }.getOrNull()
        }
    }

    var generation = 1
    var bornMs = System.currentTimeMillis()
    var lastSimMs = bornMs
    var stage = Stage.EGG
    var form = AdultForm.SCRAPPY

    // 0..4 hearts each
    var hunger = 4f
    var happy = 4f
    var energy = 4f
    var discipline = 0f

    var weight = 5
    var poops = 0
    var sick = false
    var sickMin = 0
    var miseryMin = 0
    var hungerZeroMin = 0
    var asleep = false
    var lightsOff = false
    var alive = true
    var passedPeacefully = false
    var careScore = 50f          // 0..100, the evolution judge
    var missedCalls = 0

    var activeNeed: Need? = null
    var needSinceMin = 0
    private var callCooldownMin = 0
    private var poopTimerMin = 0
    private var eggWatchMs = 0L

    var stepsToday = 0L
    var stepDayKey = ""
    var dailyGoalHit = false

    var totalMeals = 0
    var totalSnacks = 0
    var gamesWon = 0
    var gamesPlayed = 0

    private val rng = Random(System.nanoTime())

    val name: String get() = NAMES[(generation - 1) % NAMES.size]
    val ageMs: Long get() = System.currentTimeMillis() - bornMs
    val ageDays: Int get() = (ageMs / 86_400_000L).toInt()
    val overweight: Boolean get() = weight >= 12

    // ------------------------------------------------------------------
    //  Time
    // ------------------------------------------------------------------

    private fun sleepWindow(): Pair<Int, Int>? = when (stage) {
        Stage.EGG -> null
        Stage.BABY -> 20 to 8
        Stage.CHILD -> 21 to 8
        Stage.TEEN -> 22 to 8
        Stage.ADULT -> 23 to 7
        Stage.ELDER -> 21 to 7
    }

    private fun shouldSleep(atMs: Long): Boolean {
        val w = sleepWindow() ?: return false
        val cal = Calendar.getInstance().apply { timeInMillis = atMs }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val (bed, wake) = w
        return if (bed > wake) h >= bed || h < wake else h in bed until wake
    }

    /**
     * Advance the life to `nowMs`. `live` = the app is open (egg watching,
     * immediate chirps); pass a report to collect away happenings.
     */
    fun simulateTo(nowMs: Long, live: Boolean, report: AwayReport? = null) {
        if (!alive) { lastSimMs = nowMs; return }
        // A manual clock correction must not freeze the pet until wall time
        // catches back up to a stale future timestamp.
        if (nowMs < lastSimMs) {
            lastSimMs = nowMs
            return
        }
        var minutes = ((nowMs - lastSimMs) / 60_000L).toInt()
        if (minutes <= 0) return
        if (minutes > MAX_CATCHUP_MIN) minutes = MAX_CATCHUP_MIN
        var t = nowMs - minutes * 60_000L
        if (live && stage == Stage.EGG) eggWatchMs += nowMs - lastSimMs
        repeat(minutes) {
            t += 60_000L
            stepMinute(t, report)
            if (!alive) {
                lastSimMs = nowMs
                return
            }
        }
        lastSimMs = nowMs
    }

    private fun stepMinute(atMs: Long, report: AwayReport?) {
        // ---- stage growth (age is wall-clock; the egg also wants watching) ----
        val age = atMs - bornMs
        val grownStage = when {
            age < EGG_END && eggWatchMs < 5 * 60_000L -> Stage.EGG
            age < BABY_END -> Stage.BABY
            age < CHILD_END -> Stage.CHILD
            age < TEEN_END -> Stage.TEEN
            age < ADULT_END -> Stage.ADULT
            else -> Stage.ELDER
        }
        if (grownStage != stage) {
            val was = stage
            stage = grownStage
            // Catch-up is capped to seven days. A much longer absence can jump
            // directly across ADULT, so judge the adult form on any crossing.
            if (was < Stage.ADULT && stage >= Stage.ADULT) form = judgeForm()
            justEvolved = true
            report?.add("${name} grew: ${was.label} → ${stage.label}")
        }
        if (stage == Stage.EGG) return   // eggs only wobble

        if (age >= ELDER_END) {
            alive = false
            passedPeacefully = true
            report?.add("$name's long life ended peacefully at ${ageDays} days")
            return
        }

        // ---- sleep by the real clock ----
        val wantSleep = shouldSleep(atMs)
        if (wantSleep && !asleep) {
            asleep = true
            report?.add("$name fell asleep")
        } else if (!wantSleep && asleep) {
            asleep = false
            lightsOff = false
            report?.add("$name woke up")
        }

        // ---- drains & restores ----
        if (asleep) {
            energy = min(4f, energy + if (lightsOff) 1f / 100f else 1f / 200f)
            if (!lightsOff) changeCare(-0.015f)   // restless under the lamp
            hunger = (hunger - 1f / 260f).coerceAtLeast(0f)
        } else {
            hunger = (hunger - 1f / 70f).coerceAtLeast(0f)
            val happyDrain = if (overweight) 1f / 60f else 1f / 85f
            happy = (happy - happyDrain).coerceAtLeast(0f)
            energy = (energy - 1f / 240f).coerceAtLeast(0f)
            poopTimerMin++
            if (poopTimerMin >= 170 && poops < 4) {
                poopTimerMin = 0
                poops++
                justPooped = true
                report?.add("$name made a mess")
            }
        }

        // ---- sickness & the long dark ----
        if (hunger <= 0f) hungerZeroMin++ else hungerZeroMin = 0
        if (!sick) {
            var p = 0f
            if (poops >= 3) p += 0.003f
            if (hungerZeroMin > 120) p += 0.005f
            if (overweight) p += 0.001f
            if (p > 0f && rng.nextFloat() < p) {
                sick = true
                sickMin = 0
                justGotSick = true
                report?.add("$name got sick")
            }
        } else {
            sickMin++
            happy = (happy - 1f / 40f).coerceAtLeast(0f)
            if (sickMin > SICK_DEATH_MIN && stage > Stage.BABY) {
                die(report, "sickness")
                return
            }
        }
        miseryMin = if (hunger <= 0f && happy <= 0f) miseryMin + 1 else 0
        if (miseryMin > MISERY_DEATH_MIN && stage > Stage.BABY) {
            die(report, "neglect")
            return
        }

        // ---- care drift ----
        if (hunger >= 3f && happy >= 3f && poops == 0 && !sick) changeCare(0.02f)
        if (hunger <= 0f || happy <= 0f) changeCare(-0.05f)

        // ---- attention calls ----
        if (activeNeed != null) {
            // Pause the response clock while asleep: hunger/play actions are
            // unavailable then, so penalising the keeper would be unwinnable.
            if (!asleep) needSinceMin++
            if (!asleep && needSinceMin >= CALL_TIMEOUT_MIN) {
                missedCalls++
                changeCare(-3f)
                activeNeed = null
                callCooldownMin = 45
                report?.add("$name called for you… and gave up")
            }
        } else if (!asleep) {
            if (callCooldownMin > 0) callCooldownMin-- else {
                val need = when {
                    sick -> Need.SICK
                    poops > 0 -> Need.MESS
                    hunger <= 1f -> Need.HUNGRY
                    happy <= 1f -> Need.UNHAPPY
                    rng.nextFloat() < 0.002f -> Need.FALSE_ALARM
                    else -> null
                }
                if (need != null) {
                    activeNeed = need
                    needSinceMin = 0
                    justCalled = true
                }
            }
        }
    }

    private fun die(report: AwayReport?, cause: String) {
        alive = false
        passedPeacefully = false
        report?.add("$name didn't make it ($cause) — a gentle ghost lingers")
    }

    private fun judgeForm(): AdultForm = when {
        careScore >= 80f && discipline >= 2.5f && !overweight && missedCalls <= 3 -> AdultForm.ROYAL
        careScore >= 60f -> AdultForm.SPRITE
        careScore >= 30f -> AdultForm.SCRAPPY
        else -> AdultForm.GLOOM
    }

    // ------------------------------------------------------------------
    //  One-frame event flags (engine reads + clears; keeps sim UI-free)
    // ------------------------------------------------------------------

    var justEvolved = false
    var justPooped = false
    var justGotSick = false
    var justCalled = false

    // ------------------------------------------------------------------
    //  Care verbs
    // ------------------------------------------------------------------

    /** true if it actually ate (asleep/dead/full = no). */
    fun feedMeal(): Boolean {
        if (!alive || asleep || stage == Stage.EGG) return false
        if (hunger >= 4f) return false
        hunger = min(4f, hunger + 1f)
        weight = min(15, weight + 1)
        totalMeals++
        resolve(Need.HUNGRY)
        return true
    }

    fun feedSnack(): Boolean {
        if (!alive || asleep || stage == Stage.EGG) return false
        if (happy >= 4f) return false
        happy = min(4f, happy + 1f)
        weight = min(15, weight + 2)
        totalSnacks++
        resolve(Need.UNHAPPY)
        return true
    }

    fun clean(): Boolean {
        if (poops == 0) return false
        poops = 0
        poopTimerMin = 0
        changeCare(1f)
        resolve(Need.MESS)
        return true
    }

    fun medicine(): Boolean {
        if (!alive || !sick) return false
        sick = false
        sickMin = 0
        happy = min(4f, happy + 0.5f)
        changeCare(2f)
        resolve(Need.SICK)
        return true
    }

    /** Correct answer to a false alarm; scolding an innocent pet stings. */
    fun disciplineNow(): Boolean {
        if (!alive || asleep) return false
        return if (activeNeed == Need.FALSE_ALARM) {
            discipline = min(4f, discipline + 0.5f)
            changeCare(2f)
            resolve(Need.FALSE_ALARM)
            true
        } else {
            happy = (happy - 0.5f).coerceAtLeast(0f)
            false
        }
    }

    fun toggleLights() {
        lightsOff = !lightsOff
    }

    /** Spend real energy before a dance-off; prevents unlimited reward farming. */
    fun beginPlay(): Boolean {
        if (!alive || asleep || sick || stage == Stage.EGG || energy < MIN_PLAY_ENERGY) return false
        energy = (energy - PLAY_ENERGY_COST).coerceAtLeast(0f)
        return true
    }

    fun playResult(won: Boolean) {
        gamesPlayed++
        if (won) gamesWon++
        happy = min(4f, happy + if (won) 1f else 0.5f)
        weight = (weight - 1).coerceAtLeast(1)
        changeCare(if (won) 1.5f else 0.5f)
        resolve(Need.UNHAPPY)
    }

    /** Steps while wearing the glasses = walking your pet. */
    fun onSteps(delta: Long, todayKey: String): Boolean {
        if (!alive || asleep || stage == Stage.EGG) return false
        if (stepDayKey != todayKey) {
            stepDayKey = todayKey
            stepsToday = 0
            dailyGoalHit = false
        }
        val before = stepsToday
        stepsToday += delta.coerceAtLeast(0L)
        if (before / 100 != stepsToday / 100) {
            happy = min(4f, happy + 0.05f)
            changeCare(0.05f)
        }
        if (!dailyGoalHit && stepsToday >= DAILY_STEP_GOAL) {
            dailyGoalHit = true
            happy = 4f
            changeCare(3f)
            return true   // the daily walk fanfare
        }
        return false
    }

    private fun resolve(need: Need) {
        if (activeNeed == need) {
            activeNeed = null
            needSinceMin = 0
            changeCare(1.5f)
            callCooldownMin = 20
        }
    }

    private fun changeCare(delta: Float) {
        careScore = (careScore + delta).coerceIn(0f, 100f)
    }

    // ------------------------------------------------------------------
    //  Persistence
    // ------------------------------------------------------------------

    fun toJson(): String = JSONObject()
        .put("generation", generation).put("bornMs", bornMs).put("lastSimMs", lastSimMs)
        .put("stage", stage.name).put("form", form.name)
        .put("hunger", hunger.toDouble()).put("happy", happy.toDouble())
        .put("energy", energy.toDouble()).put("discipline", discipline.toDouble())
        .put("weight", weight).put("poops", poops)
        .put("sick", sick).put("sickMin", sickMin).put("miseryMin", miseryMin)
        .put("hungerZeroMin", hungerZeroMin)
        .put("asleep", asleep).put("lightsOff", lightsOff)
        .put("alive", alive).put("passed", passedPeacefully)
        .put("careScore", careScore.toDouble()).put("missedCalls", missedCalls)
        .put("activeNeed", activeNeed?.name ?: "").put("needSinceMin", needSinceMin)
        .put("callCooldownMin", callCooldownMin).put("poopTimerMin", poopTimerMin)
        .put("eggWatchMs", eggWatchMs)
        .put("stepsToday", stepsToday).put("stepDayKey", stepDayKey).put("dailyGoalHit", dailyGoalHit)
        .put("totalMeals", totalMeals).put("totalSnacks", totalSnacks)
        .put("gamesWon", gamesWon).put("gamesPlayed", gamesPlayed)
        .toString()

}
