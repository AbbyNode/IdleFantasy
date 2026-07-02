# UI Architecture

## Overview

The UI is built with **Jetpack Compose** and **Material Design 3**. All screens are reactive and driven by **Flow-based state** in ViewModels.

Navigation is handled by **Jetpack Navigation Compose** in `navigation/AppNavGraph.kt`.

## Screen Structure

Each screen typically follows this pattern:

```kotlin
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    
    when (state) {
        is SkillsUiState.Loading -> {
            CircularProgressIndicator()
        }
        is SkillsUiState.Content -> {
            SkillsContent(
                state as SkillsUiState.Content,
                onSkillSelected = { viewModel.onSkillSelected(it) },
            )
        }
    }
}

@Composable
fun SkillsContent(state: SkillsUiState.Content, onSkillSelected: (String) -> Unit) {
    // Actual UI layout
    LazyColumn {
        items(state.skills) { skill ->
            SkillCard(skill, onClick = { onSkillSelected(skill.name) })
        }
    }
}
```

### State Management

Each ViewModel has a `state` Flow that emits UI state objects:

```kotlin
@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SkillsUiState>(SkillsUiState.Loading)
    val state = _state.asStateFlow()
    
    init {
        viewModelScope.launch {
            playerRepo.playerFlow
                .collect { player ->
                    _state.value = SkillsUiState.Content(
                        skills = buildSkillList(player),
                    )
                }
        }
    }
    
    fun onSkillSelected(skillKey: String) {
        viewModelScope.launch {
            // Handle user action
            playerRepo.startSkillSession(skillKey)
        }
    }
}

sealed class SkillsUiState {
    object Loading : SkillsUiState()
    data class Content(val skills: List<SkillDisplay>) : SkillsUiState()
    data class Error(val message: String) : SkillsUiState()
}
```

### Sealed Class State

UI state is usually a sealed class to represent all possible states:
- `Loading` - Initial fetch
- `Content` - Data ready
- `Error` - Something went wrong
- Optional: `Empty` - No data to display

### collectAsState

Screens use `state.collectAsState()` to automatically recompose when the state Flow emits:

```kotlin
val state by viewModel.state.collectAsState()
```

The default scope is `viewModelScope`, which is properly cancelled when the ViewModel is cleared.

## Common Patterns

### Observing Multiple Flows

For ViewModels that need to combine multiple repositories' data:

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val sessionRepo: SessionRepository,
) : ViewModel() {
    val state = combine(
        playerRepo.playerFlow,
        sessionRepo.activeSessionFlow,
        sessionRepo.completedCountFlow,
    ) { player, activeSession, completedCount ->
        HomeUiState(
            coins = player.coins,
            skillLevels = playerRepo.getSkillLevels(),
            activeSession = activeSession,
            pendingCollectCount = completedCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}
```

`combine()` collects all flows and emits a new state whenever any upstream flow updates. `stateIn()` converts to a StateFlow with a replay buffer of the latest state.

### Snackbar and Dialog State

For temporary UI actions (showing a snackbar, opening a dialog):

```kotlin
data class HomeUiState(
    val coins: Long,
    val activeSession: SkillSession?,
    val snackbarMessage: String? = null,  // Show snackbar if not null
    val dialogType: DialogType? = null,   // Show dialog if not null
)

enum class DialogType {
    CONFIRM_QUEST,
    LEVEL_UP,
    INSUFFICIENT_ITEMS,
}
```

The screen observes these flags and conditionally renders dialogs/snackbars:

```kotlin
if (state.snackbarMessage != null) {
    LaunchedEffect(state.snackbarMessage) {
        snackbarHostState.showSnackbar(state.snackbarMessage)
        viewModel.clearSnackbar()  // Clear flag to prevent re-showing
    }
}

if (state.dialogType == DialogType.CONFIRM_QUEST) {
    AlertDialog(
        onDismiss = { viewModel.closeDialog() },
        ...
    )
}
```

### LaunchedEffect for Side Effects

Use `LaunchedEffect` to trigger side effects (API calls, logging) when state changes:

```kotlin
LaunchedEffect(state.selectedSkillKey) {
    if (state.selectedSkillKey != null) {
        // Load skill details when selection changes
        viewModel.loadSkillDetails(state.selectedSkillKey)
    }
}
```

The key is the condition; if it changes, the block re-runs. Use `DisposableEffect` for cleanup.

## Navigation

Navigation graph in `ui/navigation/AppNavGraph.kt`:

```kotlin
@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToSkills = { navController.navigate("skills") },
            )
        }
        composable("skills") {
            SkillsScreen(
                onNavigate = { route -> navController.navigate(route) },
            )
        }
        composable("combat/{dungeonKey}") { backStackEntry ->
            val dungeonKey = backStackEntry.arguments?.getString("dungeonKey") ?: return@composable
            CombatScreen(dungeonKey)
        }
    }
}
```

Deep linking and argument passing are supported. For complex data, pass minimal keys and let ViewModels load details.

## Theming

Material Design 3 theming in `ui/theme/Theme.kt`:

```kotlin
@Composable
fun IdleFantasyTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        useDarkTheme -> darkColorScheme(
            primary = Color(0xFFB39DDB),
            ...
        )
        else -> lightColorScheme(...)
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(...),
        shapes = Shapes(...),
        content = content,
    )
}
```

All screens wrap their content in `IdleFantasyTheme` at the Activity level.

## ViewModel Injection

ViewModels are injected using `hiltViewModel()`:

```kotlin
@Composable
fun SomeScreen(viewModel: SomeViewModel = hiltViewModel()) {
    // ViewModel is automatically provided by Hilt
}
```

This requires the Activity to be annotated with `@AndroidEntryPoint`.

## Performance Considerations

### Recomposition Scope
Compose recomposes only affected `@Composable` functions. Extract parameters to smaller composables to limit recomposition:

```kotlin
@Composable
fun SkillList(skills: List<SkillDisplay>) {
    LazyColumn {
        items(skills, key = { it.name }) { skill ->
            SkillItem(skill)  // Only recomposes if this item changes
        }
    }
}

@Composable
fun SkillItem(skill: SkillDisplay) {
    // Doesn't recompose if a sibling skill changes
}
```

### Expensive Operations
Memoize expensive computations:

```kotlin
val skillsSorted = remember(skills) {
    skills.sortedBy { it.name }
}
```

`remember` runs only when `skills` changes.

### Large Lists
Always use `LazyColumn`/`LazyRow` for lists, never `Column` or `Row`. Only visible items are composed.

## Screen Examples

### HomeScreen
Entry point. Shows active session, pending loots, skill levels, coins. Main actions:
- Tap session to view details
- Collect pending loot
- Navigate to other screens via FAB

### SkillsScreen
Lists all skills with current level. Tap to select an activity (e.g., "Mining: Iron Ore"). Shows:
- XP to next level
- Current XP in skill
- Available activities with level reqs
- Pet/equipment bonuses if applicable

### CombatScreen
Real-time combat display (while session is active). Shows:
- Enemy health
- Player health
- XP/damage this session
- Estimated time remaining
- Live ticker of kills/loot

When session completes, shows `CombatResultSheet` with full breakdown.

### CraftingScreen
Recipe list with material reqs. Tap to start. Shows:
- Material availability
- XP per item
- Queue multiple
- Progress bar

### ChurchScreen
Prayer blessings and ecto-offerings. Shows:
- Active buff status and duration
- Bone inventory
- Altar interface
- Upgrade to higher-level blessings

## Accessibility

Use semantic composables:
- `Button` instead of `Surface + clickable`
- `IconButton` with content description
- `Text` with proper text hierarchy
- `Scaffold` for top app bar and snackbar host

Always provide `contentDescription` for icons:

```kotlin
Icon(
    imageVector = Icons.Filled.Settings,
    contentDescription = "Open settings",
)
```

## Testing

UI tests use **Compose testing API**:

```kotlin
@get:Rule
val composeTestRule = createComposeRule()

@Test
fun skillsScreen_displaysSkills() {
    composeTestRule.setContent {
        IdleFantasyTheme {
            SkillsScreen(onNavigate = {})
        }
    }
    
    composeTestRule.onNodeWithText("Mining").assertIsDisplayed()
    composeTestRule.onNodeWithText("Mining").performClick()
    // Verify navigation happened
}
```

Tests use `composeTestRule` to interact with the UI tree and verify state.
