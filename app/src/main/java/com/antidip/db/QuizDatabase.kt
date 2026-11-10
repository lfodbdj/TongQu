package com.antidip.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [QuestionEntity::class], version = 2, exportSchema = true)
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

        fun getInstance(context: Context): QuizDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QuizDatabase::class.java,
                    "anti_addiction_quiz.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
