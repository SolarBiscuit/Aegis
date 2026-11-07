package com.solarbiscuit.aegis.mixin.encryption;

import java.security.Key;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.solarbiscuit.aegis.net.cipher.EncryptionKeyHolder;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Redirect(method = "handleHello", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Crypt;getCipher(ILjava/security/Key;)Ljavax/crypto/Cipher;"))
    private Cipher aegis$captureKey(int mode, Key key) throws CryptException {
        if (key instanceof SecretKey secret) {
            ((EncryptionKeyHolder) this.connection).aegis$setPendingKey(secret);
        }
        return Crypt.getCipher(mode, key);
    }
}
