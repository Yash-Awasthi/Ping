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
 * Binds [NearbyConnectionsTransport] (Google Nearby Connections, needs Play Services)
 * as the [NearbyTransport] used by [com.ping.app.service.NearbyExchangeService].
 * Swapping in a Wi-Fi Direct transport later only means changing this one provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideNearbyTransport(@ApplicationContext context: Context): NearbyTransport =
        NearbyConnectionsTransport(context)
}
