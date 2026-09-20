package com.devil.phoenixproject.util

/**
 * Private, disk-backed staging used while importing a backup.
 *
 * A source may be a one-shot content URI, so the importer consumes it exactly once and
 * writes each top-level data section to a separate file. Those files are replayed only
 * after the entire source has parsed successfully.
 */
internal interface BackupImportStagingArea {
    fun beginArray(section: String)
    fun appendArrayValue(section: String, rawJson: String)
    fun endArray(section: String)
    fun writeValue(section: String, rawJson: String)
    fun openSection(section: String): BackupStreamSource?
    fun cleanup()

    /**
     * Records the replay outcome for a parent row without retaining an input-sized ID set
     * in memory. Marker files live inside this import's private staging directory and are
     * removed with the staged backup.
     */
    fun recordParentStatus(namespace: String, id: String, status: BackupParentStatus) {
        require(namespace.all { it.isLetterOrDigit() || it == '_' }) { "Invalid parent namespace" }
        var probe = 0
        while (true) {
            val section = parentStatusSection(namespace, id, probe)
            val existing = openSection(section)?.readMarker()
            if (existing == null) {
                writeValue(section, status.marker + "\n" + id)
                return
            }
            if (existing.second == id) {
                val merged = existing.first.merge(status)
                if (merged != existing.first) writeValue(section, merged.marker + "\n" + id)
                return
            }
            probe++
        }
    }

    fun parentStatus(namespace: String, id: String): BackupParentStatus? {
        require(namespace.all { it.isLetterOrDigit() || it == '_' }) { "Invalid parent namespace" }
        var probe = 0
        while (true) {
            val existing = openSection(parentStatusSection(namespace, id, probe))?.readMarker() ?: return null
            if (existing.second == id) return existing.first
            probe++
        }
    }
}

internal enum class BackupParentStatus(val marker: String) {
    INSERTED("I"),
    MATCHING("M"),
    UNAVAILABLE("U"),
    ;

    val isAvailable: Boolean get() = this != UNAVAILABLE

    /** A conflict is terminal; otherwise retain INSERTED so legacy child replay is safe. */
    fun merge(other: BackupParentStatus): BackupParentStatus = when {
        this == UNAVAILABLE || other == UNAVAILABLE -> UNAVAILABLE
        this == INSERTED || other == INSERTED -> INSERTED
        else -> MATCHING
    }

    companion object {
        fun fromMarker(value: String): BackupParentStatus = values().first { it.marker == value }
    }
}

private fun parentStatusSection(namespace: String, id: String, probe: Int): String =
    "restore_${namespace}_${stableMarkerHash(id)}_$probe"

private fun stableMarkerHash(value: String): String {
    var hash = -3750763034362895579L // FNV-1a 64 offset basis represented as signed Long.
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= 1099511628211L
    }
    return if (hash < 0) "n${-(hash + 1)}" else hash.toString()
}

private fun BackupStreamSource.readMarker(): Pair<BackupParentStatus, String>? {
    open()
    return try {
        val text = buildString {
            val buffer = CharArray(256)
            while (true) {
                val count = read(buffer, 0, buffer.size)
                if (count < 0) break
                for (index in 0 until count) append(buffer[index])
            }
        }
        val separator = text.indexOf('\n')
        if (separator <= 0) null else BackupParentStatus.fromMarker(text.substring(0, separator)) to
            text.substring(separator + 1)
    } finally {
        close()
    }
}

internal expect fun createBackupImportStagingArea(): BackupImportStagingArea

/** Forward-only concatenation of generated JSON fragments and staged section files. */
internal class StagedBackupReplaySource(
    private val staging: BackupImportStagingArea,
    private val version: Int,
    private val exportedAtJson: String,
    private val appVersionJson: String,
    private val privacyJson: String?,
    private val sections: List<String>,
) : BackupStreamSource {
    private val parts = mutableListOf<Part>()
    private var partIndex = 0

    override fun open() {
        close()
        parts += Part.Text(
            buildString {
                append("{\"version\":")
                append(version)
                append(",\"exportedAt\":")
                append(exportedAtJson)
                append(",\"appVersion\":")
                append(appVersionJson)
                privacyJson?.let {
                    append(",\"privacy\":")
                    append(it)
                }
                append(",\"data\":{")
            },
        )
        var first = true
        sections.forEach { section ->
            val source = staging.openSection(section) ?: return@forEach
            parts += Part.Text((if (first) "" else ",") + "\"$section\":")
            parts += Part.Source(source)
            first = false
        }
        parts += Part.Text("}}")
        partIndex = 0
        parts.filterIsInstance<Part.Source>().forEach { it.source.open() }
    }

    override fun close() {
        parts.filterIsInstance<Part.Source>().forEach { runCatching { it.source.close() } }
        parts.clear()
        partIndex = 0
    }

    override fun read(): Int {
        while (partIndex < parts.size) {
            val value = parts[partIndex].read()
            if (value >= 0) return value
            partIndex++
        }
        return -1
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var read = 0
        while (read < length) {
            val value = read()
            if (value < 0) break
            buffer[offset + read] = value.toChar()
            read++
        }
        return if (read == 0) -1 else read
    }

    private sealed interface Part {
        fun read(): Int

        class Text(private val value: String) : Part {
            private var index = 0
            override fun read(): Int = if (index < value.length) value[index++].code else -1
        }

        class Source(val source: BackupStreamSource) : Part {
            override fun read(): Int = source.read()
        }
    }
}
