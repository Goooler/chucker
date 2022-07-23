package com.chuckerteam.chucker.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.chuckerteam.chucker.api.RetentionManager.Period
import com.chuckerteam.chucker.internal.data.repository.RepositoryProvider
import com.chuckerteam.chucker.internal.support.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Class responsible of holding the logic for the retention of your HTTP transactions.
 * You can customize how long data should be stored here.
 * @param context An Android Context
 * @param retentionPeriod A [Period] to specify the retention of data. Default 1 week.
 */
@Suppress("MagicNumber")
public class RetentionManager @JvmOverloads constructor(
    context: Context,
    retentionPeriod: Period = Period.ONE_WEEK
) {

    // The actual retention period in milliseconds (default to ONE_WEEK)
    private val period: Long = toMillis(retentionPeriod)

    // How often the cleanup should happen
    private val cleanupFrequency: Long
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, 0)
    }
    private val maintenanceMutex = Mutex()

    init {
        cleanupFrequency = if (retentionPeriod == Period.ONE_HOUR) {
            30.minutes.inWholeMilliseconds
        } else {
            2.hours.inWholeMilliseconds
        }
    }

    /**
     * Call this function to check and eventually trigger a cleanup.
     * Please note that this method is not forcing a cleanup.
     */
    internal suspend fun doMaintenance() = maintenanceMutex.withLock {
        if (period > 0) {
            val now = System.currentTimeMillis()
            if (isCleanupDue(now)) {
                Logger.info("Performing data retention maintenance...")
                deleteSince(getThreshold(now))
                updateLastCleanup(now)
            }
        }
    }

    private fun getLastCleanup(fallback: Long): Long {
        if (lastCleanup == 0L) {
            lastCleanup = prefs.getLong(KEY_LAST_CLEANUP, fallback)
        }
        return lastCleanup
    }

    private fun updateLastCleanup(time: Long) {
        lastCleanup = time
        prefs.edit { putLong(KEY_LAST_CLEANUP, time) }
    }

    private suspend fun deleteSince(threshold: Long) {
        RepositoryProvider.transaction().deleteOldTransactions(threshold)
    }

    private fun isCleanupDue(now: Long) = now - getLastCleanup(now) > cleanupFrequency

    private fun getThreshold(now: Long) = if (period == 0L) now else now - period

    private fun toMillis(period: Period): Long = when (period) {
        Period.ONE_HOUR -> 1.hours
        Period.ONE_DAY -> 1.days
        Period.ONE_WEEK -> 7.days
        Period.FOREVER -> Duration.ZERO
    }.inWholeMilliseconds

    public enum class Period {
        /** Retain data for the last hour. */
        ONE_HOUR,

        /** Retain data for the last day. */
        ONE_DAY,

        /** Retain data for the last week. */
        ONE_WEEK,

        /** Retain data forever. */
        FOREVER
    }

    private companion object {
        private const val PREFS_NAME = "chucker_preferences"
        private const val KEY_LAST_CLEANUP = "last_cleanup"
        private var lastCleanup: Long = 0
    }
}
