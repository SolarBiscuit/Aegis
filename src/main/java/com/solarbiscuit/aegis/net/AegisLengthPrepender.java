package com.solarbiscuit.aegis.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Frame length prepender.
 * <ul>
 * <li>Zero-copy mode: the length is written into its own small buffer and both buffers are written in order (the same
 * thing Netty's LengthFieldPrepender does). Vanilla copies every outgoing packet into a new buffer just to put up to
 * three bytes in front of it.</li>
 * <li>Allows 4-5 byte lengths up to the sending direction's limit (vanilla: "unable to fit X into 3").</li>
 * </ul>
 * Extends the vanilla class so {@code instanceof} checks elsewhere keep working.
 */
public class AegisLengthPrepender extends Varint21LengthFieldPrepender {
    private final PacketFlow sending;
    private final boolean zeroCopy;

    public AegisLengthPrepender(PacketFlow sending, boolean zeroCopy) {
        this.sending = sending;
        this.zeroCopy = zeroCopy;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!this.zeroCopy || !(msg instanceof ByteBuf buf)) {
            super.write(ctx, msg, promise);
            return;
        }
        int length = buf.readableBytes();
        try {
            checkLength(length);
        } catch (EncoderException e) {
            buf.release();
            throw e;
        }
        ByteBuf header = ctx.alloc().ioBuffer(VarInts.size(length));
        VarInts.write(header, length);
        // The header's own promise is void: if it fails, the frame write right after it fails the same way and
        // reports through the caller's promise.
        ctx.write(header, ctx.voidPromise());
        ctx.write(buf, promise);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        checkLength(length);
        out.ensureWritable(VarInts.size(length) + length);
        VarInts.write(out, length);
        out.writeBytes(msg, msg.readerIndex(), length);
    }

    private void checkLength(int length) {
        int max = Limits.maxOutboundFrame(this.sending);
        if (length > max) {
            throw new EncoderException("Unable to send a frame of " + length + " bytes, the limit is " + max
                    + " bytes (Aegis limits." + (this.sending == PacketFlow.CLIENTBOUND ? "clientbound" : "serverbound")
                    + "MaxPacketSize)");
        }
    }
}
