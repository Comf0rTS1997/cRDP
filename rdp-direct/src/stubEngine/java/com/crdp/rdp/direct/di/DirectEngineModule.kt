package com.crdp.rdp.direct.di

import com.crdp.core.rdp.engine.DirectEngine
import com.crdp.core.rdp.engine.RdpEngine
import com.crdp.rdp.direct.StubRdpEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Default binding for the @DirectEngine engine. Used when no real engine module
 * (e.g., :engine:afreerdp) is selected via -Pcrdp.engine=afreerdp.
 *
 * This source set is excluded from compilation when crdp.engine=afreerdp; the
 * afreerdp module provides the @DirectEngine binding in that configuration.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DirectEngineModule {

    @Binds
    @DirectEngine
    abstract fun bindEngine(impl: StubRdpEngine): RdpEngine
}
