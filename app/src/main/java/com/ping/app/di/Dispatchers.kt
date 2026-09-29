package com.ping.app.di

import javax.inject.Qualifier

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IO

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class Default
