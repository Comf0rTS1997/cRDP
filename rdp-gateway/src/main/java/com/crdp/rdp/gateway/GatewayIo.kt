package com.crdp.rdp.gateway

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GatewayIo

@Module
@InstallIn(SingletonComponent::class)
object GatewayDispatchersModule {
    @[Provides GatewayIo]
    fun provideIo(): CoroutineDispatcher = Dispatchers.IO
}
