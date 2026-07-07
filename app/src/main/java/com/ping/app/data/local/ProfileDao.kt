package com.ping.app.data.local

import androidx.room.*
import com.ping.app.model.Profile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profile WHERE id = :id LIMIT 1")
    fun observe(id: String = Profile.LOCAL_ID): Flow<Profile?>

    @Query("SELECT * FROM profile WHERE id = :id LIMIT 1")
    suspend fun get(id: String = Profile.LOCAL_ID): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: Profile)
}
