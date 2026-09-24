package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.Sex
import com.example.hevywatch.data.store.UserProfileStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Year

/**
 * Pins the demographic-store contract so a later HR-calorie formula can rely on
 * the values being bounded: birthYear within sanity range, sex defaulting to
 * UNSPECIFIED until the user actually picks, ordinal round-tripping via
 * Sex.fromOrdinal so a future enum reorder doesn't silently corrupt persisted
 * values (entries are appended only — see comment on the enum).
 */
@RunWith(RobolectricTestRunner::class)
class UserProfileStoreTest {

    private lateinit var store: UserProfileStore

    @Before
    fun setUp() {
        store = UserProfileStore(ApplicationProvider.getApplicationContext())
        // Reset to defaults so prior tests can't leak state into the current one.
        store.birthYear = UserProfileStore.DEFAULT_BIRTH_YEAR
        store.sex = UserProfileStore.DEFAULT_SEX
        store.heartRateEnabled = UserProfileStore.DEFAULT_HEART_RATE_ENABLED
    }

    @Test
    fun `defaults are exposed on a fresh store`() {
        val fresh = UserProfileStore(ApplicationProvider.getApplicationContext())
        assertEquals(UserProfileStore.DEFAULT_BIRTH_YEAR, fresh.birthYear)
        assertEquals(UserProfileStore.DEFAULT_SEX, fresh.sex)
        assertEquals(UserProfileStore.DEFAULT_HEART_RATE_ENABLED, fresh.heartRateEnabled)
    }

    @Test
    fun `heartRateEnabled round-trips and persists across new instances`() {
        store.heartRateEnabled = true
        assertEquals(true, store.heartRateEnabled)
        val store2 = UserProfileStore(ApplicationProvider.getApplicationContext())
        assertEquals(true, store2.heartRateEnabled)
        store2.heartRateEnabled = false
        assertEquals(false, store2.heartRateEnabled)
    }

    @Test
    fun `birth year below MIN clamps to MIN`() {
        store.birthYear = 1500
        assertEquals(UserProfileStore.MIN_BIRTH_YEAR, store.birthYear)
    }

    @Test
    fun `birth year above currentMaxBirthYear clamps to ceiling`() {
        // Ceiling is "this year minus MIN_AGE_YEARS" — anything beyond means
        // we'd be claiming the user is younger than the age floor.
        val ceiling = UserProfileStore.currentMaxBirthYear()
        store.birthYear = Year.now().value + 50
        assertEquals(ceiling, store.birthYear)
    }

    @Test
    fun `birth year inside range round-trips unchanged`() {
        store.birthYear = 1985
        assertEquals(1985, store.birthYear)
    }

    @Test
    fun `boundary birth years are accepted as-is`() {
        store.birthYear = UserProfileStore.MIN_BIRTH_YEAR
        assertEquals(UserProfileStore.MIN_BIRTH_YEAR, store.birthYear)
        val ceiling = UserProfileStore.currentMaxBirthYear()
        store.birthYear = ceiling
        assertEquals(ceiling, store.birthYear)
    }

    @Test
    fun `sex round-trips for each enum value`() {
        Sex.entries.forEach { value ->
            store.sex = value
            assertEquals(value, store.sex)
        }
    }

    @Test
    fun `values persist across new store instances`() {
        store.birthYear = 1978
        store.sex = Sex.FEMALE
        val store2 = UserProfileStore(ApplicationProvider.getApplicationContext())
        assertEquals(1978, store2.birthYear)
        assertEquals(Sex.FEMALE, store2.sex)
    }

    @Test
    fun `ageAt computes years since birth against a given reference year`() {
        store.birthYear = 1990
        assertEquals(36, store.ageAt(2026))
        assertEquals(0, store.ageAt(1990))
    }

    @Test
    fun `Sex_fromOrdinal returns UNSPECIFIED for an out-of-range ordinal`() {
        // If a future install holds an ordinal from a Sex enum that has since
        // shrunk (we wouldn't do that, but defensive), fromOrdinal must NOT
        // crash — it falls back to UNSPECIFIED.
        assertEquals(Sex.UNSPECIFIED, Sex.fromOrdinal(99))
        assertEquals(Sex.UNSPECIFIED, Sex.fromOrdinal(-1))
    }
}
