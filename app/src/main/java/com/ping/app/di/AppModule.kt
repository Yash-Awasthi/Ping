package com.ping.app.di

import com.ping.app.data.ContactRepository
import com.ping.app.data.local.ContactDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt module providing application-wide singletons. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContactRepository(contactDao: ContactDao): ContactRepository {
        return ContactRepository(contactDao)
    }
}
