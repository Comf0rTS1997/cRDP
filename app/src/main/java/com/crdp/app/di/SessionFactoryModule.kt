package com.crdp.app.di

import com.crdp.app.session.SessionCoordinator
import com.crdp.core.rdp.RdpSessionFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionFactoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionFactory(impl: SessionCoordinator): RdpSessionFactory
}
