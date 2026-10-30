package com.solarbiscuit.ratnetworking.net;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ByteProcessor;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Packet splitter (Krypton / Velocity design, plus the Packet Fixer size limits done safely).
 * <ul>
 * <li>Frames are passed on as retained slices of the cumulation buffer: vanilla copies every packet once
 * ({@code readBytes}) and allocates a byte[3] plus a wrapped buffer per packet. Netty never compacts or reuses a
 * cumulation buffer while slices of it are alive, so this is safe.</li>
 * <li>Runs of zero bytes ("nullping") are skipped in one step instead of producing an empty buffer each.</li>
 * <li>The frame length may use 4 or 5 bytes when the direction's limit allows it ("length wider than 21-bit",
 * "Unable to fit X into 3"). Packet Fixer did this by allocating a byte array as large as everything buffered so far on
 * every decode call. Oversized frames are rejected as soon as their header arrives, not after buffering them.</li>
 * <li>Data that still arrives after the channel closed is dropped.</li>
 * </ul>
 * Extends the vanilla class so {@code instanceof Varint21FrameDecoder} checks elsewhere keep working.
 */
public class RNFrameDecoder extends Varint21FrameDecoder {
    private final PacketFlow receiving;

    public RNFrameDecoder(PacketFlow receiving) {
        this.receiving = receiving;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!ctx.channel().isActive()) {
            in.skipBytes(in.readableBytes());
            return;
        }
        int start = in.readerIndex();
        int readable = in.readableBytes();
        if (readable == 0) {
            return;
        }
        if (in.getByte(start) == 0) {
            // Zero-length frames carry nothing (vanilla ignores them after allocating a buffer); skip the whole run.
            int next = in.forEachByte(start, readable, ByteProcessor.FIND_NON_NUL);
            in.readerIndex(next == -1 ? start + readable : next);
            return;
        }

        int length = 0;
        int headerBytes = 0;
        while (true) {
            if (headerBytes == readable) {
                return; // header incomplete
            }
            byte b = in.getByte(start + headerBytes);
            length |= (b & 0x7F) << (headerBytes * 7);
            headerBytes++;
            if ((b & 0x80) == 0) {
                break;
            }
            if (headerBytes == 5) {
                in.skipBytes(readable);
                throw new CorruptedFrameException("length wider than 32-bit");
            }
        }
        if (length < 0) {
            in.skipBytes(readable);
            throw QuietDecoderException.BAD_LENGTH;
        }
        if (headerBytes > 3 || length > Limits.VANILLA_MAX_FRAME) {
            int max = Limits.maxInboundFrame(this.receiving, ctx.channel());
            if (length > max) {
                in.skipBytes(readable);
                throw new CorruptedFrameException("Frame of " + length + " bytes is larger than the limit of " + max
                        + " bytes (RatNetworking limits." + (this.receiving == PacketFlow.CLIENTBOUND
                        ? "clientboundMaxPacketSize)" : "serverboundMaxPacketSize; before login the vanilla limit applies)"));
            }
        }
        if (readable - headerBytes < length) {
            return; // wait for the rest of the frame
        }
        out.add(in.retainedSlice(start + headerBytes, length));
        in.readerIndex(start + headerBytes + length);
    }
}
