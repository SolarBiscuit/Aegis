package com.solarbiscuit.aegis.mixin.frame;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.aegis.Features;
import com.solarbiscuit.aegis.net.AegisFrameDecoder;
import com.solarbiscuit.aegis.net.AegisLengthPrepender;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Swaps the vanilla splitter/prepender for Aegis's right after the pipeline is built (by name, so nothing else
 * about the pipeline changes). Handlers another mod put there instead of the vanilla ones are left alone.
 */
@Mixin(Connection.class)
public abstract class ConnectionSerializationMixin {
    @Inject(method = "configureSerialization", at = @At("TAIL"))
    private static void aegis$replaceFraming(ChannelPipeline pipeline, PacketFlow receiving, CallbackInfo ci) {
        if (Features.FRAME_DECODER && pipeline.get("splitter") != null && pipeline.get("splitter").getClass() == Varint21FrameDecoder.class) {
            pipeline.replace("splitter", "splitter", new AegisFrameDecoder(receiving));
        }
        if (pipeline.get("prepender") != null && pipeline.get("prepender").getClass() == Varint21LengthFieldPrepender.class) {
            pipeline.replace("prepender", "prepender", new AegisLengthPrepender(receiving.getOpposite(), Features.PREPENDER));
        }
    }
}
