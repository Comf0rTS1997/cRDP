package com.crdp.core.rdp.model

/**
 * How the credential vault file is protected at rest.
 *
 * - [None]: plaintext JSON in app files dir. No auth gate. Explicit user choice.
 * - [DeviceKey]: AES-256-GCM EncryptedFile wrapped by a Keystore master key that
 *   requires recent biometric/device-credential authentication. The on-disk
 *   cipher does NOT release without a successful prompt within the configured
 *   validity window — unlike the earlier "encrypt vault" toggle this is a real
 *   cryptographic gate, not just a UI screen.
 * - [Password]: AES-256-GCM with a key derived from a user-supplied passphrase
 *   via PBKDF2-HMAC-SHA256. The intended fallback for devices where the
 *   Keystore cannot host an auth-bound key (no screen lock, no enrolled
 *   biometric, or hardware that rejects auth-bound key creation).
 */
enum class VaultProtection { None, DeviceKey, Password }
