package com.example.hevywatch.tile

import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.degrees
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.expand
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.DimensionBuilders.wrap
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.LayoutElementBuilders.Arc
import androidx.wear.tiles.LayoutElementBuilders.ArcLine
import androidx.wear.tiles.LayoutElementBuilders.Box
import androidx.wear.tiles.LayoutElementBuilders.Column
import androidx.wear.tiles.LayoutElementBuilders.FontStyles
import androidx.wear.tiles.LayoutElementBuilders.Spacer
import androidx.wear.tiles.LayoutElementBuilders.Text
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.example.hevywatch.HevyApp
import com.example.hevywatch.MainActivity
import com.example.hevywatch.presentation.navigation.Screen
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Wear OS Tile for HevyWatch.
 *
 * Idle state (no active workout): shows routines from folder 2525049
 * as tappable rows, each launching RoutineDetailScreen.
 */
class HevyTileService : TileService() {

    companion object {
        /**
         * Default folder whose routines populate the idle tile when the user
         * hasn't configured one. Read by [com.example.hevywatch.data.store.TilePreferenceStore]
         * — historical value preserved so existing installs are unchanged.
         */
        const val TILE_FOLDER_ID = "2525049"
        private const val RESOURCES_VERSION = "1"

        // Color constants live in [TileColors] so the pure state computer and
        // unit tests share the exact values the renderer paints with.
        private val TRACK_GRAY  get() = TileColors.TRACK_GRAY
        private val RING_OUTER  get() = TileColors.RING_OUTER
        private val RING_INNER  get() = TileColors.RING_INNER
    }

    /**
     * Cached rendered layout, keyed by an input fingerprint. The Tiles
     * framework re-requests the layout on every swipe-to and on every
     * requestTileUpdate(); when underlying state hasn't changed, we hand back
     * the same LayoutElement tree without rebuilding it.
     *
     * The cache is per-service-instance (matches the Tiles framework's own
     * service lifecycle), and the fingerprint includes everything the layout
     * reads — so a stale render is impossible by construction.
     */
    private var cachedFingerprint: Any? = null
    private var cachedLayout: LayoutElementBuilders.LayoutElement? = null

    private data class IdleFingerprint(val routines: List<Pair<String, String>>)
    private data class ActiveFingerprint(val state: ActiveTileState)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val hevyApp = application as HevyApp

        val activeWorkout = hevyApp.activeWorkout
        val layout = if (activeWorkout != null) {
            // Bucket-E item 24 — read the memoized state off HevyApp instead
            // of recomputing on every swipe-to. HevyApp invalidates the cache
            // on any activeWorkout mutation, so a stale render is impossible.
            val state = hevyApp.activeTileStateOrCompute() ?: computeActiveTileState(activeWorkout)
            val fp = ActiveFingerprint(state)
            if (fp == cachedFingerprint) cachedLayout!! else buildActiveWorkoutLayout(state).also {
                cachedFingerprint = fp; cachedLayout = it
            }
        } else {
            val tileFolder = hevyApp.tilePreferenceStore.tileFolderId
            val routines = hevyApp.cachedRoutines
                .filter { it.folderId == tileFolder }
                .sortedBy { hevyApp.routineLastWorkoutAt[it.id] ?: "" }
            val fp = IdleFingerprint(routines.map { it.id to it.title })
            if (fp == cachedFingerprint) cachedLayout!! else buildIdleLayout(routines).also {
                cachedFingerprint = fp; cachedLayout = it
            }
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            // Re-render when the user swipes to the tile
            .setFreshnessIntervalMillis(0)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    // ── Idle state: routine list for folder 2525049 ──────────────────────────

    private fun buildIdleLayout(
        routines: List<com.example.hevywatch.data.model.Routine>
    ): LayoutElementBuilders.LayoutElement {
        // Caller is responsible for filtering + sorting; this method just
        // turns the prepared routine list into a LayoutElement so the cache
        // fingerprint above can match exactly what's rendered.

        val column = Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Title
        column.addContent(
            Text.Builder()
                .setText("Routines")
                .setFontStyle(
                    FontStyles.caption1(deviceParameters()).build()
                )
                .setMaxLines(1)
                .build()
        )

        column.addContent(
            Spacer.Builder().setHeight(dp(4f)).build()
        )

        if (routines.isEmpty()) {
            column.addContent(
                Text.Builder()
                    .setText("Open app to load")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(argb(0xFFAAAAAA.toInt()))
                            .build()
                    )
                    .setMaxLines(2)
                    .build()
            )
        } else {
            routines.forEach { routine ->
                column.addContent(buildRoutineRow(routine.id, routine.title))
                column.addContent(
                    Spacer.Builder().setHeight(dp(2f)).build()
                )
            }
        }

        return column.build()
    }

    private fun buildRoutineRow(
        routineId: String,
        title: String
    ): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("routine_$routineId")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(MainActivity::class.java.name)
                            .setPackageName(packageName)
                            .addKeyToExtraMapping(
                                "navigate_to",
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue("routine_detail/$routineId")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Text.Builder()
            .setText(title)
            .setFontStyle(
                FontStyles.body1(deviceParameters()).build()
            )
            .setMaxLines(1)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(dp(4f))
                            .setBottom(dp(4f))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    // ── Active workout state: Fit Goals-style rings ────────────────────────

    private fun buildActiveWorkoutLayout(state: ActiveTileState): LayoutElementBuilders.LayoutElement {
        // Pure state derivation lives in TileStateComputer; this builder just
        // paints the prepared state. The caller fingerprints `state` for the
        // cache, so identical inputs short-circuit to the prior layout.
        val outerDeg = state.outerProgressDeg
        val innerDeg = state.innerProgressDeg
        val betweenSets = state.betweenSets
        val bigText = state.bigText
        val bigColor = state.bigColor
        val bigSize = 36f
        val smallText = state.smallText
        val smallColor = state.smallColor

        // ── Build ring layout ──────────────────────────────────────────────
        val outerThickness = 8f
        val innerThickness = 6f
        val ringGap = 14f  // padding between outer and inner ring

        // Tap destination: LogSetScreen when between sets, LogWorkoutScreen between exercises
        val tapRoute = if (betweenSets) Screen.LOG_SET else Screen.LOG_WORKOUT
        val tileClickable = ModifiersBuilders.Clickable.Builder()
            .setId("active_workout_tap")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(MainActivity::class.java.name)
                            .setPackageName(packageName)
                            .addKeyToExtraMapping(
                                "navigate_to",
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue(tapRoute)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val root = Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(tileClickable)
                    .build()
            )
            // Outer ring: background track + exercise progress
            .addContent(buildArc(360f, outerThickness, TRACK_GRAY))
            .addContent(buildArc(outerDeg, outerThickness, RING_OUTER))

        // Inner ring: only shown when between sets (2b)
        if (betweenSets) {
            root.addContent(
                Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder()
                                    .setAll(dp(ringGap))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(buildArc(360f, innerThickness, TRACK_GRAY))
                    .addContent(buildArc(innerDeg, innerThickness, RING_INNER))
                    .build()
            )
        }

        // Center text
        root.addContent(
            Column.Builder()
                .setWidth(wrap())
                .setHeight(wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    Text.Builder()
                        .setText(bigText)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(bigSize))
                                .setColor(argb(bigColor))
                                .build()
                        )
                        .setMaxLines(1)
                        .build()
                )
                .addContent(
                    Text.Builder()
                        .setText(smallText)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(12f))
                                .setColor(argb(smallColor))
                                .build()
                        )
                        .setMaxLines(1)
                        .build()
                )
                .build()
        )

        return root.build()
    }

    /** Single arc segment starting at 12 o'clock (−90°). */
    private fun buildArc(
        sweepDegrees: Float,
        thickness: Float,
        color: Int
    ): LayoutElementBuilders.LayoutElement {
        if (sweepDegrees <= 0f) {
            // Empty arc — return a zero-size spacer so Box ignores it
            return Spacer.Builder().setHeight(dp(0f)).build()
        }
        return Arc.Builder()
            .setAnchorAngle(degrees(0f))  // 12 o'clock
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                ArcLine.Builder()
                    .setLength(degrees(sweepDegrees))
                    .setThickness(dp(thickness))
                    .setColor(argb(color))
                    .build()
            )
            .build()
    }

    /** Minimal device parameters for font style resolution. */
    private fun deviceParameters() =
        androidx.wear.tiles.DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(180)
            .setScreenHeightDp(180)
            .setScreenDensity(resources.displayMetrics.density)
            .build()
}
