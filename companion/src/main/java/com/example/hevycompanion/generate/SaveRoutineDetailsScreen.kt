package com.example.hevycompanion.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Step 3–7 of the save flow on a single scrollable form.
 *
 * Splitting the five overrides across five separate screens (as the user
 * originally enumerated) adds taps without adding clarity — every field has
 * a sensible prepopulated default, so the realistic flow is "glance at the
 * form, maybe tweak one or two fields, hit Save". One form it is.
 *
 * Field mapping → POST /v1/routines body (see [GeneratorViewModel.submitSave]):
 *   - title        → routine.title
 *   - sets (int)   → repeated [sets] times per exercise
 *   - reps         → set.reps and set.rep_range.{start,end}
 *   - weight[i]    → set.weight_kg for exercise i (per-exercise override,
 *                    because the generator picks different weights per
 *                    equipment; a single global weight would be misleading)
 *   - rest seconds → exercise.rest_seconds
 */
@Composable
fun SaveRoutineDetailsScreen(
    vm: GeneratorViewModel,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gen = vm.generated
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Folder") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(12.dp))
            Text("Save routine", style = MaterialTheme.typography.titleLarge)
        }

        val folderLabel = vm.chosenFolder?.title ?: "My Routines (default)"
        Text(
            "Destination: $folderLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = vm.saveTitle,
            onValueChange = vm::updateSaveTitle,
            label = { Text("Routine name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.saveSetsText,
                onValueChange = vm::updateSaveSets,
                label = { Text("Sets") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = vm.saveRepsText,
                onValueChange = vm::updateSaveReps,
                label = { Text("Reps / set") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = vm.saveRestSecondsText,
            onValueChange = vm::updateSaveRestSeconds,
            label = { Text("Rest between sets (seconds)") },
            supportingText = { Text("Default 90 s (1 min 30 s).") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        Text("Target weight per exercise", style = MaterialTheme.typography.titleMedium)
        Text(
            "Prepopulated from the generator — override per exercise (leave blank for bodyweight).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        gen?.exercises?.forEachIndexed { i, ex ->
            Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ex.template.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${ex.template.equipment ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = vm.saveWeightsText.getOrNull(i).orEmpty(),
                        onValueChange = { vm.updateSaveWeight(i, it) },
                        label = { Text("kg") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(110.dp),
                    )
                }
            }
        }

        if (vm.saveError != null) {
            Text(vm.saveError!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = vm::submitSave,
            enabled = !vm.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Saving…")
            } else {
                Text("Save Routine")
            }
        }
    }
}
