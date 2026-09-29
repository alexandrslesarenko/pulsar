package com.puls.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One heart rate measurement. ts - time in ms since the epoch (System.currentTimeMillis). */
@Entity(tableName = "hr")
data class HrSample(@PrimaryKey val ts: Long, val bpm: Int)

/**
 * Profile log record: starting at ts, profile was in effect with the range [lo, hi];
 * alarm = false - alarms were off. In effect until the next record.
 */
@Entity(tableName = "profile_log")
data class ProfileMark(@PrimaryKey val ts: Long, val profile: String, val lo: Int, val hi: Int, val alarm: Boolean)

/**
 * Motion over the interval [ts, ts + durMs): pedometer steps and average GPS speed, m/s
 * (null - GPS was off). Speed from steps is computed on display: it depends on height.
 */
@Entity(tableName = "motion")
data class MotionSample(@PrimaryKey val ts: Long, val durMs: Long, val steps: Int, val gpsMps: Float?)

data class Bucket(val t: Long, val lo: Int, val avg: Double, val hi: Int)

data class Stats(val lo: Int?, val avg: Double?, val hi: Int?, val n: Int)

/** Heart rate minute for ActivityProvider: minute start, average heart rate, sample count. */
data class MinuteHr(val minute: Long, val bpm: Double, val samples: Int)

@Dao
interface HrDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(samples: List<HrSample>)

    @Query(
        "SELECT (ts / :bucketMs) * :bucketMs AS t, MIN(bpm) AS lo, AVG(bpm) AS avg, MAX(bpm) AS hi " +
            "FROM hr WHERE ts >= :from AND ts < :to GROUP BY ts / :bucketMs ORDER BY t"
    )
    fun buckets(from: Long, to: Long, bucketMs: Long): Flow<List<Bucket>>

    @Query("SELECT MIN(bpm) AS lo, AVG(bpm) AS avg, MAX(bpm) AS hi, COUNT(*) AS n FROM hr WHERE ts >= :from AND ts < :to")
    fun stats(from: Long, to: Long): Flow<Stats>

    /** Inserts only new rows; returns -1 for ts that already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(samples: List<HrSample>): List<Long>

    @Query("SELECT COUNT(*) FROM hr")
    fun count(): Flow<Int>

    @Query("SELECT * FROM hr WHERE ts > :after AND ts < :before ORDER BY ts LIMIT :limit")
    suspend fun range(after: Long, before: Long, limit: Int): List<HrSample>

    /** Log records in effect in [from, to): the last one before from and all inside. */
    @Query(
        "SELECT * FROM profile_log WHERE ts >= (SELECT COALESCE(MAX(ts), 0) FROM profile_log WHERE ts <= :from) " +
            "AND ts < :to ORDER BY ts"
    )
    fun marks(from: Long, to: Long): Flow<List<ProfileMark>>

    /** Same as marks, but synchronous: for ActivityProvider, which runs on a binder thread. */
    @Query(
        "SELECT * FROM profile_log WHERE ts >= (SELECT COALESCE(MAX(ts), 0) FROM profile_log WHERE ts <= :from) " +
            "AND ts < :to ORDER BY ts"
    )
    fun marksNow(from: Long, to: Long): List<ProfileMark>

    @Query(
        "SELECT (ts / 60000) * 60000 AS minute, AVG(bpm) AS bpm, COUNT(*) AS samples " +
            "FROM hr WHERE ts >= :from AND ts < :to GROUP BY ts / 60000 ORDER BY minute"
    )
    fun minutesNow(from: Long, to: Long): List<MinuteHr>

    @Query("SELECT * FROM profile_log ORDER BY ts DESC LIMIT 1")
    suspend fun lastMark(): ProfileMark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMark(m: ProfileMark)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMotion(m: MotionSample)

    @Query("SELECT * FROM motion WHERE ts >= :from AND ts < :to ORDER BY ts")
    fun motion(from: Long, to: Long): Flow<List<MotionSample>>
}

@Database(entities = [HrSample::class, ProfileMark::class, MotionSample::class], version = 3, exportSchema = false)
abstract class HrDb : RoomDatabase() {
    abstract fun dao(): HrDao

    companion object {
        @Volatile private var instance: HrDb? = null

        fun get(context: Context): HrDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HrDb::class.java, "hr.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }

        /** v3: steps and GPS speed. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS motion (ts INTEGER NOT NULL, durMs INTEGER NOT NULL, " +
                        "steps INTEGER NOT NULL, gpsMps REAL, PRIMARY KEY(ts))"
                )
            }
        }

        /** v2: profile log. Heart rate history is left untouched. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profile_log (ts INTEGER NOT NULL, profile TEXT NOT NULL, " +
                        "lo INTEGER NOT NULL, hi INTEGER NOT NULL, alarm INTEGER NOT NULL, PRIMARY KEY(ts))"
                )
            }
        }
    }
}
