package com.example.hevywatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme

/**
 * R5 — tappable page-indicator dots. Previously duplicated verbatim in
 * RoutineFolderListScreen and RoutineDetailScreen; consolidated here so any
 * future styling change (dot size, spacing, color) updates both pagers at
 * once.
 */
@Composable
fun PageIndicator(
    currentPage: Int,
    pageCount: Int,
    onPageTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        val dotColor = MaterialTheme.colors.onBackground
        repeat(pageCount) { index ->
            val isSelected = currentPage == index
            Box(
                modifier = Modifier
                    .size(if (isSelected) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) dotColor else dotColor.copy(alpha = 0.4f)
                    )
                    .clickable { if (!isSelected) onPageTap(index) }
            )
        }
    }
}
