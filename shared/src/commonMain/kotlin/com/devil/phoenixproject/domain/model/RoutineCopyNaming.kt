package com.devil.phoenixproject.domain.model

private val COPY_SUFFIX = Regex(""" \(Copy( \d+)?\)$""")

/**
 * Name for a copy of a routine called [name]: "Push (Copy)", then "Push (Copy 2)", and so on,
 * one past the highest copy number among [existingNames]. A name that is already a copy
 * ("Push (Copy)") numbers against its base name, so copies never nest.
 */
fun routineCopyName(name: String, existingNames: Collection<String>): String {
    val baseName = name.replace(COPY_SUFFIX, "")
    val copyPattern = Regex("""^${Regex.escape(baseName)} \(Copy( (\d+))?\)$""")
    val existingCopyNumbers = existingNames.mapNotNull { existing ->
        when {
            existing == baseName -> 0
            existing == "$baseName (Copy)" -> 1
            else -> copyPattern.find(existing)?.groups?.get(2)?.value?.toIntOrNull()
        }
    }
    val nextCopyNumber = (existingCopyNumbers.maxOrNull() ?: 0) + 1
    return if (nextCopyNumber == 1) "$baseName (Copy)" else "$baseName (Copy $nextCopyNumber)"
}
