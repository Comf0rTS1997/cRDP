package com.crdp.engine.afreerdp

import com.crdp.core.rdp.engine.DirectEngine
import com.crdp.core.rdp.engine.RdpEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Provides @DirectEngine RdpEngine = AFreeRdpEngine when this module ships in
 * the build (i.e., when -Pcrdp.engine=afreerdp). The conditional source-set in
 * :rdp-direct excludes its StubRdpEngine binding under the same flag, so Hilt
 * sees exactly one binding for @DirectEngine RdpEngine.
 *
 * AFreeRdpEngine is NOT scoped — DirectRdpSession needs a fresh engine per
 * session because freerdp_connect blocks for the connection's lifetime on a
 * dedicated worker thread.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AFreeRdpEngineModule {

    @Binds
    @DirectEngine
    abstract fun bindEngine(impl: AFreeRdpEngine): RdpEngine
}
