package com.fantasyidler.simulator

import com.fantasyidler.data.json.DungeonData
import com.fantasyidler.data.json.EnemyCombatStats
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.data.json.EnemyDefensiveStats
import com.fantasyidler.data.json.EnemySpawn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for the most complex simulator, [CombatSimulator.simulateDungeon], made
 * deterministic through the injected `random` seam. Combat has too many moving
 * parts to assert exact per-tick damage, so this pins structural invariants
 * (frame count, non-negative HP, XP accrual) plus seeded reproducibility.
 */
class CombatSimulatorTest {

    private fun weakEnemy() = EnemyData(
        name = "rat",
        displayName = "Rat",
        hp = 1,
        combatStats = EnemyCombatStats(
            attackLevel = 1, strengthLevel = 1, defenseLevel = 1,
            attackBonus = 0, strengthBonus = 0,
        ),
        defensiveStats = EnemyDefensiveStats(
            attackDefense = 0, strengthDefense = 0, rangedDefense = 0, magicDefense = 0,
        ),
        // Combat XP per kill is read from the "combat" key (see CombatSimulator).
        xpDrops = mapOf("combat" to 20),
    )

    private fun dungeon(spawns: List<EnemySpawn>) = DungeonData(
        name = "farm",
        displayName = "Farm",
        description = "",
        recommendedLevel = 1,
        encounterRate = 1.0,
        enemySpawns = spawns,
    )

    /** A player strong enough to one-shot the weak enemy and never die. */
    private fun runStrongPlayer(seed: Int) = CombatSimulator.simulateDungeon(
        dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
        enemies = mapOf("rat" to weakEnemy()),
        playerAttack = 99,
        playerStrength = 99,
        playerDefence = 99,
        playerHp = 99,
        weaponStrengthBonus = 64,
        random = Random(seed),
    )

    @Test
    fun `a survivable dungeon run produces 60 frames`() {
        assertEquals(60, runStrongPlayer(seed = 1).frames.size)
    }

    @Test
    fun `the same seed reproduces an identical run`() {
        assertEquals(runStrongPlayer(seed = 42).frames, runStrongPlayer(seed = 42).frames)
    }

    @Test
    fun `a strong player accrues combat XP, scores kills, and never dies`() {
        val frames = runStrongPlayer(seed = 7).frames
        val totalXp = frames.sumOf { frame -> frame.xpBySkill.values.sum() }
        val totalKills = frames.sumOf { it.kills }

        assertTrue("expected positive combat XP", totalXp > 0)
        assertTrue("expected at least one kill", totalKills > 0)
        assertFalse("strong player should not die", frames.any { it.died })
    }

    @Test
    fun `recorded HP is never negative`() {
        assertTrue(runStrongPlayer(seed = 3).frames.all { it.hpAfter >= 0 })
    }

    @Test
    fun `an empty spawn pool yields no frames but a valid duration`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(emptyList()),
            enemies = emptyMap(),
            playerAttack = 10,
            playerStrength = 10,
            playerDefence = 10,
            agilityLevel = 1,
        )
        assertTrue(result.frames.isEmpty())
        SkillSimulator.setAgilityContext(1, 0)
        try {
            assertEquals(SkillSimulator.sessionDurationMs(), result.durationMs)
        } finally {
            SkillSimulator.clearAgilityContext()
        }
    }
}
