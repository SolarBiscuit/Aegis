package com.solarbiscuit.ratnetworking.mixin.encryption;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.ratnetworking.net.NativeSupport;
import com.solarbiscuit.ratnetworking.net.cipher.EncryptionKeyHolder;
import com.solarbiscuit.ratnetworking.net.cipher.RNCipherDecoder;
import com.solarbiscuit.ratnetworking.net.cipher.RNCipherEncoder;
import com.velocitypowered.natives.encryption.VelocityCipher;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

/**
 * Uses the (native where possible) in-place cipher instead of vanilla's copying one, on both server and client.
 * Krypton only did the server side and replaced vanilla's ciphers with null, so any failure left the connection
 * without encryption handling; here vanilla's ciphers are kept as the fallback.
 */
@Mixin(Connection.class)
public abstract class ConnectionEncryptionMixin implements EncryptionKeyHolder {
    @Unique
    private static final Logger ratnetworking$LOGGER = LogManager.getLogger("RatNetworking");

    @Shadow
    private Channel channel;

    @Shadow
    private boolean encrypted;

    @Unique
    private volatile SecretKey ratnetworking$pendingKey;

    @Override
    public void ratnetworking$setPendingKey(SecretKey key) {
        this.ratnetworking$pendingKey = key;
    }

    @Inject(method = "setEncryptionKey", at = @At("HEAD"), cancellable = true)
    private void ratnetworking$setupEncryption(Cipher decrypt, Cipher encrypt, CallbackInfo ci) {
        SecretKey key = this.ratnetworking$pendingKey;
        this.ratnetworking$pendingKey = null; // do not keep the secret around
        if (key == null || this.channel == null || !NativeSupport.nativeCipher()) {
            return;
        }
        VelocityCipher decryption = null;
        VelocityCipher encryption = null;
        try {
            decryption = NativeSupport.newCipher(false, key);
            encryption = NativeSupport.newCipher(true, key);
        } catch (Throwable t) {
            if (decryption != null) {
                decryption.close();
            }
            ratnetworking$LOGGER.warn("Falling back to vanilla encryption for {}: {}", this.channel.remoteAddress(), t.toString());
            return;
        }
        this.encrypted = true;
        this.channel.pipeline().addBefore("splitter", "decrypt", new RNCipherDecoder(decryption));
        this.channel.pipeline().addBefore("prepender", "encrypt", new RNCipherEncoder(encryption));
        ci.cancel();
    }
}
