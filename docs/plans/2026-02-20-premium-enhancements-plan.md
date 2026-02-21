# Premium Enhancements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement next-generation premium features across three tracks (Visual, Intelligence, Safety) using "Maximum Wow" strategy — ship visual spectacle first.

**Architecture:** Portal (React 19 + Supabase + Vite) handles all server-side computation and rich visualization. Mobile (KMP + Compose) provides real-time BLE data capture, camera-based pose estimation, and compact feature UIs. All new premium features gate behind PHOENIX or ELITE tiers using existing `SubscriptionGate` and `FeatureGate` patterns.

**Tech Stack:**
- Portal: React 19, TypeScript, Supabase (PostgREST + Edge Functions), TanStack Query, visx, Recharts, React Three Fiber, Framer Motion, Tailwind v4
- Mobile: Kotlin Multiplatform, Compose, Koin DI, SQLDelight, MediaPipe, CameraX
- Shared DB: Supabase (PostgreSQL)

**Design Doc:** `docs/plans/2026-02-20-premium-enhancements-design.md`

---

## Build Order

| Phase | Feature | Track | Tier | Depends On |
|-------|---------|-------|------|------------|
| 1 | RPG Skill Trees | Visual | PHOENIX | None |
| 2 | Ghost Racing | Visual | PHOENIX/ELITE | None |
| 3 | Human Digital Twin 3D | Visual | ELITE | Phase 1 (attributes feed twin) |
| 4 | Force-Velocity Profile Dashboard | Intelligence | PHOENIX | None |
| 5 | Mechanical Impulse Quantification | Intelligence | PHOENIX | None |
| 6 | Enhanced Wearable Ingestion | Intelligence | ELITE | None |
| 7 | Predictive Fatigue Model + AI Auto-Regulation | Intelligence | ELITE | Phases 4, 5, 6 |
| 8 | CV Pose Estimation (Mobile) | Safety | PHOENIX | None |
| 9 | CV Form Rules Engine | Safety | PHOENIX | Phase 8 |
| 10 | CV Portal Analytics | Safety | ELITE | Phase 9 |

Phases 1-3 are parallel (Track 1). Phases 4-7 are sequential (Track 2). Phases 8-10 are sequential (Track 3) but parallel to Track 2.

---

## Phase 1: RPG Skill Trees

### Task 1.1: Database Migration — Attribute Tables

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260223_rpg_attributes.sql`

**Step 1: Write the migration**

```sql
-- RPG Skill Tree attributes
CREATE TABLE user_attributes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  -- Attribute levels (1-99)
  strength_level INTEGER NOT NULL DEFAULT 1,
  strength_xp BIGINT NOT NULL DEFAULT 0,
  power_level INTEGER NOT NULL DEFAULT 1,
  power_xp BIGINT NOT NULL DEFAULT 0,
  stamina_level INTEGER NOT NULL DEFAULT 1,
  stamina_xp BIGINT NOT NULL DEFAULT 0,
  consistency_level INTEGER NOT NULL DEFAULT 1,
  consistency_xp BIGINT NOT NULL DEFAULT 0,
  mastery_level INTEGER NOT NULL DEFAULT 1,
  mastery_xp BIGINT NOT NULL DEFAULT 0,
  -- Derived
  character_class TEXT NOT NULL DEFAULT 'Initiate',
  overall_level INTEGER NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id)
);

ALTER TABLE user_attributes ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own attributes"
  ON user_attributes FOR SELECT
  USING (auth.uid() = user_id);
CREATE POLICY "Service role can manage attributes"
  ON user_attributes FOR ALL
  USING (auth.role() = 'service_role');

-- XP gain log
CREATE TABLE attribute_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  attribute TEXT NOT NULL CHECK (attribute IN ('strength', 'power', 'stamina', 'consistency', 'mastery')),
  xp_gained INTEGER NOT NULL,
  source_session_id UUID REFERENCES workout_sessions(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE attribute_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own history"
  ON attribute_history FOR SELECT
  USING (auth.uid() = user_id);
CREATE POLICY "Service role can manage history"
  ON attribute_history FOR ALL
  USING (auth.role() = 'service_role');

CREATE INDEX idx_attribute_history_user ON attribute_history(user_id);
CREATE INDEX idx_attribute_history_created ON attribute_history(created_at);
```

**Step 2: Apply migration**

Run: `cd C:/Users/dasbl/AndroidStudioProjects/phoenix-portal && npx supabase db push`
Expected: Migration applied successfully

**Step 3: Regenerate types**

Run: `cd C:/Users/dasbl/AndroidStudioProjects/phoenix-portal && npm run gen:types`
Expected: `src/lib/database.types.ts` updated with `user_attributes` and `attribute_history` types

**Step 4: Commit**

```bash
git add supabase/migrations/20260223_rpg_attributes.sql src/lib/database.types.ts
git commit -m "feat(rpg): add user_attributes and attribute_history tables"
```

---

### Task 1.2: RPG Calculation Library

**Files:**
- Create: `phoenix-portal/src/lib/rpg.ts`
- Create: `phoenix-portal/src/lib/__tests__/rpg.test.ts`

**Step 1: Write the failing tests**

```typescript
// src/lib/__tests__/rpg.test.ts
import { describe, it, expect } from "vitest";
import {
  calculateXpForLevel,
  levelFromXp,
  determineCharacterClass,
  calculateStrengthXp,
  calculatePowerXp,
  calculateStaminaXp,
  type AttributeLevels,
} from "../rpg";

describe("RPG XP System", () => {
  describe("calculateXpForLevel", () => {
    it("returns 0 for level 1", () => {
      expect(calculateXpForLevel(1)).toBe(0);
    });
    it("returns 100 for level 2", () => {
      expect(calculateXpForLevel(2)).toBe(100);
    });
    it("scales exponentially", () => {
      const xp10 = calculateXpForLevel(10);
      const xp20 = calculateXpForLevel(20);
      expect(xp20).toBeGreaterThan(xp10 * 2);
    });
    it("returns finite value for level 99", () => {
      expect(calculateXpForLevel(99)).toBeGreaterThan(0);
      expect(Number.isFinite(calculateXpForLevel(99))).toBe(true);
    });
  });

  describe("levelFromXp", () => {
    it("returns 1 for 0 XP", () => {
      expect(levelFromXp(0)).toBe(1);
    });
    it("returns 2 at exactly 100 XP", () => {
      expect(levelFromXp(100)).toBe(2);
    });
    it("caps at 99", () => {
      expect(levelFromXp(Number.MAX_SAFE_INTEGER)).toBe(99);
    });
  });

  describe("determineCharacterClass", () => {
    it("returns Powerlifter when strength dominant", () => {
      const attrs: AttributeLevels = {
        strength: 50, power: 20, stamina: 20, consistency: 20, mastery: 20,
      };
      expect(determineCharacterClass(attrs)).toBe("Powerlifter");
    });
    it("returns Athlete when power dominant", () => {
      const attrs: AttributeLevels = {
        strength: 20, power: 50, stamina: 20, consistency: 20, mastery: 20,
      };
      expect(determineCharacterClass(attrs)).toBe("Athlete");
    });
    it("returns Phoenix when balanced above threshold", () => {
      const attrs: AttributeLevels = {
        strength: 40, power: 40, stamina: 40, consistency: 40, mastery: 40,
      };
      expect(determineCharacterClass(attrs)).toBe("Phoenix");
    });
    it("returns Initiate when all levels are 1", () => {
      const attrs: AttributeLevels = {
        strength: 1, power: 1, stamina: 1, consistency: 1, mastery: 1,
      };
      expect(determineCharacterClass(attrs)).toBe("Initiate");
    });
  });

  describe("calculateStrengthXp", () => {
    it("awards XP for low-velocity high-load reps", () => {
      // VBT zone <0.5 m/s = strength zone
      const xp = calculateStrengthXp(0.3, 80); // 0.3 m/s, 80kg
      expect(xp).toBeGreaterThan(0);
    });
    it("awards zero for high-velocity reps", () => {
      const xp = calculateStrengthXp(1.2, 30); // 1.2 m/s = explosive
      expect(xp).toBe(0);
    });
  });

  describe("calculatePowerXp", () => {
    it("awards XP for high-velocity reps", () => {
      const xp = calculatePowerXp(1.0, 500); // 1.0 m/s, 500W
      expect(xp).toBeGreaterThan(0);
    });
    it("awards zero for slow reps", () => {
      const xp = calculatePowerXp(0.3, 200);
      expect(xp).toBe(0);
    });
  });

  describe("calculateStaminaXp", () => {
    it("awards XP based on TUT and volume", () => {
      const xp = calculateStaminaXp(5000, 30, 2400); // 5s TUT, 30 reps, 2400kg volume
      expect(xp).toBeGreaterThan(0);
    });
  });
});
```

**Step 2: Run test to verify it fails**

Run: `cd C:/Users/dasbl/AndroidStudioProjects/phoenix-portal && npx vitest run src/lib/__tests__/rpg.test.ts`
Expected: FAIL — module `../rpg` not found

**Step 3: Write implementation**

```typescript
// src/lib/rpg.ts

export interface AttributeLevels {
  strength: number;
  power: number;
  stamina: number;
  consistency: number;
  mastery: number;
}

export type CharacterClass =
  | "Initiate"
  | "Powerlifter"
  | "Athlete"
  | "Ironman"
  | "Monk"
  | "Phoenix";

// Exponential XP curve: XP needed to reach a given level
// Level 1 = 0 XP, Level 2 = 100 XP, Level 99 ~= 2.4M XP
const BASE_XP = 100;
const EXPONENT = 1.5;

export function calculateXpForLevel(level: number): number {
  if (level <= 1) return 0;
  return Math.floor(BASE_XP * Math.pow(level - 1, EXPONENT));
}

export function levelFromXp(xp: number): number {
  if (xp <= 0) return 1;
  // Inverse of XP formula: level = (xp / BASE_XP)^(1/EXPONENT) + 1
  const level = Math.floor(Math.pow(xp / BASE_XP, 1 / EXPONENT) + 1);
  return Math.min(Math.max(level, 1), 99);
}

const BALANCED_THRESHOLD = 30;
const DOMINANT_RATIO = 1.5;

export function determineCharacterClass(attrs: AttributeLevels): CharacterClass {
  const { strength, power, stamina, consistency, mastery } = attrs;
  const all = [strength, power, stamina, consistency, mastery];
  const avg = all.reduce((a, b) => a + b, 0) / all.length;
  const max = Math.max(...all);

  // Initiate: all levels very low
  if (avg < 5) return "Initiate";

  // Phoenix: balanced (all above threshold and no dominant)
  if (all.every((a) => a >= BALANCED_THRESHOLD) && max / avg < DOMINANT_RATIO) {
    return "Phoenix";
  }

  // Dominant class
  if (max === strength) return "Powerlifter";
  if (max === power) return "Athlete";
  if (max === stamina) return "Ironman";
  if (max === mastery) return "Monk";

  // Consistency dominant doesn't have a special class name
  return "Phoenix";
}

// Strength XP: high-load, low-velocity (VBT < 0.5 m/s)
export function calculateStrengthXp(
  velocityMps: number,
  weightKg: number,
): number {
  if (velocityMps >= 0.5) return 0;
  // More weight and slower velocity = more XP
  const velocityMultiplier = Math.max(0, 1 - velocityMps / 0.5);
  return Math.round(weightKg * velocityMultiplier * 0.5);
}

// Power XP: high-velocity, explosive (VBT > 0.75 m/s)
export function calculatePowerXp(
  velocityMps: number,
  powerWatts: number,
): number {
  if (velocityMps < 0.75) return 0;
  const velocityMultiplier = Math.min(velocityMps / 1.3, 2.0);
  return Math.round(powerWatts * velocityMultiplier * 0.02);
}

// Stamina XP: high TUT, high reps, high volume
export function calculateStaminaXp(
  tutMs: number,
  totalReps: number,
  totalVolumeKg: number,
): number {
  const tutSeconds = tutMs / 1000;
  const tutFactor = Math.min(tutSeconds / 60, 3); // cap at 3x for 60s TUT
  const repFactor = Math.min(totalReps / 20, 2); // cap at 2x for 20 reps
  const volumeFactor = totalVolumeKg / 500; // scale by 500kg units
  return Math.round((tutFactor + repFactor + volumeFactor) * 10);
}

// Consistency XP: based on streak and workout frequency
export function calculateConsistencyXp(
  currentStreak: number,
  workoutsThisWeek: number,
): number {
  const streakBonus = Math.min(currentStreak, 30) * 2; // cap at 30-day streak
  const frequencyBonus = Math.min(workoutsThisWeek, 7) * 5;
  return streakBonus + frequencyBonus;
}

// Mastery XP: based on rep quality score (0-100)
export function calculateMasteryXp(
  avgQualityScore: number,
  repCount: number,
): number {
  if (avgQualityScore <= 0) return 0;
  const qualityFactor = avgQualityScore / 100;
  return Math.round(qualityFactor * repCount * 2);
}

export function calculateOverallLevel(attrs: AttributeLevels): number {
  const { strength, power, stamina, consistency, mastery } = attrs;
  return Math.floor((strength + power + stamina + consistency + mastery) / 5);
}
```

**Step 4: Run tests**

Run: `cd C:/Users/dasbl/AndroidStudioProjects/phoenix-portal && npx vitest run src/lib/__tests__/rpg.test.ts`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add src/lib/rpg.ts src/lib/__tests__/rpg.test.ts
git commit -m "feat(rpg): add XP calculation library with leveling and character classes"
```

---

### Task 1.3: Query Keys and Data Fetching

**Files:**
- Modify: `phoenix-portal/src/queries/keys.ts` — add `attributes` namespace
- Create: `phoenix-portal/src/queries/attributes.ts` — Supabase queries
- Create: `phoenix-portal/src/hooks/useAttributes.ts` — React hook

**Step 1: Add query keys**

Add to `src/queries/keys.ts`:

```typescript
attributes: {
  all: ["attributes"] as const,
  byUser: (userId: string) => [...queryKeys.attributes.all, userId] as const,
  history: (userId: string) => [...queryKeys.attributes.all, "history", userId] as const,
},
```

**Step 2: Write query module**

```typescript
// src/queries/attributes.ts
import { supabase } from "@/lib/supabase";

export async function fetchUserAttributes(userId: string) {
  const { data, error } = await supabase
    .from("user_attributes")
    .select("*")
    .eq("user_id", userId)
    .maybeSingle();

  if (error) throw error;
  return data;
}

export async function fetchAttributeHistory(
  userId: string,
  limit = 50,
) {
  const { data, error } = await supabase
    .from("attribute_history")
    .select("*")
    .eq("user_id", userId)
    .order("created_at", { ascending: false })
    .limit(limit);

  if (error) throw error;
  return data ?? [];
}
```

**Step 3: Write React hook**

```typescript
// src/hooks/useAttributes.ts
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/providers/AuthProvider";
import { queryKeys } from "@/queries/keys";
import { fetchUserAttributes, fetchAttributeHistory } from "@/queries/attributes";
import {
  levelFromXp,
  determineCharacterClass,
  calculateOverallLevel,
  type AttributeLevels,
  type CharacterClass,
} from "@/lib/rpg";

export interface UserAttributes {
  strengthLevel: number;
  strengthXp: number;
  powerLevel: number;
  powerXp: number;
  staminaLevel: number;
  staminaXp: number;
  consistencyLevel: number;
  consistencyXp: number;
  masteryLevel: number;
  masteryXp: number;
  characterClass: CharacterClass;
  overallLevel: number;
}

const DEFAULT_ATTRIBUTES: UserAttributes = {
  strengthLevel: 1, strengthXp: 0,
  powerLevel: 1, powerXp: 0,
  staminaLevel: 1, staminaXp: 0,
  consistencyLevel: 1, consistencyXp: 0,
  masteryLevel: 1, masteryXp: 0,
  characterClass: "Initiate",
  overallLevel: 1,
};

export function useAttributes() {
  const { user } = useAuth();

  const { data, isLoading, error } = useQuery({
    queryKey: queryKeys.attributes.byUser(user?.id ?? ""),
    queryFn: () => fetchUserAttributes(user!.id),
    enabled: !!user,
    staleTime: 5 * 60 * 1000,
  });

  if (!data) {
    return { attributes: DEFAULT_ATTRIBUTES, isLoading, error };
  }

  const attributes: UserAttributes = {
    strengthLevel: data.strength_level,
    strengthXp: data.strength_xp,
    powerLevel: data.power_level,
    powerXp: data.power_xp,
    staminaLevel: data.stamina_level,
    staminaXp: data.stamina_xp,
    consistencyLevel: data.consistency_level,
    consistencyXp: data.consistency_xp,
    masteryLevel: data.mastery_level,
    masteryXp: data.mastery_xp,
    characterClass: data.character_class as CharacterClass,
    overallLevel: data.overall_level,
  };

  return { attributes, isLoading, error };
}
```

**Step 4: Commit**

```bash
git add src/queries/keys.ts src/queries/attributes.ts src/hooks/useAttributes.ts
git commit -m "feat(rpg): add attribute queries and React hook"
```

---

### Task 1.4: Attribute Radar Chart Component

**Files:**
- Create: `phoenix-portal/src/app/components/rpg/AttributeRadar.tsx`
- Create: `phoenix-portal/src/app/components/rpg/ClassBadge.tsx`

**Step 1: Write AttributeRadar component**

```tsx
// src/app/components/rpg/AttributeRadar.tsx
import {
  Radar,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  ResponsiveContainer,
} from "recharts";
import type { UserAttributes } from "@/hooks/useAttributes";

interface AttributeRadarProps {
  attributes: UserAttributes;
}

export function AttributeRadar({ attributes }: AttributeRadarProps) {
  const data = [
    { attribute: "Strength", level: attributes.strengthLevel, fullMark: 99 },
    { attribute: "Power", level: attributes.powerLevel, fullMark: 99 },
    { attribute: "Stamina", level: attributes.staminaLevel, fullMark: 99 },
    { attribute: "Consistency", level: attributes.consistencyLevel, fullMark: 99 },
    { attribute: "Mastery", level: attributes.masteryLevel, fullMark: 99 },
  ];

  return (
    <ResponsiveContainer width="100%" height={300}>
      <RadarChart data={data}>
        <PolarGrid stroke="hsl(var(--border))" />
        <PolarAngleAxis
          dataKey="attribute"
          tick={{ fill: "hsl(var(--foreground))", fontSize: 12 }}
        />
        <PolarRadiusAxis
          angle={90}
          domain={[0, 99]}
          tick={{ fill: "hsl(var(--muted-foreground))", fontSize: 10 }}
        />
        <Radar
          name="Level"
          dataKey="level"
          stroke="hsl(var(--primary))"
          fill="hsl(var(--primary))"
          fillOpacity={0.3}
          strokeWidth={2}
        />
      </RadarChart>
    </ResponsiveContainer>
  );
}
```

**Step 2: Write ClassBadge component**

```tsx
// src/app/components/rpg/ClassBadge.tsx
import type { CharacterClass } from "@/lib/rpg";

const CLASS_CONFIG: Record<CharacterClass, { icon: string; color: string; description: string }> = {
  Initiate: { icon: "seedling", color: "text-muted-foreground", description: "Just beginning the journey" },
  Powerlifter: { icon: "dumbbell", color: "text-red-500", description: "Master of raw strength" },
  Athlete: { icon: "zap", color: "text-yellow-500", description: "Explosive power specialist" },
  Ironman: { icon: "heart-pulse", color: "text-green-500", description: "Endurance incarnate" },
  Monk: { icon: "target", color: "text-purple-500", description: "Precision and mastery" },
  Phoenix: { icon: "flame", color: "text-orange-500", description: "Balanced excellence" },
};

interface ClassBadgeProps {
  characterClass: CharacterClass;
  overallLevel: number;
  size?: "sm" | "md" | "lg";
}

export function ClassBadge({ characterClass, overallLevel, size = "md" }: ClassBadgeProps) {
  const config = CLASS_CONFIG[characterClass];
  const sizeClasses = {
    sm: "text-sm px-2 py-1",
    md: "text-base px-3 py-1.5",
    lg: "text-lg px-4 py-2",
  };

  return (
    <div className={`inline-flex items-center gap-2 rounded-full border bg-card ${sizeClasses[size]}`}>
      <span className={config.color}>Lv.{overallLevel}</span>
      <span className="font-semibold">{characterClass}</span>
    </div>
  );
}
```

**Step 3: Commit**

```bash
git add src/app/components/rpg/
git commit -m "feat(rpg): add AttributeRadar chart and ClassBadge components"
```

---

### Task 1.5: RPG Profile Page (Portal Route)

**Files:**
- Create: `phoenix-portal/src/app/routes/attributes.tsx`
- Modify: `phoenix-portal/src/app/routes/index.tsx` — add route

**Step 1: Write the page component**

```tsx
// src/app/routes/attributes.tsx
import { AttributeRadar } from "@/app/components/rpg/AttributeRadar";
import { ClassBadge } from "@/app/components/rpg/ClassBadge";
import { SubscriptionGate } from "@/app/components/SubscriptionGate";
import { Card, CardContent, CardHeader, CardTitle } from "@/app/components/ui/card";
import { Skeleton } from "@/app/components/ui/skeleton";
import { useAttributes } from "@/hooks/useAttributes";
import { calculateXpForLevel } from "@/lib/rpg";

export default function AttributesPage() {
  return (
    <SubscriptionGate requiredTier="PHOENIX">
      <AttributesContent />
    </SubscriptionGate>
  );
}

function AttributesContent() {
  const { attributes, isLoading } = useAttributes();

  if (isLoading) {
    return (
      <div className="space-y-6 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-[300px] w-full" />
      </div>
    );
  }

  const attrs = [
    { name: "Strength", level: attributes.strengthLevel, xp: attributes.strengthXp },
    { name: "Power", level: attributes.powerLevel, xp: attributes.powerXp },
    { name: "Stamina", level: attributes.staminaLevel, xp: attributes.staminaXp },
    { name: "Consistency", level: attributes.consistencyLevel, xp: attributes.consistencyXp },
    { name: "Mastery", level: attributes.masteryLevel, xp: attributes.masteryXp },
  ];

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Skill Tree</h1>
        <ClassBadge
          characterClass={attributes.characterClass}
          overallLevel={attributes.overallLevel}
          size="lg"
        />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Attribute Profile</CardTitle>
        </CardHeader>
        <CardContent>
          <AttributeRadar attributes={attributes} />
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {attrs.map((attr) => {
          const nextLevelXp = calculateXpForLevel(attr.level + 1);
          const currentLevelXp = calculateXpForLevel(attr.level);
          const progress = nextLevelXp > currentLevelXp
            ? ((attr.xp - currentLevelXp) / (nextLevelXp - currentLevelXp)) * 100
            : 100;

          return (
            <Card key={attr.name}>
              <CardContent className="pt-6">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-medium">{attr.name}</span>
                  <span className="text-sm text-muted-foreground">Lv.{attr.level}</span>
                </div>
                <div className="h-2 rounded-full bg-muted overflow-hidden">
                  <div
                    className="h-full rounded-full bg-primary transition-all"
                    style={{ width: `${Math.min(progress, 100)}%` }}
                  />
                </div>
                <p className="text-xs text-muted-foreground mt-1">
                  {attr.xp.toLocaleString()} / {nextLevelXp.toLocaleString()} XP
                </p>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
```

**Step 2: Add route to router**

In `src/app/routes/index.tsx`, add alongside existing routes:

```tsx
import AttributesPage from "@/app/routes/attributes";
// In the route config, inside the protected routes:
{ path: "attributes", element: <AttributesPage /> },
```

**Step 3: Commit**

```bash
git add src/app/routes/attributes.tsx src/app/routes/index.tsx
git commit -m "feat(rpg): add skill tree page with attribute radar and progress bars"
```

---

### Task 1.6: Compute Attributes Edge Function

**Files:**
- Create: `phoenix-portal/supabase/functions/compute-attributes/index.ts`

**Step 1: Write the Edge Function**

```typescript
// supabase/functions/compute-attributes/index.ts
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

// XP calculation constants (mirror src/lib/rpg.ts)
const BASE_XP = 100;
const EXPONENT = 1.5;

function levelFromXp(xp: number): number {
  if (xp <= 0) return 1;
  const level = Math.floor(Math.pow(xp / BASE_XP, 1 / EXPONENT) + 1);
  return Math.min(Math.max(level, 1), 99);
}

function determineCharacterClass(levels: Record<string, number>): string {
  const all = Object.values(levels);
  const avg = all.reduce((a, b) => a + b, 0) / all.length;
  const max = Math.max(...all);
  if (avg < 5) return "Initiate";
  if (all.every((a) => a >= 30) && max / avg < 1.5) return "Phoenix";
  const maxKey = Object.entries(levels).find(([, v]) => v === max)?.[0];
  const classMap: Record<string, string> = {
    strength: "Powerlifter", power: "Athlete", stamina: "Ironman",
    mastery: "Monk", consistency: "Phoenix",
  };
  return classMap[maxKey ?? ""] ?? "Phoenix";
}

Deno.serve(async (req) => {
  try {
    const { user_id, session_id } = await req.json();

    // Fetch rep summaries for this session
    const { data: reps } = await supabase
      .from("rep_summaries")
      .select("mean_velocity_mps, peak_velocity_mps, mean_force_n, power_watts, tut_ms, rom_mm")
      .eq("session_id", session_id);

    // Fetch session summary
    const { data: session } = await supabase
      .from("workout_sessions")
      .select("total_volume, set_count, exercise_count")
      .eq("id", session_id)
      .single();

    if (!reps || !session) {
      return new Response(JSON.stringify({ error: "Session not found" }), { status: 404 });
    }

    // Calculate XP gains per attribute
    let strengthXp = 0, powerXp = 0, staminaXp = 0, masteryXp = 0;

    for (const rep of reps) {
      const vel = rep.mean_velocity_mps ?? 0;
      const force = rep.mean_force_n ?? 0;
      const power = rep.power_watts ?? 0;
      const tut = rep.tut_ms ?? 0;
      const weightKg = force / 9.81;

      // Strength: low velocity, high load
      if (vel < 0.5 && vel > 0) {
        strengthXp += Math.round(weightKg * Math.max(0, 1 - vel / 0.5) * 0.5);
      }
      // Power: high velocity
      if (vel >= 0.75) {
        powerXp += Math.round(power * Math.min(vel / 1.3, 2.0) * 0.02);
      }
      // Stamina: TUT contribution
      staminaXp += Math.round((tut / 1000 / 60) * 10);
      // Mastery: quality (simplified - full quality score not in rep_summaries yet)
      if (rep.rom_mm && rep.rom_mm > 100) {
        masteryXp += 2; // basic ROM compliance
      }
    }

    // Stamina: session-level volume and rep count
    staminaXp += Math.round((session.total_volume ?? 0) / 500 * 10);

    // Fetch or create user_attributes row
    const { data: existing } = await supabase
      .from("user_attributes")
      .select("*")
      .eq("user_id", user_id)
      .maybeSingle();

    const currentXp = {
      strength: (existing?.strength_xp ?? 0) + strengthXp,
      power: (existing?.power_xp ?? 0) + powerXp,
      stamina: (existing?.stamina_xp ?? 0) + staminaXp,
      consistency: existing?.consistency_xp ?? 0, // computed separately
      mastery: (existing?.mastery_xp ?? 0) + masteryXp,
    };

    const levels = {
      strength: levelFromXp(currentXp.strength),
      power: levelFromXp(currentXp.power),
      stamina: levelFromXp(currentXp.stamina),
      consistency: levelFromXp(currentXp.consistency),
      mastery: levelFromXp(currentXp.mastery),
    };

    const characterClass = determineCharacterClass(levels);
    const overallLevel = Math.floor(Object.values(levels).reduce((a, b) => a + b, 0) / 5);

    const upsertData = {
      user_id,
      strength_level: levels.strength, strength_xp: currentXp.strength,
      power_level: levels.power, power_xp: currentXp.power,
      stamina_level: levels.stamina, stamina_xp: currentXp.stamina,
      consistency_level: levels.consistency, consistency_xp: currentXp.consistency,
      mastery_level: levels.mastery, mastery_xp: currentXp.mastery,
      character_class: characterClass,
      overall_level: overallLevel,
      updated_at: new Date().toISOString(),
    };

    await supabase.from("user_attributes").upsert(upsertData, { onConflict: "user_id" });

    // Log XP gains to history
    const historyEntries = [
      { attribute: "strength", xp_gained: strengthXp },
      { attribute: "power", xp_gained: powerXp },
      { attribute: "stamina", xp_gained: staminaXp },
      { attribute: "mastery", xp_gained: masteryXp },
    ].filter((e) => e.xp_gained > 0).map((e) => ({
      ...e, user_id, source_session_id: session_id,
    }));

    if (historyEntries.length > 0) {
      await supabase.from("attribute_history").insert(historyEntries);
    }

    return new Response(JSON.stringify({ levels, characterClass, overallLevel, xpGains: { strengthXp, powerXp, staminaXp, masteryXp } }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: String(err) }), { status: 500 });
  }
});
```

**Step 2: Commit**

```bash
git add supabase/functions/compute-attributes/
git commit -m "feat(rpg): add compute-attributes Edge Function for XP calculation"
```

---

### Task 1.7: Wire RPG Computation to Sync Flow

**Files:**
- Modify: `phoenix-portal/src/hooks/useRealtimeSync.ts` — trigger attribute computation on sync

**Step 1: Add attribute computation trigger**

After the existing `sync_complete` event handler, add a call to the `compute-attributes` Edge Function:

```typescript
// Inside the sync event handler, after invalidating existing queries:
supabase.functions.invoke("compute-attributes", {
  body: { user_id: user.id, session_id: payload.session_id },
}).then(() => {
  queryClient.invalidateQueries({ queryKey: queryKeys.attributes.all });
});
```

**Step 2: Commit**

```bash
git add src/hooks/useRealtimeSync.ts
git commit -m "feat(rpg): trigger attribute computation on workout sync"
```

---

### Task 1.8: Mobile — Feature Gate + Attribute Card

**Files:**
- Modify: `shared/.../domain/premium/FeatureGate.kt` — add `RPG_SKILL_TREES` feature
- Create: `shared/.../domain/model/RPGModels.kt` — data classes
- Create: `androidApp/.../presentation/ui/rpg/AttributeCard.kt` — Compose card

**Step 1: Add feature gate**

In `FeatureGate.kt`, add `RPG_SKILL_TREES` to the `Feature` enum and `phoenixFeatures` set.

**Step 2: Write RPG data models**

```kotlin
// shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RPGModels.kt
package com.devil.phoenixproject.domain.model

data class UserAttributes(
    val strengthLevel: Int = 1,
    val strengthXp: Long = 0,
    val powerLevel: Int = 1,
    val powerXp: Long = 0,
    val staminaLevel: Int = 1,
    val staminaXp: Long = 0,
    val consistencyLevel: Int = 1,
    val consistencyXp: Long = 0,
    val masteryLevel: Int = 1,
    val masteryXp: Long = 0,
    val characterClass: String = "Initiate",
    val overallLevel: Int = 1
)
```

**Step 3: Write Compose card (simplified view)**

```kotlin
// androidApp/src/main/kotlin/.../presentation/ui/rpg/AttributeCard.kt
// Compact card showing 5 attribute levels, class badge, and "View on Portal" button
// Gate behind PremiumFeatureGate composable
```

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/FeatureGate.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RPGModels.kt
git add androidApp/src/main/kotlin/.../presentation/ui/rpg/
git commit -m "feat(rpg): add mobile feature gate and attribute card composable"
```

---

## Phase 2: Ghost Racing

### Task 2.1: Best-Matching-Session RPC Function

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260224_ghost_racing.sql` — RPC function

Creates a Supabase RPC `best_matching_session(p_user_id UUID, p_exercise_name TEXT, p_weight_kg NUMERIC, p_tolerance_pct NUMERIC DEFAULT 5)` that returns the session ID with the best performance (highest mean velocity) for a matching exercise and weight within the tolerance range.

### Task 2.2: Ghost Racing Portal Components

**Files:**
- Create: `phoenix-portal/src/lib/ghost.ts` — matching and comparison logic
- Create: `phoenix-portal/src/app/components/replay/DualSessionReplay.tsx` — side-by-side replay
- Modify: `phoenix-portal/src/app/routes/replay.tsx` — add ghost comparison mode

Extends the existing Session Replay page with a "Compare with Best" toggle. Uses visx to overlay two force curves on the same chart with distinct colors. Per-rep stats table shows velocity delta, force delta, and win/lose indicators.

### Task 2.3: Mobile Ghost Racing Overlay

**Files:**
- Create: `shared/.../domain/model/GhostRaceModels.kt` — data classes
- Create: `androidApp/.../presentation/ui/workout/GhostRaceOverlay.kt` — Compose overlay
- Modify: `shared/.../domain/premium/FeatureGate.kt` — add `GHOST_RACING` feature
- Modify: `androidApp/.../presentation/ui/workout/ActiveWorkoutScreen.kt` — integrate overlay

The overlay shows two vertical animated bars during active sets. Current cable position drives the left bar (live BLE); historical rep data drives the right bar (fetched from portal before set starts). "Ahead"/"Behind" text updates per rep based on concentric velocity comparison.

**PHOENIX tier:** Uses `rep_summaries` data (per-rep averages, not full telemetry). Shows simplified "beat your best velocity" comparison.

**ELITE tier:** Uses full `rep_telemetry` (50Hz). Real-time position overlay with smooth animation.

### Task 2.4: Tests and Commit

Unit tests for matching logic, comparison calculations. Integration test for the RPC function.

---

## Phase 3: Human Digital Twin (3D)

### Task 3.1: Install Three.js Dependencies

```bash
cd C:/Users/dasbl/AndroidStudioProjects/phoenix-portal
npm install three @react-three/fiber @react-three/drei @types/three
```

### Task 3.2: Muscle Recovery State Table

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260225_muscle_recovery.sql`

Table `muscle_recovery_state`: `user_id`, `muscle_group` (TEXT), `last_trained_at` (TIMESTAMPTZ), `volume_last_session` (NUMERIC), `estimated_ready_at` (TIMESTAMPTZ), `fatigue_level` (0-100), `asymmetry_flag` (BOOLEAN).

Edge Function `compute-recovery-state` runs on workout sync, calculates per-muscle recovery timelines based on: time since training, volume intensity, ACWR-extended model.

### Task 3.3: GLTF Model and Muscle Mapping

**Files:**
- Create: `phoenix-portal/public/models/body.glb` — 3D model asset
- Create: `phoenix-portal/src/lib/muscle-mapping.ts` — maps MuscleGroup enum to GLTF mesh names

Use a Creative Commons human body GLTF model with named mesh groups. Each of the 12 `MuscleGroup` enum values maps to one or more mesh names in the model. The mapping file exports a `MUSCLE_MESH_MAP: Record<string, string[]>`.

### Task 3.4: Digital Twin React Component

**Files:**
- Create: `phoenix-portal/src/app/components/twin/DigitalTwinScene.tsx` — R3F Canvas
- Create: `phoenix-portal/src/app/components/twin/MuscleModel.tsx` — GLTF loader with shader materials
- Create: `phoenix-portal/src/app/components/twin/MuscleTooltip.tsx` — HTML overlay on hover/click
- Create: `phoenix-portal/src/hooks/useMuscleRecovery.ts` — hook for recovery state data

The R3F scene loads the GLTF model, applies per-muscle shader materials based on `fatigue_level` (red → yellow → green gradient), adds OrbitControls for rotation/zoom. Clicking a muscle group shows a tooltip with: last trained date, volume, estimated recovery time, asymmetry warning if flagged.

### Task 3.5: Twin Route and Integration

**Files:**
- Create: `phoenix-portal/src/app/routes/twin.tsx` — route component
- Modify: `phoenix-portal/src/app/routes/index.tsx` — add `/twin` route (ELITE gated)

Page wraps `DigitalTwinScene` in `SubscriptionGate requiredTier="ELITE"`. Sidebar shows muscle group legend, recovery timeline, and quick links to related analytics.

### Task 3.6: Tests and Commit

Snapshot test for the twin page rendering. Unit tests for muscle mapping and recovery state calculations.

---

## Phase 4: Force-Velocity Profile Dashboard

### Task 4.1: F-v Profile Tables and Edge Function

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260226_fv_profiles.sql`
- Create: `phoenix-portal/supabase/functions/compute-fv-profile/index.ts`

Table `fv_profiles`: `user_id`, `exercise_name`, `v0` (NUMERIC), `f0` (NUMERIC), `pmax` (NUMERIC), `r2` (NUMERIC), `data_points` (JSONB), `computed_at` (TIMESTAMPTZ).

Table `fv_profile_history`: same fields + `id` for time-series tracking.

Edge Function performs least-squares linear regression on (weight_kg, mean_velocity_mps) pairs from `rep_summaries` + `sets` for a given exercise. Requires 3+ distinct loads with 2+ reps each. Outputs V0, F0, Pmax, R-squared.

### Task 4.2: F-v Calculation Library

**Files:**
- Create: `phoenix-portal/src/lib/force-velocity.ts` — regression math
- Create: `phoenix-portal/src/lib/__tests__/force-velocity.test.ts` — tests

Pure math: `linearRegression(points: {x: number, y: number}[])` returning slope, intercept, r2. `calculateFVProfile(dataPoints: {weightKg: number, velocityMps: number}[])` returning V0, F0, Pmax, R-squared.

### Task 4.3: CNS Fatigue Detection

**Files:**
- Create: `phoenix-portal/src/lib/cns-fatigue.ts`
- Create: `phoenix-portal/src/lib/__tests__/cns-fatigue.test.ts`

Compares F-v profile slope over sliding 14-day windows. If slope decreases by >10% from 30-day baseline, flags CNS fatigue. Returns `{ isFatigued: boolean, slopeChange: number, recommendation: string }`.

### Task 4.4: F-v Dashboard Components

**Files:**
- Create: `phoenix-portal/src/app/components/biomechanics/ForceVelocityChart.tsx` — visx scatter + regression
- Create: `phoenix-portal/src/app/components/biomechanics/FVProfileCard.tsx` — summary card
- Create: `phoenix-portal/src/app/components/biomechanics/CNSFatigueAlert.tsx` — warning banner
- Modify: `phoenix-portal/src/app/routes/biomechanics.tsx` — add F-v tab

### Task 4.5: Tests and Commit

Unit tests for regression math (known data points → known regression). Component snapshot tests.

---

## Phase 5: Mechanical Impulse Quantification

### Task 5.1: Impulse Calculation Library

**Files:**
- Create: `phoenix-portal/src/lib/impulse.ts`
- Create: `phoenix-portal/src/lib/__tests__/impulse.test.ts`

```typescript
export function calculateImpulse(forceN: number, tutMs: number): number {
  return Math.round(forceN * (tutMs / 1000)); // Newton-seconds
}

export function calculatePreciseImpulse(
  telemetry: { force_n: number; timestamp_ms: number }[],
): { concentric: number; eccentric: number; total: number } {
  // Integrate force over time using trapezoidal rule on 50Hz data
}
```

### Task 5.2: Database Column Addition

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260227_impulse.sql`

```sql
ALTER TABLE rep_summaries ADD COLUMN impulse_ns NUMERIC;
ALTER TABLE exercise_progress ADD COLUMN total_impulse_ns NUMERIC;
```

### Task 5.3: Portal UI Integration

Add impulse metric to session detail page, analytics trend chart, and biomechanics page. Show concentric/eccentric split for ELITE users with full telemetry.

---

## Phase 6: Enhanced Wearable Ingestion (HRV/Sleep)

### Task 6.1: Biometric Readings Table

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260228_biometric_readings.sql`

Table `biometric_readings`: `user_id`, `date` (DATE), `provider` (TEXT), `hrv_rmssd` (NUMERIC), `resting_hr` (INTEGER), `sleep_score` (INTEGER), `deep_sleep_minutes` (INTEGER), `rem_sleep_minutes` (INTEGER), `total_sleep_minutes` (INTEGER).

### Task 6.2: Oura Integration

**Files:**
- Create: `phoenix-portal/supabase/functions/oura-oauth/index.ts`
- Create: `phoenix-portal/supabase/functions/oura-sync/index.ts`

OAuth2 callback following the same pattern as `strava-oauth`. Sync function fetches daily readiness and sleep data from Oura Cloud API v2, upserts to `biometric_readings`.

### Task 6.3: Extend Garmin/Fitbit Sync

**Files:**
- Modify: `phoenix-portal/supabase/functions/garmin-webhook/index.ts` — extract HRV/sleep fields
- Modify: `phoenix-portal/supabase/functions/fitbit-sync/index.ts` — extract HRV/sleep fields

Both already fetch activity data. Extend to also fetch daily HRV summaries and sleep logs from their respective APIs.

### Task 6.4: Portal Biometric Dashboard

**Files:**
- Create: `phoenix-portal/src/app/components/recovery/BiometricTrends.tsx`
- Modify: `phoenix-portal/src/app/routes/recovery.tsx` — add biometric section

Show HRV trend (7/14/30 day), sleep score trend, RHR trend. Highlight days where HRV is >1 SD below baseline. Gate behind ELITE.

---

## Phase 7: Predictive Fatigue Model + AI Auto-Regulation

### Task 7.1: Readiness Score Tables

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260301_readiness.sql`

Tables: `readiness_scores` (daily per-user, per-muscle-group), `readiness_adjustments` (AI suggestion log + acceptance).

### Task 7.2: Bannister FFM Computation

**Files:**
- Create: `phoenix-portal/src/lib/fatigue-model.ts`
- Create: `phoenix-portal/src/lib/__tests__/fatigue-model.test.ts`
- Create: `phoenix-portal/supabase/functions/compute-readiness/index.ts`

Implements Fitness-Fatigue Model with biometric correction:
- `Fitness(t) = SUM w(i) * e^(-(t-i)/42)` (long-term, tau=42 days)
- `Fatigue(t) = SUM w(i) * e^(-(t-i)/7)` (short-term, tau=7 days)
- Biometric correction: if HRV >1 SD below baseline, increase tau2 (slower recovery)
- Output: readiness 0-100 per muscle group + overall CNS

Edge Function runs nightly (via Supabase cron) or on workout sync.

### Task 7.3: AI Coach API

**Files:**
- Create: `phoenix-portal/supabase/functions/pre-workout-briefing/index.ts`

RPC that returns: readiness score, suggested weight adjustments, reasoning text. Called by mobile app before workout starts.

### Task 7.4: Mobile Pre-Workout Briefing

**Files:**
- Create: `shared/.../domain/model/ReadinessModels.kt`
- Create: `androidApp/.../presentation/ui/workout/PreWorkoutBriefing.kt`
- Modify: `androidApp/.../presentation/ui/workout/ActiveWorkoutScreen.kt`

Shows readiness score (Green/Yellow/Red) before first set. Displays "AI Suggested Weight" alongside prescribed weight. User accepts or overrides.

### Task 7.5: Portal Readiness Dashboard

**Files:**
- Create: `phoenix-portal/src/app/components/recovery/ReadinessTimeline.tsx`
- Create: `phoenix-portal/src/app/components/recovery/LoadReadinessChart.tsx`
- Modify: `phoenix-portal/src/app/routes/recovery.tsx`

Daily readiness timeline, load vs. readiness correlation, overreaching risk alert.

---

## Phase 8: CV Pose Estimation (Mobile)

### Task 8.1: Add MediaPipe Dependencies

**Files:**
- Modify: `androidApp/build.gradle.kts` — add MediaPipe Tasks Vision dependency
- Modify: `shared/build.gradle.kts` — add camera permission in manifest

```kotlin
// androidApp/build.gradle.kts
implementation("com.google.mediapipe:tasks-vision:0.10.21")
implementation("androidx.camera:camera-core:1.4.1")
implementation("androidx.camera:camera-camera2:1.4.1")
implementation("androidx.camera:camera-lifecycle:1.4.1")
implementation("androidx.camera:camera-view:1.4.1")
```

### Task 8.2: Joint Angle Calculator (commonMain)

**Files:**
- Create: `shared/.../domain/cv/JointAngleCalculator.kt`
- Create: `shared/.../domain/cv/FormRuleEngine.kt`
- Create: `shared/.../domain/cv/ExerciseFormRules.kt`
- Create: `shared/.../domain/model/CVModels.kt`

Pure Kotlin math in `commonMain`. `JointAngleCalculator` computes angles between three 3D points. `FormRuleEngine` evaluates exercise-specific rules (e.g., lumbar flexion > 30 degrees = warning). `ExerciseFormRules` defines thresholds per exercise.

### Task 8.3: Android Camera + MediaPipe Integration

**Files:**
- Create: `androidApp/.../platform/cv/CameraPreviewComposable.kt`
- Create: `androidApp/.../platform/cv/PoseAnalyzer.kt`
- Create: `androidApp/.../platform/cv/FormFeedbackOverlay.kt`

CameraX preview with MediaPipe `PoseLandmarker`. Feeds frames at 30fps, extracts 33 landmarks, passes to `JointAngleCalculator`, evaluates rules via `FormRuleEngine`, renders skeleton overlay and warning text on Canvas.

### Task 8.4: Integrate into Workout Screen

**Files:**
- Modify: `androidApp/.../presentation/ui/workout/ActiveWorkoutScreen.kt` — add "Form Check" toggle
- Modify: `shared/.../domain/premium/FeatureGate.kt` — add `CV_FORM_CHECK` feature

Toggle appears for PHOENIX+ users. When enabled, camera preview appears as PiP overlay. Warnings show as audio cues + screen border flash. **No auto-deload — warnings only.**

### Task 8.5: iOS Placeholder

**Files:**
- Create: `iosApp/.../cv/PoseAnalysisView.swift` — stub for iOS MediaPipe integration

Minimal stub that shows "Form Check coming soon to iOS" message. Full iOS implementation deferred.

---

## Phase 9: CV Form Rules Engine (Exercise-Specific)

### Task 9.1: Define Rules for Core Exercises

**Files:**
- Modify: `shared/.../domain/cv/ExerciseFormRules.kt`

Define joint angle thresholds for:
- Squat (knee flexion, hip hinge, lumbar flexion, knee valgus)
- Deadlift/RDL (hip hinge, lumbar flexion, knee position)
- Overhead Press (shoulder elevation, lumbar extension)
- Curl (elbow flexion, shoulder stability)
- Row (lumbar flexion, shoulder retraction)

Each rule: `joint`, `threshold_degrees`, `severity` (INFO/WARNING/CRITICAL), `message`.

### Task 9.2: Form Score Computation

**Files:**
- Create: `shared/.../domain/cv/FormScoreCalculator.kt`

Composite 0-100 score from joint angle compliance across all tracked joints. Weighted by severity of violations. Per-rep and per-exercise aggregation.

### Task 9.3: Form Data Persistence

**Files:**
- Modify: `shared/.../database/VitruvianDatabase.sq` — add form assessment tables (local)
- Create portal migration for `form_assessments` and `form_violations` tables

Store per-exercise form scores, violation counts, and joint angle summaries. Sync to portal on workout completion (ELITE tier only).

---

## Phase 10: CV Portal Analytics

### Task 10.1: Form Analytics Tables

**Files:**
- Create: `phoenix-portal/supabase/migrations/20260302_form_analytics.sql`

Tables: `form_assessments` (per-session, per-exercise), `form_violations` (individual violations).

### Task 10.2: Form Analysis Dashboard

**Files:**
- Create: `phoenix-portal/src/app/components/biomechanics/FormScoreTrend.tsx` — Recharts line
- Create: `phoenix-portal/src/app/components/biomechanics/JointAngleRadar.tsx` — spider chart
- Create: `phoenix-portal/src/app/components/biomechanics/FatigueFormCorrelation.tsx` — scatter
- Create: `phoenix-portal/src/app/components/biomechanics/PosturalHeatmap.tsx` — body silhouette
- Modify: `phoenix-portal/src/app/routes/biomechanics.tsx` — add "Form Analysis" tab

Gate behind ELITE. Shows form quality trends, joint compliance radar, fatigue-form correlation (rep number vs. form score), and body silhouette with color-coded compliance.

### Task 10.3: Tests and Final Commit

Component tests, integration tests for form data sync, end-to-end test for form analytics page.

---

## Summary

| Phase | Tasks | New Files | Modified Files | New DB Tables |
|-------|-------|-----------|----------------|---------------|
| 1 | 8 | ~12 | 3 | 2 |
| 2 | 4 | ~8 | 4 | 1 RPC |
| 3 | 6 | ~10 | 2 | 1 |
| 4 | 5 | ~8 | 1 | 2 |
| 5 | 3 | ~4 | 2 | 0 (column adds) |
| 6 | 4 | ~6 | 2 | 1 |
| 7 | 5 | ~8 | 3 | 2 |
| 8 | 5 | ~8 | 3 | 0 |
| 9 | 3 | ~4 | 2 | 2 |
| 10 | 3 | ~6 | 1 | 2 |

**Total: ~46 tasks, ~74 new files, ~23 modified files, 12 new database tables/RPCs**

---

*This plan was generated from the approved design at `docs/plans/2026-02-20-premium-enhancements-design.md`.*
