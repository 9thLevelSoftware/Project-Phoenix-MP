# Phase 21: RPG Attributes - Research

**Researched:** 2026-02-28
**Domain:** Gamification engine (attribute computation, character classification, schema migration, Compose UI card)
**Confidence:** HIGH

## Summary

Phase 21 adds an RPG-style attribute system to the existing gamification screen (`BadgesScreen`). Five attributes (Strength, Power, Stamina, Consistency, Mastery) are computed locally from workout session data already stored in the database. A character class is auto-assigned from the dominant attribute ratio. The feature follows the exact pattern established by Phase 20 (ReadinessEngine): a pure stateless computation engine in `domain/premium/`, domain model types, a Compose card component, and tier-gated UI integration.

The primary technical challenge is the schema migration (v17) for the new `RpgAttributes` table, which requires syncing `DriverFactory.ios.kt` CURRENT_SCHEMA_VERSION. The computation engine itself is straightforward -- all required workout data (weight, reps, volume, session timestamps, PR counts, unique exercises) is already queryable via existing SQLDelight queries (`countTotalWorkouts`, `countTotalVolume`, `countPersonalRecords`, `selectWorkoutDates`, etc.) or simple additions.

**Primary recommendation:** Follow the ReadinessEngine pattern exactly -- pure `object` computation engine with TDD, domain model types, single composable card, gated by `hasProAccess` on the BadgesScreen.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| RPG-01 | Five attributes (Strength, Power, Stamina, Consistency, Mastery) computed locally from workout data | RpgAttributeEngine pure object computes from existing DB data; GamificationRepository or SmartSuggestionsRepository provides session data |
| RPG-02 | Character class auto-assigned from attribute ratios (Powerlifter, Athlete, Ironman, Monk, Phoenix) | CharacterClass enum with `fromAttributes()` factory using dominant attribute ratio analysis |
| RPG-03 | Compact attribute card displayed on gamification screen (Phoenix+ tier) | RpgAttributeCard composable placed in BadgesScreen above StreakWidget, gated by `hasProAccess` |
| RPG-04 | "View full skill tree on Phoenix Portal" deep link on attribute card | TextButton with `onPortalLink` callback, matching ReadinessBriefingCard pattern |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| SQLDelight | 2.0.2 | Type-safe DB for RpgAttributes table | Already used for all persistence in the project |
| Compose Multiplatform | 1.7.1 | RpgAttributeCard composable | Project's UI framework |
| Koin | 4.0.0 | DI for repository injection | Project's DI framework |
| kotlin.test | (bundled) | TDD for RpgAttributeEngine | Used for ReadinessEngineTest, SmartSuggestionsEngineTest |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Material3 | (via Compose) | Card, LinearProgressIndicator, AccessibilityTheme | For attribute card UI |
| Coroutines | 1.9.0 | Flow-based attribute state | Already wired throughout presentation layer |

No new dependencies needed. Everything required is already in the project.

## Architecture Patterns

### Recommended Project Structure
```
shared/src/
  commonMain/kotlin/com/devil/phoenixproject/
    domain/
      model/
        RpgModels.kt           # RpgAttribute enum, RpgProfile, CharacterClass
      premium/
        RpgAttributeEngine.kt  # Pure computation object (like ReadinessEngine)
    data/
      repository/
        GamificationRepository.kt  # Add getRpgProfile() method or new RpgRepository
    presentation/
      components/
        RpgAttributeCard.kt    # Composable card (like ReadinessBriefingCard)
      screen/
        BadgesScreen.kt        # Wire RpgAttributeCard with tier gating
  commonMain/sqldelight/.../
    VitruvianDatabase.sq       # Add RpgAttributes table + queries
    migrations/
      17.sqm                   # CREATE TABLE RpgAttributes
  iosMain/.../
    DriverFactory.ios.kt       # CURRENT_SCHEMA_VERSION = 18L
```

### Pattern 1: Pure Stateless Computation Engine
**What:** All RPG attribute math in a stateless `object` with pure functions. No DB, no DI.
**When to use:** Always for domain computation (established pattern)
**Example:**
```kotlin
// Source: ReadinessEngine.kt pattern (verified in codebase)
object RpgAttributeEngine {

    /**
     * Compute RPG profile from aggregate workout data.
     * Pure function -- all inputs passed explicitly for testability.
     */
    fun computeProfile(input: RpgInput): RpgProfile {
        val strength = computeStrength(input)
        val power = computePower(input)
        val stamina = computeStamina(input)
        val consistency = computeConsistency(input)
        val mastery = computeMastery(input)
        val characterClass = classifyCharacter(strength, power, stamina, consistency, mastery)
        return RpgProfile(strength, power, stamina, consistency, mastery, characterClass)
    }
}
```

### Pattern 2: Tier-Gated Card on Existing Screen
**What:** Card composable placed on BadgesScreen, gated by `hasProAccess` from SubscriptionManager
**When to use:** For Phoenix+ tier features on the gamification screen
**Example:**
```kotlin
// Source: ActiveWorkoutScreen.kt readiness briefing pattern (verified in codebase)
@Composable
fun BadgesScreen(...) {
    val hasProAccess by subscriptionManager.hasProAccess.collectAsState()

    Column {
        // RPG attribute card -- Phoenix+ only (RPG-03)
        if (hasProAccess) {
            val rpgProfile by rpgViewModel.rpgProfile.collectAsState()
            rpgProfile?.let { profile ->
                RpgAttributeCard(
                    profile = profile,
                    onPortalLink = { /* deep link */ },
                    modifier = Modifier.padding(Spacing.medium)
                )
            }
        }

        // Existing StreakWidget, StatsRow, etc.
        StreakWidget(...)
    }
}
```

### Pattern 3: Schema Migration with iOS Sync
**What:** New SQLDelight migration file + iOS DriverFactory version bump
**When to use:** Any schema change (established pattern, Daem0n warning #155)
**Example:**
```sql
-- 17.sqm: RPG Attributes table (Phase 21, RPG-01)
CREATE TABLE RpgAttributes (
    id INTEGER PRIMARY KEY DEFAULT 1,
    strength INTEGER NOT NULL DEFAULT 0,
    power INTEGER NOT NULL DEFAULT 0,
    stamina INTEGER NOT NULL DEFAULT 0,
    consistency INTEGER NOT NULL DEFAULT 0,
    mastery INTEGER NOT NULL DEFAULT 0,
    characterClass TEXT NOT NULL DEFAULT 'Phoenix',
    lastComputed INTEGER NOT NULL DEFAULT 0
);
```

### Anti-Patterns to Avoid
- **Storing RPG data in GamificationStats singleton:** Explicit project decision -- RPG gets its own table (STATE.md). GamificationStats has hardcoded `id=1` and is already overloaded.
- **Complex SQL for attribute computation:** Keep SQL queries simple (counts, sums). All math belongs in `RpgAttributeEngine` for testability.
- **Calling DB during composition:** Follow the readiness briefing pattern -- compute once on screen open via `LaunchedEffect`, store in state.
- **Using `PremiumFeatureGate` composable for this:** The `PremiumFeatureGate` component gates via `SubscriptionManager.hasProAccess` which is a generic Pro check. Instead, use the direct `hasProAccess` collectAsState pattern from ActiveWorkoutScreen to conditionally render the card (cleaner, no lock overlay needed).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Workout data aggregation | Custom raw SQL cursor walks | Existing SQLDelight queries (`countTotalWorkouts`, `countTotalVolume`, `countPersonalRecords`, `selectWorkoutDates`) | Already type-safe, tested, and maintained |
| Tier gating | Custom tier check logic | `SubscriptionManager.hasProAccess` StateFlow | Established pattern; handles grace period via FeatureGate.resolveEffectiveTier |
| Attribute normalization | Ad-hoc percentage math | Standard min-max normalization with configurable ceiling values | Reproducible, testable, consistent across attributes |
| Deep link handling | Intent/URI construction | Simple `onPortalLink` callback placeholder (like ReadinessBriefingCard) | Portal integration deferred to v0.6.0+ (PORTAL-02) |

**Key insight:** RPG attribute computation is fundamentally a pure mapping from existing aggregate data to a 0-100 scale per attribute. No new data collection is needed -- the same `WorkoutSession`, `PersonalRecord`, and `EarnedBadge` tables already hold everything required.

## Common Pitfalls

### Pitfall 1: iOS Schema Version Mismatch
**What goes wrong:** App crashes on iOS if `DriverFactory.ios.kt` CURRENT_SCHEMA_VERSION stays at 17L when migration 17.sqm exists (SQLDelight expects version 18L = 1 + number of .sqm files)
**Why it happens:** iOS uses a custom DriverFactory with manual version management rather than SQLDelight's auto-migration
**How to avoid:** Migration 17.sqm requires `CURRENT_SCHEMA_VERSION = 18L` in `DriverFactory.ios.kt`. This is a same-commit change, not a follow-up.
**Warning signs:** iOS build succeeds but crashes at runtime with SQLite version mismatch

### Pitfall 2: Division by Zero in Attribute Normalization
**What goes wrong:** New users with 0 workouts, 0 PRs, or 0 volume will produce NaN/Infinity in percentage calculations
**Why it happens:** Normalization requires dividing by max/ceiling values that could be zero for edge cases
**How to avoid:** Return `RpgProfile.EMPTY` (all zeros, "Phoenix" class) when total workouts < 1. Guard every division.
**Warning signs:** Kotlin `Float.NaN` propagates silently and renders as "NaN" in Compose Text

### Pitfall 3: RpgAttributes Table Singleton Pattern
**What goes wrong:** Using hardcoded `id = 1` like GamificationStats creates tight coupling and makes multi-user support harder later
**Why it happens:** GamificationStats used this pattern and it worked
**How to avoid:** Accept the `id = 1` singleton pattern for now (matches GamificationStats), but use `INSERT OR REPLACE` and always query by `WHERE id = 1`. This is explicitly acceptable per the project's current single-user architecture.
**Warning signs:** None for now -- this is an informed tradeoff

### Pitfall 4: koinInject Inside Conditional Block
**What goes wrong:** Calling `koinInject()` inside an `if (hasProAccess)` block means DI resolution is skipped for non-premium users, which is correct and intentional for performance
**Why it happens:** Following the Phase 20 pattern (koinInject SmartSuggestionsRepository inside Elite guard)
**How to avoid:** This is the desired pattern per STATE.md decision: "koinInject SmartSuggestionsRepository inside Elite guard to avoid DI resolution for non-Elite users"
**Warning signs:** None -- this is intentional

### Pitfall 5: Stale Attributes on Screen Navigation
**What goes wrong:** User completes a workout, navigates to BadgesScreen, sees old attribute values
**Why it happens:** If attributes are cached in DB and only recomputed on workout save, they might not reflect the most recent session
**How to avoid:** Recompute attributes on BadgesScreen LaunchedEffect (fresh computation every time screen opens), then persist the result. Computation is cheap (a few SQL aggregate queries + math).

## Code Examples

### Domain Models (RpgModels.kt)
```kotlin
// Source: Codebase pattern from ReadinessModels.kt, Gamification.kt

enum class RpgAttribute(val displayName: String, val description: String) {
    STRENGTH("Strength", "Peak load lifted"),
    POWER("Power", "Explosive force generation"),
    STAMINA("Stamina", "Training volume endurance"),
    CONSISTENCY("Consistency", "Training regularity"),
    MASTERY("Mastery", "Exercise variety and technique")
}

enum class CharacterClass(val displayName: String, val description: String) {
    POWERLIFTER("Powerlifter", "Dominant in raw strength"),
    ATHLETE("Athlete", "Dominant in explosive power"),
    IRONMAN("Ironman", "Dominant in volume endurance"),
    MONK("Monk", "Dominant in training discipline"),
    PHOENIX("Phoenix", "Balanced across all attributes")
}

data class RpgProfile(
    val strength: Int,      // 0-100
    val power: Int,         // 0-100
    val stamina: Int,       // 0-100
    val consistency: Int,   // 0-100
    val mastery: Int,       // 0-100
    val characterClass: CharacterClass,
    val lastComputed: Long = 0
) {
    companion object {
        val EMPTY = RpgProfile(0, 0, 0, 0, 0, CharacterClass.PHOENIX)
    }
}
```

### Computation Input Data Class
```kotlin
/**
 * Flattened input for RpgAttributeEngine computation.
 * All data fetched from DB before calling the engine.
 */
data class RpgInput(
    val maxWeightLiftedKg: Double,       // Strength: heaviest single lift
    val totalVolumeKg: Double,           // Stamina: lifetime volume
    val totalWorkouts: Int,              // Consistency: session count
    val totalReps: Int,                  // Stamina: total reps
    val uniqueExercises: Int,            // Mastery: exercise variety
    val personalRecords: Int,            // Mastery: PRs achieved
    val peakPowerWatts: Double,          // Power: max power output
    val avgWorkingWeightKg: Double,      // Strength: typical working weight
    val currentStreak: Int,              // Consistency: current streak days
    val longestStreak: Int,              // Consistency: best streak
    val trainingDays: Int,               // Consistency: distinct training days
    val badgesEarned: Int                // Mastery: badges unlocked
)
```

### Attribute Computation Logic
```kotlin
// Source: ReadinessEngine pattern + domain-specific formulas

object RpgAttributeEngine {

    // Normalization ceilings -- these define "100" for each metric
    // Tuned for Vitruvian Trainer (max 220kg, typical training patterns)
    private const val MAX_WEIGHT_CEILING = 200.0   // 200kg+ is exceptional
    private const val VOLUME_CEILING = 500_000.0    // 500t lifetime volume
    private const val WORKOUT_CEILING = 500         // 500+ workouts
    private const val POWER_CEILING = 2000.0        // 2000W peak
    private const val EXERCISE_CEILING = 50         // 50 unique exercises
    private const val PR_CEILING = 100              // 100 PRs
    private const val STREAK_CEILING = 90           // 90-day streak

    fun computeProfile(input: RpgInput): RpgProfile {
        if (input.totalWorkouts < 1) return RpgProfile.EMPTY

        val strength = computeStrength(input)
        val power = computePower(input)
        val stamina = computeStamina(input)
        val consistency = computeConsistency(input)
        val mastery = computeMastery(input)
        val characterClass = classifyCharacter(strength, power, stamina, consistency, mastery)

        return RpgProfile(strength, power, stamina, consistency, mastery, characterClass)
    }

    // Strength: weighted combination of max lift and avg working weight
    internal fun computeStrength(input: RpgInput): Int {
        val maxLiftScore = normalize(input.maxWeightLiftedKg, MAX_WEIGHT_CEILING)
        val avgWeightScore = normalize(input.avgWorkingWeightKg, MAX_WEIGHT_CEILING * 0.6)
        return ((maxLiftScore * 0.7 + avgWeightScore * 0.3) * 100).toInt().coerceIn(0, 100)
    }

    // Power: peak power output
    internal fun computePower(input: RpgInput): Int {
        return (normalize(input.peakPowerWatts, POWER_CEILING) * 100).toInt().coerceIn(0, 100)
    }

    // Stamina: total volume and total reps
    internal fun computeStamina(input: RpgInput): Int {
        val volumeScore = normalize(input.totalVolumeKg, VOLUME_CEILING)
        val repsScore = normalize(input.totalReps.toDouble(), 50_000.0)
        return ((volumeScore * 0.6 + repsScore * 0.4) * 100).toInt().coerceIn(0, 100)
    }

    // Consistency: streak, training frequency, total sessions
    internal fun computeConsistency(input: RpgInput): Int {
        val streakScore = normalize(input.longestStreak.toDouble(), STREAK_CEILING.toDouble())
        val frequencyScore = normalize(input.trainingDays.toDouble(), WORKOUT_CEILING.toDouble())
        val currentStreakBonus = normalize(input.currentStreak.toDouble(), 30.0) * 0.2
        return ((streakScore * 0.4 + frequencyScore * 0.4 + currentStreakBonus) * 100)
            .toInt().coerceIn(0, 100)
    }

    // Mastery: exercise variety, PRs, badges
    internal fun computeMastery(input: RpgInput): Int {
        val varietyScore = normalize(input.uniqueExercises.toDouble(), EXERCISE_CEILING.toDouble())
        val prScore = normalize(input.personalRecords.toDouble(), PR_CEILING.toDouble())
        val badgeScore = normalize(input.badgesEarned.toDouble(), 40.0)
        return ((varietyScore * 0.4 + prScore * 0.35 + badgeScore * 0.25) * 100)
            .toInt().coerceIn(0, 100)
    }

    // Character class from dominant attribute
    internal fun classifyCharacter(str: Int, pow: Int, sta: Int, con: Int, mas: Int): CharacterClass {
        val max = maxOf(str, pow, sta, con, mas)
        val min = minOf(str, pow, sta, con, mas)

        // "Phoenix" = balanced (spread <= 15 points)
        if (max - min <= 15) return CharacterClass.PHOENIX

        return when (max) {
            str -> CharacterClass.POWERLIFTER
            pow -> CharacterClass.ATHLETE
            sta -> CharacterClass.IRONMAN
            con -> CharacterClass.MONK
            mas -> CharacterClass.PHOENIX  // Mastery dominant = Phoenix (well-rounded)
            else -> CharacterClass.PHOENIX
        }
    }

    private fun normalize(value: Double, ceiling: Double): Double {
        if (ceiling <= 0.0) return 0.0
        return (value / ceiling).coerceIn(0.0, 1.0)
    }
}
```

### Migration File (17.sqm)
```sql
-- Migration 17: RPG Attributes table (Phase 21, RPG-01/RPG-04)
CREATE TABLE RpgAttributes (
    id INTEGER PRIMARY KEY DEFAULT 1,
    strength INTEGER NOT NULL DEFAULT 0,
    power INTEGER NOT NULL DEFAULT 0,
    stamina INTEGER NOT NULL DEFAULT 0,
    consistency INTEGER NOT NULL DEFAULT 0,
    mastery INTEGER NOT NULL DEFAULT 0,
    characterClass TEXT NOT NULL DEFAULT 'Phoenix',
    lastComputed INTEGER NOT NULL DEFAULT 0
);
```

### SQLDelight Queries
```sql
-- RPG Attributes Queries
selectRpgAttributes:
SELECT * FROM RpgAttributes WHERE id = 1;

upsertRpgAttributes:
INSERT OR REPLACE INTO RpgAttributes
(id, strength, power, stamina, consistency, mastery, characterClass, lastComputed)
VALUES (1, ?, ?, ?, ?, ?, ?, ?);

-- Additional queries needed for RPG computation input
selectMaxWeightLifted:
SELECT MAX(heaviestLiftKg) FROM WorkoutSession WHERE heaviestLiftKg IS NOT NULL;

selectAvgWorkingWeight:
SELECT AVG(workingAvgWeightKg) FROM WorkoutSession WHERE workingAvgWeightKg IS NOT NULL;

selectPeakPower:
SELECT MAX(peakPowerWatts) FROM RepMetric;

countTrainingDays:
SELECT COUNT(DISTINCT DATE(timestamp / 1000, 'unixepoch')) FROM WorkoutSession;
```

### RpgAttributeCard Composable
```kotlin
// Source: ReadinessBriefingCard pattern (verified in codebase)
@Composable
fun RpgAttributeCard(
    profile: RpgProfile,
    onPortalLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            // Header: Character class name + icon
            Text(
                text = profile.characterClass.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Five attribute bars (compact layout)
            RpgAttribute.entries.forEach { attr ->
                val value = when (attr) {
                    RpgAttribute.STRENGTH -> profile.strength
                    RpgAttribute.POWER -> profile.power
                    RpgAttribute.STAMINA -> profile.stamina
                    RpgAttribute.CONSISTENCY -> profile.consistency
                    RpgAttribute.MASTERY -> profile.mastery
                }
                AttributeBar(name = attr.displayName, value = value)
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Portal deep link (RPG-04)
            TextButton(
                onClick = onPortalLink,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "View full skill tree on Phoenix Portal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| GamificationStats singleton for all gamification data | Dedicated table per feature domain | Phase 21 (decided in STATE.md) | Cleaner schema, easier to extend |
| PremiumFeatureGate composable for lock overlay | Direct `hasProAccess` conditional rendering | Phase 20 pattern | Cleaner UX, no lock icon overlay on gamification screen |

**Deprecated/outdated:**
- PremiumFeatureGate composable is legacy -- newer phases use direct StateFlow gating (see ActiveWorkoutScreen readiness pattern)

## Open Questions

1. **Normalization ceiling values**
   - What we know: Values must be tuned for Vitruvian Trainer's capabilities (0-220kg machine)
   - What's unclear: Exact ceiling values depend on real user data distribution (we don't have production telemetry yet)
   - Recommendation: Start with reasonable defaults (provided in code examples above), document that these are tunable constants. The engine exposes `internal fun` methods for testing individual attribute computations.

2. **Peak power data availability**
   - What we know: `RepMetric.peakPowerWatts` exists in the database and is populated during workouts
   - What's unclear: Whether all users will have RepMetric data (it's a newer table)
   - Recommendation: Use `MAX(peakPowerWatts)` with NULL fallback to 0. If no RepMetric data exists, Power attribute starts at 0 (graceful degradation).

3. **Deep link URL format for Portal**
   - What we know: RPG-04 requires a "View full skill tree on Phoenix Portal" deep link
   - What's unclear: Portal URL isn't defined yet (portal integration is v0.6.0+)
   - Recommendation: Use placeholder callback `onPortalLink: () -> Unit` (matching ReadinessBriefingCard pattern). The actual URL handling is deferred.

## Sources

### Primary (HIGH confidence)
- Codebase files read directly:
  - `FeatureGate.kt` -- RPG_ATTRIBUTES already registered as Phoenix tier feature
  - `ReadinessEngine.kt` + `ReadinessEngineTest.kt` -- exact pattern to follow
  - `ReadinessBriefingCard.kt` -- card composable pattern with portal deep link
  - `ActiveWorkoutScreen.kt` -- tier gating pattern (hasProAccess, hasEliteAccess)
  - `VitruvianDatabase.sq` -- all existing queries for workout data aggregation
  - `GamificationRepository.kt` -- repository interface pattern
  - `BadgesScreen.kt` -- gamification screen where card will be placed
  - `Gamification.kt` -- existing domain models (GamificationStats, BadgeCategory, etc.)
  - `DriverFactory.ios.kt` -- CURRENT_SCHEMA_VERSION = 17L (must become 18L)
  - `migrations/16.sqm` -- current latest migration (next is 17.sqm)

### Secondary (MEDIUM confidence)
- STATE.md decisions: "RPG attributes get dedicated table (not GamificationStats singleton) -- schema v17"
- STATE.md blockers: "RPG schema migration v17 requires iOS DriverFactory.ios.kt CURRENT_SCHEMA_VERSION sync"
- REQUIREMENTS.md: RPG-01 through RPG-04 requirement definitions

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- no new dependencies, all patterns verified in codebase
- Architecture: HIGH -- direct pattern replication from Phase 20 (ReadinessEngine -> RpgAttributeEngine)
- Pitfalls: HIGH -- iOS schema sync is a known, documented concern (Daem0n warning #155)
- Computation formulas: MEDIUM -- normalization ceilings are reasonable defaults but may need tuning with real data

**Research date:** 2026-02-28
**Valid until:** 2026-03-28 (stable -- no external dependencies)
