package com.solarbiscuit.ratnetworking.net.cipher;

import java.util.List;

import com.solarbiscuit.ratnetworking.net.NativeSupport;
import com.velocitypowered.natives.encryption.VelocityCipher;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * Decrypts in place (Krypton/Velocity). Vanilla copies the data into a byte[], decrypts into a new heap buffer and
 * keeps both arrays alive per connection.
 */
public class RNCipherDecoder extends MessageToMessageDecoder<ByteBuf> {
    private final VelocityCipher cipher;

    public RNCipherDecoder(VelocityCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        ByteBuf compatible = NativeSupport.ensureCompatible(ctx.alloc(), this.cipher, in, true).slice();
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
