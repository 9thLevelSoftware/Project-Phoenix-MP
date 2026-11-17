package com.devil.phoenixproject.presentation.garmin

/**
 * Frozen v1 wire contract for Garmin watch controls (issue #1391 Phase 1).
 *
 * Byte-for-byte mirror of the watch-side codec in `garmin/source/PhoenixProtocol.mc`
 * (Monkey C `PhoenixProtocol` module): identical message types, field names, enums and
 * limits. Monkey C `Dictionary` values arrive as `Map<String, Any?>` through the
 * platform transport ([com.devil.phoenixproject.data.garmin.GarminCompanionTransport]);
 * there is no REST anywhere.
 *
 * Contract (integration-model.md §4): keys are strings, numbers are bounded integers,
 * booleans are actual booleans, arrays are bounded, no floating point/null/arbitrary
 * objects. Optional fields are omitted, never null. Unknown, mistyped or duplicate keys
 * fail closed. Every message passes the bounded validation walk ([checkMessage]) before
 * it is built or after it is parsed, before any app-level allocation/dispatch.
 */
object GarminProtocol {
    const val PROTOCOL_VERSION = 1

    // ---- Message limits (wire contract, provisional application-level thresholds) ----
    const val MAX_MESSAGE_BYTES = 2048
    const val MAX_DICT_KEYS = 24
    const val MAX_CONTAINER_LEVELS = 2 // root dict + scalar array only
    const val MAX_ARRAY_ELEMENTS = 3
    const val MAX_ID_BYTES = 64 // ids / grants / tokens, ASCII only
    const val MAX_LABEL_BYTES = 80 // UTF-8 bytes
    const val MAX_ENUM_BYTES = 32 // enum strings, UTF-8 bytes

    // ---- Timing (phone monotonic clock is authoritative) ----
    const val GRANT_TTL_MS = 3000L
    const val CONFIRMATION_TTL_MS = 10000L
    const val RESULT_TIMEOUT_MS = 5000L

    // ---- Dedupe: fail-closed replay protection per epoch ----
    const val DEDUPE_CAPACITY = 256

    // ---- Message types ----
    const val TYPE_HELLO = "HELLO"
    const val TYPE_STATE = "STATE"
    const val TYPE_COMMAND = "COMMAND"
    const val TYPE_RESULT = "RESULT"

    // ---- Actions (exactly three) ----
    const val ACTION_START_NEXT = "START_NEXT"
    const val ACTION_STOP_SET = "STOP_SET"
    const val ACTION_CANCEL_EXERCISE = "CANCEL_EXERCISE"

    // ---- Result stages ----
    const val STAGE_ACCEPTED = "ACCEPTED"
    const val STAGE_APPLIED = "APPLIED"
    const val STAGE_REJECTED = "REJECTED"

    // ---- Rejection reasons ----
    const val REASON_STALE_STATE = "STALE_STATE"
    const val REASON_NOT_READY = "NOT_READY"
    const val REASON_DISCONNECTED = "DISCONNECTED"
    const val REASON_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val REASON_DISABLED = "DISABLED"
    const val REASON_BUSY = "BUSY"
    const val REASON_RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    const val REASON_SAVE_FAILED = "SAVE_FAILED"
    const val REASON_UNKNOWN_RESULT = "UNKNOWN_RESULT"
    const val REASON_INVALID_MESSAGE = "INVALID_MESSAGE"
    const val REASON_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val REASON_UNAUTHORIZED_DEVICE = "UNAUTHORIZED_DEVICE"
    const val REASON_COMMAND_ID_CONFLICT = "COMMAND_ID_CONFLICT"
    const val REASON_CONFIRMATION_EXPIRED = "CONFIRMATION_EXPIRED"
    const val REASON_EPOCH_CAP_REACHED = "EPOCH_CAP_REACHED"

    // ---- Readiness enums (wire strings) ----
    const val READY_DISABLED = "DISABLED"
    const val READY_UNAVAILABLE = "UNAVAILABLE"
    const val READY_SETUP = "SETUP"
    const val READY_CONNECTED = "CONNECTED"
    const val READY_ARMED_READY = "ARMED_READY"
    const val READY_ACTIVE = "ACTIVE"
    const val READY_RESTING = "RESTING"
    const val READY_SUMMARY = "SUMMARY"
    const val READY_PHONE_INPUT_REQUIRED = "PHONE_INPUT_REQUIRED"
    const val READY_BUSY = "BUSY"
    const val READY_ERROR = "ERROR"
    const val READY_RECOVERY_REQUIRED = "RECOVERY_REQUIRED"

    // =====================================================================
    //  Primitive validators (mirror of PhoenixProtocol.mc)
    // =====================================================================

    fun utf8Bytes(s: String): Int {
        var n = 0
        for (c in s) {
            val code = c.code
            n += when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                code < 0x10000 -> 3
                else -> 4
            }
        }
        return n
    }

    fun isAsciiString(s: String): Boolean = s.all { it.code <= 0x7F }

    /** ids / grants / tokens / commandIds: non-empty, <= 64 ASCII bytes. */
    fun isValidId(s: String?): Boolean {
        if (s == null || s.isEmpty()) return false
        return isAsciiString(s) && utf8Bytes(s) <= MAX_ID_BYTES
    }

    /** Human-readable labels (displayLabel, actionLabel, confirmationLabel). */
    fun isValidLabel(s: String?): Boolean = s != null && utf8Bytes(s) <= MAX_LABEL_BYTES

    /** Enum strings (actions, readiness, stages, reasons). */
    fun isValidEnum(s: String?): Boolean = s != null && utf8Bytes(s) <= MAX_ENUM_BYTES

    fun isAction(s: String?): Boolean =
        s == ACTION_START_NEXT || s == ACTION_STOP_SET || s == ACTION_CANCEL_EXERCISE

    fun isReadiness(s: String?): Boolean = s != null && s in setOf(
        READY_DISABLED, READY_UNAVAILABLE, READY_SETUP, READY_CONNECTED, READY_ARMED_READY,
        READY_ACTIVE, READY_RESTING, READY_SUMMARY, READY_PHONE_INPUT_REQUIRED, READY_BUSY,
        READY_ERROR, READY_RECOVERY_REQUIRED,
    )

    fun isStage(s: String?): Boolean =
        s == STAGE_ACCEPTED || s == STAGE_APPLIED || s == STAGE_REJECTED

    fun isReason(s: String?): Boolean = s != null && s in setOf(
        REASON_STALE_STATE, REASON_NOT_READY, REASON_DISCONNECTED, REASON_UNSUPPORTED_VERSION,
        REASON_DISABLED, REASON_BUSY, REASON_RECOVERY_REQUIRED, REASON_SAVE_FAILED,
        REASON_UNKNOWN_RESULT, REASON_INVALID_MESSAGE, REASON_PAYLOAD_TOO_LARGE,
        REASON_UNAUTHORIZED_DEVICE, REASON_COMMAND_ID_CONFLICT, REASON_CONFIRMATION_EXPIRED,
        REASON_EPOCH_CAP_REACHED,
    )

    // =====================================================================
    //  Message limit enforcement (bounded validation walk)
    // =====================================================================

    /**
     * Conservative upper bound of the encoded size of a value; never underestimates.
     * Mirrors `estimateBytes` in PhoenixProtocol.mc exactly so phone and watch agree
     * on what fits before anything is sent.
     */
    fun estimateBytes(value: Any?): Int = when (value) {
        is String -> utf8Bytes(value) + 2
        is Boolean -> 5
        is Byte, is Short, is Int, is Long -> 12
        is Double -> 26 // floats never enter the contract; treat as fat/invalid
        is List<*> -> {
            var total = 2
            for (e in value) total += estimateBytes(e) + 1
            total
        }
        is Map<*, *> -> {
            var total = 2
            for ((k, v) in value) total += estimateBytes(k) + estimateBytes(v) + 2
            total
        }
        // Long / Float / Double / Char / unknown: assume fat, fail if abused.
        else -> 26
    }

    /**
     * Container shape: root dictionary only; values are scalars or scalar arrays
     * (<= 3 elements). No nested dictionaries, no nested arrays.
     */
    fun hasValidContainers(msg: Map<String, Any?>): Boolean {
        if (msg.size > MAX_DICT_KEYS) return false
        for ((_, v) in msg) {
            if (v is List<*>) {
                if (v.size > MAX_ARRAY_ELEMENTS) return false
                for (e in v) {
                    if (e is List<*> || e is Map<*, *>) return false
                }
            } else if (v is Map<*, *>) {
                return false
            }
        }
        return true
    }

    /** The full pre-send/pre-dispatch gate: shape + size. Every builder and parser funnels through it. */
    fun checkMessage(msg: Map<String, Any?>): Boolean =
        hasValidContainers(msg) && estimateBytes(msg) <= MAX_MESSAGE_BYTES

    /** True only for bounded integral numbers (wire contract has no floats). */
    fun asBoundedInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Double -> {
            // Native SDK bridges may box integers as Double; accept only integral
            // values inside Int range. A real fractional float stays invalid.
            if (value.isFinite() && value == kotlin.math.floor(value) &&
                value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()
            ) value.toInt() else null
        }
        else -> null
    }

    private fun expectedKeys(
        required: Set<String>,
        optional: Set<String>,
        msg: Map<String, Any?>,
    ): Boolean = msg.keys.all { it in required || it in optional } && required.all { it in msg.keys }

    // =====================================================================
    //  Parsed message models
    // =====================================================================

    /** HELLO (watch -> phone). Handshake only, never Arm. */
    data class HelloMessage(
        val watchInstanceId: String,
        val supportedVersions: List<Int>,
    )

    /** COMMAND (watch -> phone). */
    data class CommandMessage(
        val controlEpoch: String,
        val commandId: String,
        val contextId: String,
        val expectedRevision: Int,
        val action: String,
        val actionGrant: String,
        val confirmationToken: String?,
    ) {
        /** Full payload binding for dedupe: identical duplicate returns cached outcome. */
        fun payloadFingerprint(): String =
            listOf(controlEpoch, commandId, contextId, expectedRevision.toString(), action, actionGrant, confirmationToken ?: "")
                .joinToString("|")
    }

    /** STATE (phone -> watch). */
    data class StateMessage(
        val controlEpoch: String,
        val revision: Int,
        val contextId: String,
        val allowedActions: List<String>,
        val readiness: String,
        val phoneForeground: Boolean,
        val foregroundRequired: Boolean,
        val backgroundStopSupported: Boolean,
        val displayLabel: String,
        val actionLabel: String,
        val actionGrant: String? = null,
        val grantTtlMs: Int? = null,
        val confirmationToken: String? = null,
        val confirmationTtlMs: Int? = null,
        val confirmationLabel: String? = null,
    )

    /** RESULT (phone -> watch). REJECTED requires a reason. */
    data class ResultMessage(
        val commandId: String,
        val controlEpoch: String,
        val contextId: String,
        val stage: String,
        val reason: String? = null,
    ) {
        init {
            require(isStage(stage)) { "invalid stage" }
            require(isValidId(commandId) && isValidId(controlEpoch) && isValidId(contextId)) { "invalid id" }
            if (stage == STAGE_REJECTED) require(reason != null && isReason(reason)) { "REJECTED requires a reason" }
        }
    }

    // =====================================================================
    //  Builders
    // =====================================================================

    /** HELLO = {v:1, type:"HELLO", watchInstanceId, supportedVersions:[1]} (watch -> phone; mirrored for tests). */
    fun buildHello(watchInstanceId: String): Map<String, Any?>? {
        if (!isValidId(watchInstanceId)) return null
        val msg = mapOf<String, Any?>(
            "v" to PROTOCOL_VERSION,
            "type" to TYPE_HELLO,
            "watchInstanceId" to watchInstanceId,
            "supportedVersions" to listOf(PROTOCOL_VERSION),
        )
        return if (checkMessage(msg)) msg else null
    }

    /** COMMAND (watch -> phone; mirrored for tests). Tokens ride ONLY on confirmed Cancel. */
    fun buildCommand(
        controlEpoch: String,
        commandId: String,
        contextId: String,
        expectedRevision: Int,
        action: String,
        actionGrant: String,
        confirmationToken: String?,
    ): Map<String, Any?>? {
        if (!isValidId(controlEpoch) || !isValidId(commandId) || !isValidId(contextId) || !isValidId(actionGrant)) return null
        if (expectedRevision < 0) return null
        if (!isValidEnum(action) || !isAction(action)) return null
        if (action == ACTION_CANCEL_EXERCISE) {
            if (!isValidId(confirmationToken)) return null
        } else if (confirmationToken != null) {
            return null
        }
        val msg = mutableMapOf<String, Any?>(
            "v" to PROTOCOL_VERSION,
            "type" to TYPE_COMMAND,
            "controlEpoch" to controlEpoch,
            "commandId" to commandId,
            "contextId" to contextId,
            "expectedRevision" to expectedRevision,
            "action" to action,
            "actionGrant" to actionGrant,
        )
        if (confirmationToken != null) msg["confirmationToken"] = confirmationToken
        return if (checkMessage(msg)) msg else null
    }

    /** STATE (phone -> watch). */
    fun buildState(st: StateMessage): Map<String, Any?>? {
        if (!isValidId(st.controlEpoch)) return null
        if (st.revision < 0) return null
        if (!isValidId(st.contextId)) return null
        if (st.allowedActions.size > MAX_ARRAY_ELEMENTS) return null
        if (st.allowedActions.any { !isValidEnum(it) || !isAction(it) }) return null
        if (!isValidEnum(st.readiness) || !isReadiness(st.readiness)) return null
        if (!isValidLabel(st.displayLabel) || !isValidLabel(st.actionLabel)) return null
        if (st.actionGrant != null && !isValidId(st.actionGrant)) return null
        if (st.confirmationToken != null && !isValidId(st.confirmationToken)) return null
        if (st.confirmationLabel != null && !isValidLabel(st.confirmationLabel)) return null
        if (st.grantTtlMs != null && st.grantTtlMs <= 0) return null
        if (st.confirmationTtlMs != null && st.confirmationTtlMs <= 0) return null
        val msg = mutableMapOf<String, Any?>(
            "v" to PROTOCOL_VERSION,
            "type" to TYPE_STATE,
            "controlEpoch" to st.controlEpoch,
            "revision" to st.revision,
            "contextId" to st.contextId,
            "allowedActions" to st.allowedActions,
            "readiness" to st.readiness,
            "phoneForeground" to st.phoneForeground,
            "foregroundRequired" to st.foregroundRequired,
            "backgroundStopSupported" to st.backgroundStopSupported,
            "displayLabel" to st.displayLabel,
            "actionLabel" to st.actionLabel,
        )
        st.actionGrant?.let { msg["actionGrant"] = it }
        st.grantTtlMs?.let { msg["grantTtlMs"] = it }
        st.confirmationToken?.let { msg["confirmationToken"] = it }
        st.confirmationTtlMs?.let { msg["confirmationTtlMs"] = it }
        st.confirmationLabel?.let { msg["confirmationLabel"] = it }
        return if (checkMessage(msg)) msg else null
    }

    /** RESULT (phone -> watch). */
    fun buildResult(res: ResultMessage): Map<String, Any?>? {
        if (!isValidEnum(res.stage) || !isStage(res.stage)) return null
        if (res.reason != null && (!isValidEnum(res.reason) || !isReason(res.reason))) return null
        if (res.stage == STAGE_REJECTED && res.reason == null) return null
        val msg = mutableMapOf<String, Any?>(
            "v" to PROTOCOL_VERSION,
            "type" to TYPE_RESULT,
            "commandId" to res.commandId,
            "controlEpoch" to res.controlEpoch,
            "contextId" to res.contextId,
            "stage" to res.stage,
        )
        if (res.reason != null) msg["reason"] = res.reason
        return if (checkMessage(msg)) msg else null
    }

    // =====================================================================
    //  Parsers (fail closed: bounded walk + exact key sets + typed fields)
    // =====================================================================

    private val HELLO_REQUIRED = setOf("v", "type", "watchInstanceId", "supportedVersions")
    private val COMMAND_REQUIRED = setOf("v", "type", "controlEpoch", "commandId", "contextId", "expectedRevision", "action", "actionGrant")
    private val COMMAND_OPTIONAL = setOf("confirmationToken")
    private val STATE_REQUIRED = setOf(
        "v", "type", "controlEpoch", "revision", "contextId", "allowedActions", "readiness",
        "phoneForeground", "foregroundRequired", "backgroundStopSupported", "displayLabel", "actionLabel",
    )
    private val STATE_OPTIONAL = setOf("actionGrant", "grantTtlMs", "confirmationToken", "confirmationTtlMs", "confirmationLabel")
    private val RESULT_REQUIRED = setOf("v", "type", "commandId", "controlEpoch", "contextId", "stage")
    private val RESULT_OPTIONAL = setOf("reason")

    private fun versionOk(msg: Map<String, Any?>): Boolean =
        asBoundedInt(msg["v"]) == PROTOCOL_VERSION

    fun parseHello(msg: Map<String, Any?>): HelloMessage? {
        if (!checkMessage(msg)) return null
        if (!expectedKeys(HELLO_REQUIRED, emptySet(), msg)) return null
        if (!versionOk(msg) || msg["type"] != TYPE_HELLO) return null
        val instanceId = msg["watchInstanceId"] as? String ?: return null
        if (!isValidId(instanceId)) return null
        val versions = msg["supportedVersions"] as? List<*> ?: return null
        val parsed = versions.map { asBoundedInt(it) ?: return null }
        if (parsed.isEmpty() || parsed.size > MAX_ARRAY_ELEMENTS) return null
        return HelloMessage(instanceId, parsed)
    }

    fun parseCommand(msg: Map<String, Any?>): CommandMessage? {
        if (!checkMessage(msg)) return null
        if (!expectedKeys(COMMAND_REQUIRED, COMMAND_OPTIONAL, msg)) return null
        if (!versionOk(msg) || msg["type"] != TYPE_COMMAND) return null
        val epoch = msg["controlEpoch"] as? String ?: return null
        val commandId = msg["commandId"] as? String ?: return null
        val contextId = msg["contextId"] as? String ?: return null
        if (!isValidId(epoch) || !isValidId(commandId) || !isValidId(contextId)) return null
        val revision = asBoundedInt(msg["expectedRevision"]) ?: return null
        if (revision < 0) return null
        val action = msg["action"] as? String ?: return null
        if (!isValidEnum(action) || !isAction(action)) return null
        val grant = msg["actionGrant"] as? String ?: return null
        if (!isValidId(grant)) return null
        val token = msg["confirmationToken"]
        if (action == ACTION_CANCEL_EXERCISE) {
            val t = token as? String ?: return null
            if (!isValidId(t)) return null
            return CommandMessage(epoch, commandId, contextId, revision, action, grant, t)
        }
        if (token != null) return null // tokens ride ONLY on confirmed Cancel
        return CommandMessage(epoch, commandId, contextId, revision, action, grant, null)
    }

    fun parseState(msg: Map<String, Any?>): StateMessage? {
        if (!checkMessage(msg)) return null
        if (!expectedKeys(STATE_REQUIRED, STATE_OPTIONAL, msg)) return null
        if (!versionOk(msg) || msg["type"] != TYPE_STATE) return null
        val epoch = msg["controlEpoch"] as? String ?: return null
        if (!isValidId(epoch)) return null
        val revision = asBoundedInt(msg["revision"]) ?: return null
        if (revision < 0) return null
        val contextId = msg["contextId"] as? String ?: return null
        if (!isValidId(contextId)) return null
        val allowed = msg["allowedActions"] as? List<*> ?: return null
        if (allowed.size > MAX_ARRAY_ELEMENTS) return null
        val actions = allowed.map { it as? String ?: return null }
        if (actions.any { !isValidEnum(it) || !isAction(it) }) return null
        val readiness = msg["readiness"] as? String ?: return null
        if (!isValidEnum(readiness) || !isReadiness(readiness)) return null
        val phoneForeground = msg["phoneForeground"] as? Boolean ?: return null
        val foregroundRequired = msg["foregroundRequired"] as? Boolean ?: return null
        val backgroundStopSupported = msg["backgroundStopSupported"] as? Boolean ?: return null
        val displayLabel = msg["displayLabel"] as? String ?: return null
        val actionLabel = msg["actionLabel"] as? String ?: return null
        if (!isValidLabel(displayLabel) || !isValidLabel(actionLabel)) return null
        val grant = msg["actionGrant"]?.let { it as? String ?: return null }
        if (grant != null && !isValidId(grant)) return null
        val grantTtl = msg["grantTtlMs"]?.let { asBoundedInt(it) ?: return null }
        if (grantTtl != null && grantTtl <= 0) return null
        val token = msg["confirmationToken"]?.let { it as? String ?: return null }
        if (token != null && !isValidId(token)) return null
        val confirmTtl = msg["confirmationTtlMs"]?.let { asBoundedInt(it) ?: return null }
        if (confirmTtl != null && confirmTtl <= 0) return null
        val confirmLabel = msg["confirmationLabel"]?.let { it as? String ?: return null }
        if (confirmLabel != null && !isValidLabel(confirmLabel)) return null
        return StateMessage(
            controlEpoch = epoch, revision = revision, contextId = contextId,
            allowedActions = actions, readiness = readiness,
            phoneForeground = phoneForeground, foregroundRequired = foregroundRequired,
            backgroundStopSupported = backgroundStopSupported,
            displayLabel = displayLabel, actionLabel = actionLabel,
            actionGrant = grant, grantTtlMs = grantTtl,
            confirmationToken = token, confirmationTtlMs = confirmTtl, confirmationLabel = confirmLabel,
        )
    }

    fun parseResult(msg: Map<String, Any?>): ResultMessage? {
        if (!checkMessage(msg)) return null
        if (!expectedKeys(RESULT_REQUIRED, RESULT_OPTIONAL, msg)) return null
        if (!versionOk(msg) || msg["type"] != TYPE_RESULT) return null
        val commandId = msg["commandId"] as? String ?: return null
        val epoch = msg["controlEpoch"] as? String ?: return null
        val contextId = msg["contextId"] as? String ?: return null
        if (!isValidId(commandId) || !isValidId(epoch) || !isValidId(contextId)) return null
        val stage = msg["stage"] as? String ?: return null
        if (!isValidEnum(stage) || !isStage(stage)) return null
        val reason = msg["reason"]?.let { it as? String ?: return null }
        if (reason != null && (!isValidEnum(reason) || !isReason(reason))) return null
        if (stage == STAGE_REJECTED && reason == null) return null
        return ResultMessage(commandId, epoch, contextId, stage, reason)
    }
}
