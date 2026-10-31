package com.solarbiscuit.ratnetworking.net.compression;

import java.util.List;

import com.solarbiscuit.ratnetworking.net.Limits;
import com.solarbiscuit.ratnetworking.net.NativeSupport;
import com.solarbiscuit.ratnetworking.net.VarInts;
import com.velocitypowered.natives.compression.VelocityCompressor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Decompression (Krypton/Velocity design) with the checks vanilla, Krypton and Packet Fixer were missing:
 * <ul>
 * <li>The claimed decompressed size is checked against the direction's limit before anything is allocated, on the
 * client too (vanilla and Krypton only check on the server; Packet Fixer turned the check into "unlimited", so one
 * small packet could make the receiver allocate up to 2 GiB).</li>
 * <li>A claimed size the compressed data cannot possibly produce (deflate expands at most ~1032x) is rejected.</li>
 * <li>Decompression never writes past the claimed size and must produce exactly that many bytes (Velocity's Java
 * fallback grew the output buffer without bound, vanilla left trailing zero bytes on short streams).</li>
 * <li>Uncompressed packets are passed on as a slice, not copied.</li>
 * </ul>
 */
public class RNCompressDecoder extends MessageToMessageDecoder<ByteBuf> {
    private final SharedCompressor compressor;
    private final PacketFlow receiving;
    private int threshold;
    private boolean validate;

    public RNCompressDecoder(SharedCompressor compressor, PacketFlow receiving, int threshold, boolean validate) {
        this.compressor = compressor;
        this.receiving = receiving;
        this.threshold = threshold;
        this.validate = validate;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }
        int claimed = VarInts.read(in);
        if (claimed == 0) {
            out.add(in.retainedSlice());
            return;
        }
        if (claimed < 0) {
            throw new DecoderException("Badly compressed packet - negative size " + claimed);
        }
        if (this.validate && claimed < this.threshold) {
            throw new DecoderException("Badly compressed packet - size of " + claimed + " is below server threshold of " + this.threshold);
        }
        int max = Limits.maxInboundUncompressed(this.receiving, ctx.channel());
        if (claimed > max) {
            throw new DecoderException("Badly compressed packet - size of " + claimed + " is larger than the maximum of " + max
                    + " (RatNetworking limits." + (this.receiving == PacketFlow.CLIENTBOUND ? "clientbound" : "serverbound") + "MaxPacketSize)");
        }
        int compressed = in.readableBytes();
        if (claimed > compressed * Limits.MAX_DEFLATE_RATIO + 64) {
            throw new DecoderException("Badly compressed packet - " + compressed + " compressed bytes cannot inflate to " + claimed);
        }
        // A zlib stream that asks for a preset dictionary (FLG bit 5) makes no progress in Velocity's Java inflate loop,
        // which then spins the Netty thread forever. Nothing legitimate (vanilla, Velocity, libdeflate) sends one.
        if (compressed < 2 || (in.getByte(in.readerIndex() + 1) & 0x20) != 0) {
            throw new DecoderException("Badly compressed packet - " + (compressed < 2 ? "truncated zlib header" : "preset dictionary"));
        }

        VelocityCompressor c = this.compressor.get();
        ByteBuf source = NativeSupport.ensureCompatible(ctx.alloc(), c, in, false);
        // One spare byte: java.util.zip may need a last call with free output space to read the stream trailer. A
        // stream that really produces more than the claimed size fails the size check below or hits the cap.
        ByteBuf uncompressed = NativeSupport.preferredBuffer(ctx.alloc(), c, claimed + 1, claimed + 1);
        try {
            c.inflate(source, uncompressed, claimed);
            if (uncompressed.readableBytes() != claimed) {
                throw new DecoderException("Badly compressed packet - decompressed to " + uncompressed.readableBytes() + " bytes instead of " + claimed);
            }
            out.add(uncompressed);
        } catch (Throwable t) {
            uncompressed.release();
            if (t instanceof DecoderException de) {
                throw de;
            }
            throw new DecoderException("Badly compressed packet", t);
        } finally {
            source.release();
        }
    }

    public void setThreshold(int threshold, boolean validate) {
        this.threshold = threshold;
        this.validate = validate;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.compressor.release();
    }
}
