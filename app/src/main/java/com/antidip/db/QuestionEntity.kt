package com.antidip.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "questions",
    indices = [Index(value = ["content", "grade"], unique = true)]
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "subject")
    val subject: String,  // chinese, math, english, science, moral

    @ColumnInfo(name = "grade")
    val grade: Int,

    @ColumnInfo(name = "type")
    val type: String,  // choice, fill

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "options")
    val options: String?,  // JSON array string for choice questions, null for fill

    @ColumnInfo(name = "answer")
    val answer: String,

    @ColumnInfo(name = "difficulty")
    val difficulty: Int = 1,

    @ColumnInfo(name = "last_asked")
    val lastAsked: Long = 0,  // timestamp of last time this question was asked

    @ColumnInfo(name = "consecutive_correct")
    val consecutiveCorrect: Int = 0,  // consecutive correct answers (B14: 2 -> remove)

    /**
     * V2.5.1（用户要求 2026-11-28）：该题是否曾答对过（至少一次）。用于
     * 「科目过半徽章」与「年级无所不知」成就的判定；不随答错重置。
     */
    @ColumnInfo(name = "answered_correct")
    val answeredCorrect: Boolean = false
)
