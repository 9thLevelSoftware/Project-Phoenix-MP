# Phase 25 Research: Edge Functions

**Phase:** 25 — Edge Functions
**Repo:** phoenix-portal
**Researched:** 2026-03-02

---

## 1. Supabase Edge Functions Runtime

- **Runtime:** Deno (TypeScript/JavaScript)
- **Entry point:** `Deno.serve(async (req) => { ... })` — NOT the old `serve()` import
- **File structure:** `supabase/functions/<function-name>/index.ts`
- **Shared code:** `supabase/functions/_shared/` with relative imports (`.ts` extension required)
- **Limits:** 256MB memory, 2s CPU time, 150s/400s wall clock (free/paid), 20MB bundle

### Import pattern (existing functions use):
```typescript
import { createClient } from 'jsr:@supabase/supabase-js@2';
import { getCorsHeaders } from '../_shared/cors.ts';
```

### Auto-injected environment variables:
| Variable | Available | Purpose |
|----------|-----------|---------|
| `SUPABASE_URL` | Always | Project API URL |
| `SUPABASE_ANON_KEY` | Always | Public anon key |
| `SUPABASE_SERVICE_ROLE_KEY` | Always | Admin key (bypasses RLS) |
| `SUPABASE_DB_URL` | Always | Direct Postgres connection |
| `APP_URL` | Custom secret | Portal web URL (for CORS) |
| `ENVIRONMENT` | Custom secret | "production" / "development" |

---

## 2. Portal's Existing Edge Function Pattern

13 existing functions follow this exact pattern:

```typescript
import { createClient } from 'jsr:@supabase/supabase-js@2';
import { getCorsHeaders } from '../_shared/cors.ts';

Deno.serve(async (req) => {
  const cors = getCorsHeaders(req);

  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: cors });
  }

  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
  );

  // ... function logic ...

  return new Response(JSON.stringify(result), {
    headers: { ...cors, 'Content-Type': 'application/json' },
  });
});
```

---

## 3. CORS for Mobile Clients

### Existing `_shared/cors.ts` behavior:
- `getCorsHeaders(req)` checks Origin against whitelist (APP_URL + localhost:5173/3000)
- If Origin not in whitelist → returns `Access-Control-Allow-Origin: ''`
- If no Origin header (mobile) → `origin` is `''`, `isAllowed` is `false`, returns empty ACAO

### Impact on mobile:
- **Mobile clients do NOT send Origin header** (CORS is browser-only)
- **Mobile clients ignore CORS headers** in responses
- Function processes request normally regardless of Origin
- HTTP 200 returned to mobile — no CORS rejection possible
- SC #3 is satisfied by default with existing CORS handler

---

## 4. JWT Verification

### Recommended pattern (from Supabase docs):
```typescript
const authHeader = req.headers.get('Authorization');
if (!authHeader) {
  return new Response(JSON.stringify({ error: 'Missing authorization header' }),
    { status: 401, headers: { ...cors, 'Content-Type': 'application/json' } });
}
const token = authHeader.replace('Bearer ', '');

// Create user-scoped client to verify JWT
const supabaseAuth = createClient(
  Deno.env.get('SUPABASE_URL')!,
  Deno.env.get('SUPABASE_ANON_KEY')!,
  { global: { headers: { Authorization: `Bearer ${token}` } } }
);

const { data: { user }, error } = await supabaseAuth.auth.getUser();
if (error || !user) {
  return new Response(JSON.stringify({ error: 'Invalid token' }),
    { status: 401, headers: { ...cors, 'Content-Type': 'application/json' } });
}

const userId = user.id; // This is the auth.uid() = JWT sub claim
```

### Alternative: Manual JWKS validation
Using `jsr:@panva/jose@6` for offline verification. More complex, not needed for our use case.

---

## 5. Database Table Schema for Push/Pull

### Tables the push function writes to:

**Workout hierarchy (FK-ordered):**
1. `workout_sessions` — id, user_id, name, started_at, duration_seconds, total_volume, set_count, exercise_count, pr_count, routine_name, workout_mode, routine_session_id, notes
2. `exercises` — id, session_id, user_id, name, muscle_group, order_index
3. `sets` — id, exercise_id, user_id, set_number, target_reps, actual_reps, weight_kg, rpe, is_pr, notes, workout_mode
4. `rep_summaries` — id, set_id, user_id, rep_number, mean_velocity_mps, peak_velocity_mps, mean_force_n, peak_force_n, power_watts, rom_mm, tut_ms, left_force_avg, right_force_avg, asymmetry_pct, vbt_zone

**Routines (UPSERT):**
5. `routines` — id, user_id, name, description, exercise_count, estimated_duration, times_completed, last_used_at, tags, is_favorite
6. `routine_exercises` — id, routine_id, name, muscle_group, sets, reps, weight, rest_seconds, mode, order_index, + 12 advanced columns (superset_id, superset_color, superset_order, per_set_weights, per_set_rest, is_amrap, pr_percentage, rep_count_timing, stop_at_position, stall_detection, eccentric_load, echo_level)

**Gamification (UPSERT):**
7. `rpg_attributes` — id, user_id, strength, power, stamina, consistency, mastery, character_class, level, experience_points, updated_at
8. `earned_badges` — id, user_id, badge_id, badge_name, badge_description, badge_tier, earned_at (UNIQUE on user_id + badge_id)
9. `gamification_stats` — id, user_id, total_workouts, total_reps, total_volume_kg, longest_streak, current_streak, total_time_seconds, updated_at

**Computed server-side:**
10. `exercise_progress` — id, user_id, exercise_name, session_id, recorded_at, max_weight_kg, total_volume_kg, estimated_1rm_kg, max_reps, set_count
11. `personal_records` — id, user_id, exercise_name, muscle_group, record_type, value, unit, achieved_at, previous_value

### Tables the pull function reads:
Same tables, filtered by user_id and timestamp where available.

---

## 6. Mobile Wire Format (PortalSyncPayload)

### Push payload structure:
```json
{
  "deviceId": "string",
  "platform": "android|ios",
  "lastSync": 1740000000000,
  "sessions": [
    {
      "id": "uuid",
      "userId": "uuid (IGNORED — use JWT)",
      "name": "string?",
      "startedAt": "ISO 8601",
      "durationSeconds": 0,
      "totalVolume": 0.0,
      "setCount": 0,
      "exerciseCount": 0,
      "prCount": 0,
      "routineName": "string?",
      "workoutMode": "OLD_SCHOOL|PUMP|TUT|TUT_BEAST|ECCENTRIC_ONLY|ECHO",
      "routineSessionId": "string?",
      "exercises": [
        {
          "id": "uuid",
          "sessionId": "uuid",
          "name": "string",
          "muscleGroup": "General",
          "orderIndex": 0,
          "sets": [
            {
              "id": "uuid",
              "exerciseId": "uuid",
              "setNumber": 1,
              "targetReps": null,
              "actualReps": 0,
              "weightKg": 0.0,
              "rpe": null,
              "isPr": false,
              "notes": null,
              "workoutMode": "string?",
              "repSummaries": [
                {
                  "id": "uuid",
                  "setId": "uuid",
                  "repNumber": 1,
                  "meanVelocityMps": null,
                  "peakVelocityMps": null,
                  "meanForceN": null,
                  "peakForceN": null,
                  "powerWatts": null,
                  "romMm": null,
                  "tutMs": null,
                  "leftForceAvg": null,
                  "rightForceAvg": null,
                  "asymmetryPct": null,
                  "vbtZone": null
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  "routines": [
    {
      "id": "uuid",
      "userId": "uuid (IGNORED)",
      "name": "string",
      "description": "",
      "exerciseCount": 0,
      "estimatedDuration": 0,
      "timesCompleted": 0,
      "isFavorite": false,
      "exercises": [
        {
          "id": "uuid",
          "routineId": "uuid",
          "name": "string",
          "muscleGroup": "General",
          "sets": 3,
          "reps": 10,
          "weight": 0.0,
          "restSeconds": 90,
          "mode": "OLD_SCHOOL",
          "orderIndex": 0,
          "supersetId": null,
          "supersetColor": null,
          "supersetOrder": null,
          "perSetWeights": null,
          "perSetRest": null,
          "isAmrap": false,
          "prPercentage": null,
          "repCountTiming": null,
          "stopAtPosition": null,
          "stallDetection": true,
          "eccentricLoad": null,
          "echoLevel": null
        }
      ]
    }
  ],
  "rpgAttributes": {
    "userId": "uuid (IGNORED)",
    "strength": 0, "power": 0, "stamina": 0,
    "consistency": 0, "mastery": 0,
    "characterClass": null, "level": 1, "experiencePoints": 0
  },
  "badges": [
    {
      "userId": "uuid (IGNORED)",
      "badgeId": "string",
      "badgeName": "string",
      "badgeDescription": null,
      "badgeTier": "bronze",
      "earnedAt": "ISO 8601"
    }
  ],
  "gamificationStats": {
    "userId": "uuid (IGNORED)",
    "totalWorkouts": 0, "totalReps": 0, "totalVolumeKg": 0.0,
    "longestStreak": 0, "currentStreak": 0, "totalTimeSeconds": 0
  }
}
```

### Push response (SyncPushResponse):
```json
{
  "syncTime": 1740000000000,
  "sessionsInserted": 0,
  "exercisesInserted": 0,
  "setsInserted": 0,
  "repSummariesInserted": 0,
  "routinesUpserted": 0,
  "badgesUpserted": 0
}
```

### Pull request:
```json
{
  "deviceId": "string",
  "lastSync": 1740000000000
}
```

### Pull response (SyncPullResponse):
```json
{
  "syncTime": 1740000000000,
  "sessions": [...],
  "routines": [...],
  "rpgAttributes": {...},
  "badges": [...],
  "gamificationStats": {...}
}
```

---

## 7. Mode Display Names (SC #4)

**Already handled by existing infrastructure:**
- Phase 23-02 migrated all DB values to wire format (SCREAMING_SNAKE)
- `transforms.ts` maps wire → display: `OLD_SCHOOL` → "Old School", `ECHO` → "Echo", etc.
- Portal workout history page uses `workoutModeSchema` which applies the transform
- SC #4 is satisfied without any Phase 25 changes

---

## 8. Key Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Duplicate INSERT on retry | Data corruption | Use `ON CONFLICT DO NOTHING` for workout data; UPSERT for routines/gamification |
| Large payload size | Edge Function timeout | Rep telemetry excluded; 2s CPU limit sufficient for ~10 sessions |
| personal_records missing INSERT RLS | Security gap via PostgREST | Service_role bypasses; noted for future migration |
| routines lack created_at/updated_at | Pull returns all routines | Acceptable for small dataset; optimize in Phase 27 |
| exercise_progress computation accuracy | Inconsistent portal analytics | Use exact same Brzycki formula as mobile; cap at reps ≤ 12 |
