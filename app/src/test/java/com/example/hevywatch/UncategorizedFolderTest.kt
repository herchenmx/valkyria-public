package com.example.hevywatch

import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.presentation.routine.RoutineFolderListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the synthetic "Uncategorized" folder behavior. Hevy's API never
 * returns this folder; we add it on the client when the user has at least
 * one folder-less routine. Without it those routines are silently
 * unreachable from the watch's folder list.
 */
class UncategorizedFolderTest {

    private fun routine(id: String, folderId: String?): Routine = Routine(
        id = id,
        title = id,
        notes = null,
        folderId = folderId,
        exercises = emptyList(),
        updatedAt = "1970-01-01T00:00:00Z",
    )

    private val realFolders = listOf(
        RoutineFolderResponse(id = "10", title = "Push", index = 0),
        RoutineFolderResponse(id = "11", title = "Pull", index = 1),
    )

    @Test
    fun `no synthetic folder when every routine has a folder id`() {
        val routines = listOf(routine("a", "10"), routine("b", "11"))
        val out = RoutineFolderListViewModel.foldersWithUncategorized(realFolders, routines)
        assertEquals(realFolders, out)
    }

    @Test
    fun `synthetic folder appended when at least one routine has no folder id`() {
        val routines = listOf(routine("a", "10"), routine("orphan", null))
        val out = RoutineFolderListViewModel.foldersWithUncategorized(realFolders, routines)

        assertEquals(3, out.size)
        val last = out.last()
        assertEquals(RoutineFolderListViewModel.UNCATEGORIZED_ID, last.id)
        assertEquals("Uncategorized", last.title)
        // index strictly greater than every real index, so it sorts last.
        assertTrue(last.index > realFolders.maxOf { it.index })
    }

    @Test
    fun `synthetic folder is added even when there are no real folders`() {
        val out = RoutineFolderListViewModel.foldersWithUncategorized(
            folders = emptyList(),
            routines = listOf(routine("orphan", null)),
        )
        assertEquals(1, out.size)
        assertEquals(RoutineFolderListViewModel.UNCATEGORIZED_ID, out.first().id)
    }

    // ── displayedFolders: foldersWithUncategorized + FOLDERS_DISPLAYED_LIMIT cap ──

    @Test
    fun `displayedFolders caps at the configured limit`() {
        // 8 folders, no orphans → cap at FOLDERS_DISPLAYED_LIMIT (= 5).
        val many = (0 until 8).map { i ->
            RoutineFolderResponse(id = "f$i", title = "F$i", index = i)
        }
        val routines = listOf(routine("a", "f0"))
        val out = RoutineFolderListViewModel.displayedFolders(many, routines)
        assertEquals(RoutineFolderListViewModel.FOLDERS_DISPLAYED_LIMIT, out.size)
        assertEquals(5, out.size)
        // The cap preserves the natural order (API index asc), so f0..f4.
        assertEquals(listOf("f0", "f1", "f2", "f3", "f4"), out.map { it.id })
    }

    @Test
    fun `displayedFolders with fewer than limit returns all of them`() {
        val three = (0 until 3).map { i ->
            RoutineFolderResponse(id = "f$i", title = "F$i", index = i)
        }
        val out = RoutineFolderListViewModel.displayedFolders(three, emptyList())
        assertEquals(3, out.size)
    }

    @Test
    fun `displayedFolders cap counts the synthetic Uncategorized too`() {
        // 5 real folders + an orphan triggers Uncategorized = 6 candidates.
        // The cap keeps the first 5, which means Uncategorized is hidden in
        // this configuration (it's always appended last). Acceptable
        // behavior: the user can find folderless routines via the source app.
        val five = (0 until 5).map { i ->
            RoutineFolderResponse(id = "f$i", title = "F$i", index = i)
        }
        val routines = listOf(routine("orphan", null))
        val out = RoutineFolderListViewModel.displayedFolders(five, routines)
        assertEquals(5, out.size)
        assertEquals(setOf("f0", "f1", "f2", "f3", "f4"), out.map { it.id }.toSet())
    }

    @Test
    fun `displayedFolders includes Uncategorized when room remains`() {
        // 4 real folders + orphan → Uncategorized fits within the cap.
        val four = (0 until 4).map { i ->
            RoutineFolderResponse(id = "f$i", title = "F$i", index = i)
        }
        val routines = listOf(routine("orphan", null))
        val out = RoutineFolderListViewModel.displayedFolders(four, routines)
        assertEquals(5, out.size)
        assertEquals(RoutineFolderListViewModel.UNCATEGORIZED_ID, out.last().id)
    }

    @Test
    fun `routine counts include the synthetic bucket only when needed`() {
        val routines = listOf(
            routine("a", "10"),
            routine("b", "10"),
            routine("c", "11"),
            routine("orphan1", null),
            routine("orphan2", null),
        )
        val counts = RoutineFolderListViewModel.routineCountsByFolder(routines)
        assertEquals(2, counts["10"])
        assertEquals(1, counts["11"])
        assertEquals(2, counts[RoutineFolderListViewModel.UNCATEGORIZED_ID])
    }

    @Test
    fun `routine counts omit the synthetic bucket when there are no orphans`() {
        val routines = listOf(routine("a", "10"))
        val counts = RoutineFolderListViewModel.routineCountsByFolder(routines)
        assertEquals(1, counts["10"])
        assertNull(counts[RoutineFolderListViewModel.UNCATEGORIZED_ID])
    }
}
