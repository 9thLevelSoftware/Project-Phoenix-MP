BEGIN;

DO $preflight$
DECLARE
    matching_key text[];
BEGIN
    IF to_regclass('public.local_profiles') IS NULL THEN
        RAISE EXCEPTION 'profile preferences preflight: public.local_profiles does not exist';
    END IF;

    SELECT array_agg(attribute.attname::text ORDER BY key_column.ordinality)
      INTO matching_key
      FROM pg_constraint constraint_row
      CROSS JOIN LATERAL unnest(constraint_row.conkey)
          WITH ORDINALITY AS key_column(attnum, ordinality)
      JOIN pg_attribute attribute
        ON attribute.attrelid = constraint_row.conrelid
       AND attribute.attnum = key_column.attnum
     WHERE constraint_row.conrelid = 'public.local_profiles'::regclass
       AND constraint_row.contype IN ('p', 'u')
     GROUP BY constraint_row.oid
    HAVING array_agg(attribute.attname::text ORDER BY key_column.ordinality)
           = ARRAY['user_id', 'id']::text[]
     LIMIT 1;

    IF matching_key IS NULL THEN
        RAISE EXCEPTION
            'profile preferences preflight: expected unique local_profiles(user_id, id) parent key';
    END IF;
END
$preflight$;

CREATE TABLE public.local_profile_preferences (
    user_id uuid NOT NULL,
    local_profile_id text NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version = 1),

    body_weight_kg double precision NOT NULL DEFAULT 0,
    weight_unit text NOT NULL DEFAULT 'LB',
    weight_increment double precision NOT NULL DEFAULT -1,
    core_revision bigint NOT NULL DEFAULT 0 CHECK (core_revision >= 0),
    core_updated_at timestamptz NOT NULL DEFAULT now(),

    equipment_rack jsonb NOT NULL DEFAULT '{"version":1,"items":[]}'::jsonb,
    rack_revision bigint NOT NULL DEFAULT 0 CHECK (rack_revision >= 0),
    rack_updated_at timestamptz NOT NULL DEFAULT now(),

    workout_preferences jsonb NOT NULL DEFAULT '{"version":1}'::jsonb,
    workout_revision bigint NOT NULL DEFAULT 0 CHECK (workout_revision >= 0),
    workout_updated_at timestamptz NOT NULL DEFAULT now(),

    led_color_scheme_id integer NOT NULL DEFAULT 0,
    led_preferences jsonb NOT NULL DEFAULT '{"version":1,"discoModeUnlocked":false}'::jsonb,
    led_revision bigint NOT NULL DEFAULT 0 CHECK (led_revision >= 0),
    led_updated_at timestamptz NOT NULL DEFAULT now(),

    vbt_enabled boolean NOT NULL DEFAULT true,
    vbt_preferences jsonb NOT NULL DEFAULT '{"version":1,"velocityLossThresholdPercent":20,"autoEndOnVelocityLoss":false,"defaultScalingBasis":"MAX_WEIGHT_PR","verbalEncouragementEnabled":false,"vulgarModeEnabled":false,"vulgarTier":"STRONG","dominatrixModeUnlocked":false,"dominatrixModeActive":false}'::jsonb,
    vbt_revision bigint NOT NULL DEFAULT 0 CHECK (vbt_revision >= 0),
    vbt_updated_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz GENERATED ALWAYS AS (
        greatest(core_updated_at, rack_updated_at, workout_updated_at, led_updated_at, vbt_updated_at)
    ) STORED,

    CONSTRAINT local_profile_preferences_pkey PRIMARY KEY (user_id, local_profile_id),
    CONSTRAINT local_profile_preferences_parent_fkey
        FOREIGN KEY (user_id, local_profile_id)
        REFERENCES public.local_profiles(user_id, id)
        ON DELETE CASCADE,
    CONSTRAINT local_profile_preferences_body_weight_check CHECK (
        body_weight_kg NOT IN ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        AND (body_weight_kg = 0 OR body_weight_kg BETWEEN 20 AND 300)
    ),
    CONSTRAINT local_profile_preferences_weight_unit_check CHECK (weight_unit IN ('KG', 'LB')),
    CONSTRAINT local_profile_preferences_weight_increment_check CHECK (
        weight_increment NOT IN ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        AND (weight_increment = -1 OR weight_increment > 0)
    ),
    CONSTRAINT local_profile_preferences_led_scheme_check CHECK (led_color_scheme_id >= 0),
    CONSTRAINT local_profile_preferences_rack_object_check CHECK (
        jsonb_typeof(equipment_rack) = 'object'
        AND equipment_rack @> '{"version":1}'::jsonb
        AND jsonb_typeof(equipment_rack -> 'items') = 'array'
        AND NOT jsonb_path_exists(equipment_rack, '$.items[*] ? (@.weightKg < 0)')
        AND octet_length(equipment_rack::text) <= 262144
    ),
    CONSTRAINT local_profile_preferences_workout_object_check CHECK (
        jsonb_typeof(workout_preferences) = 'object'
        AND workout_preferences @> '{"version":1}'::jsonb
        AND (
            NOT workout_preferences ? 'summaryCountdownSeconds'
            OR (
                jsonb_typeof(workout_preferences -> 'summaryCountdownSeconds') = 'number'
                AND (workout_preferences ->> 'summaryCountdownSeconds')::integer
                    IN (-1, 0, 5, 10, 15, 20, 25, 30)
            )
        )
        AND (
            NOT workout_preferences ? 'autoStartCountdownSeconds'
            OR (
                jsonb_typeof(workout_preferences -> 'autoStartCountdownSeconds') = 'number'
                AND (workout_preferences ->> 'autoStartCountdownSeconds')::integer BETWEEN 2 AND 10
            )
        )
        AND (
            NOT workout_preferences ? 'defaultRoutineExerciseWeightPercentOfPR'
            OR (
                jsonb_typeof(workout_preferences -> 'defaultRoutineExerciseWeightPercentOfPR') = 'number'
                AND (workout_preferences ->> 'defaultRoutineExerciseWeightPercentOfPR')::integer BETWEEN 50 AND 120
            )
        )
        AND octet_length(workout_preferences::text) <= 262144
    ),
    CONSTRAINT local_profile_preferences_led_object_check CHECK (
        jsonb_typeof(led_preferences) = 'object'
        AND led_preferences @> '{"version":1}'::jsonb
        AND jsonb_typeof(led_preferences -> 'discoModeUnlocked') = 'boolean'
        AND octet_length(led_preferences::text) <= 262144
    ),
    CONSTRAINT local_profile_preferences_vbt_object_check CHECK (
        jsonb_typeof(vbt_preferences) = 'object'
        AND vbt_preferences @> '{"version":1}'::jsonb
        AND jsonb_typeof(vbt_preferences -> 'velocityLossThresholdPercent') = 'number'
        AND (vbt_preferences ->> 'velocityLossThresholdPercent')::integer BETWEEN 10 AND 50
        AND octet_length(vbt_preferences::text) <= 262144
    )
);

ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;

CREATE POLICY local_profile_preferences_owner_select
    ON public.local_profile_preferences
    FOR SELECT TO authenticated
    USING ((select auth.uid()) = user_id);

CREATE POLICY local_profile_preferences_owner_insert
    ON public.local_profile_preferences
    FOR INSERT TO authenticated
    WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY local_profile_preferences_owner_update
    ON public.local_profile_preferences
    FOR UPDATE TO authenticated
    USING ((select auth.uid()) = user_id)
    WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY local_profile_preferences_owner_delete
    ON public.local_profile_preferences
    FOR DELETE TO authenticated
    USING ((select auth.uid()) = user_id);

REVOKE ALL ON TABLE public.local_profile_preferences FROM PUBLIC;
REVOKE ALL ON TABLE public.local_profile_preferences FROM anon;
REVOKE ALL ON TABLE public.local_profile_preferences FROM authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.local_profile_preferences TO service_role;

COMMIT;
