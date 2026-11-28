package com.antidip.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [QuestionEntity::class], version = 3, exportSchema = true)
abstract class QuizDatabase : RoomDatabase() {

    abstract fun questionDao(): QuestionDao

    companion object {
        @Volatile
        private var INSTANCE: QuizDatabase? = null

        /**
         * Migration v1 -> v2 (task B14 + #R):
         *  - add the `consecutive_correct` column (default 0)
         *  - de-duplicate existing rows on (content, grade) keeping the lowest id,
         *    otherwise the UNIQUE index expected by the @Entity would fail to create
         *  - (re)create the UNIQUE index so Room's post-migration schema validation
         *    passes for users upgrading from v1
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE questions ADD COLUMN consecutive_correct INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "DELETE FROM questions WHERE id NOT IN (" +
                        "SELECT MIN(id) FROM questions GROUP BY content, grade)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_questions_content_grade " +
                        "ON questions (content, grade)"
                )
            }
        }

        /**
         * Migration v2 -> v3 (V2.5.1, 2026-11-28):
         *  - add the `answered_correct` column (default 0). Historical answers
         *    cannot be retro-marked (no per-question history), so every row
         *    starts at 0 and the "科目过半徽章 / 年级无所不知" progress builds
         *    from this version onward.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE questions ADD COLUMN answered_correct INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): QuizDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QuizDatabase::class.java,
                    "anti_addiction_quiz.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
