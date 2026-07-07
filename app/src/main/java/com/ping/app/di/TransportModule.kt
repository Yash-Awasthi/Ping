package com.ping.app.di

import android.content.Context
import com.ping.app.service.NearbyConnectionsTransport
import com.ping.app.service.NearbyTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the `gms` product flavor.
 *
 * Provides [NearbyConnectionsTransport] as [NearbyTransport] so that
 * [com.ping.app.service.NearbyExchangeService] uses Google Nearby
 * Connections on Play Services devices.
 *
 * The `foss` flavor's [TransportModule] provides [WifiDirectTransport] instead,
 * removing the GMS dependency entirely (F-Droid eligible).
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideNearbyTransport(@ApplicationContext context: Context): NearbyTransport =
        NearbyConnectionsTransport(context)
}
