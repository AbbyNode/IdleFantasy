# Architecture Overview

## What Is It?

Idle Fantasy is an offline idle RPG for Android. The player sets their character to perform a skill or combat activity for up to 60 minutes, then closes the app. The phone continues simulating progress in the background. When the player returns, they collect XP and loot, and start a new session.

**Key Design Principle:** No internet, no accounts, no stamina bars—just pure idle gameplay.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────┐
│             Compose UI Layer                        │
│  (Screens, ViewModels, Theme)                       │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────────┐
│          Hilt Dependency Injection                  │
└────────────────────┬────────────────────────────────┘
                     │
        ┌────────────┴────────────┬──────────────────┐
        │                         │                  │
┌───────▼─────────┐  ┌──────────▼──────┐  ┌────────▼──────┐
│ Repositories    │  │ Simulators      │  │ Notifications │
│ (Game State)    │  │ (Game Logic)    │  │ & Workers     │
└───────┬─────────┘  └─────────────────┘  └───────────────┘
        │
┌───────▼──────────────────┐
│  Room Database           │
│  + JSON Serialization    │
└──────────────────────────┘
```

## Core Components

### 1. **Data Layer**
- **Room Database** (`AppDatabase.kt`) - Single player profile + sessions, quests, farming, arena records
- **JSON Serialization** - Complex fields stored as JSON (inventory, skills, flags) using `kotlinx.serialization`
- **GameDataRepository** - Lazy-loads static game data from `assets/data/` (ores, fish, recipes, etc.)

### 2. **Business Logic Layer**
- **Simulators** - Pre-calculate 60 frames of skill/combat XP and item drops
  - `SkillSimulator` - Mining, fishing, woodcutting (gathering)
  - `CombatSimulator` - Enemy combat with exact RSC-like tick mechanics
  - `CarnivalSimulator`, `ThievingSimulator`, etc. - Specialized activities
- **Repositories** - Manage game state, coordinate simulators with the database
  - `PlayerRepository` - Inventory, equipment, skills (with `playerMutex` synchronization)
  - `SessionRepository` - Manage active/completed sessions, schedule AlarmManager alarms
  - Specialized repos: `QuestRepository`, `FarmingRepository`, `GuildRepository`, etc.

### 3. **Background Services**
- **SessionAlarmReceiver** - BroadcastReceiver fires when session completes; wakes CPU, marks session complete, triggers next queued session
- **SessionNotificationManager** - Sends Android notifications when sessions complete
- **QueuedSessionStarter** - Chains completed sessions into new sessions; used for repeated actions

### 4. **UI Layer**
- **Screens** (Compose) - `HomeScreen`, `CombatScreen`, `CraftingScreen`, etc.
- **ViewModels** - Flow-based state management; no LiveData
- **Theme** - Material Design 3 theming
- **Navigation** - Jetpack Navigation Compose

### 5. **Dependency Injection**
- **Hilt** for singleton repositories, database, JSON codec
- AndroidEntryPoint on all Activities/BroadcastReceivers
- Single application-scoped `AppModule` for shared `Json` instance

## Data Flow Example: Starting a Mining Session

1. User selects iron ore in `SkillsViewModel`
2. ViewModel calls `SessionRepository.startSession()`
3. `SessionRepository`:
   - Calls `SkillSimulator.simulateMining()` to pre-compute 60 frames
   - Serializes frames as JSON
   - Creates `SkillSession` entity with start/end timestamps
   - Inserts into Room DB
   - Schedules an AlarmManager alarm for the session end time
4. UI updates via `activeSessionFlow` and shows countdown timer
5. When alarm fires:
   - `SessionAlarmReceiver.onReceive()` wakes the CPU
   - Marks session as completed
   - Notifies user
   - Calls `QueuedSessionStarter` to chain next queued session if any

## Key Design Decisions

### JSON Storage in Database
Complex fields (inventory, skills, equipped items) are stored as JSON strings in Room entities, not normalized. This matches the original Python/SQLite schema and keeps the data model simple for an offline single-player game.

**Implication:** Always use repositories to deserialize JSON, never raw DAO access.

### Pre-Simulation
All 60 frames of XP and drops are calculated upfront in the simulator, serialized as JSON, and stored. At completion time, frames are played back into the player's inventory. This allows:
- Accurate drop simulation without running simulation again
- Perfect replayability (same random seed, same results)
- Easy debugging of sessions

### AlarmManager + BroadcastReceiver
Sessions are scheduled via Android's `AlarmManager`. When the session time elapses (or the device is plugged in for charging), the alarm fires and `SessionAlarmReceiver` handles the completion. This works even if the app is force-closed.

**Why not WorkManager?** WorkManager adds extra queuing delay and is less precise for critical game events. BroadcastReceiver is instant and wakes the CPU even if the app is dead.

### playerMutex
The `PlayerRepository` uses a coroutine `Mutex` to serialize all writes to the player entity. This prevents race conditions during rapid inventory changes (e.g., applying 5 level-ups and 100 item drops simultaneously).

## Directory Structure

```
app/src/main/kotlin/com/fantasyidler/
├── data/
│   ├── db/              # Room entities, DAOs, database
│   │   ├── dao/
│   │   ├── entity/
│   │   └── AppDatabase.kt
│   ├── model/           # Domain models (Player, SkillSession, etc.)
│   ├── json/            # Serializable data classes for static game data
│   └── (serializer)     # Custom JSON serializers if needed
├── simulator/           # Game logic (SkillSimulator, CombatSimulator, etc.)
├── repository/          # State management, DB coordination
├── ui/
│   ├── screen/          # Compose screens
│   ├── viewmodel/       # ViewModels
│   ├── theme/           # Material Design theming
│   └── navigation/      # Navigation graph
├── receiver/            # BroadcastReceiver for alarms
├── notification/        # Notification handling
├── util/                # Utility functions
└── di/                  # Dependency injection (Hilt modules)
```

## Technology Stack

- **Kotlin** - Language
- **Jetpack Compose** - UI framework
- **Room** - Local SQLite database
- **Hilt** - Dependency injection
- **Coroutines** - Asynchronous programming
- **kotlinx.serialization** - JSON serialization
- **Jetpack Navigation** - Screen routing
- **Jetpack DataStore** - Preferences (used by some modules)
- **Material Design 3** - UI components and theming
