package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val type: String, // "INCOME" or "EXPENSE"
    val category: String, // e.g., "Food", "Salary", "Transportation", "Shopping", "Entertainment", "Utilities", "Other"
    val date: Long, // timestamp in millis
    val isBankImported: Boolean = false,
    val rawText: String? = null
)
