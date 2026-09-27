package com.smartguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smartguard.data.local.dao.EmbeddingDao
import com.smartguard.data.local.dao.ProfileDao
import com.smartguard.data.local.entity.EmbeddingEntity
import com.smartguard.data.local.entity.ProfileEntity

@Database(
    entities = [ProfileEntity::class, EmbeddingEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 -> v2: per-profile restricted-apps list. Keeps existing profiles and face data. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN blockedPackagesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** v2 -> v3: mark face samples as enrolled or learned (profiles that grow with the child). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE embeddings ADD COLUMN source TEXT NOT NULL DEFAULT 'ENROLLED'")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smartguard_database.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
