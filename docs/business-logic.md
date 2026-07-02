# Business Logic Layer

## Simulators

Simulators pre-calculate all 60 frames of a session upfront. This approach enables:
- Accurate random seed (same seed = same drops, no variance)
- Instant frame playback at completion
- Easy debugging and testing

All simulators follow the same pattern:
1. Load player stats and equipment from `PlayerRepository`
2. Load activity data from `GameDataRepository`
3. Generate 60 frames of drops and XP
4. Compute agility penalty on duration
5. Return frames + final duration

### SkillSimulator (Gathering)

Handles mining, woodcutting, fishing, agility courses. Pattern:

```kotlin
fun simulateMining(
    oreKey: String,
    oreData: OreData,
    gems: Map<String, GemData>,
    startXp: Long,
    agilityLevel: Int,
    petBoostPct: Int,
    toolEfficiency: Float,
    rnd: Random,
): Result {
    // 1. Calculate per-minute drop rate from ore data
    val dropsPerMinute = oreData.rate * toolEfficiency
    
    // 2. Loop 60 minutes
    val frames = mutableListOf<SessionFrame>()
    for (minute in 1..60) {
        // 3. Roll for ore drop
        val oreCount = dropsPerMinute.toInt() + if (rnd.nextDouble() < dropsPerMinute % 1.0) 1 else 0
        
        // 4. Roll for bonus gems
        val gemDrops = mutableMapOf<String, Int>()
        repeat(oreCount) {
            val gem = selectGemByWeight(gems, rnd)
            gemDrops[gem]?:0 + 1
        }
        
        // 5. Calculate XP (with level-up checks)
        val xp = oreData.xpPerOre * oreCount
        frames.add(SessionFrame(itemDrops = gemDrops, xpGain = xp, levelUp = checkLevelUp(...)))
    }
    
    // 6. Apply agility reduction to duration
    val agilityReduction = 0.01 * agilityLevel  // 1% per level
    val durationMs = 3_600_000L * (1 - agilityReduction)
    
    return Result(frames, durationMs)
}
```

Key details:
- **Drop rates** are per-minute (e.g., 2.5 ores/min → 2–3 per minute)
- **Agility penalty** reduces wall-clock duration (affects session end time)
- **Pet boosts** increase XP by a fixed percentage
- **Random seed** is taken as a parameter for reproducibility

### CombatSimulator

Most complex simulator. Simulates tick-by-tick combat with:
- Player and enemy hit rolls (RSC-style combat formula)
- Food consumption when HP drops
- Arrow/rune usage and reclamation
- Multi-enemy encounters (spawning new enemies from a pool)
- Bone drops and prayer XP (if high enough level)

Example tick-by-tick logic:

```kotlin
val playerHitChance = when {
    playerEffAtk > enemyDefStat -> 1.0 - enemyDefStat / (2.0 * playerEffAtk)
    else -> playerEffAtk / (2.0 * enemyDefStat)
}.coerceIn(0.15, 0.95)

// Each tick (120 ticks per frame = 2 min of combat)
repeat(TICKS_PER_FRAME) {
    // Player hits?
    if (rnd.nextDouble() < playerHitChance) {
        val damage = rnd.nextInt(1, playerMaxHit + 1)
        enemyHp -= damage
    }
    
    // Enemy hits?
    if (rnd.nextDouble() < enemyHitChance) {
        val damage = rnd.nextInt(1, enemyMaxHit + 1)
        playerHp -= damage
        
        // Consume food if needed
        if (playerHp <= 0) {
            playerHp += foodRestoration
            foodConsumed++
        }
    }
    
    // Enemy dead?
    if (enemyHp <= 0) {
        kills++
        loot.addAll(enemy.dropTable.roll(rnd))
        enemyHp = 0
    }
}
```

**Important:** Combat uses exact RSC-like mechanics, including:
- Effective attack/strength stats computed from base level + equipment bonuses
- Damage caps based on max hit formula: `1 + str * (str_bonus + 64) / 640`
- Hit chance clamped to 15–95%
- Separate stats for melee/ranged/magic combat styles

### Other Simulators

- **CarnivalSimulator** - Stall purchases and profit calculations
- **ThievingSimulator** - Pickpocketing with failure chances
- **MercantileSimulator** - Buy/sell margins and costs
- **SkillingDungeonSimulator** - Dungeon-specific drops (mystical dust, etc.)

## Repositories

Repositories manage game state and coordinate simulators with the database. All mutations are **synchronous in the repository** but called from **coroutines**.

### SessionRepository

Manages all skill/combat sessions:

```kotlin
suspend fun startSession(
    skillName: String,           // "mining", "combat", etc.
    activityKey: String,         // "iron_ore", "ice_warrior", etc.
    frames: String,              // Pre-serialized JSON of frames
    durationMs: Long,
    skillDisplayName: String,    // Localized name for notification
    insertAsCompleted: Boolean = false,
): SkillSession {
    val session = SkillSession(
        sessionId = UUID.randomUUID().toString(),
        skillName = skillName,
        activityKey = activityKey,
        startedAt = System.currentTimeMillis(),
        endsAt = startedAt + durationMs,
        frames = frames,
    )
    sessionDao.insert(session)
    scheduleAlarm(session.sessionId, session.endsAt, skillDisplayName)
    return session
}
```

Key methods:
- `startSession()` - Create and schedule
- `startWorkerSession()` - Same but with efficiency multiplier
- `getActiveSession()` / `activeSessionFlow` - Query or observe
- `markCompleted()` - Mark session done (called by alarm receiver)
- `getSession()` - Fetch by ID

### PlayerRepository

Manages player state with synchronized mutations via `playerMutex`:

```kotlin
suspend fun addXp(skill: String, amount: Long) = playerMutex.withLock {
    val player = getOrCreatePlayer()
    val xpMap: MutableMap<String, Long> = json.decodeFromString(player.skillXp)
    val oldLevel = XpTable.levelForXp(xpMap[skill] ?: 0L)
    xpMap[skill] = (xpMap[skill] ?: 0L) + amount
    val newLevel = XpTable.levelForXp(xpMap[skill]!!)
    
    if (newLevel > oldLevel) {
        // Grant level-up cape if applicable
        grantMissingCapesUnlocked()
    }
    
    playerDao.upsert(player.copy(
        skillXp = json.encode<Map<String, Long>>(xpMap),
        skillLevels = json.encode<Map<String, Int>>(levels),
    ))
}
```

All mutations lock `playerMutex` to prevent race conditions. For example, when a session completes with 5 level-ups and 100 item drops, the entire operation is atomic.

### Specialized Repositories

- **QuestRepository** - Track quest progress (daily/weekly/main quests)
- **FarmingRepository** - Manage crop growth cycles
- **GuildRepository** - Guild quests and member bonuses
- **ChurchRepository** - Prayer blessings and timers
- **ShopRepository** - Item purchase limits and stock
- **GameDataRepository** - Load static data from JSON (covered in data-layer.md)

## QueuedSessionStarter

When a session completes, `QueuedSessionStarter` plays back each frame into the player's inventory and XP:

```kotlin
suspend fun applySessionFrames(session: SkillSession) {
    val frames: List<SessionFrame> = json.decodeFromString(session.frames)
    
    playerRepo.withLock {
        for (frame in frames) {
            // Add XP
            playerRepo.addXpUnlocked(session.skillName, frame.xpGain)
            
            // Add items
            for ((itemKey, qty) in frame.itemDrops) {
                playerRepo.grantItemUnlocked(itemKey, qty)
            }
            
            // Handle special effects (level-ups, prayers, etc.)
        }
    }
}
```

The `withLock` ensures all frames are applied atomically. After session frames are applied, if there's a queued next session, it starts immediately.

## XpTable

Hardcoded XP table for levels 1–99:

```kotlin
object XpTable {
    private val table = intArrayOf(
        0,      // level 1
        83,     // level 2
        174,    // level 3
        ...
        4_294_967_296, // level 99
    )
    
    fun levelForXp(xp: Long): Int {
        // Binary search or linear scan through table
        return table.indexOfLast { it <= xp } + 1
    }
    
    fun xpForLevel(level: Int): Long = table[level - 1]
}
```

The table is taken from RuneScape Classic (RSC) for authenticity.

## Workers (Background Sessions)

Players can hire workers to grind skills while the main player session is active. Workers are managed by:

- **WorkerQueuedSessionStarter** - Chain worker sessions together
- **SessionRepository** - Track worker sessions with `workerSlot` and `efficiencyMultiplier`

Workers:
- Have lower efficiency (e.g., 70% XP compared to main player)
- Can have up to 3 slots
- Continue queuing independently
- Are deleted when the player dismisses them

## Key Patterns

### Simulator Input
Simulators take all player stats and activity data as **parameters**, not by reading the database. This makes them pure functions, easy to test.

### Frame Precomputation
All game logic is deterministic. Sessions are fully simulated upfront, serialized, and stored. At completion, frames are played back. This avoids:
- Duplicate simulation
- Non-deterministic outcomes
- Needing to persist partial progress

### Mutex Synchronization
Multi-step changes (XP + items + level-up capes) are wrapped in `playerMutex.withLock()` to ensure atomicity. ViewModels observe the result via `playerFlow`, so UI updates are automatic.

### Lazy Evaluation
Game data is lazy-loaded on first access. The first mining session triggers loading all ore data, but subsequent sessions reuse the cached map.
