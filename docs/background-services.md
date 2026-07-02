# Background Services

## SessionAlarmReceiver

`BroadcastReceiver` that fires when a skill/combat session completes.

### Flow

1. **Session started** → `SessionRepository.startSession()` creates session and schedules alarm via `AlarmManager`
2. **Time elapses** → System fires the alarm
3. **Receiver woken** → `SessionAlarmReceiver.onReceive()` is called even if app is closed
4. **Wake lock acquired** → `PowerManager.PARTIAL_WAKE_LOCK` keeps CPU awake during coroutine execution
5. **Frame playback** → Frames are applied to player inventory/XP
6. **Next session queued** → If user has queued another session, start it immediately
7. **Notification sent** → Show Android notification with loot summary

### Implementation Details

```kotlin
@AndroidEntryPoint
class SessionAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var queuedSessionStarter: QueuedSessionStarter
    @Inject lateinit var notificationManager: SessionNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        // 1. Acquire partial wake lock so CPU stays awake after onReceive() returns
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fantasyidler:session_alarm")
            .apply { acquire(30_000L) }  // Max 30 seconds

        // 2. Call goAsync() to allow async work
        val pending = goAsync()
        val sessionId = intent.getStringExtra(KEY_SESSION_ID) ?: run {
            pending.finish()
            wakeLock.release()
            return
        }

        // 3. Launch coroutine on IO dispatcher
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = sessionRepository.getSession(sessionId)
                sessionRepository.markCompleted(sessionId)
                
                // 4. Compute how late the alarm fired (for frame backdate)
                val now = System.currentTimeMillis()
                val backdateMs = if (session != null) maxOf(0L, now - session.endsAt) else 0L
                
                // 5. Handle worker vs main sessions
                if (session?.isWorkerSession == true) {
                    val slot = session.workerSlot.coerceAtLeast(1)
                    val workerStarted = workerQueuedSessionStarter.startNextQueued(slot)
                    if (!workerStarted) {
                        notificationManager.showSessionComplete(skillDisplayName)
                    }
                } else {
                    // 6. Apply frames and chain next session
                    var catchUpMs = backdateMs
                    while (catchUpMs > 0) {
                        val nextStarted = queuedSessionStarter.startNextQueued()
                        if (!nextStarted) {
                            notificationManager.showSessionComplete(skillDisplayName)
                            break
                        }
                        catchUpMs -= 3_600_000L  // Next session duration
                    }
                }
            } finally {
                pending.finish()
                wakeLock.release()
            }
        }
    }
}
```

### Why BroadcastReceiver, Not WorkManager?

- **Instant** - BroadcastReceiver fires immediately; WorkManager has queuing overhead
- **Wake lock control** - Explicit wake lock prevents CPU suspension mid-execution
- **Precise timing** - AlarmManager with `setAndAllowWhileIdle()` is reliable for game events
- **Minimal overhead** - No extra Jetpack dependency for a single use case

### AndroidManifest Declaration

```xml
<receiver android:name=".receiver.SessionAlarmReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.fantasyidler.SESSION_ALARM" />
    </intent-filter>
</receiver>
```

## SessionNotificationManager

Sends Android notifications when sessions complete.

### Usage

```kotlin
suspend fun showSessionComplete(skillDisplayName: String) {
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("$skillDisplayName session complete!")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(pendingIntent)
        .build()
    
    notificationManager.notify(NOTIFICATION_ID, notification)
}
```

Notifications include:
- Skill/activity name
- XP gained
- Top 3 items looted
- Tap to open app

### Notification Channels

Android 8.0+ requires notification channels:

```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,
    "Session Completion",
    NotificationManager.IMPORTANCE_DEFAULT,
)
channel.description = "Notifications when skill/combat sessions complete"
notificationManager.createNotificationChannel(channel)
```

## QueuedSessionStarter

Manages chaining of sessions. When a session completes and the user has queued actions (e.g., "Mine iron for 10 sessions"), this handles auto-starting the next one.

### Queue State

Queue is stored in `GlobalState` entity:

```kotlin
@Entity(tableName = "global_state")
data class GlobalState(
    @PrimaryKey val key: String,
    val value: String,  // JSON
)
```

Example queue entry:

```json
{
    "type": "queuedSession",
    "skill": "mining",
    "activity": "iron_ore",
    "countRemaining": 9,
    "startedAt": 1720000000000
}
```

### Startup Logic

```kotlin
suspend fun startNextQueued(): Boolean {
    val queuedEntry = globalStateRepo.getQueuedSession() ?: return false
    
    // 1. Load player stats
    val player = playerRepo.getOrCreatePlayer()
    
    // 2. Load activity data
    val activity = gameData.getActivity(queuedEntry.skill, queuedEntry.activity)
    
    // 3. Simulate session
    val simulationResult = when (queuedEntry.skill) {
        "mining" -> SkillSimulator.simulateMining(...)
        "combat" -> CombatSimulator.simulateCombat(...)
        else -> return false
    }
    
    // 4. Start session with frames
    sessionRepository.startSession(
        skillName = queuedEntry.skill,
        activityKey = queuedEntry.activity,
        frames = json.encode(simulationResult.frames),
        durationMs = simulationResult.durationMs,
        skillDisplayName = activity.displayName,
    )
    
    // 5. Decrement counter
    val newCount = queuedEntry.countRemaining - 1
    if (newCount > 0) {
        globalStateRepo.updateQueuedSession(queuedEntry.copy(countRemaining = newCount))
    } else {
        globalStateRepo.clearQueuedSession()
    }
    
    return true
}
```

### Queuing from UI

When the user selects "Start 10x sessions":

```kotlin
suspend fun queueMultipleSessions(
    skill: String,
    activity: String,
    count: Int,
) {
    if (count <= 0) return
    
    globalStateRepo.setQueuedSession(QueuedSessionEntry(
        skill = skill,
        activity = activity,
        countRemaining = count,
    ))
    
    // Start the first one immediately
    startNextQueued()
}
```

## WorkerQueuedSessionStarter

Similar to `QueuedSessionStarter`, but for **worker** sessions (background grinding).

Players can hire workers to grind while the main player is doing something else. Workers have:
- **Efficiency multiplier** - Usually 70% of main player efficiency
- **Independent queue** - Each worker slot has its own queue
- **Slot number** - 1, 2, or 3 workers

### Worker Session Flow

```kotlin
suspend fun startNextQueued(workerSlot: Int): Boolean {
    val worker = getWorkerConfig(workerSlot) ?: return false
    val queue = globalStateRepo.getWorkerQueue(workerSlot) ?: return false
    
    val simulationResult = when (worker.activity.skill) {
        "mining" -> SkillSimulator.simulateMining(
            // ... same as main session, but:
            petBoostPct = worker.petBoostPercent,
            // Efficiency is already baked into the recipe
        )
        // ...
    }
    
    // Start worker session
    sessionRepository.startWorkerSession(
        workerSlot = workerSlot,
        skillName = worker.activity.skill,
        activityKey = worker.activity.key,
        frames = json.encode(simulationResult.frames),
        durationMs = simulationResult.durationMs,
        efficiencyMultiplier = worker.efficiencyMultiplier,
    )
    
    // Decrement and continue
    queue.countRemaining--
    if (queue.countRemaining > 0) {
        globalStateRepo.updateWorkerQueue(workerSlot, queue)
    } else {
        globalStateRepo.clearWorkerQueue(workerSlot)
    }
    
    return true
}
```

When a worker session completes, frames are applied with the efficiency multiplier already factored in (no separate scaling needed at completion time).

## Backdate Handling

When the system wakes up late (e.g., device was off), the receiver computes how many additional sessions could complete in the elapsed time:

```kotlin
val now = System.currentTimeMillis()
val backdateMs = maxOf(0L, now - session.endsAt)

// Queue additional sessions to "catch up"
var catchUpMs = backdateMs
while (catchUpMs > 0) {
    val nextStarted = queuedSessionStarter.startNextQueued()
    if (!nextStarted) break
    catchUpMs -= 3_600_000L  // 1 hour session
}
```

This ensures the player gets all the XP/loot they would have earned if the app had been open.

## AlarmManager Scheduling

Alarms are scheduled with `setAndAllowWhileIdle()` to work even in Doze mode:

```kotlin
val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
val intent = PendingIntent.getBroadcast(
    context,
    sessionId.hashCode(),
    Intent(ACTION_SESSION_ALARM).apply {
        putExtra(KEY_SESSION_ID, sessionId)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

alarmManager.setAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP,
    sessionEndTimeMs,
    intent,
)
```

## Permissions Required

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- `SCHEDULE_EXACT_ALARM` - Set precise alarms
- `WAKE_LOCK` - Acquire partial wake lock in receiver
- `POST_NOTIFICATIONS` - Send notifications (Android 13+)

## Debugging

Test alarm firing locally:

```bash
# Trigger alarm for session:
adb shell am broadcast -a com.fantasyidler.SESSION_ALARM \
    --es sessionId "test-session-123"

# Check wake lock status:
adb shell dumpsys power | grep fantasyidler
```

Check logs for receiver execution:
```bash
adb logcat | grep SessionAlarmReceiver
```
