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

/** Одно измерение пульса. ts - время в мс от эпохи (System.currentTimeMillis). */
@Entity(tableName = "hr")
data class HrSample(@PrimaryKey val ts: Long, val bpm: Int)

/**
 * Запись журнала профилей: с момента ts действовал профиль profile с коридором [lo, hi];
 * alarm = false - сигналы были выключены. Действует до следующей записи.
 */
@Entity(tableName = "profile_log")
data class ProfileMark(@PrimaryKey val ts: Long, val profile: String, val lo: Int, val hi: Int, val alarm: Boolean)

/**
 * Движение за интервал [ts, ts + durMs): шаги по шагомеру и средняя скорость GPS, м/с
 * (null - GPS не работал). Скорость по шагам считается при показе: зависит от роста.
 */
@Entity(tableName = "motion")
data class MotionSample(@PrimaryKey val ts: Long, val durMs: Long, val steps: Int, val gpsMps: Float?)

data class Bucket(val t: Long, val lo: Int, val avg: Double, val hi: Int)

data class Stats(val lo: Int?, val avg: Double?, val hi: Int?, val n: Int)

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

    /** Вставляет только новые строки; для уже существующих ts возвращает -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(samples: List<HrSample>): List<Long>

    @Query("SELECT COUNT(*) FROM hr")
    fun count(): Flow<Int>

    @Query("SELECT * FROM hr WHERE ts > :after AND ts < :before ORDER BY ts LIMIT :limit")
    suspend fun range(after: Long, before: Long, limit: Int): List<HrSample>

    /** Записи журнала, действовавшие в [from, to): последняя до from и все внутри. */
    @Query(
        "SELECT * FROM profile_log WHERE ts >= (SELECT COALESCE(MAX(ts), 0) FROM profile_log WHERE ts <= :from) " +
            "AND ts < :to ORDER BY ts"
    )
    fun marks(from: Long, to: Long): Flow<List<ProfileMark>>

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

        /** v3: шаги и скорость GPS. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS motion (ts INTEGER NOT NULL, durMs INTEGER NOT NULL, " +
                        "steps INTEGER NOT NULL, gpsMps REAL, PRIMARY KEY(ts))"
                )
            }
        }

        /** v2: журнал профилей. Историю пульса не трогаем. */
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
