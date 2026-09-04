package com.minesafear.data.converter

import androidx.room.TypeConverter

/**
 * Room only stores primitives, so the one collection column in the schema
 * ([com.minesafear.data.entity.TrainingModuleEntity.requiredForRoles]) is kept as
 * a newline-delimited string. Newline is safe here because job-role names never
 * contain one, and it avoids pulling in a JSON dependency this early.
 */
class Converters {

    @TypeConverter
    fun fromStringList(values: List<String>?): String? = values?.joinToString(SEPARATOR)

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.split(SEPARATOR)?.filter { it.isNotBlank() }

    private companion object {
        const val SEPARATOR = "\n"
    }
}
