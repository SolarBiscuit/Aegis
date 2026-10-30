package com.solarbiscuit.ratnetworking.net.flush;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * Flush consolidation (Krypton + VMP), done with a Netty handler instead of rewriting Connection's send methods (which
 * Fabric API / Forgified Fabric API also inject into).
 * <p>
 * While a connection is "held", flushes that reach the handler are swallowed and remembered; releasing the hold flushes
 * once. Packets are still written immediately and in order, only the socket write (one syscall per flush) is batched.
 * Holds are taken around work that sends many packets to many players in one go: a level's chunk tick (block changes)
 * plus entity tracker tick, and a player's chunk loading. They are always released in a finally-like way; if something
 * still leaks a hold, {@link #onConnectionTick} releases it on the next network tick.
 */
public final class FlushControl {
    public static final String HANDLER_NAME = "ratnetworking_flush";

    private FlushControl() {
    }

    /**
     * While held, a flush is let through anyway once this many bytes are waiting, so a long burst (a player's whole view
     * distance of chunks) starts going out after the first few chunks instead of after the last one.
     */
    private static final int MAX_HELD_BYTES = 64 * 1024;

    /**
     * Added at the head of every real (non in-memory) server connection, where it sees the final encoded bytes. Holds no
     * reference to anything but its own state.
     */
    public static final class Handler extends ChannelOutboundHandlerAdapter {
        private volatile boolean held;
        private boolean pending; // event loop only
        private int heldBytes; // event loop only

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf buf) {
                this.heldBytes += buf.readableBytes();
            }
            ctx.write(msg, promise);
        }

        public boolean hold() {
            if (this.held) {
                return false;
            }
            this.held = true;
            return true;
        }

        public void release(Channel channel) {
            this.held = false;
            // Runs after every write that was queued before it, so everything held back goes out in this flush.
            channel.flush();
        }

        public boolean isHeld() {
            return this.held;
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (this.held && this.heldBytes < MAX_HELD_BYTES) {
                this.pending = true;
            } else {
                flushNow(ctx);
            }
        }

        private void flushNow(ChannelHandlerContext ctx) {
            this.pending = false;
            this.heldBytes = 0;
            ctx.flush();
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            if (this.pending) {
                flushNow(ctx);
            }
            super.close(ctx, promise);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (this.pending) {
                flushNow(ctx);
            }
        }
    }

    /** A set of connections held by one {@link #hold} call. */
    public static final class Batch {
        private static final Batch EMPTY = new Batch(List.of());
        private final List<Object> held; // alternating Handler, Channel

        private Batch(List<Object> held) {
            this.held = held;
        }

        public static Batch none() {
            return EMPTY;
        }

        public void release() {
            for (int i = 0; i < this.held.size(); i += 2) {
                ((Handler) this.held.get(i)).release((Channel) this.held.get(i + 1));
            }
        }
    }

    public static Batch hold(List<ServerPlayer> players) {
        List<Object> held = null;
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer player = players.get(i);
            if (player.connection == null) {
                continue;
            }
            Connection connection = player.connection.connection;
            Channel channel = connection.channel();
            if (channel == null) {
                continue;
            }
            Handler handler = handler(channel);
            if (handler != null && handler.hold()) {
                if (held == null) {
                    held = new ArrayList<>(Math.min(n, 64) * 2);
                }
                held.add(handler);
                held.add(channel);
            }
        }
        return held == null ? Batch.EMPTY : new Batch(held);
    }

    public static Batch hold(ServerPlayer player) {
        if (player.connection == null) {
            return Batch.EMPTY;
        }
        Channel channel = player.connection.connection.channel();
        if (channel == null) {
            return Batch.EMPTY;
        }
        Handler handler = handler(channel);
        if (handler != null && handler.hold()) {
            List<Object> held = new ArrayList<>(2);
            held.add(handler);
            held.add(channel);
            return new Batch(held);
        }
        return Batch.EMPTY;
    }

    private static Handler handler(Channel channel) {
        return channel.pipeline().get(HANDLER_NAME) instanceof Handler h ? h : null;
    }

    /** Safety net, called from Connection.tick (outside of any batch): releases a hold that was never released. */
    public static void onConnectionTick(Channel channel) {
        if (channel != null) {
            Handler handler = handler(channel);
            if (handler != null && handler.isHeld()) {
                handler.release(channel);
            }
        }
    }
}
