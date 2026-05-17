package com.crdp.app.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Vault stored as plaintext JSON. No key material, no auth gate. Explicit user
 * choice; we don't try to gate or pretend otherwise.
 */
internal class PlaintextVaultCrypto(
    private val target: File,
    private val tmp: File,
) : VaultCrypto {

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!target.exists()) null else target.readText(Charsets.UTF_8)
    }

    override suspend fun write(plaintext: String) = withContext(Dispatchers.IO) {
        if (tmp.exists()) tmp.delete()
        tmp.writeText(plaintext, Charsets.UTF_8)
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Unit
    }
}
