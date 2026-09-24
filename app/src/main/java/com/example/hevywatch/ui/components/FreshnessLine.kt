package com.example.hevywatch.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.hevywatch.util.FormatUtils
import kotlinx.coroutines.delay

/**
 * Caption shown directly below a Refresh chip, e.g. `"2 hrs ago"`. The
 * single source of truth on every screen for "how stale is what I'm looking
 * at" — replaces the ad-hoc `"From cache — pull to refresh"` /
 * `"↻ Syncing…"` / `"Updated …"` lines that used to be sprinkled through the
 * folder, routine, and detail screens.
 *
 * Accepts one or more `refreshedAtMs` stamps because some pages render data
 * from multiple endpoints that refresh independently (e.g. Folder List Page
 * 0 paints from both folders and routines). When the stamps disagree by
 * more than [DIVERGENCE_TOLERANCE_MS] the caption renders `various` so the
 * user isn't misled into thinking everything on the page is from the same
 * fetch. Zero stamps are treated as "never refreshed this session" and are
 * filtered out before the divergence check.
 *
 * Re-evaluates every 30s so a long-lingering screen ticks `1 min → 2 mins →
 * 3 mins …` without the user having to navigate away and back.
 */
@Composable
fun FreshnessLine(vararg refreshedAtMs: Long, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(refreshedAtMs.contentHashCode()) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val text = freshnessText(refreshedAtMs.toList(), nowMs)
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSecondary.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
    )
}

/** Pure helper, unit-testable from the JVM. */
internal fun freshnessText(refreshedAtMs: List<Long>, nowMs: Long): String {
    val nonZero = refreshedAtMs.filter { it != 0L }
    if (nonZero.isEmpty()) return "—"
    val oldest = nonZero.min()
    val newest = nonZero.max()
    return if ((newest - oldest) > DIVERGENCE_TOLERANCE_MS) {
        "various"
    } else {
        // When the sources agree the oldest stamp is the conservative
        // choice — the caption then reflects the staleness of whichever
        // source is most behind. With the 60s tolerance the choice rarely
        // changes the rendered string; this is the safer default.
        FormatUtils.relativeTimeAgo(oldest, nowMs)
    }
}

/** Two refresh stamps within this many ms of each other are treated as the
 *  same refresh for the purpose of the "various" check. */
internal const val DIVERGENCE_TOLERANCE_MS: Long = 60_000L
