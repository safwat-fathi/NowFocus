package app.getnowfocus.android

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalsTest {
    private fun goal(text: String, p: GoalPriority) = Goal(id = text, text = text, priority = p, createdAt = 0)

    @Test
    fun `no goals means nothing to show`() {
        assertNull(Goals.pick(emptyList()))
    }

    @Test
    fun `a high priority goal is always preferred over lower ones`() {
        val goals = listOf(goal("low", GoalPriority.LOW), goal("high", GoalPriority.HIGH), goal("med", GoalPriority.MEDIUM))
        repeat(50) { seed -> assertEquals("high", Goals.pick(goals, Random(seed))?.text) }
    }

    @Test
    fun `without a high goal any goal can be picked`() {
        val goals = listOf(goal("a", GoalPriority.MEDIUM), goal("b", GoalPriority.LOW))
        val picked = (0 until 50).map { Goals.pick(goals, Random(it))!!.text }.toSet()
        assertEquals(setOf("a", "b"), picked)
    }

    @Test
    fun `among several high goals the pick varies`() {
        val goals = listOf(goal("x", GoalPriority.HIGH), goal("y", GoalPriority.HIGH))
        val picked = (0 until 50).map { Goals.pick(goals, Random(it))!!.text }.toSet()
        assertTrue(picked.size == 2)
    }

    @Test
    fun `labels match macOS`() {
        assertEquals(listOf(R.string.priority_high, R.string.priority_medium, R.string.priority_low), GoalPriority.entries.map { it.label })
    }
}
