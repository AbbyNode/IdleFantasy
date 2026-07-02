# Architectural Flaws and Anti-Patterns

This document lists known architectural issues, anti-patterns, and bad practices in the codebase. These are things that would be nice to fix but have tradeoffs or require significant refactoring.

## Critical Issues

### 1. JSON Storage with Missing Field Migrations

**Problem:** Complex fields are stored as JSON strings in Room entities (inventory, skills, flags). When a new field is added to a data class, old save files are missing it.

**Current Workaround:** The shared `Json` instance has `coerceInputValues = true`, which coerces null/missing fields to Kotlin defaults. This works but is fragile.

**Example:** If we add `var maxHp: Int = 100` to `PlayerFlags`, old saves without this field will get `maxHp = 0` (the Int default), not 100.

**Better Solution:** Implement explicit JSON migrations for data classes, similar to Room schema migrations. This requires:
- Custom `JsonElement` serializers with version numbers
- Transformation logic in deserializers
- Comprehensive testing of save file compatibility

**Affected:** PlayerRepository (inventory, skills, flags), QuestProgress, FarmingPatch, GlobalState, etc.

### 2. Mutex-Based Concurrency (Coarse-Grained Locking)

**Problem:** `PlayerRepository.playerMutex` is a single, application-wide lock. All mutations (adding XP, consuming items, spending coins) wait for each other.

**Consequence:** If a session completes while the UI is iterating through the inventory, the entire player state is locked. This is safe but inefficient.

**Example Race Condition If Lock Didn't Exist:**
```
Thread A: Read inventory (1000 items)
Thread B: Grant 1 item (new total: 1001)
Thread A: Deserialize old state, write back 1000 items (lost 1 item)
```

**Better Solution:** Fine-grained locks per sub-object (inventory lock, skills lock, etc.), or use an event-sourcing pattern to append changes sequentially without conflicting reads.

**Current Justification:** Single-player offline game; lock contention is rare. The coarse lock is simpler to reason about and debug.

**Impact:** Low (single player, predictable coroutine execution order), but makes the architecture feel crude.

### 3. Simulators Don't Validate Preconditions

**Problem:** Simulators (SkillSimulator, CombatSimulator) take player stats as parameters but don't validate them. If you pass invalid data (e.g., negative XP, non-existent equipment), the simulator will produce garbage output.

**Example:**
```kotlin
SkillSimulator.simulateMining(
    oreKey = "iron_ore",
    startXp = -999_999,  // Invalid! No validation
    agilityLevel = 150,  // Over level 99! No validation
)
// Returns valid frames anyway
```

**Better Solution:** Validate preconditions at simulator entry:
```kotlin
require(startXp >= 0) { "startXp must be non-negative" }
require(agilityLevel in 1..99) { "agilityLevel must be 1–99" }
```

**Current Justification:** Simulators are internal; callers (ViewModels) always source data from PlayerRepository, which ensures valid data.

**Problem:** If ViewModels make mistakes or repositories have bugs, invalid sessions can be created with wrong XP/drops.

### 4. Combat Simulator Is a 600-Line God Object

**Problem:** `CombatSimulator.kt` is ~600 lines of complex tick-by-tick combat math. It's hard to test, hard to modify, and has many local variables that are easy to confuse.

**Current Structure:**
```kotlin
fun simulateCombat(...): Result {
    // 50 lines of stat computation
    var playerHp = ...
    var enemyHp = ...
    var foodConsumed = 0
    var arrowsReclaimed = 0
    // ...
    
    for (minute in 1..60) {
        // 40 lines of per-frame logic
        for (tick in 1..TICKS_PER_FRAME) {
            // 30 lines of hit rolls, damage, enemy spawning
            repeat(TICKS_PER_FRAME) { ... }
        }
    }
    
    // Return frames
    return Result(frames, duration)
}
```

**Better Solution:** Extract helper classes and functions:
```kotlin
class CombatState(val playerHp: Int, val enemyHp: Int, val inventory: Map<String, Int>, ...)
class CombatCalculator(val player: Player, val equipment: Equipment, val spells: Map<String, SpellData>)

fun CombatCalculator.hitChance(playerAttack: Int, enemyDefense: Int): Double = ...
fun CombatCalculator.dealDamage(attacker: Attacker, defender: Defender): Int = ...
```

**Impact:** Hard to review, hard to debug, easy to introduce bugs when adding new combat mechanics.

### 5. ViewModels Directly Call Repositories Without Error Handling

**Problem:** ViewModels call repositories in `viewModelScope.launch {}` without try-catch:

```kotlin
@HiltViewModel
class CombatViewModel(...) : ViewModel() {
    fun startCombat(dungeonKey: String) {
        viewModelScope.launch {
            val simulator = CombatSimulator  // Might throw
            val result = simulator.simulate(...)  // Might throw
            sessionRepo.startSession(...)  // Might throw
            _state.value = CombatUiState.Success(...)
        }
    }
}
```

**Consequence:** If any repository throws an exception, it crashes the ViewModel scope and leaves the UI in an inconsistent state. The user sees nothing (the coroutine fails silently).

**Better Solution:** Wrap mutations in try-catch and emit error state:

```kotlin
fun startCombat(dungeonKey: String) {
    viewModelScope.launch {
        try {
            _state.value = CombatUiState.Loading
            val result = simulator.simulate(...)
            sessionRepo.startSession(...)
            _state.value = CombatUiState.Success(...)
        } catch (e: Exception) {
            _state.value = CombatUiState.Error("Failed to start combat: ${e.message}")
        }
    }
}
```

**Current Justification:** Repositories are trusted to not throw (they validate input). ViewModels assume happy path.

**Problem:** Silent failures are worse than crashes. Users don't know why their action didn't work.

## Medium Issues

### 6. No Rate Limiting on Session Starts

**Problem:** A ViewModel can call `sessionRepository.startSession()` multiple times in rapid succession (e.g., double-tap on "Start Session" button). Each call creates a session and schedules an alarm.

**Consequence:** Multiple overlapping sessions in the database, multiple alarms firing, confusion.

**Current Workaround:** UI buttons have `.clickable(enabled = state.canStartSession)` guard, but this is a UI-layer workaround, not a repository-layer guarantee.

**Better Solution:** Repository should reject duplicate sessions:
```kotlin
suspend fun startSession(...): Result<SkillSession, Error> {
    if (getActiveSession() != null) {
        return Error.SessionAlreadyActive
    }
    // Create session
}
```

### 7. No Validation of Equipment and Items

**Problem:** PlayerRepository doesn't validate that equipped items actually exist in the inventory.

**Example:**
```kotlin
val player = Player(
    equipped = """{"weapon": "excalibur"}""",  // Item doesn't exist!
    inventory = """{}""",
)
```

This can happen if:
- A developer manually edits the database
- JSON deserialization bug loses an item
- A quest grants an item but the grant fails midway

**Better Solution:** Add repository-level validation:
```kotlin
suspend fun equipItem(slot: String, itemKey: String): Boolean {
    if (getInventoryQuantity(itemKey) == 0) return false  // Don't equip nonexistent items
    updateEquipped(slot, itemKey)
    return true
}
```

### 8. Simulators Use Raw `Random` With No Seed Control

**Problem:** Frame simulation uses `Random()`, which uses `System.nanoTime()` as seed. This means:
- Sessions are non-deterministic (same player/activity/stats = different drops each time)
- Can't replay a session for debugging
- Can't generate test fixtures easily

**Current Workaround:** Pass `rnd: Random` as parameter, caller provides seed.

**Better Solution:** Make seed explicit and injectable:
```kotlin
data class SimulationConfig(val randomSeed: Long)
fun simulateMining(..., config: SimulationConfig): Result {
    val rnd = Random(config.randomSeed)
    // ...
}
```

Then seed is stored with the session for perfect replayability.

### 9. No Pagination in Large Lists

**Problem:** Some screens (inventory, quest list, shop stock) load all items at once. If a player has 500 items, the entire list is composed in Compose, even though only 10 are visible.

**Current Workaround:** `LazyColumn` only composes visible items, so performance is OK.

**Better Solution:** Implement pagination or cursor-based loading at the repository level:
```kotlin
suspend fun getInventoryPage(pageSize: Int = 50, page: Int = 0): List<InventoryItem>
```

### 10. Global State Repository Is a Junk Drawer

**Problem:** `GlobalState` is a generic key-value store used for everything that doesn't fit in Player:
- Queued sessions
- Worker configurations
- Active buffs
- Guild state
- Church state

No schema, no migrations, no validation. Just JSON strings.

**Better Solution:** Separate entities for each domain:
```kotlin
@Entity(tableName = "queued_sessions") data class QueuedSession(...)
@Entity(tableName = "workers") data class Worker(...)
@Entity(tableName = "buffs") data class ActiveBuff(...)
```

This provides better type safety and easier migrations.

## Minor Issues / Design Quirks

### 11. Notification Text Truncation

**Problem:** Session completion notifications show top 3 items, but if an item name is 50 characters long, it truncates poorly.

**Workaround:** Android handles this, but truncation is ungraceful.

### 12. No Animation on Session Completion

**Problem:** When a session completes and the UI updates, there's no transition animation. The state change is instant, which feels jarring.

**Better Solution:** Compose offers transition animations:
```kotlin
AnimatedVisibility(
    visible = state.sessionActive,
    enter = expandVertically(),
    exit = shrinkVertically(),
) {
    ActiveSessionCard(...)
}
```

### 13. XpTable Is Hardcoded

**Problem:** The XP table (levels 1–99) is a hardcoded array in `XpTable.kt`. If we ever want to change progression (e.g., level cap 120), it requires code changes and recompilation.

**Better Solution:** Load XP table from `assets/data/xp_table.json`.

### 14. SessionFrame Only Stores Items and XP

**Problem:** `SessionFrame` doesn't track other session state changes:
- Prayers (bone burials don't increment prayer XP in current frame structure)
- Prayer blessings expiring
- Daily quest progress
- Guild quest progress

Workaround: These are handled as side effects in `QueuedSessionStarter.applySessionFrames()`, outside the frame structure.

**Better Solution:** Make `SessionFrame` a generic event structure:
```kotlin
sealed class SessionEvent {
    data class ItemDrop(val itemKey: String, val qty: Int) : SessionEvent()
    data class XpGain(val skill: String, val amount: Long) : SessionEvent()
    data class QuestProgress(val questId: String, val amount: Int) : SessionEvent()
}
```

Then frames contain a list of events, and handlers apply each event type.

### 15. BroadcastReceiver Direct CoroutineScope Launch

**Problem:** `SessionAlarmReceiver.onReceive()` launches a coroutine with `CoroutineScope(Dispatchers.IO)`. This is a "fire and forget" scope that's not managed:

```kotlin
CoroutineScope(Dispatchers.IO).launch {
    // Coroutine is not cancelled when Activity is destroyed
    // Coroutine is not tracked by any parent scope
}
```

**Better Solution:** Use `lifecycleScope` if available, or inject a supervisor scope:
```kotlin
@Inject lateinit var appScope: CoroutineScope  // Provided by Hilt

override fun onReceive(context: Context, intent: Intent) {
    appScope.launch(Dispatchers.IO) {
        // Managed by app-level supervisor scope
    }
}
```

This ensures proper cancellation on app termination.

## Summary

| Issue | Severity | Refactoring Cost | Impact |
|-------|----------|------------------|---------|
| JSON field migrations | High | Medium | Save file compatibility |
| Mutex coarse locking | Medium | High | Performance (low priority, single-player) |
| Simulator no validation | Medium | Low | Invalid sessions possible |
| Combat simulator bloat | Medium | High | Code maintainability |
| ViewModel error handling | High | Medium | User-facing crashes |
| Rate limiting | Medium | Low | Duplicate sessions possible |
| Equipment validation | Medium | Low | Equip nonexistent items |
| Random seed control | Low | Low | Testing and replayability |
| Pagination | Low | Medium | Performance on large inventories |
| GlobalState junk drawer | Low | High | Schema clarity |

## Recommended First Fixes

1. **Error handling in ViewModels** - Prevents silent failures
2. **Simulator validation** - Prevents invalid game states
3. **Rate limiting** - Prevents duplicate sessions
4. **Combat simulator refactoring** - Improves maintainability (but high effort)

Defer JSON field migrations until a breaking schema change is needed (rare in a single-player game).
