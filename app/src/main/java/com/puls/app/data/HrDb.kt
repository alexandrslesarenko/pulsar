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
import kotlinx.coroutines.flow.Flow

/** Одно измерение пульса. ts - время в мс от эпохи (System.currentTimeMillis). */
@Entity(tableName = "hr")
data class HrSample(@PrimaryKey val ts: Long, val bpm: Int)

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
}

@Database(entities = [HrSample::class], version = 1, exportSchema = false)
abstract class HrDb : RoomDatabase() {
    abstract fun dao(): HrDao

    companion object {
        @Volatile private var instance: HrDb? = null

        fun get(context: Context): HrDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HrDb::class.java, "hr.db")
                .build().also { instance = it }
        }
    }
}
