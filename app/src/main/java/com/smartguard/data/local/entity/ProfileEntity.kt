package com.smartguard.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val role: String, // CHILD, TEEN, ADULT
    val screenTimeBudgetMinutes: Int,
    /** Legacy whitelist (v1). Kept for the column; enforcement now uses [blockedPackagesJson]. */
    val allowedPackagesJson: String,
    val isOwner: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** JSON array of package names the parent restricted for this profile (v2). */
    @ColumnInfo(defaultValue = "[]")
    val blockedPackagesJson: String = "[]"
)
