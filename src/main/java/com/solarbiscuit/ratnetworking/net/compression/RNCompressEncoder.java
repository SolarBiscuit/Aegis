package com.solarbiscuit.ratnetworking.net.compression;

import com.solarbiscuit.ratnetworking.net.NativeSupport;
import com.solarbiscuit.ratnetworking.net.VarInts;
import com.velocitypowered.natives.compression.VelocityCompressor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Compression (Krypton/Velocity design): compresses straight from the packet buffer into the output buffer. Vanilla
 * copies every compressed packet into a fresh byte[] of its full size first, then deflates through an 8 KiB array,
 * and its Deflater (~256 KiB of native memory) is only freed when the garbage collector finds the handler.
 */
public class RNCompressEncoder extends MessageToByteEncoder<ByteBuf> {
    private final SharedCompressor compressor;
    private int threshold;

    public RNCompressEncoder(SharedCompressor compressor, int threshold) {
        this.compressor = compressor;
        this.threshold = threshold;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        int length = msg.readableBytes();
        if (length < this.threshold) {
            out.writeByte(0);
            out.writeBytes(msg);
            return;
        }
        VarInts.write(out, length);
        VelocityCompressor c = this.compressor.get();
        ByteBuf source = NativeSupport.ensureCompatible(ctx.alloc(), c, msg, false);
        try {
            c.deflate(source, out);
        } finally {
            source.release();
        }
    }

    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect) {
        int length = msg.readableBytes();
        if (length < this.threshold) {
            return ctx.alloc().ioBuffer(length + 1);
        }
        // Room for the size prefix and deflate's worst case on incompressible data, so libdeflate does not have to
        // retry with a doubled buffer.
        int initial = length + (length >>> 12) + 32;
        return NativeSupport.preferredBuffer(ctx.alloc(), this.compressor.get(), initial, Integer.MAX_VALUE);
    }

    public int getThreshold() {
        return this.threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.compressor.release();
    }
}
