package com.example.hevycompanion.overview

import com.example.hevycompanion.generate.EquipmentMap
import com.example.hevycompanion.muscle.MuscleAssetMap

/**
 * Renders the Strength Overview as RFC-4180 CSV. Pure (no Android deps) so the
 * escaping is unit-testable on the JVM. Columns mirror the on-screen table:
 *
 *     Muscle Group,Exercise,Equipment,Highest KG,Est 1RM KG,Date
 *
 * Rows follow the same grouping/sort as the screen (muscle group, then
 * equipment, then title) via [groupByMuscle], so the export reads top-to-bottom
 * identically to what the user sees. `Est 1RM KG` is empty when the history
 * lacked usable weight+reps — see [ExerciseMax.epley1rm] for the caveat on
 * reading that column.
 */
object ExerciseMaxCsv {

    private const val HEADER = "Muscle Group,Exercise,Equipment,Highest KG,Est 1RM KG,Date"

    fun toCsv(rows: List<ExerciseMaxRow>, sort: OverviewSort = OverviewSort.NAME_ASC): String {
        val sb = StringBuilder().append(HEADER).append("\r\n")
        for (section in groupByMuscle(rows, sort)) {
            val muscle = MuscleAssetMap.displayName(section.muscleGroup)
            for (row in section.rows) {
                sb.append(escape(muscle)).append(',')
                    .append(escape(row.title)).append(',')
                    .append(escape(EquipmentMap.displayLabel(row.equipment))).append(',')
                    .append(escape(OverviewFormat.kg(row.highestKg))).append(',')
                    .append(escape(row.estimated1rmKg?.let { OverviewFormat.kg(it) } ?: "")).append(',')
                    .append(escape(OverviewFormat.date(row.workoutStartTime)))
                    .append("\r\n")
            }
        }
        return sb.toString()
    }

    /** Quote a field if it contains a comma, quote, CR or LF; double any
     *  embedded quotes. */
    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
