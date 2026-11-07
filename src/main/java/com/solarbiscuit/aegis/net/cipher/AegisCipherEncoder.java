package com.solarbiscuit.aegis.net.cipher;

import java.util.List;

import com.solarbiscuit.aegis.net.NativeSupport;
import com.velocitypowered.natives.encryption.VelocityCipher;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * Encrypts in place (Krypton/Velocity). Buffers that cannot be modified in place (read-only, wrong memory type) are
 * copied first.
 */
public class AegisCipherEncoder extends MessageToMessageEncoder<ByteBuf> {
    private final VelocityCipher cipher;

    public AegisCipherEncoder(VelocityCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        ByteBuf compatible = NativeSupport.ensureCompatible(ctx.alloc(), this.cipher, msg, true);
        try {
            this.cipher.process(compatible);
            out.add(compatible);
        } catch (Throwable t) {
            compatible.release();
            throw t;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.cipher.close();
    }
}
