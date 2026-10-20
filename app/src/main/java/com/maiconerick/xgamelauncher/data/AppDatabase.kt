package com.maiconerick.xgamelauncher.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Dao
interface RomDao {
    @Query("SELECT * FROM rom WHERE platformId = :platformId ORDER BY name COLLATE NOCASE ASC")
    suspend fun byPlatform(platformId: String): List<RomRow>

    @Query("SELECT * FROM rom ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<RomRow>

    @Query("DELETE FROM rom WHERE platformId = :platformId")
    suspend fun clearPlatform(platformId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RomRow>)
}

@Dao
interface ArtDao {
    @Query("SELECT * FROM art WHERE gameId = :gameId")
    suspend fun get(gameId: String): ArtRow?

    @Query("SELECT * FROM art")
    suspend fun all(): List<ArtRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ArtRow)

    @Query("DELETE FROM art WHERE gameId = :gameId")
    suspend fun clear(gameId: String)
}

@Dao
interface PlatformMetaDao {
    @Query("SELECT * FROM platform_meta ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun all(): List<PlatformMetaRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PlatformMetaRow>)

    @Query("DELETE FROM platform_meta")
    suspend fun clear()
}

@Dao
interface AndroidGameDao {
    @Query("SELECT * FROM android_game ORDER BY label COLLATE NOCASE ASC")
    suspend fun all(): List<AndroidGameRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AndroidGameRow)

    @Query("DELETE FROM android_game WHERE pkg = :pkg")
    suspend fun delete(pkg: String)

    @Query("SELECT COUNT(*) FROM android_game")
    suspend fun count(): Int
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session ORDER BY startedUnix DESC")
    suspend fun all(): List<SessionRow>

    @Query("SELECT * FROM session WHERE isOpen = 1")
    suspend fun openSessions(): List<SessionRow>

    /** Re-emits whenever a session opens or closes, so the UI never shows a stale "live" state. */
    @Query("SELECT * FROM session WHERE isOpen = 1")
    fun openSessionsFlow(): kotlinx.coroutines.flow.Flow<List<SessionRow>>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: Long): SessionRow?

    @Insert
    suspend fun insert(row: SessionRow): Long

    @Update
    suspend fun update(row: SessionRow)

    @Query("DELETE FROM session WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>): Int

    /** Carry an app rename over to its playtime history (playtime is keyed by name). */
    @Query("UPDATE session SET gameName = :name WHERE pkg = :pkg AND platform = 'Android'")
    suspend fun renameAndroidApp(pkg: String, name: String)
}

@Dao
interface GameOverrideDao {
    @Query("SELECT * FROM game_override WHERE gameId = :gameId")
    suspend fun get(gameId: String): GameOverrideRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GameOverrideRow)

    @Query("DELETE FROM game_override WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}

@Database(
    entities = [
        RomRow::class,
        ArtRow::class,
        PlatformMetaRow::class,
        AndroidGameRow::class,
        GameOverrideRow::class,
        SessionRow::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun romDao(): RomDao
    abstract fun artDao(): ArtDao
    abstract fun platformMetaDao(): PlatformMetaDao
    abstract fun androidGameDao(): AndroidGameDao
    abstract fun gameOverrideDao(): GameOverrideDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "xgl.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
