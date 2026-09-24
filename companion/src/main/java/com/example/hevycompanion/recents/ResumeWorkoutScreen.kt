package com.example.hevycompanion.recents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * In-companion Resume — logs the remaining sets of an incomplete workout on the
 * phone and submits a merged `PUT /v1/workouts/{id}`. The companion counterpart
 * to finishing a resumed workout on the watch, minus the live timer and
 * biometrics.
 */
@Composable
fun ResumeWorkoutScreen(
    workoutId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ResumeWorkoutViewModel = viewModel(),
) {
    LaunchedEffect(workoutId) { vm.load(workoutId) }
    // Close automatically only when there is nothing to report. A submit that
    // succeeded on a fallback still has a reason the first attempt failed, and
    // closing silently would bury it.
    LaunchedEffect(vm.done, vm.submitNotice) { if (vm.done && vm.submitNotice == null) onDone() }

    vm.submitNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.dismissNotice() },
            title = { Text("Saved — with a problem") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { vm.dismissNotice() }) { Text("OK") } },
        )
    }

    if (vm.swapForIndex != null) {
        SwapPickerDialog(
            candidates = vm.swapCandidates,
            loading = vm.swapLoading,
            onPick = { vm.applySwap(it) },
            onDismiss = { vm.closeSwap() },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = "Resume — ${vm.workoutTitle}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { vm.submit() },
                enabled = !vm.submitting && !vm.isLoading && vm.error == null && vm.exercises.isNotEmpty(),
            ) {
                val p = vm.submitProgress
                Text(
                    when {
                        !vm.submitting -> "Submit"
                        p == null -> "Saving…"
                        p.phase == SubmitProgress.Phase.FALLBACK -> "Backup ${p.attempt}/${p.maxAttempts}"
                        else -> "Saving ${p.attempt}/${p.maxAttempts}"
                    }
                )
            }
        }
        HorizontalDivider()

        when {
            vm.isLoading -> CenteredBox { CircularProgressIndicator() }
            vm.error != null -> CenteredBox {
                Text(
                    text = vm.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> ResumeBody(vm)
        }
    }
}

@Composable
private fun ColumnScope.ResumeBody(vm: ResumeWorkoutViewModel) {
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Live submit progress: which path + attempt, and why the last attempt
        // failed (incl. HTTP code). Shown while submitting so a retrying/stalled
        // save is legible instead of an opaque "Saving…".
        vm.submitProgress?.let { p ->
            item {
                Column {
                    Text(
                        text = when (p.phase) {
                            SubmitProgress.Phase.FALLBACK -> "Backup save — attempt ${p.attempt}/${p.maxAttempts}"
                            SubmitProgress.Phase.PRIVATE -> "Saving — attempt ${p.attempt}/${p.maxAttempts}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    p.lastError?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        vm.submitError?.let { msg ->
            item {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(vm.exercises.size) { i ->
            val ex = vm.exercises[i]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ex.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (ex.swappable) {
                        TextButton(onClick = { vm.openSwap(i) }) { Text("Swap") }
                    }
                }
                ex.sets.forEach { set -> SetRow(set) }
                TextButton(
                    onClick = { vm.addSet(i) },
                    modifier = Modifier.padding(top = 2.dp),
                ) { Text("+ Add set") }
            }
        }
    }
}

/** Swap picker — substitutes ordered by recency of use (most recent first). */
@Composable
private fun SwapPickerDialog(
    candidates: List<SwapOption>,
    loading: Boolean,
    onPick: (SwapOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Swap exercise") },
        text = {
            when {
                loading && candidates.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                candidates.isEmpty() -> Text("No alternatives available.")
                else -> Column {
                    candidates.forEach { option ->
                        TextButton(
                            onClick = { onPick(option) },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = option.title,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SetRow(set: ResumeSetUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (set.type == "warmup") "Warmup" else "Set",
            style = MaterialTheme.typography.bodySmall,
            color = if (set.type == "warmup") MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        OutlinedTextField(
            value = set.weight,
            onValueChange = { set.weight = it },
            label = { Text("kg") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = set.reps,
            onValueChange = { set.reps = it },
            label = { Text("reps") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = set.logged, onCheckedChange = { set.logged = it })
    }
}

@Composable
private fun ColumnScope.CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        content()
    }
}
