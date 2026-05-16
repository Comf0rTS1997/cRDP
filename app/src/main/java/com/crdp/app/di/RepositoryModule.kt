package com.crdp.app.di

import com.crdp.app.data.ProfileRepositoryImpl
import com.crdp.app.data.SessionHistoryRepositoryImpl
import com.crdp.app.data.VaultRepositoryImpl
import com.crdp.core.rdp.reachability.ReachabilityRepository
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.SessionHistoryRepository
import com.crdp.core.rdp.repository.VaultRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindSessionHistoryRepository(impl: SessionHistoryRepositoryImpl): SessionHistoryRepository

    @Binds
    @Singleton
    abstract fun bindVaultRepository(impl: VaultRepositoryImpl): VaultRepository

    companion object {
        @Provides
        @Singleton
        fun provideReachabilityRepository(): ReachabilityRepository = ReachabilityRepository()
    }
}
