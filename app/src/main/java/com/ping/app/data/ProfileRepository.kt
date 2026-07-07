package com.ping.app.data

import com.ping.app.data.local.ProfileDao
import com.ping.app.model.Profile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Repository for the user's single card. */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    /** Flow of the user's card (null until first saved). */
    val profile: Flow<Profile?> = profileDao.observe()

    suspend fun get(): Profile? = profileDao.get()

    /** Insert or replace the card, stamping the update time. */
    suspend fun save(profile: Profile) =
        profileDao.upsert(profile.copy(id = Profile.LOCAL_ID, updatedAt = System.currentTimeMillis()))

    suspend fun getOrCreate(): Profile =
        profileDao.get() ?: Profile().also { profileDao.upsert(it) }
}
