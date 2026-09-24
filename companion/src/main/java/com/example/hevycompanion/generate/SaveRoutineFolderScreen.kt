package com.example.hevycompanion.generate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hevycompanion.data.RoutineFolder

/**
 * Step 2 of the save flow — pick a folder for the generated routine.
 *
 * Always shows a first "My Routines (default)" entry that corresponds to
 * [folder_id: null] in the POST body. The Hevy app treats that as the
 * unfiled-routines bucket; every user has it, even if they have zero custom
 * folders. So the list is never empty and the UI never has to deal with
 * "no-folders-pick-one" weirdness.
 */
@Composable
fun SaveRoutineFolderScreen(
    folders: List<RoutineFolder>?,
    isLoading: Boolean,
    errorMessage: String?,
    onPick: (RoutineFolder?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(12.dp))
            Text("Save to folder", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Pick a folder to save this routine into.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isLoading && folders == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Loading folders…", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FolderRow(
                    title = "My Routines (default)",
                    subtitle = "Unfiled — no folder",
                    onClick = { onPick(null) },
                )
                HorizontalDivider()
            }
            items(folders.orEmpty()) { folder ->
                FolderRow(
                    title = folder.title,
                    subtitle = null,
                    onClick = { onPick(folder) },
                )
            }
        }
    }
}

@Composable
private fun FolderRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
