package com.crdp.core.rdp

import com.crdp.core.rdp.model.ConnectionProfile

fun interface RdpSessionFactory {
    fun portFor(profile: ConnectionProfile): RdpSessionPort
}
