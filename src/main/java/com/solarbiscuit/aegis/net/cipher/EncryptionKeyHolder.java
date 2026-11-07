package com.solarbiscuit.aegis.net.cipher;

import javax.crypto.SecretKey;

/**
 * Implemented by Connection. The login code builds two javax Ciphers from the shared secret and hands only those to
 * the connection; the secret is captured on the way so the native cipher can be built from it.
 */
public interface EncryptionKeyHolder {
    void aegis$setPendingKey(SecretKey key);
}
