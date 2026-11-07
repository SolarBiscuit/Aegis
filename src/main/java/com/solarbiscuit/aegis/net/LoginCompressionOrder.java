package com.solarbiscuit.aegis.net;

import io.netty.channel.Channel;

/**
 * Vanilla turns compression on in the write-completion listener of ClientboundLoginCompressionPacket and sends
 * ClientboundGameProfilePacket right after. When the socket cannot take the first packet at once (a full send buffer),
 * the profile packet is encoded before the listener runs, goes out uncompressed, and the client (which already switched
 * to compression) disconnects with "Badly compressed packet".
 * <p>
 * The compression packet has passed every encoder as soon as its write ran on the event loop, so compression can be
 * turned on right then: run as the next event loop task after that write, it comes before the write of any packet sent
 * afterwards.
 */
public final class LoginCompressionOrder {
    private LoginCompressionOrder() {
    }

    /** Runs {@code task} on the channel's event loop after every write already queued from this thread. */
    public static void afterQueuedWrites(Channel channel, Runnable task) {
        if (channel.eventLoop().inEventLoop()) {
            task.run(); // Connection.send wrote inline, so the write is done
        } else {
            channel.eventLoop().execute(task);
        }
    }
}
