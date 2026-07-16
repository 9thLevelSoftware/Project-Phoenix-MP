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

CREATE TABLE public.local_profile_preferences(
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

CREATE FUNCTION public.local_profile_preference_section_canonical(
    p_row public.local_profile_preferences,
    p_section text
) RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $canonical$
    SELECT jsonb_build_object(
        'localProfileId', p_row.local_profile_id,
        'section', p_section,
        'documentVersion', CASE p_section
            WHEN 'CORE' THEN 1
            WHEN 'RACK' THEN (p_row.equipment_rack ->> 'version')::integer
            WHEN 'WORKOUT' THEN (p_row.workout_preferences ->> 'version')::integer
            WHEN 'LED' THEN (p_row.led_preferences ->> 'version')::integer
            WHEN 'VBT' THEN (p_row.vbt_preferences ->> 'version')::integer
        END,
        'serverRevision', CASE p_section
            WHEN 'CORE' THEN p_row.core_revision
            WHEN 'RACK' THEN p_row.rack_revision
            WHEN 'WORKOUT' THEN p_row.workout_revision
            WHEN 'LED' THEN p_row.led_revision
            WHEN 'VBT' THEN p_row.vbt_revision
        END,
        'serverUpdatedAt', to_char(
            (CASE p_section
                WHEN 'CORE' THEN p_row.core_updated_at
                WHEN 'RACK' THEN p_row.rack_updated_at
                WHEN 'WORKOUT' THEN p_row.workout_updated_at
                WHEN 'LED' THEN p_row.led_updated_at
                WHEN 'VBT' THEN p_row.vbt_updated_at
            END) AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
        ),
        'payload', CASE p_section
            WHEN 'CORE' THEN jsonb_build_object(
                'bodyWeightKg', p_row.body_weight_kg,
                'weightUnit', p_row.weight_unit,
                'weightIncrement', p_row.weight_increment
            )
            WHEN 'RACK' THEN p_row.equipment_rack
            WHEN 'WORKOUT' THEN p_row.workout_preferences
            WHEN 'LED' THEN jsonb_build_object(
                'ledColorSchemeId', p_row.led_color_scheme_id,
                'preferences', p_row.led_preferences
            )
            WHEN 'VBT' THEN jsonb_build_object(
                'vbtEnabled', p_row.vbt_enabled,
                'preferences', p_row.vbt_preferences
            )
        END
    );
$canonical$;

CREATE FUNCTION public.mutate_local_profile_preference_section(
    p_user_id uuid,
    p_local_profile_id text,
    p_section text,
    p_document_version integer,
    p_base_revision bigint,
    p_payload jsonb
) RETURNS TABLE (
    accepted boolean,
    rejection_reason text,
    server_revision bigint,
    canonical_section jsonb
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $mutation$
DECLARE
    current_row public.local_profile_preferences%ROWTYPE;
    current_revision bigint;
BEGIN
    IF p_section IS NULL OR p_section NOT IN ('CORE', 'RACK', 'WORKOUT', 'LED', 'VBT') THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_SECTION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_document_version IS NULL OR p_document_version <> 1 THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_DOCUMENT_VERSION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_base_revision IS NULL OR p_base_revision < 0
       OR p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN
        RETURN QUERY SELECT false, 'VALIDATION_FAILED', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.local_profiles
         WHERE user_id = p_user_id AND id = p_local_profile_id
    ) THEN
        RETURN QUERY SELECT false, 'UNKNOWN_PROFILE', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;

    CASE p_section
        WHEN 'CORE' THEN
            UPDATE public.local_profile_preferences
               SET body_weight_kg = (p_payload ->> 'bodyWeightKg')::double precision,
                   weight_unit = p_payload ->> 'weightUnit',
                   weight_increment = (p_payload ->> 'weightIncrement')::double precision,
                   core_revision = core_revision + 1,
                   core_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND core_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'RACK' THEN
            UPDATE public.local_profile_preferences
               SET equipment_rack = p_payload,
                   rack_revision = rack_revision + 1,
                   rack_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND rack_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'WORKOUT' THEN
            UPDATE public.local_profile_preferences
               SET workout_preferences = p_payload,
                   workout_revision = workout_revision + 1,
                   workout_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND workout_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'LED' THEN
            UPDATE public.local_profile_preferences
               SET led_color_scheme_id = (p_payload ->> 'ledColorSchemeId')::integer,
                   led_preferences = p_payload -> 'preferences',
                   led_revision = led_revision + 1,
                   led_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND led_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'VBT' THEN
            UPDATE public.local_profile_preferences
               SET vbt_enabled = (p_payload ->> 'vbtEnabled')::boolean,
                   vbt_preferences = p_payload -> 'preferences',
                   vbt_revision = vbt_revision + 1,
                   vbt_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND vbt_revision = p_base_revision
            RETURNING * INTO current_row;
    END CASE;

    IF FOUND THEN
        canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
        server_revision := (canonical_section ->> 'serverRevision')::bigint;
        RETURN QUERY SELECT true, NULL::text, server_revision, canonical_section;
        RETURN;
    END IF;

    IF p_base_revision = 0 THEN
        INSERT INTO public.local_profile_preferences (
            user_id, local_profile_id,
            body_weight_kg, weight_unit, weight_increment, core_revision,
            equipment_rack, rack_revision,
            workout_preferences, workout_revision,
            led_color_scheme_id, led_preferences, led_revision,
            vbt_enabled, vbt_preferences, vbt_revision
        ) VALUES (
            p_user_id,
            p_local_profile_id,
            CASE WHEN p_section = 'CORE' THEN (p_payload ->> 'bodyWeightKg')::double precision ELSE 0 END,
            CASE WHEN p_section = 'CORE' THEN p_payload ->> 'weightUnit' ELSE 'LB' END,
            CASE WHEN p_section = 'CORE' THEN (p_payload ->> 'weightIncrement')::double precision ELSE -1 END,
            CASE WHEN p_section = 'CORE' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'RACK' THEN p_payload ELSE '{"version":1,"items":[]}'::jsonb END,
            CASE WHEN p_section = 'RACK' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'WORKOUT' THEN p_payload ELSE '{"version":1}'::jsonb END,
            CASE WHEN p_section = 'WORKOUT' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'LED' THEN (p_payload ->> 'ledColorSchemeId')::integer ELSE 0 END,
            CASE WHEN p_section = 'LED' THEN p_payload -> 'preferences' ELSE '{"version":1,"discoModeUnlocked":false}'::jsonb END,
            CASE WHEN p_section = 'LED' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'VBT' THEN (p_payload ->> 'vbtEnabled')::boolean ELSE true END,
            CASE WHEN p_section = 'VBT' THEN p_payload -> 'preferences' ELSE '{"version":1,"velocityLossThresholdPercent":20,"autoEndOnVelocityLoss":false,"defaultScalingBasis":"MAX_WEIGHT_PR","verbalEncouragementEnabled":false,"vulgarModeEnabled":false,"vulgarTier":"STRONG","dominatrixModeUnlocked":false,"dominatrixModeActive":false}'::jsonb END,
            CASE WHEN p_section = 'VBT' THEN 1 ELSE 0 END
        )
        ON CONFLICT (user_id, local_profile_id) DO NOTHING
        RETURNING * INTO current_row;

        IF FOUND THEN
            canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
            server_revision := (canonical_section ->> 'serverRevision')::bigint;
            RETURN QUERY SELECT true, NULL::text, server_revision, canonical_section;
            RETURN;
        END IF;

        CASE p_section
            WHEN 'CORE' THEN
                UPDATE public.local_profile_preferences
                   SET body_weight_kg = (p_payload ->> 'bodyWeightKg')::double precision,
                       weight_unit = p_payload ->> 'weightUnit',
                       weight_increment = (p_payload ->> 'weightIncrement')::double precision,
                       core_revision = 1,
                       core_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND core_revision = 0
                RETURNING * INTO current_row;
            WHEN 'RACK' THEN
                UPDATE public.local_profile_preferences
                   SET equipment_rack = p_payload, rack_revision = 1, rack_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND rack_revision = 0
                RETURNING * INTO current_row;
            WHEN 'WORKOUT' THEN
                UPDATE public.local_profile_preferences
                   SET workout_preferences = p_payload, workout_revision = 1, workout_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND workout_revision = 0
                RETURNING * INTO current_row;
            WHEN 'LED' THEN
                UPDATE public.local_profile_preferences
                   SET led_color_scheme_id = (p_payload ->> 'ledColorSchemeId')::integer,
                       led_preferences = p_payload -> 'preferences',
                       led_revision = 1,
                       led_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND led_revision = 0
                RETURNING * INTO current_row;
            WHEN 'VBT' THEN
                UPDATE public.local_profile_preferences
                   SET vbt_enabled = (p_payload ->> 'vbtEnabled')::boolean,
                       vbt_preferences = p_payload -> 'preferences',
                       vbt_revision = 1,
                       vbt_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND vbt_revision = 0
                RETURNING * INTO current_row;
        END CASE;

        IF FOUND THEN
            canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
            server_revision := (canonical_section ->> 'serverRevision')::bigint;
            RETURN QUERY SELECT true, NULL::text, server_revision, canonical_section;
            RETURN;
        END IF;
    END IF;

    SELECT * INTO current_row
      FROM public.local_profile_preferences
     WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'REVISION_CONFLICT', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;

    canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
    current_revision := (canonical_section ->> 'serverRevision')::bigint;
    RETURN QUERY SELECT false, 'REVISION_CONFLICT', current_revision, canonical_section;
END
$mutation$;

REVOKE ALL ON FUNCTION public.local_profile_preference_section_canonical(
    public.local_profile_preferences, text
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.mutate_local_profile_preference_section(
    uuid, text, text, integer, bigint, jsonb
) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.local_profile_preference_section_canonical(
    public.local_profile_preferences, text
) TO service_role;
GRANT EXECUTE ON FUNCTION public.mutate_local_profile_preference_section(
    uuid, text, text, integer, bigint, jsonb
) TO service_role;

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
