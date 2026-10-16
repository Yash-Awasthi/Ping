package com.ping.app.di

import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.ping.app.service.BleL2capTransport
import com.ping.app.service.NearbyConnectionsTransport
import com.ping.app.service.NearbyTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the [NearbyTransport] used by [com.ping.app.service.NearbyExchangeService] and the room:
 * Google Nearby Connections when Play Services is present, otherwise [BleL2capTransport].
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideNearbyTransport(@ApplicationContext context: Context): NearbyTransport =
        // L2CAP channels need API 29, so older phones without Play Services have no transport.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasPlayServices(context)) {
            NearbyConnectionsTransport(context)
        } else {
            BleL2capTransport(context)
        }

    private fun hasPlayServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}
