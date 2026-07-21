package com.tapogotchi.engine

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetGameplayTest {

    @Test
    fun fullMoodRejectsSnackWithoutAddingWeight() {
        val pet = playablePet().apply {
            happy = 4f
            weight = 6
        }

        assertFalse(pet.feedSnack())
        assertEquals(6, pet.weight)
        assertEquals(0, pet.totalSnacks)
    }

    @Test
    fun danceOffRequiresAndSpendsEnergy() {
        val tired = playablePet().apply { energy = 0.99f }
        assertFalse(tired.beginPlay())
        assertEquals(0.99f, tired.energy, 0.001f)

        val rested = playablePet().apply { energy = 1f }
        assertTrue(rested.beginPlay())
        assertEquals(1f - Pet.PLAY_ENERGY_COST, rested.energy, 0.001f)
    }

    @Test
    fun sleepingPetDoesNotMissAnUnanswerableRequest() {
        val atTwoAm = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pet = playablePet().apply {
            bornMs = atTwoAm - 2L * 86_400_000L
            lastSimMs = atTwoAm - 60_000L
            stage = Stage.CHILD
            activeNeed = Need.HUNGRY
            needSinceMin = Pet.CALL_TIMEOUT_MIN - 1
        }

        pet.simulateTo(atTwoAm, live = false)

        assertTrue(pet.asleep)
        assertEquals(Need.HUNGRY, pet.activeNeed)
        assertEquals(Pet.CALL_TIMEOUT_MIN - 1, pet.needSinceMin)
        assertEquals(0, pet.missedCalls)
    }

    @Test
    fun longCatchUpStillJudgesAdultForm() {
        val now = System.currentTimeMillis()
        val pet = playablePet().apply {
            bornMs = now - 8L * 86_400_000L
            lastSimMs = now - 60_000L
            stage = Stage.EGG
            careScore = 85f
            discipline = 3f
            missedCalls = 0
            weight = 5
        }

        pet.simulateTo(now, live = false)

        assertEquals(Stage.ADULT, pet.stage)
        assertEquals(AdultForm.ROYAL, pet.form)
    }

    @Test
    fun careRewardsStayWithinDisplayAndEvolutionRange() {
        val pet = playablePet().apply {
            hunger = 3f
            careScore = 99.5f
            activeNeed = Need.HUNGRY
        }

        assertTrue(pet.feedMeal())
        assertEquals(100f, pet.careScore, 0.001f)
    }

    @Test
    fun negativeSensorDeltaCannotUndoWalkProgress() {
        val pet = playablePet().apply {
            stepDayKey = "test-day"
            stepsToday = 123
        }

        pet.onSteps(-50, "test-day")

        assertEquals(123, pet.stepsToday)
    }

    @Test
    fun clockRollbackRebasesInsteadOfFreezingSimulation() {
        val pet = playablePet().apply { lastSimMs = 20_000L }

        pet.simulateTo(10_000L, live = false)

        assertEquals(10_000L, pet.lastSimMs)
    }

    private fun playablePet() = Pet().apply {
        stage = Stage.CHILD
        alive = true
        asleep = false
        sick = false
        energy = 4f
        hunger = 3f
        happy = 3f
    }
}
