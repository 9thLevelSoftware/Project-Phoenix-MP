# Mobile Third-Party Integrations

**Date**: 2026-03-25
**Status**: Draft
**Scope**: Hevy, Liftosaur, Strong (CSV), Apple Health, Google Health Connect

## Context

The Phoenix portal currently handles all third-party integrations (CSV export/import, API sync, health app instructions). This provides limited value because:

1. Users work out on the mobile app, not the portal
2. Apple Health and Google Health Connect require native platform APIs (mobile-only)
3. CSV export from local SQLite is faster than querying Supabase
4. A competitor app is launching with integrated sync features

This design moves integration management to the mobile app as the primary interface. The portal's existing integrations remain untouched.

## Architecture Decision: Hybrid

**Local-first data, server-side secrets.**

- CSV export/import: fully local (SQLite, no server)
- Health integrations: platform-native APIs (HealthKit, Health Connect)
- API sync (Hevy, Liftosaur): delegated to Edge Functions (API keys stored server-side in `oauth_tokens`, never on device)
- External activity storage: local SQLite with optional cloud sync for paid users

## Business Model

All integration features are **free** to maximize competitive positioning. Cloud sync of integration data is paid.

| Feature | Free | Paid |
|---------|------|------|
| CSV export from local SQLite | Yes | Yes |
| CSV import to local SQLite | Yes | Yes |
| Apple Health / Health Connect write | Yes | Yes |
| Hevy / Liftosaur API sync to local SQLite | Yes | Yes |
| External Activities view (local data) | Yes | Yes |
| Cloud sync of external_activities to Supabase | No | Yes |
| External activities visible on portal | No | Yes |

## Data Model

### New SQLite Table: ExternalActivity

```sql
CREATE TABLE ExternalActivity (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    name TEXT NOT NULL,
    activityType TEXT NOT NULL DEFAULT 'strength',
    startedAt INTEGER NOT NULL,
    durationSeconds INTEGER NOT NULL DEFAULT 0,
    distanceMeters REAL,
    calories INTEGER,
    avgHeartRate INTEGER,
    maxHeartRate INTEGER,
    elevationGainMeters REAL,
    rawData TEXT,
    syncedAt INTEGER NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    needsSync INTEGER NOT NULL DEFAULT 1,
    UNIQUE(externalId, provider)
);
```

### New SQLite Table: IntegrationStatus

```sql
CREATE TABLE IntegrationStatus (
    provider TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'disconnected',
    lastSyncAt INTEGER,
    errorMessage TEXT,
    profileId TEXT NOT NULL DEFAULT 'default',
    PRIMARY KEY(provider, profileId)
);
```

### Domain Models

```kotlin
// domain/model/ExternalActivity.kt
data class ExternalActivity(
    val id: String,
    val externalId: String,
    val provider: IntegrationProvider,
    val name: String,
    val activityType: String,
    val startedAt: Long,
    val durationSeconds: Int,
    val distanceMeters: Double?,
    val calories: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val elevationGainMeters: Double?,
    val rawData: String?,
    val syncedAt: Long,
    val profileId: String = "default",
    val needsSync: Boolean = true
)

enum class IntegrationProvider(val key: String) {
    HEVY("hevy"),
    LIFTOSAUR("liftosaur"),
    STRONG("strong"),
    APPLE_HEALTH("apple_health"),
    GOOGLE_HEALTH("google_health")
}

data class IntegrationStatus(
    val provider: IntegrationProvider,
    val status: ConnectionStatus,
    val lastSyncAt: Long?,
    val errorMessage: String?,
    val profileId: String = "default"
)

enum class ConnectionStatus {
    CONNECTED, DISCONNECTED, ERROR, TOKEN_EXPIRED
}
```

### Repository Interface

```kotlin
// data/integration/ExternalActivityRepository.kt
interface ExternalActivityRepository {
    fun getAll(profileId: String, provider: IntegrationProvider? = null): Flow<List<ExternalActivity>>
    fun getUnsyncedActivities(profileId: String): List<ExternalActivity>
    suspend fun upsertActivities(activities: List<ExternalActivity>)
    suspend fun markSynced(ids: List<String>)
    fun getIntegrationStatus(provider: IntegrationProvider): Flow<IntegrationStatus>
    suspend fun updateIntegrationStatus(
        provider: IntegrationProvider,
        status: ConnectionStatus,
        lastSyncAt: Long? = null,
        errorMessage: String? = null
    )
    suspend fun deleteActivities(provider: IntegrationProvider, profileId: String)
}
```

## CSV Export

### Format

Strong CSV format (de facto standard accepted by Hevy, Strong, analytics tools):

```
Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes
2026-03-25 14:30:00,Chest Day,1h 15m,Bench Press,1,80,10,,,,
```

### Weight Conversion

Per-cable DB value x 2 (total weight) with optional kg-to-lbs conversion (user selects unit before export).

### Implementation

```kotlin
// data/integration/CsvExporter.kt
class CsvExporter(
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository
) {
    suspend fun generateStrongCsv(
        profileId: String,
        weightUnit: WeightUnit = WeightUnit.KG
    ): String

    // Returns CSV string content, caller handles file writing + sharing
}
```

Platform sharing via expect/actual:
- Android: `Intent.ACTION_SEND` with `text/csv` MIME type, or save to Downloads
- iOS: `UIActivityViewController` with temp file URL

## CSV Import

### Supported Formats

1. **Strong CSV**: Columns — `Date, Workout Name, Duration, Exercise Name, Set Order, Weight, Reps, Distance, Seconds, Notes, Workout Notes`
2. **Hevy CSV**: Columns — `title, start_time, end_time, description, exercise_title, superset_id, exercise_notes, set_index, set_type, weight_lbs, reps, distance_miles, duration_seconds, rpe`

Format auto-detected by column headers.

### Deduplication

Deterministic `externalId` per provider:
- Strong: `strong-{workoutName}-{epochSeconds}`
- Hevy: `hevy-{title}-{epochSeconds}`

Upsert on `(externalId, provider)` unique constraint prevents duplicates on re-import.

### Implementation

```kotlin
// data/integration/CsvParser.kt
class CsvParser {
    fun parseStrongCsv(content: String, weightUnit: WeightUnit): List<ExternalActivity>
    fun parseHevyCsv(content: String): List<ExternalActivity>
    fun detectFormat(content: String): CsvFormat  // AUTO, STRONG, HEVY
}
```

### Import Flow

1. User taps "Import CSV" on Integrations screen
2. Platform file picker opens (expect/actual)
3. File read as string, format auto-detected
4. Preview bottom sheet: workout count, date range, total duration
5. User confirms -> upsert to local ExternalActivity table
6. For paid users: `needsSync = true`, next sync push sends to Supabase
7. For free users: `needsSync = false`, stays local only

## API Sync (Hevy & Liftosaur)

### Flow

```
User enters API key in mobile UI
  -> Mobile POSTs to mobile-integration-sync Edge Function
  -> Edge Function stores key in oauth_tokens (server-side)
  -> Edge Function calls provider API (Hevy/Liftosaur)
  -> Edge Function returns normalized activities in response
  -> If paid: Edge Function also upserts to Supabase external_activities
  -> If free: response only, no Supabase persistence
  -> Mobile stores returned activities in local ExternalActivity table
```

### API Key Security

- API keys are transmitted once (HTTPS) and stored server-side in `oauth_tokens`
- Mobile never stores or caches API keys
- Mobile stores only the connection status (connected/disconnected)

### Edge Function: mobile-integration-sync

New Edge Function that:
1. Accepts `{ provider: "hevy" | "liftosaur", apiKey?: string, action: "connect" | "sync" | "disconnect" }`
2. Authenticates via JWT (GoTrue)
3. On `connect`: stores API key, performs initial sync, returns activities
4. On `sync`: uses stored API key, fetches new activities since last sync, returns activities
5. On `disconnect`: deletes stored API key, updates integration status
6. Paid users: activities also persisted to Supabase `external_activities`
7. Free users: activities returned in response only

### Hevy API Integration

- Endpoint: `https://api.hevyapp.com/v1/workouts`
- Auth: Bearer token (API key)
- Requires Hevy PRO subscription on the Hevy side
- Bidirectional: GET (read history) + POST (push Phoenix workouts)

**Pull from Hevy (GET):** Fetch paginated workout history. Normalize each workout to ExternalActivity (name, start time, duration, exercises aggregated into rawData JSON).

**Push to Hevy (POST):** Convert WorkoutSession to Hevy workout JSON:
- `title`: exercise name (or routine name if from a routine)
- `start_time` / `end_time`: from session timestamps
- `exercises[].title`: exercise name
- `exercises[].sets[].weight_kg`: per-cable weight x 2 (total weight)
- `exercises[].sets[].reps`: rep count
- `exercises[].sets[].set_type`: "normal"

### Liftosaur API Integration

- Endpoint: `https://www.liftosaur.com/api/v1/history`
- Auth: Bearer token (lftsk_* API key)
- Requires Liftosaur Premium on the Liftosaur side
- Bidirectional: GET (read history) + POST (push Phoenix workouts in Liftoscript format)

**Pull from Liftosaur (GET):** Fetch paginated history records in Liftoscript text format. Parse timestamp, program name, day name, duration. Normalize to ExternalActivity.

**Push to Liftosaur (POST):** Convert WorkoutSession to Liftoscript workout text:
- Format: `{timestamp} / program: "Phoenix" / dayName: "{exerciseName}" / exercises: { {exerciseName} / {sets}x{reps} {weight}kg }`
- Weight: per-cable x 2 (total weight)
- Sent as `{ "text": "..." }` JSON body

### Mobile Integration Manager

```kotlin
// data/integration/IntegrationManager.kt
class IntegrationManager(
    private val apiClient: PortalApiClient,
    private val externalActivityRepo: ExternalActivityRepository
) {
    suspend fun connectProvider(provider: IntegrationProvider, apiKey: String): Result<List<ExternalActivity>>
    suspend fun syncProvider(provider: IntegrationProvider): Result<List<ExternalActivity>>
    suspend fun disconnectProvider(provider: IntegrationProvider): Result<Unit>
    suspend fun pushWorkoutToProvider(provider: IntegrationProvider, session: WorkoutSession): Result<Unit>
}
```

## Health Integrations

### Common Interface

```kotlin
// commonMain
expect class HealthIntegration {
    suspend fun isAvailable(): Boolean
    suspend fun requestPermissions(): Boolean
    suspend fun hasPermissions(): Boolean
    suspend fun writeWorkout(session: WorkoutSession): Result<Unit>
}
```

### Android: Google Health Connect

```kotlin
// androidMain
actual class HealthIntegration(private val context: Context) {
    // Uses androidx.health.connect.client
    // Writes ExerciseSessionRecord with:
    //   - exerciseType: EXERCISE_TYPE_WEIGHTLIFTING
    //   - duration from session
    //   - TotalCaloriesBurnedRecord from session.calories
    //   - title: session exercise name
}
```

**Permissions required**: `WRITE_EXERCISE_SESSION`, `WRITE_TOTAL_CALORIES_BURNED`

### iOS: HealthKit

```kotlin
// iosMain
actual class HealthIntegration {
    // Uses HKHealthStore via Kotlin/Native interop
    // Writes HKWorkout with:
    //   - workoutActivityType: .traditionalStrengthTraining
    //   - duration from session
    //   - totalEnergyBurned: HKQuantity from session.calories
}
```

**Permissions required**: `HKWorkoutType`, `HKQuantityType(.activeEnergyBurned)`

### Auto-Push Trigger

After `ActiveSessionEngine` completes a workout:
1. Check if health integration is connected (IntegrationStatus)
2. If connected, call `HealthIntegration.writeWorkout(session)`
3. Fire-and-forget — failure logged but doesn't block workout completion
4. No retry logic — if it fails, user can manually trigger from External Activities view

## Sync Extension

### Push (mobile -> Supabase)

Extend `SyncManager.pushToPortal()`:
1. Check subscription status
2. If paid: query `ExternalActivity` where `needsSync = true`
3. Map to `ExternalActivityDto` (camelCase wire format)
4. Include in push payload alongside sessions, routines, etc.
5. On success: mark records `needsSync = false`

### Pull (Supabase -> mobile)

Extend `SyncManager.pullFromPortal()`:
1. If paid: request external activities modified since `lastSync`
2. Upsert into local `ExternalActivity` table with `needsSync = false`
3. Handles activities synced from portal (e.g., user ran Hevy sync from portal)

### DTO

```kotlin
// data/sync/PortalSyncDtos.kt (extend)
@Serializable
data class ExternalActivityDto(
    val id: String,
    val externalId: String,
    val provider: String,
    val name: String,
    val activityType: String,
    val startedAt: String,  // ISO-8601
    val durationSeconds: Int,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null,
    val syncedAt: String  // ISO-8601
)
```

### Edge Function Changes

**mobile-sync-push** (MODIFIED): Accept optional `externalActivities` array in payload, upsert to Supabase `external_activities`.

**mobile-sync-pull** (MODIFIED): Return `externalActivities` modified since lastSync timestamp.

## UI Design

### Integrations Screen (Settings -> Integrations)

Vertical scrolling list with two sections:

**Section: Health Apps**
- Platform-conditional: Apple Health on iOS, Google Health Connect on Android
- Toggle switch to connect/disconnect
- On first connect: request platform permissions
- Status: "Connected — auto-syncs after workouts" / "Not connected"

**Section: Workout Trackers**

**Hevy card:**
- Connection status badge (connected/disconnected)
- "Connect" -> bottom sheet with API key text input
- Once connected: "Sync Now" button, "Push to Hevy" toggle, last sync time
- "Export CSV" button
- Note: "Requires Hevy PRO for API sync"

**Liftosaur card:**
- Same pattern as Hevy
- Note: "Requires Liftosaur Premium"

**Strong / CSV card:**
- "Export CSV" button with weight unit toggle (kg/lbs)
- "Import CSV" button -> file picker -> preview -> confirm
- No connection state (file-based only)

### External Activities Screen

Accessible from Integrations screen or workout history:
- List of external activities sorted by date (newest first)
- Each item: name, provider icon, date, duration, activity type
- Tap to expand: calories, heart rate, distance (if available)
- Filter chips by provider
- Read-only view

### Import Preview Bottom Sheet

Shown after file selection:
- Detected format (Hevy / Strong)
- Number of workouts
- Date range (oldest - newest)
- Total duration
- "Import" button + "Cancel"

## Implementation Phases

### Phase 1: CSV Export + Health Writes

Fastest competitive response. No server changes needed.

**Deliverables:**
- CsvExporter (commonMain)
- HealthIntegration expect/actual (Android Health Connect, iOS HealthKit)
- Auto-push hook in workout completion flow
- Platform share sheet (expect/actual)
- Basic Integrations screen (health toggle + CSV export)
- SQLDelight migration for IntegrationStatus table

### Phase 2: CSV Import + External Activities Storage

Completes the CSV story and local data layer.

**Deliverables:**
- SQLDelight migration for ExternalActivity table
- ExternalActivityRepository + SqlDelight implementation
- CsvParser (Strong + Hevy formats)
- Import preview bottom sheet
- External Activities list screen
- Platform file picker (expect/actual)

### Phase 3: API Sync (Hevy + Liftosaur)

Requires Edge Function work.

**Deliverables:**
- mobile-integration-sync Edge Function (new)
- IntegrationManager (commonMain)
- API key input UI
- Hevy API integration (read + write)
- Liftosaur API integration (read + write)
- Liftoscript format converter for pushing workouts

### Phase 4: Sync Extension + Polish

Full cloud sync for paid users.

**Deliverables:**
- Extend mobile-sync-push to include externalActivities
- Extend mobile-sync-pull to return externalActivities
- SyncManager extension
- Conflict resolution (dedup by externalId + provider)
- Error handling, retry logic
- Integration status sync between mobile and portal

## File Structure

### New Files (Mobile - shared/src/commonMain)

```
data/integration/
    CsvExporter.kt
    CsvParser.kt
    IntegrationManager.kt
    ExternalActivityRepository.kt
    SqlDelightExternalActivityRepository.kt

domain/model/
    ExternalActivity.kt
    IntegrationStatus.kt

presentation/screen/
    IntegrationsScreen.kt
    ExternalActivitiesScreen.kt

presentation/viewmodel/
    IntegrationsViewModel.kt
```

### New Files (Mobile - platform)

```
androidMain/data/integration/
    HealthIntegration.android.kt
    PlatformFilePicker.android.kt
    PlatformShareSheet.android.kt

iosMain/data/integration/
    HealthIntegration.ios.kt
    PlatformFilePicker.ios.kt
    PlatformShareSheet.ios.kt
```

### New Files (Portal - Edge Functions)

```
supabase/functions/mobile-integration-sync/
    index.ts
```

### Modified Files (Mobile)

```
shared/src/commonMain/sqldelight/.../VitruvianDatabase.sq  (new tables)
shared/src/commonMain/kotlin/.../data/sync/SyncManager.kt  (extend push/pull)
shared/src/commonMain/kotlin/.../data/sync/PortalSyncDtos.kt  (new DTO)
shared/src/commonMain/kotlin/.../di/DataModule.kt  (register new repos)
shared/src/commonMain/kotlin/.../di/SyncModule.kt  (register IntegrationManager)
shared/src/commonMain/kotlin/.../di/PresentationModule.kt  (register VM)
shared/src/commonMain/kotlin/.../presentation/screen/SettingsTab.kt  (add Integrations nav)
shared/src/commonMain/kotlin/.../presentation/manager/ActiveSessionEngine.kt  (health auto-push)
```

### Modified Files (Portal - Edge Functions)

```
supabase/functions/mobile-sync-push/index.ts  (accept externalActivities)
supabase/functions/mobile-sync-pull/index.ts  (return externalActivities)
```

## Weight Convention Reminder

Per project CLAUDE.md: all weight values in the database are per-cable. CSV export must multiply by 2 (WEIGHT_MULTIPLIER) for total weight display, then optionally convert to lbs. CSV import must reverse this: divide imported total weight by 2 if storing as per-cable, or store as-is in ExternalActivity (which represents external data, not Vitruvian per-cable values).

External activities store weights as-is from the source app (total weight, not per-cable). Only Vitruvian WorkoutSession data uses per-cable convention.

## Testing Strategy

- Unit tests for CsvExporter (format correctness, weight conversion)
- Unit tests for CsvParser (both formats, edge cases, malformed input)
- Unit tests for ExternalActivityRepository (CRUD, dedup, sync flag)
- Integration test for IntegrationManager (mock Edge Function responses)
- Manual testing for HealthKit and Health Connect (requires real devices)
- Manual testing for platform file picker and share sheet
