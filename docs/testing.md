# Testing Guide

## Testing Philosophy

The codebase prioritizes **simulator tests** (deterministic game logic) over UI tests (expensive and brittle). Integration tests cover the data flow (simulator → repository → database).

## Test Structure

```
app/src/test/kotlin/com/fantasyidler/
├── simulator/
│   ├── SkillSimulatorTest.kt
│   ├── CombatSimulatorTest.kt
│   └── XpTableTest.kt
├── repository/
│   ├── PlayerRepositoryTest.kt
│   ├── SessionRepositoryTest.kt
│   └── GameDataRepositoryTest.kt
└── util/
    └── JsonSerializationTest.kt
```

## Simulator Tests

Simulators are pure functions (no dependencies on repositories or database). Tests verify:
- **Determinism** - Same input = same output
- **XP calculations** - Correct XP for level and activity
- **Drop rates** - Correct item drops and quantities
- **Duration reduction** - Agility bonus applied correctly

### Example: SkillSimulator Test

```kotlin
class SkillSimulatorTest {
    private val oreData = OreData(
        name = "Iron Ore",
        rate = 3.0,  // 3 ores per minute
        xpPerOre = 25,
        levelReq = 15,
    )
    private val gems = mapOf(
        "sapphire" to GemData(weight = 40, xpPerGem = 10),
        "emerald" to GemData(weight = 60, xpPerGem = 20),
    )

    @Test
    fun simulateMining_withSameSeed_producesSameResult() {
        val seed = 12345L
        val result1 = SkillSimulator.simulateMining(
            oreKey = "iron_ore",
            oreData = oreData,
            gems = gems,
            startXp = 0L,
            agilityLevel = 1,
            petBoostPct = 0,
            toolEfficiency = 1.0f,
            rnd = Random(seed),
        )
        
        val result2 = SkillSimulator.simulateMining(
            oreKey = "iron_ore",
            oreData = oreData,
            gems = gems,
            startXp = 0L,
            agilityLevel = 1,
            petBoostPct = 0,
            toolEfficiency = 1.0f,
            rnd = Random(seed),
        )
        
        assertEquals(result1.frames, result2.frames)
        assertEquals(result1.durationMs, result2.durationMs)
    }

    @Test
    fun simulateMining_appliesAgilityReduction() {
        val noAgility = SkillSimulator.simulateMining(
            agilityLevel = 1,
            rnd = Random(1),
            // ... other params
        )
        
        val withAgility = SkillSimulator.simulateMining(
            agilityLevel = 99,
            rnd = Random(1),
            // ... other params
        )
        
        // Agility reduces duration (99 - 1) * 1% = 98% reduction
        assertTrue(withAgility.durationMs < noAgility.durationMs)
        assertEquals(withAgility.durationMs, (noAgility.durationMs * 0.02).toLong(), delta = 1000L)
    }

    @Test
    fun simulateMining_correctsXpThreshold() {
        val result = SkillSimulator.simulateMining(
            startXp = XpTable.xpForLevel(20) - 1,  // 1 XP before level up
            agilityLevel = 1,
            rnd = Random(2),
            // ... other params
        )
        
        // First frame should have exactly enough XP to level up
        val firstFrame = result.frames.first()
        assertNotNull(firstFrame.levelUp)
        assertEquals(20, firstFrame.levelUp?.toLevel)
    }

    @Test
    fun simulateMining_countsItemsCorrectly() {
        val result = SkillSimulator.simulateMining(
            oreData = oreData.copy(rate = 2.0),  // Exactly 2 ores per minute
            rnd = Random(3),
            // ... other params
        )
        
        var totalOres = 0
        for (frame in result.frames) {
            totalOres += frame.itemDrops["iron_ore"] ?: 0
        }
        
        // 60 frames × 2 ores/frame = 120 ores
        assertEquals(120, totalOres)
    }
}
```

## Repository Tests

Repository tests are **integration tests** with mocked database. They verify:
- Correct serialization/deserialization
- State mutations via mutex
- Notifications triggered correctly

### Example: PlayerRepositoryTest

```kotlin
@get:Rule
val instantExecutorRule = InstantTaskExecutorRule()

private lateinit var playerDao: PlayerDao
private lateinit var playerRepository: PlayerRepository
private lateinit var json: Json

@Before
fun setup() {
    playerDao = mockk(relaxed = true)
    json = Json { /* standard config */ }
    playerRepository = PlayerRepository(
        playerDao = playerDao,
        json = json,
        // ... other mocked dependencies
    )
}

@Test
fun addXp_correctlyUpdatesSkillXp() = runTest {
    val initialPlayer = Player(skillXp = json.encode<Map<String, Long>>(mapOf("mining" to 0L)))
    coEvery { playerDao.getPlayer() } returns initialPlayer
    
    playerRepository.addXp("mining", 100L)
    
    // Verify upsert was called with updated XP
    coVerify { playerDao.upsert(match { player ->
        val xpMap: Map<String, Long> = json.decodeFromString(player.skillXp)
        xpMap["mining"] == 100L
    }) }
}

@Test
fun addXp_triggersMutex() = runTest {
    // Launch multiple coroutines that all try to add XP
    val results = mutableListOf<Unit>()
    repeat(10) {
        launch {
            playerRepository.addXp("mining", 10L)
            results.add(Unit)
        }
    }
    
    advanceUntilIdle()
    
    // All 10 coroutines completed (no deadlock)
    assertEquals(10, results.size)
    
    // Verify XP was added (not lost due to race condition)
    coVerify { playerDao.upsert(match { player ->
        val xpMap: Map<String, Long> = json.decodeFromString(player.skillXp)
        xpMap["mining"] == 100L  // 10 × 10L
    }) }
}

@Test
fun consumeItems_failsIfNotEnoughItems() = runTest {
    val player = Player(inventory = json.encode<Map<String, Int>>(mapOf("iron_ore" to 5)))
    coEvery { playerDao.getPlayer() } returns player
    
    val success = playerRepository.consumeItems(mapOf("iron_ore" to 10))
    
    assertFalse(success)
    coVerify(exactly = 0) { playerDao.upsert(any()) }  // No update
}
```

## JSON Serialization Tests

Test that JSON round-trips work and forward/backward compatibility is maintained:

```kotlin
class JsonSerializationTest {
    private val json = Json { /* standard config */ }

    @Test
    fun playerFlags_roundTrip() {
        val original = PlayerFlags(
            currentHp = 50,
            activeSpell = "water_strike",
            activePrayer = "superhuman_strength",
            seenItemKeys = listOf("iron_ore", "gold_ore"),
        )
        
        val serialized = json.encodeToString<PlayerFlags>(original)
        val deserialized = json.decodeFromString<PlayerFlags>(serialized)
        
        assertEquals(original, deserialized)
    }

    @Test
    fun playerFlags_forwardCompatibility() {
        // Old JSON missing a new field
        val oldJson = """
        {
            "currentHp": 50,
            "activeSpell": "water_strike"
        }
        """
        
        val deserialized = json.decodeFromString<PlayerFlags>(oldJson)
        
        // Missing field gets default value
        assertEquals(50, deserialized.currentHp)
        assertEquals("water_strike", deserialized.activeSpell)
        assertNull(deserialized.activePrayer)  // New field, default to null
    }

    @Test
    fun sessionFrame_listRoundTrip() {
        val frames = listOf(
            SessionFrame(xpGain = 100L, itemDrops = mapOf("iron_ore" to 3)),
            SessionFrame(xpGain = 120L, itemDrops = mapOf("iron_ore" to 2, "copper_ore" to 1)),
        )
        
        val serialized = json.encodeToString<List<SessionFrame>>(frames)
        val deserialized = json.decodeFromString<List<SessionFrame>>(serialized)
        
        assertEquals(frames, deserialized)
    }
}
```

## End-to-End Session Tests

Test a full session flow: simulate → store → complete → apply frames → verify inventory:

```kotlin
@get:Rule
val instantExecutorRule = InstantTaskExecutorRule()

@Test
fun miningSession_completesSuccessfully() = runTest {
    val database = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).build()
    
    val playerDao = database.playerDao()
    val sessionDao = database.skillSessionDao()
    
    val playerRepo = PlayerRepository(playerDao, /* ... */)
    val sessionRepo = SessionRepository(sessionDao, /* ... */)
    val queuedStarter = QueuedSessionStarter(playerRepo, /* ... */)
    
    // 1. Create player with 0 mining XP
    playerRepo.getOrCreatePlayer()
    
    // 2. Start mining session
    val frames = listOf(
        SessionFrame(xpGain = 100L, itemDrops = mapOf("iron_ore" to 3)),
        SessionFrame(xpGain = 100L, itemDrops = mapOf("iron_ore" to 2)),
    )
    val framesJson = json.encodeToString(frames)
    
    val session = sessionRepo.startSession(
        skillName = "mining",
        activityKey = "iron_ore",
        frames = framesJson,
        durationMs = 100,  // Immediate completion
    )
    
    // 3. Mark completed
    sessionRepo.markCompleted(session.sessionId)
    
    // 4. Apply frames
    queuedStarter.applySessionFrames(session)
    
    // 5. Verify state
    val finalPlayer = playerRepo.getOrCreatePlayer()
    val inventory = playerRepo.getInventory()
    val miningXp = playerRepo.getSkillXp("mining")
    
    assertEquals(200L, miningXp)  // 100 + 100
    assertEquals(5, inventory["iron_ore"])  // 3 + 2
}
```

## Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests SkillSimulatorTest

# Run specific test method
./gradlew test --tests SkillSimulatorTest.simulateMining_withSameSeed_producesSameResult

# Run with coverage
./gradlew testDebugUnitTestCoverage
```

## What NOT to Test

- **UI Layout** - Compose layout tests are brittle. Test state instead.
- **Navigation** - Tested by hand. Easy to break, hard to test.
- **Database Migrations** - Test manually on actual database.
- **Material Design components** - Trust Jetpack libraries.

## Mocking Strategy

- **Mock DAOs** - Use `mockk` for database interactions
- **Mock GameDataRepository** - Return hardcoded test data
- **Don't mock Simulators** - Test them directly (they're pure functions)
- **Real Json instance** - Use standard Json config, not a mock

## Test Data Builders

For complex test objects, use builder pattern:

```kotlin
fun createTestPlayer(
    mining: Int = 1,
    miningXp: Long = 0L,
    coins: Long = 1000L,
) = Player(
    skillLevels = json.encode<Map<String, Int>>(mapOf("mining" to mining)),
    skillXp = json.encode<Map<String, Long>>(mapOf("mining" to miningXp)),
    coins = coins,
)

fun createTestSession(
    skillName: String = "mining",
    completed: Boolean = false,
) = SkillSession(
    sessionId = UUID.randomUUID().toString(),
    skillName = skillName,
    activityKey = "iron_ore",
    completed = completed,
    startedAt = System.currentTimeMillis(),
    endsAt = System.currentTimeMillis() + 3_600_000,
    frames = "[]",
)
```

## Debugging Tests

```kotlin
// Print state during test
println("Player: $player")
println("Inventory: ${playerRepo.getInventory()}")

// Step through with breakpoint
debugPrintln("Before: ${playerRepo.getSkillXp(\"mining\")}")
playerRepo.addXp("mining", 100L)
debugPrintln("After: ${playerRepo.getSkillXp(\"mining\")}")
```

## CI/CD Integration

Tests run on every push via GitHub Actions. See `.github/workflows/test.yml`:

```yaml
- name: Run unit tests
  run: ./gradlew test

- name: Upload coverage
  uses: codecov/codecov-action@v3
  with:
    files: ./app/build/coverage.xml
```

Current coverage target: **60%+** for business logic, lower for UI.
