# Data Layer

## Database Schema

Room database defined in `AppDatabase.kt` with 6 main entities:

### Player Entity
```kotlin
@Entity(tableName = "players")
data class Player(
    @PrimaryKey val id: Long = 1L,
    val skillLevels: String,     // JSON: Map<String, Int>
    val skillXp: String,         // JSON: Map<String, Long>
    val inventory: String,       // JSON: Map<String, Int>
    val equipped: String,        // JSON: Map<String, String?>
    val flags: String,           // JSON: PlayerFlags
    val pets: String,            // JSON: List<OwnedPet>
    val coins: Long,
)
```

Complex fields are stored as JSON strings, not normalized. This is intentional—it matches the original Python schema and keeps the model simple for a single-player offline game.

**Never access Player directly in DAOs.** Use `PlayerRepository` to deserialize and handle JSON.

### SkillSession Entity
```kotlin
@Entity(tableName = "skill_sessions")
data class SkillSession(
    @PrimaryKey val sessionId: String,
    val skillName: String,        // e.g., "mining", "combat"
    val activityKey: String,      // e.g., "iron_ore", "ice_warrior"
    val startedAt: Long,          // milliseconds
    val endsAt: Long,             // milliseconds
    val frames: String,           // JSON: List<SessionFrame>
    val completed: Boolean,
    val isWorkerSession: Boolean, // false = main player, true = background worker
    val workerSlot: Int,          // worker slot (1, 2, 3) if isWorkerSession
    val efficiencyMultiplier: Float, // worker efficiency (affects XP/drops)
)
```

**Frames** are pre-calculated by simulators and stored as JSON. When the session completes, `QueuedSessionStarter` plays back each frame, applying XP and drops to the player.

### QuestProgress Entity
```kotlin
@Entity(tableName = "quest_progress")
data class QuestProgress(
    @PrimaryKey val questId: String,
    val progress: String,        // JSON: Map<String, Int> or quest-specific data
    val started: Boolean,
    val completed: Boolean,
)
```

### FarmingPatch Entity
```kotlin
@Entity(tableName = "farming_patches")
data class FarmingPatch(
    @PrimaryKey val patchId: String,
    val cropKey: String,
    val state: String,           // JSON: FarmingPatchState
    val plantedAt: Long,
)
```

### GlobalState Entity
```kotlin
@Entity(tableName = "global_state")
data class GlobalState(
    @PrimaryKey val key: String,
    val value: String,           // JSON: varies by key
)
```

Used for singleton game state (buffs, current event, settings) that doesn't fit in Player.

### ArenaRecord Entity
Tracks combat arena history (wins, losses, opponent records).

## Database Migrations

Migrations are defined at the top of `AppDatabase.kt`:
- `MIGRATION_1_2` - Added worker session columns
- `MIGRATION_2_3` - Added worker slot numbering

When schema changes, add a new migration object. Always increment the `@Database(version = N)` number.

## JSON Serialization Strategy

Complex fields use `kotlinx.serialization` (not GSON). The shared `Json` instance is provided by Hilt in `AppModule`:

```kotlin
@Provides
@Singleton
fun provideJson(): Json = Json {
    ignoreUnknownKeys = true      // Forward-compatible
    isLenient = true              // Tolerates whitespace
    encodeDefaults = true         // Always write all fields
    coerceInputValues = true      // null → default value instead of crash
}
```

This allows:
- **Forward compatibility**: Old app reading new save data (missing fields use defaults)
- **Backward compatibility**: New app reading old save data (old fields are ignored)
- **Safe defaults**: If a field is missing or null, use the Kotlin default

### Encoding Pattern
Repositories use a two-argument `encodeToString()` to avoid Kotlin 2.0 ambiguity:

```kotlin
private inline fun <reified T> Json.encode(value: T): String =
    encodeToString(serializersModule.serializer<T>(), value)
```

## GameDataRepository

Lazy-loads static game data from JSON files in `assets/data/`. Examples:
- `ores.json` - Mining ore definitions
- `fish.json` - Fishing spot data
- `recipes.json` - Crafting recipes
- `equipment.json` - Armor and weapons
- `enemies.json` - Enemy stats and drop tables

Data is cached in memory on first access. Example:

```kotlin
val ores by lazy {
    json.decodeFromString<Map<String, OreData>>(loadAsset("data/ores.json"))
}
```

All repositories receive `GameDataRepository` as a dependency to look up item definitions, enemy stats, etc.

## Data Models (Domain Objects)

### Core Models
- `Player` - Player entity
- `SkillSession` - A session (skill training or combat)
- `SessionFrame` - One minute of a session (XP, items dropped)
- `PlayerFlags` - Transient player state (current HP, active spell, buffs)
- `InventoryItem` - Item with ID and quantity

### Nested Models
- `OwnedPet` - A pet the player has collected
- `QuestProgress` - Quest state (depends on quest type)
- `FarmingPatchState` - Crop and growth stage

## PlayerRepository API

The entry point for all player state changes:

```kotlin
// Queries
suspend fun getOrCreatePlayer(): Player
suspend fun getSkillLevel(skill: String): Int
suspend fun getInventory(): Map<String, Int>
suspend fun getCoins(): Long

// Mutations (all synchronized via playerMutex)
suspend fun addXp(skill: String, amount: Long)
suspend fun addLevel(skill: String)
suspend fun grantItem(key: String, qty: Int = 1)
suspend fun consumeItems(items: Map<String, Int>): Boolean
suspend fun addCoins(amount: Long)
suspend fun spendCoins(amount: Long): Boolean

// Transient state
suspend fun updateFlags(flags: PlayerFlags)
suspend fun updateFlagsAtomically(block: (PlayerFlags) -> PlayerFlags)

// Batch operations
suspend fun <T> withLock(block: suspend () -> T): T
```

All mutations go through `playerMutex` to prevent race conditions. Never bypass this—always go through the repository.

## SkillSessionDao

DAOs for database access. Example:

```kotlin
@Dao
interface SkillSessionDao {
    @Query("SELECT * FROM skill_sessions WHERE id = 1 AND completed = 0")
    suspend fun getActiveSession(): SkillSession?

    @Query("SELECT * FROM skill_sessions WHERE id = 1 AND completed = 0")
    fun observeActiveSession(): Flow<SkillSession?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SkillSession)
    
    @Update
    suspend fun update(session: SkillSession)
}
```

All queries are suspend functions or return Flow for reactive updates. Flow collectors in ViewModels automatically recompose on changes.

## Save Data Format

The local database file is `databases/idlefantasy.db` in the app's internal storage. There is no cloud sync—the game is purely offline.

On startup, if the database doesn't exist, `PlayerRepository.getOrCreatePlayer()` creates a new player with level 1, 0 XP, and empty inventory.

## Data Consistency

### Atomicity
Room transactions wrap each DAO call. For multi-step changes (e.g., apply 5 level-ups + 100 item drops), use `playerMutex.withLock { ... }` to ensure all steps complete before other coroutines can read the player.

### Validation
Repositories validate all mutations:
- Can't consume more items than you have
- Can't spend more coins than you have
- Skills capped at level 99
- XP never goes negative

## Performance Notes

- Database queries are run on `Dispatchers.IO` by default (Room requirement)
- JSON serialization happens on the calling coroutine (usually IO)
- Large JSON objects (inventory with 1000+ items) are fast enough for <= 60ms session completion
- No indexes beyond primary keys (dataset is tiny for a single player)
