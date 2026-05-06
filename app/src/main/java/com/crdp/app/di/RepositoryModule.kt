package com.crdp.app.di

import com.crdp.app.data.ProfileRepositoryImpl
import com.crdp.app.data.SessionHistoryRepositoryImpl
import com.crdp.core.rdp.reachability.ReachabilityRepository
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.SessionHistoryRepository
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

    companion object {
        @Provides
        @Singleton
        fun provideReachabilityRepository(): ReachabilityRepository = ReachabilityRepository()
    }
}
