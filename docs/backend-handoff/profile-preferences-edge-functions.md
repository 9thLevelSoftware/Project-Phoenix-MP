# Profile Preference Edge Function Handoff

This is a local-only backend handoff. The mobile repository does not deploy an Edge Function, apply a remote migration, or mutate a Supabase project. The portal implementer must follow the current official [Edge authorization guidance](https://supabase.com/docs/guides/functions/auth), [row-level-security guidance](https://supabase.com/docs/guides/database/postgres/row-level-security), and [Data API exposure change](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically).

Create or update only these portal targets:

```text
supabase/functions/mobile-sync-push/index.ts
supabase/functions/mobile-sync-push/index.test.ts
supabase/functions/mobile-sync-pull/index.ts
supabase/functions/mobile-sync-pull/index.test.ts
supabase/functions/_shared/profile-preference-byte-goldens.json
supabase/tests/database/profile_preferences.test.sql
supabase/config.toml
supabase/migrations/  (created by: supabase migration new profile_preferences)
docs/profile-preferences-advisor-dispositions.md
```

The SQL in `profile-preferences-supabase.sql` is the migration source. Create the portal migration with the CLI rather than inventing a filename. The canonical projection and mutation RPC are `SECURITY INVOKER`; client roles have neither direct table DML nor function execution, and only `service_role` receives the explicitly listed privileges.

## Shared wire types and response semantics

```typescript
type JsonRecord = Record<string, unknown>;
type ProfilePreferenceSection = "CORE" | "RACK" | "WORKOUT" | "LED" | "VBT";

interface PortalProfilePreferenceSectionMutation {
  localProfileId: string;
  section: ProfilePreferenceSection;
  documentVersion: number;
  baseRevision: number;
  clientModifiedAt: string;
  payload: Record<string, unknown>;
}

interface PortalProfilePreferenceSectionCanonical {
  localProfileId: string;
  section: ProfilePreferenceSection;
  documentVersion: number;
  serverRevision: number;
  serverUpdatedAt: string;
  payload: Record<string, unknown>;
}

interface ProfilePreferenceSectionRejection {
  localProfileId: string;
  section: string;
  serverRevision: number;
  reason:
    | "REVISION_CONFLICT"
    | "VALIDATION_FAILED"
    | "UNSUPPORTED_SECTION"
    | "UNSUPPORTED_DOCUMENT_VERSION"
    | "SECTION_TOO_LARGE"
    | "DUPLICATE_SECTION"
    | "UNKNOWN_PROFILE";
  canonicalSection?: PortalProfilePreferenceSectionCanonical;
}

interface MobileSyncPushAdditions {
  profilePreferenceSections?: PortalProfilePreferenceSectionMutation[];
}

interface MobileSyncPushResponseAdditions {
  profilePreferencesAccepted?: boolean;
  canonicalProfilePreferenceSections: PortalProfilePreferenceSectionCanonical[];
  profilePreferenceRejections: ProfilePreferenceSectionRejection[];
}

interface MobileSyncPullResponseAdditions {
  profilePreferenceSections?: PortalProfilePreferenceSectionCanonical[];
}
```

`REVISION_CONFLICT`, `VALIDATION_FAILED`, `UNSUPPORTED_SECTION`, `UNSUPPORTED_DOCUMENT_VERSION`, and `UNKNOWN_PROFILE` are domain rejections returned by the RPC and may coexist with successful siblings. `SECTION_TOO_LARGE` and `DUPLICATE_SECTION` are Edge validation rejections. Authentication, transport, PostgREST/RPC, permission, timeout, malformed-RPC-row, and pull-query failures are infrastructure failures: return a sanitized HTTP 5xx and never relabel them as a domain rejection. Version `1` is the only supported wrapper and embedded document version.

## Exact raw-byte contract

Copy `profile-preference-byte-goldens.json` byte-for-byte to `supabase/functions/_shared/profile-preference-byte-goldens.json`. Before using it, Deno tests must compare the portal copy's SHA-256 with this handoff artifact. Kotlin and Deno parse only the artifact wrapper and preserve both raw template strings verbatim.

For a section golden, assert the padding marker appears exactly once, replace it with enough ASCII `x` bytes to reach the requested section target, and assert the final UTF-8 count. For a request golden, first replace `__SECTION_JSON__` with the valid section template containing one ASCII padding byte, then replace the request padding marker with enough ASCII `x` bytes to reach the complete-body target. Keep the `20.0` decimal lexeme, `-1e3` exponent lexeme, escaped quote/backslash, and multibyte `π界🙂` unchanged.

The 9,500,000-byte cap applies to the exact HTTP body only when `profilePreferenceSections` is absent. When that key is present, the 524,288-byte cap applies to the complete exact raw `PortalSyncPayload`, including whitespace and ordinary fields. The 262,144-byte cap applies to each exact raw array-element span; never reconstruct it with `JSON.stringify`. Exact limits are inclusive. Kotlin verifies decoder/scanner parity; Deno invokes the real raw handler and verifies 400/413 responses and privileged-call suppression.

## Push validation and raw scanner

Place this executable contract in `mobile-sync-push/index.ts`, or import it unchanged from a tested sibling module. Validation is strict: unknown keys fail and no schema library may strip or coerce values.

```typescript
type ValidationReason =
  | "VALIDATION_FAILED"
  | "UNSUPPORTED_SECTION"
  | "UNSUPPORTED_DOCUMENT_VERSION";

class PreferenceValidationError extends Error {
  constructor(
    readonly reason: ValidationReason,
    readonly field: string,
  ) {
    super("Invalid profile preference field: " + field);
    this.name = "PreferenceValidationError";
  }
}

class PreferenceInfrastructureError extends Error {
  constructor(readonly operation: string) {
    super("Profile preference infrastructure failure");
    this.name = "PreferenceInfrastructureError";
  }
}

const MAX_PROFILE_PREFERENCE_SECTION_BYTES = 262_144;
const MAX_PROFILE_PREFERENCE_REQUEST_BYTES = 524_288;
const MAX_MOBILE_SYNC_REQUEST_BYTES = 9_500_000;
const utf8Bytes = (rawJson: string): number =>
  new TextEncoder().encode(rawJson).byteLength;

const PUSH_BODY_KEYS = new Set([
  "deviceId",
  "platform",
  "lastSync",
  "sessions",
  "telemetry",
  "routines",
  "deletedRoutineIds",
  "cycles",
  "deletedCycleIds",
  "rpgAttributes",
  "badges",
  "gamificationStats",
  "phaseStatistics",
  "exerciseSignatures",
  "assessments",
  "customExercises",
  "profileId",
  "profileName",
  "allProfiles",
  "externalActivities",
  "personalRecords",
  "profilePreferenceSections",
]);

const MUTATION_KEYS = [
  "localProfileId",
  "section",
  "documentVersion",
  "baseRevision",
  "clientModifiedAt",
  "payload",
] as const;

const LOCAL_ONLY_KEYS = new Set([
  "safeword",
  "safewordcalibrated",
  "adultsonlyconfirmed",
  "adultsonlyprompted",
  "localgeneration",
  "dirty",
  "legacymigrationversion",
]);

const normalizeKey = (key: string): string =>
  key.replace(/[^a-z0-9]/gi, "").toLowerCase();

const fail = (
  field: string,
  reason: ValidationReason = "VALIDATION_FAILED",
): never => {
  throw new PreferenceValidationError(reason, field);
};

const requireRecord = (value: unknown, field: string): JsonRecord => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) fail(field);
  return value as JsonRecord;
};

const requireExactRecord = (
  value: unknown,
  keys: readonly string[],
  field: string,
): JsonRecord => {
  const record = requireRecord(value, field);
  const allowed = new Set(keys);
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) fail(field + "." + key);
  }
  for (const key of keys) {
    if (!Object.hasOwn(record, key)) fail(field + "." + key);
  }
  return record;
};

const requireKnownKeys = (
  record: JsonRecord,
  allowed: ReadonlySet<string>,
  field: string,
): void => {
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) fail(field + "." + key);
  }
};

const requireArray = (value: unknown, field: string): unknown[] => {
  if (!Array.isArray(value)) fail(field);
  return value;
};

interface RawJsonSpan {
  start: number;
  end: number;
}

interface TopLevelJsonScan {
  valueSpans: Map<string, RawJsonSpan>;
  duplicateKeys: Set<string>;
}

const skipJsonWhitespace = (raw: string, from: number): number => {
  let index = from;
  while (index < raw.length && /[\u0009\u000a\u000d\u0020]/.test(raw[index])) index += 1;
  return index;
};

function scanJsonString(raw: string, start: number): number {
  if (raw[start] !== '"') fail("rawJson.string");
  let index = start + 1;
  while (index < raw.length) {
    const character = raw[index];
    if (character === '"') return index + 1;
    if (character === "\\") index += 2;
    else index += 1;
  }
  fail("rawJson.unterminatedString");
}

function scanJsonValue(raw: string, from: number, depth = 0): number {
  if (depth > 256) fail("rawJson.depth");
  let index = skipJsonWhitespace(raw, from);
  if (raw[index] === '"') return scanJsonString(raw, index);
  if (raw[index] === "[") {
    index = skipJsonWhitespace(raw, index + 1);
    if (raw[index] === "]") return index + 1;
    while (index < raw.length) {
      index = skipJsonWhitespace(raw, scanJsonValue(raw, index, depth + 1));
      if (raw[index] === "]") return index + 1;
      if (raw[index] !== ",") fail("rawJson.arrayDelimiter");
      index = skipJsonWhitespace(raw, index + 1);
    }
    fail("rawJson.unterminatedArray");
  }
  if (raw[index] === "{") {
    index = skipJsonWhitespace(raw, index + 1);
    if (raw[index] === "}") return index + 1;
    while (index < raw.length) {
      const keyEnd = scanJsonString(raw, index);
      index = skipJsonWhitespace(raw, keyEnd);
      if (raw[index] !== ":") fail("rawJson.objectColon");
      index = skipJsonWhitespace(raw, scanJsonValue(raw, index + 1, depth + 1));
      if (raw[index] === "}") return index + 1;
      if (raw[index] !== ",") fail("rawJson.objectDelimiter");
      index = skipJsonWhitespace(raw, index + 1);
    }
    fail("rawJson.unterminatedObject");
  }
  const tokenStart = index;
  while (index < raw.length && !/[\u0009\u000a\u000d\u0020,\]}]/.test(raw[index])) {
    index += 1;
  }
  if (index === tokenStart) fail("rawJson.value");
  return index;
}
```

```typescript
function scanTopLevelJsonObject(raw: string): TopLevelJsonScan {
  let index = skipJsonWhitespace(raw, 0);
  if (raw[index] !== "{") fail("body");
  index = skipJsonWhitespace(raw, index + 1);
  const valueSpans = new Map<string, RawJsonSpan>();
  const duplicateKeys = new Set<string>();
  if (raw[index] === "}") {
    index = skipJsonWhitespace(raw, index + 1);
    if (index !== raw.length) fail("rawJson.trailingData");
    return { valueSpans, duplicateKeys };
  }
  while (index < raw.length) {
    const keyStart = index;
    const keyEnd = scanJsonString(raw, keyStart);
    const key = JSON.parse(raw.slice(keyStart, keyEnd)) as string;
    index = skipJsonWhitespace(raw, keyEnd);
    if (raw[index] !== ":") fail("rawJson.objectColon");
    const valueStart = skipJsonWhitespace(raw, index + 1);
    const valueEnd = scanJsonValue(raw, valueStart);
    if (valueSpans.has(key)) duplicateKeys.add(key);
    else valueSpans.set(key, { start: valueStart, end: valueEnd });
    index = skipJsonWhitespace(raw, valueEnd);
    if (raw[index] === "}") {
      index = skipJsonWhitespace(raw, index + 1);
      if (index !== raw.length) fail("rawJson.trailingData");
      return { valueSpans, duplicateKeys };
    }
    if (raw[index] !== ",") fail("rawJson.objectDelimiter");
    index = skipJsonWhitespace(raw, index + 1);
  }
  fail("rawJson.unterminatedObject");
}

function scanJsonArrayElementSpans(raw: string, arraySpan: RawJsonSpan): RawJsonSpan[] {
  let index = skipJsonWhitespace(raw, arraySpan.start);
  if (raw[index] !== "[") fail("body.profilePreferenceSections");
  index = skipJsonWhitespace(raw, index + 1);
  const spans: RawJsonSpan[] = [];
  if (raw[index] === "]") {
    if (index + 1 !== arraySpan.end) fail("body.profilePreferenceSections.span");
    return spans;
  }
  while (index < arraySpan.end) {
    const start = index;
    const end = scanJsonValue(raw, start);
    spans.push({ start, end });
    index = skipJsonWhitespace(raw, end);
    if (raw[index] === "]") {
      if (index + 1 !== arraySpan.end) fail("body.profilePreferenceSections.span");
      return spans;
    }
    if (raw[index] !== ",") fail("body.profilePreferenceSections.delimiter");
    index = skipJsonWhitespace(raw, index + 1);
  }
  fail("body.profilePreferenceSections.span");
}

const sameJsonValue = (left: unknown, right: unknown): boolean =>
  JSON.stringify(left) === JSON.stringify(right);

const requireBoolean = (value: unknown, field: string): boolean => {
  if (typeof value !== "boolean") fail(field);
  return value;
};

const requireFiniteNumber = (
  value: unknown,
  field: string,
  predicate: (number: number) => boolean = () => true,
): number => {
  if (typeof value !== "number" || !Number.isFinite(value) || !predicate(value)) fail(field);
  return value;
};

const requireSafeInteger = (
  value: unknown,
  field: string,
  predicate: (number: number) => boolean = () => true,
): number => {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || !predicate(value)) fail(field);
  return value;
};

const requireNonBlank = (value: unknown, field: string): string => {
  if (typeof value !== "string" || value.trim().length === 0) fail(field);
  return value;
};

const requireEnum = <T extends string>(
  value: unknown,
  allowed: readonly T[],
  field: string,
): T => {
  if (typeof value !== "string" || !allowed.includes(value as T)) fail(field);
  return value as T;
};

const requireVersionOne = (value: unknown, field: string): 1 => {
  if (value !== 1) fail(field, "UNSUPPORTED_DOCUMENT_VERSION");
  return 1;
};

const requireIsoTimestamp = (value: unknown, field: string): string => {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) fail(field);
  return value;
};

const rejectLocalOnlyKeys = (value: unknown, field = "profilePreferenceSections"): void => {
  if (Array.isArray(value)) {
    value.forEach((child, index) => rejectLocalOnlyKeys(child, field + "[" + index + "]"));
    return;
  }
  if (typeof value !== "object" || value === null) return;
  for (const [key, child] of Object.entries(value as JsonRecord)) {
    if (LOCAL_ONLY_KEYS.has(normalizeKey(key))) fail(field + "." + key);
    rejectLocalOnlyKeys(child, field + "." + key);
  }
};

const RACK_ITEM_KEYS = [
  "id",
  "name",
  "category",
  "weightKg",
  "behavior",
  "enabled",
  "sortOrder",
  "createdAt",
  "updatedAt",
] as const;
const RACK_CATEGORIES = [
  "WEIGHTED_VEST",
  "DIP_BELT",
  "CHAINS",
  "BAND",
  "ASSISTANCE",
  "ATTACHMENT",
  "OTHER",
] as const;
const RACK_BEHAVIORS = [
  "ADDED_RESISTANCE",
  "COUNTERWEIGHT",
  "DISPLAY_ONLY",
] as const;
const WORKOUT_MODES = [0, 2, 3, 4, 6, 10] as const;
const REP_COUNT_TIMINGS = ["TOP", "BOTTOM"] as const;

function validateCorePayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(
    value,
    ["bodyWeightKg", "weightUnit", "weightIncrement"],
    "payload",
  );
  requireFiniteNumber(
    payload.bodyWeightKg,
    "payload.bodyWeightKg",
    (number) => number === 0 || (number >= 20 && number <= 300),
  );
  requireEnum(payload.weightUnit, ["KG", "LB"] as const, "payload.weightUnit");
  requireFiniteNumber(
    payload.weightIncrement,
    "payload.weightIncrement",
    (number) => number === -1 || number > 0,
  );
  return payload;
}

function validateRackPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(value, ["version", "items"], "payload");
  requireVersionOne(payload.version, "payload.version");
  const ids = new Set<string>();
  requireArray(payload.items, "payload.items").forEach((rawItem, index) => {
    const field = "payload.items[" + index + "]";
    const item = requireExactRecord(rawItem, RACK_ITEM_KEYS, field);
    const id = requireNonBlank(item.id, field + ".id");
    requireNonBlank(item.name, field + ".name");
    if (ids.has(id)) fail(field + ".id");
    ids.add(id);
    requireEnum(item.category, RACK_CATEGORIES, field + ".category");
    requireFiniteNumber(item.weightKg, field + ".weightKg", (number) => number >= 0);
    requireEnum(item.behavior, RACK_BEHAVIORS, field + ".behavior");
    requireBoolean(item.enabled, field + ".enabled");
    requireSafeInteger(item.sortOrder, field + ".sortOrder");
    requireSafeInteger(item.createdAt, field + ".createdAt");
    requireSafeInteger(item.updatedAt, field + ".updatedAt");
  });
  return payload;
}
```

```typescript
const JUST_LIFT_KEYS = [
  "workoutModeId",
  "weightPerCableKg",
  "weightChangePerRep",
  "eccentricLoadPercentage",
  "echoLevelValue",
  "stallDetectionEnabled",
  "repCountTimingName",
  "restSeconds",
] as const;

const SINGLE_EXERCISE_KEYS = [
  "exerciseId",
  "setReps",
  "weightPerCableKg",
  "setWeightsPerCableKg",
  "progressionKg",
  "setRestSeconds",
  "workoutModeId",
  "eccentricLoadPercentage",
  "echoLevelValue",
  "duration",
  "isAMRAP",
  "perSetRestTime",
  "defaultRackItemIds",
] as const;

const WORKOUT_KEYS = [
  "version",
  "stopAtTop",
  "beepsEnabled",
  "stallDetectionEnabled",
  "audioRepCountEnabled",
  "repCountTiming",
  "summaryCountdownSeconds",
  "autoStartCountdownSeconds",
  "gamificationEnabled",
  "autoStartRoutine",
  "countdownBeepsEnabled",
  "repSoundEnabled",
  "motionStartEnabled",
  "weightSuggestionsEnabled",
  "defaultRoutineExerciseUsePercentOfPR",
  "defaultRoutineExerciseWeightPercentOfPR",
  "voiceStopEnabled",
  "justLiftDefaults",
  "singleExerciseDefaults",
] as const;

function validateJustLiftDefaults(value: unknown, field: string): void {
  const defaults = requireExactRecord(value, JUST_LIFT_KEYS, field);
  requireSafeInteger(
    defaults.workoutModeId,
    field + ".workoutModeId",
    (number) => WORKOUT_MODES.includes(number as typeof WORKOUT_MODES[number]),
  );
  requireFiniteNumber(defaults.weightPerCableKg, field + ".weightPerCableKg", (number) => number >= 0);
  requireFiniteNumber(defaults.weightChangePerRep, field + ".weightChangePerRep");
  requireSafeInteger(
    defaults.eccentricLoadPercentage,
    field + ".eccentricLoadPercentage",
    (number) => number >= 0 && number <= 150,
  );
  requireSafeInteger(
    defaults.echoLevelValue,
    field + ".echoLevelValue",
    (number) => number >= 0 && number <= 3,
  );
  requireBoolean(defaults.stallDetectionEnabled, field + ".stallDetectionEnabled");
  requireEnum(defaults.repCountTimingName, REP_COUNT_TIMINGS, field + ".repCountTimingName");
  requireSafeInteger(
    defaults.restSeconds,
    field + ".restSeconds",
    (number) => number === 0 || (number >= 5 && number <= 300),
  );
}

function validateSingleExerciseDefaults(
  mapKey: string,
  value: unknown,
  field: string,
): void {
  const defaults = requireExactRecord(value, SINGLE_EXERCISE_KEYS, field);
  const exerciseId = requireNonBlank(defaults.exerciseId, field + ".exerciseId");
  if (mapKey.trim().length === 0 || exerciseId !== mapKey) fail(field + ".exerciseId");
  requireArray(defaults.setReps, field + ".setReps").forEach((rep, index) => {
    if (rep !== null) {
      requireSafeInteger(rep, field + ".setReps[" + index + "]", (number) => number >= 0);
    }
  });
  requireFiniteNumber(defaults.weightPerCableKg, field + ".weightPerCableKg", (number) => number >= 0);
  requireArray(defaults.setWeightsPerCableKg, field + ".setWeightsPerCableKg")
    .forEach((weight, index) => requireFiniteNumber(
      weight,
      field + ".setWeightsPerCableKg[" + index + "]",
      (number) => number >= 0,
    ));
  requireFiniteNumber(defaults.progressionKg, field + ".progressionKg");
  requireArray(defaults.setRestSeconds, field + ".setRestSeconds")
    .forEach((rest, index) => requireSafeInteger(
      rest,
      field + ".setRestSeconds[" + index + "]",
      (number) => number === 0 || (number >= 5 && number <= 300),
    ));
  requireSafeInteger(
    defaults.workoutModeId,
    field + ".workoutModeId",
    (number) => WORKOUT_MODES.includes(number as typeof WORKOUT_MODES[number]),
  );
  requireSafeInteger(
    defaults.eccentricLoadPercentage,
    field + ".eccentricLoadPercentage",
    (number) => number >= 0 && number <= 150,
  );
  requireSafeInteger(
    defaults.echoLevelValue,
    field + ".echoLevelValue",
    (number) => number >= 0 && number <= 3,
  );
  requireSafeInteger(defaults.duration, field + ".duration", (number) => number >= 0);
  requireBoolean(defaults.isAMRAP, field + ".isAMRAP");
  requireBoolean(defaults.perSetRestTime, field + ".perSetRestTime");
  const rackIds = requireArray(defaults.defaultRackItemIds, field + ".defaultRackItemIds")
    .map((rackId, index) => requireNonBlank(
      rackId,
      field + ".defaultRackItemIds[" + index + "]",
    ));
  if (new Set(rackIds).size !== rackIds.length) fail(field + ".defaultRackItemIds");
}

function validateWorkoutPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(value, WORKOUT_KEYS, "payload");
  requireVersionOne(payload.version, "payload.version");
  [
    "stopAtTop",
    "beepsEnabled",
    "stallDetectionEnabled",
    "audioRepCountEnabled",
    "gamificationEnabled",
    "autoStartRoutine",
    "countdownBeepsEnabled",
    "repSoundEnabled",
    "motionStartEnabled",
    "weightSuggestionsEnabled",
    "defaultRoutineExerciseUsePercentOfPR",
    "voiceStopEnabled",
  ].forEach((key) => requireBoolean(payload[key], "payload." + key));
  requireEnum(payload.repCountTiming, REP_COUNT_TIMINGS, "payload.repCountTiming");
  requireSafeInteger(
    payload.summaryCountdownSeconds,
    "payload.summaryCountdownSeconds",
    (number) => [-1, 0, 5, 10, 15, 20, 25, 30].includes(number),
  );
  requireSafeInteger(
    payload.autoStartCountdownSeconds,
    "payload.autoStartCountdownSeconds",
    (number) => number >= 2 && number <= 10,
  );
  requireSafeInteger(
    payload.defaultRoutineExerciseWeightPercentOfPR,
    "payload.defaultRoutineExerciseWeightPercentOfPR",
    (number) => number >= 50 && number <= 120,
  );
  validateJustLiftDefaults(payload.justLiftDefaults, "payload.justLiftDefaults");
  const singleExerciseDefaults = requireRecord(
    payload.singleExerciseDefaults,
    "payload.singleExerciseDefaults",
  );
  Object.entries(singleExerciseDefaults).forEach(([key, defaults]) =>
    validateSingleExerciseDefaults(
      key,
      defaults,
      "payload.singleExerciseDefaults." + key,
    )
  );
  return payload;
}

function validateLedPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(
    value,
    ["ledColorSchemeId", "preferences"],
    "payload",
  );
  requireSafeInteger(
    payload.ledColorSchemeId,
    "payload.ledColorSchemeId",
    (number) => number >= 0,
  );
  const preferences = requireExactRecord(
    payload.preferences,
    ["version", "discoModeUnlocked"],
    "payload.preferences",
  );
  requireVersionOne(preferences.version, "payload.preferences.version");
  requireBoolean(preferences.discoModeUnlocked, "payload.preferences.discoModeUnlocked");
  return payload;
}

function validateVbtPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(value, ["vbtEnabled", "preferences"], "payload");
  requireBoolean(payload.vbtEnabled, "payload.vbtEnabled");
  const preferences = requireExactRecord(
    payload.preferences,
    [
      "version",
      "velocityLossThresholdPercent",
      "autoEndOnVelocityLoss",
      "defaultScalingBasis",
      "verbalEncouragementEnabled",
      "vulgarModeEnabled",
      "vulgarTier",
      "dominatrixModeUnlocked",
      "dominatrixModeActive",
    ],
    "payload.preferences",
  );
  requireVersionOne(preferences.version, "payload.preferences.version");
  requireSafeInteger(
    preferences.velocityLossThresholdPercent,
    "payload.preferences.velocityLossThresholdPercent",
    (number) => number >= 10 && number <= 50,
  );
  requireBoolean(preferences.autoEndOnVelocityLoss, "payload.preferences.autoEndOnVelocityLoss");
  requireEnum(
    preferences.defaultScalingBasis,
    ["MAX_WEIGHT_PR", "MAX_VOLUME_PR", "ESTIMATED_1RM"] as const,
    "payload.preferences.defaultScalingBasis",
  );
  requireBoolean(
    preferences.verbalEncouragementEnabled,
    "payload.preferences.verbalEncouragementEnabled",
  );
  requireBoolean(preferences.vulgarModeEnabled, "payload.preferences.vulgarModeEnabled");
  requireEnum(
    preferences.vulgarTier,
    ["MILD", "STRONG", "MIX"] as const,
    "payload.preferences.vulgarTier",
  );
  requireBoolean(preferences.dominatrixModeUnlocked, "payload.preferences.dominatrixModeUnlocked");
  requireBoolean(preferences.dominatrixModeActive, "payload.preferences.dominatrixModeActive");
  return payload;
}
```

```typescript
function parsePreferenceMutation(value: unknown): PortalProfilePreferenceSectionMutation {
  rejectLocalOnlyKeys(value);
  const mutation = requireExactRecord(value, MUTATION_KEYS, "mutation");
  const localProfileId = requireNonBlank(mutation.localProfileId, "mutation.localProfileId");
  if (typeof mutation.section !== "string") fail("mutation.section", "UNSUPPORTED_SECTION");
  if (!["CORE", "RACK", "WORKOUT", "LED", "VBT"].includes(mutation.section)) {
    fail("mutation.section", "UNSUPPORTED_SECTION");
  }
  const section = mutation.section as ProfilePreferenceSection;
  const documentVersion = requireSafeInteger(mutation.documentVersion, "mutation.documentVersion");
  requireVersionOne(documentVersion, "mutation.documentVersion");
  const baseRevision = requireSafeInteger(
    mutation.baseRevision,
    "mutation.baseRevision",
    (number) => number >= 0,
  );
  const clientModifiedAt = requireIsoTimestamp(
    mutation.clientModifiedAt,
    "mutation.clientModifiedAt",
  );
  const payload = ({
    CORE: validateCorePayload,
    RACK: validateRackPayload,
    WORKOUT: validateWorkoutPayload,
    LED: validateLedPayload,
    VBT: validateVbtPayload,
  } as const)[section](mutation.payload);
  return {
    localProfileId,
    section,
    documentVersion,
    baseRevision,
    clientModifiedAt,
    payload,
  };
}

interface PreferenceEnvelope {
  present: boolean;
  validatedMutations: PortalProfilePreferenceSectionMutation[];
  rejections: ProfilePreferenceSectionRejection[];
}

interface PreferenceRawContext {
  rawBody: string;
  preferenceElementSpans: RawJsonSpan[];
}

const rawPreferenceIdentity = (value: unknown): string | null => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const record = value as JsonRecord;
  if (typeof record.localProfileId !== "string" || typeof record.section !== "string") {
    return null;
  }
  return JSON.stringify([record.localProfileId, record.section]);
};

const rawPreferenceLabel = (value: unknown): { localProfileId: string; section: string } => {
  const record =
    typeof value === "object" && value !== null && !Array.isArray(value)
      ? value as JsonRecord
      : {};
  return {
    localProfileId: typeof record.localProfileId === "string" ? record.localProfileId : "",
    section: typeof record.section === "string" ? record.section : "UNKNOWN",
  };
};

function parsePreferenceEnvelope(
  body: JsonRecord,
  rawContext: PreferenceRawContext,
): PreferenceEnvelope {
  requireKnownKeys(body, PUSH_BODY_KEYS, "body");
  if (!Object.hasOwn(body, "profilePreferenceSections")) {
    if (rawContext.preferenceElementSpans.length !== 0) {
      fail("body.profilePreferenceSections.span");
    }
    return { present: false, validatedMutations: [], rejections: [] };
  }
  const rawMutations = requireArray(
    body.profilePreferenceSections,
    "body.profilePreferenceSections",
  );
  if (rawMutations.length !== rawContext.preferenceElementSpans.length) {
    fail("body.profilePreferenceSections.span");
  }
  rawMutations.forEach((rawMutation, index) => {
    const span = rawContext.preferenceElementSpans[index];
    let reparsed: unknown;
    try {
      reparsed = JSON.parse(rawContext.rawBody.slice(span.start, span.end));
    } catch {
      fail("body.profilePreferenceSections.span");
    }
    if (!sameJsonValue(reparsed, rawMutation)) fail("body.profilePreferenceSections.span");
  });

  const identityCounts = new Map<string, number>();
  rawMutations.forEach((rawMutation) => {
    const identity = rawPreferenceIdentity(rawMutation);
    if (identity !== null) {
      identityCounts.set(identity, (identityCounts.get(identity) ?? 0) + 1);
    }
  });
  const duplicateIdentities = new Set(
    [...identityCounts.entries()]
      .filter(([, count]) => count > 1)
      .map(([identity]) => identity),
  );

  const validatedMutations: PortalProfilePreferenceSectionMutation[] = [];
  const rejections: ProfilePreferenceSectionRejection[] = [];
  const duplicateReported = new Set<string>();
  rawMutations.forEach((rawMutation, index) => {
    const label = rawPreferenceLabel(rawMutation);
    const identity = rawPreferenceIdentity(rawMutation);
    if (identity !== null && duplicateIdentities.has(identity)) {
      if (!duplicateReported.has(identity)) {
        duplicateReported.add(identity);
        rejections.push({
          ...label,
          serverRevision: 0,
          reason: "DUPLICATE_SECTION",
        });
      }
      return;
    }
    const span = rawContext.preferenceElementSpans[index];
    const rawPreferenceElementBytes = utf8Bytes(
      rawContext.rawBody.slice(span.start, span.end),
    );
    if (rawPreferenceElementBytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
      rejections.push({
        ...label,
        serverRevision: 0,
        reason: "SECTION_TOO_LARGE",
      });
      return;
    }
    try {
      const mutation = parsePreferenceMutation(rawMutation);
      validatedMutations.push(mutation);
    } catch (error) {
      if (!(error instanceof PreferenceValidationError)) throw error;
      rejections.push({
        ...label,
        serverRevision: 0,
        reason: error.reason,
      });
    }
  });
  return { present: true, validatedMutations, rejections };
}
```

Rack item IDs must be unique, but rack names may repeat. Signed safe-integer `createdAt` and `updatedAt` values are valid. If an existing local `Long` timestamp is outside JavaScript's safe-integer range, the mobile sync planner must emit a local diagnostic and omit that section until corrected; Edge must never round it. Duplicate `(localProfileId, section)` identities are pre-counted: emit exactly one `DUPLICATE_SECTION` result for the key and execute zero RPCs for every occurrence, while valid non-duplicated siblings continue.

## Push authentication and privileged boundary

Authenticate with the caller-scoped anon client. Parse and validate the complete request—including the final ordinary item and final preference item—before constructing the privileged client. `validateExistingMobileSyncPushBody` is the endpoint's existing strict, side-effect-free validator for every non-preference key in `PUSH_BODY_KEYS`.

```typescript
const authorization = req.headers.get("Authorization");
if (!authorization?.startsWith("Bearer ")) {
  return new Response(JSON.stringify({ error: "Missing bearer token" }), { status: 401 });
}
const userJwt = authorization.slice("Bearer ".length);
const authClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  global: { headers: { Authorization: authorization } },
  auth: { persistSession: false, autoRefreshToken: false },
});
const { data: userData, error: userError } = await authClient.auth.getUser(userJwt);
if (userError || !userData.user) {
  return new Response(JSON.stringify({ error: "Invalid bearer token" }), { status: 401 });
}
const verifiedUserId = userData.user.id;

const rawBody = await req.text();
const rawBodyBytes = new TextEncoder().encode(rawBody).byteLength;
if (rawBodyBytes > MAX_MOBILE_SYNC_REQUEST_BYTES) {
  return new Response(JSON.stringify({ error: "Request too large" }), { status: 413 });
}

let topLevelScan: TopLevelJsonScan;
try {
  topLevelScan = scanTopLevelJsonObject(rawBody);
  for (const duplicateKey of topLevelScan.duplicateKeys) {
    if (PUSH_BODY_KEYS.has(duplicateKey)) fail("body." + duplicateKey);
  }
} catch (error) {
  if (!(error instanceof PreferenceValidationError) && !(error instanceof SyntaxError)) {
    throw error;
  }
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}
const preferenceValueSpan = topLevelScan.valueSpans.get("profilePreferenceSections");
if (
  preferenceValueSpan !== undefined &&
  rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES
) {
  return new Response(JSON.stringify({ error: "Request too large" }), { status: 413 });
}

let body: JsonRecord;
let preferenceEnvelope: PreferenceEnvelope;
try {
  const preferenceElementSpans = preferenceValueSpan === undefined
    ? []
    : scanJsonArrayElementSpans(rawBody, preferenceValueSpan);
  const parsedBody = JSON.parse(rawBody) as unknown;
  body = requireRecord(parsedBody, "body");
  preferenceEnvelope = parsePreferenceEnvelope(body, {
    rawBody,
    preferenceElementSpans,
  });
  validateExistingMobileSyncPushBody(body);
} catch (error) {
  if (!(error instanceof PreferenceValidationError) && !(error instanceof SyntaxError)) {
    throw error;
  }
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}

const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});
```

`clientModifiedAt` is validated ISO audit metadata only. Never use it, `deviceId`, or a client-generated idempotency value for mutation ordering.

## RPC result parsing and push response

Only a successfully parsed single RPC row is a domain result. Empty/multiple/malformed rows, unknown reasons, permission failures, and PostgREST errors are infrastructure failures.

```typescript
interface RpcMutationRow {
  accepted: boolean;
  rejection_reason: string | null;
  server_revision: number | string;
  canonical_section: unknown | null;
}

const RPC_DOMAIN_REASONS = new Set([
  "REVISION_CONFLICT",
  "VALIDATION_FAILED",
  "UNSUPPORTED_SECTION",
  "UNSUPPORTED_DOCUMENT_VERSION",
  "UNKNOWN_PROFILE",
]);

const infrastructureRevision = (value: unknown): number => {
  const number = typeof value === "string" && /^[0-9]+$/.test(value)
    ? Number(value)
    : value;
  if (typeof number !== "number" || !Number.isSafeInteger(number) || number < 0) {
    throw new PreferenceInfrastructureError("malformed revision");
  }
  return number;
};

function parseInfrastructureCanonical(
  value: unknown,
  mutation: PortalProfilePreferenceSectionMutation,
): PortalProfilePreferenceSectionCanonical {
  try {
    const canonical = requireExactRecord(
      value,
      [
        "localProfileId",
        "section",
        "documentVersion",
        "serverRevision",
        "serverUpdatedAt",
        "payload",
      ],
      "canonical",
    );
    if (canonical.localProfileId !== mutation.localProfileId) fail("canonical.localProfileId");
    if (canonical.section !== mutation.section) fail("canonical.section");
    requireVersionOne(canonical.documentVersion, "canonical.documentVersion");
    const serverRevision = infrastructureRevision(canonical.serverRevision);
    const serverUpdatedAt = requireIsoTimestamp(
      canonical.serverUpdatedAt,
      "canonical.serverUpdatedAt",
    );
    const payload = ({
      CORE: validateCorePayload,
      RACK: validateRackPayload,
      WORKOUT: validateWorkoutPayload,
      LED: validateLedPayload,
      VBT: validateVbtPayload,
    } as const)[mutation.section](canonical.payload);
    return {
      localProfileId: mutation.localProfileId,
      section: mutation.section,
      documentVersion: 1,
      serverRevision,
      serverUpdatedAt,
      payload,
    };
  } catch (error) {
    if (error instanceof PreferenceInfrastructureError) throw error;
    throw new PreferenceInfrastructureError("malformed canonical");
  }
}

function parseRpcMutationRow(
  data: unknown,
  mutation: PortalProfilePreferenceSectionMutation,
): {
  accepted: boolean;
  rejectionReason: string | null;
  serverRevision: number;
  canonicalSection?: PortalProfilePreferenceSectionCanonical;
} {
  if (!Array.isArray(data) || data.length !== 1) {
    throw new PreferenceInfrastructureError("RPC row cardinality");
  }
  try {
    const row = requireExactRecord(
      data[0],
      ["accepted", "rejection_reason", "server_revision", "canonical_section"],
      "rpcRow",
    ) as unknown as RpcMutationRow;
    if (typeof row.accepted !== "boolean") fail("rpcRow.accepted");
    const serverRevision = infrastructureRevision(row.server_revision);
    const canonicalSection = row.canonical_section === null
      ? undefined
      : parseInfrastructureCanonical(row.canonical_section, mutation);
    if (row.accepted) {
      if (row.rejection_reason !== null || !canonicalSection) fail("rpcRow");
    } else {
      if (
        typeof row.rejection_reason !== "string" ||
        !RPC_DOMAIN_REASONS.has(row.rejection_reason)
      ) {
        fail("rpcRow.rejection_reason");
      }
      if (row.rejection_reason === "REVISION_CONFLICT" && !canonicalSection) {
        fail("rpcRow.canonical_section");
      }
    }
    if (canonicalSection && canonicalSection.serverRevision !== serverRevision) fail("rpcRow");
    return {
      accepted: row.accepted,
      rejectionReason: row.rejection_reason,
      serverRevision,
      canonicalSection,
    };
  } catch (error) {
    if (error instanceof PreferenceInfrastructureError) throw error;
    throw new PreferenceInfrastructureError("malformed RPC row");
  }
}

const canonicalProfilePreferenceSections: PortalProfilePreferenceSectionCanonical[] = [];
const profilePreferenceRejections: ProfilePreferenceSectionRejection[] = [
  ...preferenceEnvelope.rejections,
];

try {
  for (const mutation of preferenceEnvelope.validatedMutations) {
    const { data, error } = await admin.rpc("mutate_local_profile_preference_section", {
      p_user_id: verifiedUserId,
      p_local_profile_id: mutation.localProfileId,
      p_section: mutation.section,
      p_document_version: mutation.documentVersion,
      p_base_revision: mutation.baseRevision,
      p_payload: mutation.payload,
    });
    if (error) throw new PreferenceInfrastructureError("mutation RPC");
    const result = parseRpcMutationRow(data, mutation);
    if (result.accepted) {
      canonicalProfilePreferenceSections.push(result.canonicalSection!);
    } else {
      profilePreferenceRejections.push({
        localProfileId: mutation.localProfileId,
        section: mutation.section,
        serverRevision: result.serverRevision,
        reason: result.rejectionReason as ProfilePreferenceSectionRejection["reason"],
        ...(result.canonicalSection
          ? { canonicalSection: result.canonicalSection }
          : {}),
      });
    }
  }
} catch (error) {
  console.error("profile preference infrastructure failure", {
    name: error instanceof Error ? error.name : "UnknownError",
  });
  return new Response(JSON.stringify({ error: "Sync temporarily unavailable" }), {
    status: 503,
  });
}

const preferenceResponseAdditions = {
  ...(preferenceEnvelope.present ? { profilePreferencesAccepted: true } : {}),
  canonicalProfilePreferenceSections,
  profilePreferenceRejections,
};
```

Emit `profilePreferencesAccepted: true` only when the field was present, envelope-validated, and evaluated; omit it for legacy callers. Every accepted row adds its canonical object. Well-formed domain rejections include the reason and canonical object when supplied. Local validation rejections coexist with valid siblings. Merge these additions into the existing response without removing or changing `syncTime`.

Any unexpected RPC/query/permission/transport error aborts the response with a sanitized 5xx. Log only `{ name }`: never log a payload, JWT, service-role secret, profile id, PostgREST message, or raw error message. One RPC invocation owns one complete Postgres transaction, and the Edge function performs no network call while a database lock is held.

Same-section concurrent first writes must yield exactly one revision-1 acceptance and one canonical conflict. Different-section concurrent first writes for one profile both reach revision 1 and preserve their sibling document. Lost acknowledgement is normal convergence: if A commits and later sibling B fails, return 5xx and acknowledge neither. Retrying A at its old base revision returns `REVISION_CONFLICT` with the committed revision-1 canonical state without incrementing again; the mobile generation ledger applies it, and B retries normally.

Keep JWT verification enabled:

```toml
[functions.mobile-sync-push]
verify_jwt = true

[functions.mobile-sync-pull]
verify_jwt = true
```

## Pull contract

In `mobile-sync-pull/index.ts`, repeat bearer-token verification with `auth.getUser(userJwt)`, derive `verifiedUserId` only from that verified user, and construct the service-role client only after authentication. Query preferences only on the first page for a nonblank requested profile, apply both owner predicates, and map the typed columns/documents into the exact canonical wrappers returned by mutation.

```typescript
const canonical = (
  localProfileId: string,
  section: ProfilePreferenceSection,
  serverRevision: number,
  serverUpdatedAt: string,
  payload: JsonRecord,
): PortalProfilePreferenceSectionCanonical => ({
  localProfileId,
  section,
  documentVersion: 1,
  serverRevision,
  serverUpdatedAt,
  payload,
});

const canonicalTimestamp = (value: string): string => new Date(value).toISOString();

async function loadFirstPageProfilePreferences(
  cursor: string | null | undefined,
  requestedProfileId: string | null | undefined,
): Promise<PortalProfilePreferenceSectionCanonical[] | undefined> {
  if (cursor || !requestedProfileId || requestedProfileId.trim().length === 0) {
    return undefined;
  }
  const { data: preferenceRow, error: preferenceError } = await admin
    .from("local_profile_preferences")
    .select(
      "local_profile_id,body_weight_kg,weight_unit,weight_increment," +
        "core_revision,core_updated_at,equipment_rack,rack_revision,rack_updated_at," +
        "workout_preferences,workout_revision,workout_updated_at," +
        "led_color_scheme_id,led_preferences,led_revision,led_updated_at," +
        "vbt_enabled,vbt_preferences,vbt_revision,vbt_updated_at",
    )
    .eq("user_id", verifiedUserId)
    .eq("local_profile_id", requestedProfileId)
    .maybeSingle();
  if (preferenceError) throw new PreferenceInfrastructureError("preference pull");
  if (!preferenceRow) return undefined;

  try {
    const core = validateCorePayload({
      bodyWeightKg: preferenceRow.body_weight_kg,
      weightUnit: preferenceRow.weight_unit,
      weightIncrement: preferenceRow.weight_increment,
    });
    const rack = validateRackPayload(preferenceRow.equipment_rack);
    const workout = validateWorkoutPayload(preferenceRow.workout_preferences);
    const led = validateLedPayload({
      ledColorSchemeId: preferenceRow.led_color_scheme_id,
      preferences: preferenceRow.led_preferences,
    });
    const vbt = validateVbtPayload({
      vbtEnabled: preferenceRow.vbt_enabled,
      preferences: preferenceRow.vbt_preferences,
    });
    return [
      canonical(
        requestedProfileId,
        "CORE",
        infrastructureRevision(preferenceRow.core_revision),
        canonicalTimestamp(preferenceRow.core_updated_at),
        core,
      ),
      canonical(
        requestedProfileId,
        "RACK",
        infrastructureRevision(preferenceRow.rack_revision),
        canonicalTimestamp(preferenceRow.rack_updated_at),
        rack,
      ),
      canonical(
        requestedProfileId,
        "WORKOUT",
        infrastructureRevision(preferenceRow.workout_revision),
        canonicalTimestamp(preferenceRow.workout_updated_at),
        workout,
      ),
      canonical(
        requestedProfileId,
        "LED",
        infrastructureRevision(preferenceRow.led_revision),
        canonicalTimestamp(preferenceRow.led_updated_at),
        led,
      ),
      canonical(
        requestedProfileId,
        "VBT",
        infrastructureRevision(preferenceRow.vbt_revision),
        canonicalTimestamp(preferenceRow.vbt_updated_at),
        vbt,
      ),
    ];
  } catch (error) {
    if (error instanceof PreferenceInfrastructureError) throw error;
    throw new PreferenceInfrastructureError("malformed preference pull row");
  }
}

const profilePreferenceSections = await loadFirstPageProfilePreferences(
  cursor,
  requestedProfileId,
);
```

An absent row omits the field and never creates a row. Later pages omit the field while retaining normal pagination and `syncTime`. The containing pull handler catches `PreferenceInfrastructureError`, logs only its sanitized class name, and returns a generic 5xx.

## Required portal test manifest

```portal-test-manifest
database:exact-function-acls-and-no-client-dml
database:temporary-grant-owner-rls-and-cross-owner-user-id-protection
database:base-revision-accept-and-stale-canonical-conflict
edge:auth-and-cross-user-profile-rejection
edge:strict-five-section-validation-and-local-only-rejection
edge:section-262143-262144-262145-byte-boundaries
edge:envelope-524287-524288-524289-byte-boundaries
edge:unexpected-rpc-error-is-sanitized-5xx
edge:same-section-concurrent-first-write
edge:different-section-concurrent-first-write
edge:lost-ack-retry-canonical-convergence
edge:mutation-and-first-page-pull-canonical-equality
edge:later-pull-pages-omit-preferences-and-keep-sync-time
```

Implement every manifest entry as executable pgTAP or Deno coverage, not a string search:

- In `supabase/tests/database/profile_preferences.test.sql`, use pgTAP in a transaction. Assert both exact function signatures, `SECURITY INVOKER`, empty `search_path`, owner, volatility, return shapes, and ACLs. Assert `PUBLIC`, `anon`, and `authenticated` have no function execution or table DML while `service_role` has only the intended privileges.
- Temporarily grant table DML inside the rolled-back pgTAP transaction to test RLS. With authenticated JWT claims for owners A and B, prove CRUD is restricted to each composite parent and owner A cannot update `user_id` to owner B because `WITH CHECK` fails. Do not claim RLS makes a same-owner `local_profile_id` immutable; no trigger is part of this contract. Revoke the temporary grants and reassert production ACLs.
- Seed two composite profile keys and call the real mutation RPC for all five sections. Prove base 0 acceptance at revision 1, matching-base increment, stale-base canonical conflict, sibling preservation, key preservation, and explicit domain rejection rows.
- Push tests use injected anon/admin clients and two real local users. Missing/invalid JWT, cross-owner requests, and any body `userId` authority fail. Malformed final ordinary or preference items produce zero privileged calls.
- Table-drive required/unknown keys, primitive types, enum/range edges, nested objects, duplicate rack ID, duplicate section identity, all five wrappers, unsupported versions, and recursively normalized local-only names. Duplicate rack names and signed safe timestamps are accepted.
- Exercise shared UTF-8 goldens at 262143/262144/262145 bytes per raw section and 524287/524288/524289 bytes per complete request. Verify scanner offsets through whitespace, escapes, and nesting. A large ordinary-only request below 9,500,000 bytes must not inherit the preference request cap.
- Inject RPC error, null/empty/multiple rows, malformed canonical, mismatched revision, and unknown reason. Each produces a generic 5xx with sanitized logging and never fabricated `VALIDATION_FAILED`; a valid domain rejection still coexists with valid siblings.
- Use real RPC calls plus `Promise.all` for same-section and different-section first-write races, and exercise the lost-ack retry convergence path.
- Pull tests seed all five documents through mutation and deep-compare first-page canonical wrappers to mutation responses, including normalized timestamps. Verify both predicates, cross-owner isolation, no row creation on absence, later-page omission, and retained `syncTime`.

## Portal verification and handoff boundary

Run from the portal repository, with Supabase CLI 2.81.3 or newer for `db advisors`:

```bash
supabase --version
supabase start
supabase db reset --local
supabase migration list --local
supabase test db --local
deno test --allow-env --allow-net supabase/functions/mobile-sync-push
deno test --allow-env --allow-net supabase/functions/mobile-sync-pull
supabase db lint --local --fail-on warning
supabase db advisors --local
git status --short
git diff --check
git diff --name-only
git diff -- supabase/functions/mobile-sync-push supabase/functions/mobile-sync-pull supabase/functions/_shared/profile-preference-byte-goldens.json supabase/tests/database/profile_preferences.test.sql supabase/config.toml supabase/migrations docs/profile-preferences-advisor-dispositions.md
```

If the installed CLI lacks `db advisors`, use the equivalent Supabase Advisors through the project MCP integration or Dashboard before approval; do not skip it. Record each lint/advisor finding with id, severity, object, fix or evidence-backed disposition, command/source, and rerun result in `docs/profile-preferences-advisor-dispositions.md`.

The portal diff must contain only the named targets. Do not run `supabase db push`, deploy functions, apply a remote migration, create a remote commit, or otherwise mutate a remote Supabase project as part of this handoff.
