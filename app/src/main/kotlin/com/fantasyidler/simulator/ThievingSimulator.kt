package com.fantasyidler.simulator

import com.fantasyidler.data.json.ThievingNpcData
import com.fantasyidler.data.model.SessionFrame
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pre-simulates all 60 frames of a thieving session.
 *
 * Each frame the player attempts to pickpocket the NPC. If successful, coins
 * and loot are awarded. On failure the player is stunned — the following frame
 * is skipped (no XP, no loot) to simulate the stun penalty.
 *
 * success_chance = clamp(0.10, 0.40 + (thievingLevel - npcMinLevel) * 0.02, 0.95)
 */
object ThievingSimulator {

    data class Result(
        val frames: List<SessionFrame>,
        val durationMs: Long,
    )

    fun simulate(
        npcKey: String,
        npc: ThievingNpcData,
        startXp: Long,
        thievingLevel: Int,
        agilityLevel: Int = 1,
        agilityPrestige: Int = 0,
        petBoostPct: Int = 0,
        petDropKey: String? = null,
        petDropChance: Double = 0.0,
        random: Random = Random.Default,
    ): Result {
        val successChance = (0.40 + (thievingLevel - npc.levelRequired) * 0.02)
            .coerceIn(0.10, 0.95)

        var currentXp = startXp
        val frames = mutableListOf<SessionFrame>()
        var stunNextFrame = false

        for (minute in 1..60) {
            val xpBefore = currentXp
            val levelBefore = XpTable.levelForXp(currentXp)

            if (stunNextFrame) {
                stunNextFrame = false
                frames.add(
                    SessionFrame(
                        minute = minute,
                        xpGain = 0,
                        xpBefore = xpBefore,
                        xpAfter = xpBefore,
                        levelBefore = levelBefore,
                        levelAfter = levelBefore,
                        items = emptyMap(),
                        leveledUp = false,
                        success = false,
                    )
                )
                continue
            }

            val success = random.nextDouble() < successChance
            if (!success) {
                stunNextFrame = true
                frames.add(
                    SessionFrame(
                        minute = minute,
                        xpGain = 0,
                        xpBefore = xpBefore,
                        xpAfter = xpBefore,
                        levelBefore = levelBefore,
                        levelAfter = levelBefore,
                        items = emptyMap(),
                        leveledUp = false,
                        success = false,
                    )
                )
                continue
            }

            val xpGain = if (petBoostPct > 0) (npc.baseXp * (1.0 + petBoostPct / 100.0)).toInt() else npc.baseXp
            currentXp += xpGain
            val levelAfter = XpTable.levelForXp(currentXp)

            val items = mutableMapOf<String, Int>()

            // Coins — random amount in npc's range
            val coins = npc.coinsMin + random.nextInt(npc.coinsMax - npc.coinsMin + 1)
            items["coins"] = coins

            // Loot table rolls
            for (entry in npc.lootTable) {
                if (random.nextDouble() < entry.chance) {
                    val qty = if (entry.minQty == entry.maxQty) entry.minQty
                    else entry.minQty + random.nextInt(entry.maxQty - entry.minQty + 1)
                    items[entry.item] = (items[entry.item] ?: 0) + qty
                }
            }

            // Pet drop
            if (petDropKey != null && petDropChance > 0.0 && random.nextDouble() < petDropChance) {
                items[petDropKey] = 1
            }

            frames.add(
                SessionFrame(
                    minute = minute,
                    xpGain = xpGain,
                    xpBefore = xpBefore,
                    xpAfter = currentXp,
                    levelBefore = levelBefore,
                    levelAfter = levelAfter,
                    items = items,
                    leveledUp = levelAfter > levelBefore,
                    success = true,
                )
            )
        }

        return Result(frames, SkillSimulator.sessionDurationMs(agilityLevel, agilityPrestige))
    }
}
