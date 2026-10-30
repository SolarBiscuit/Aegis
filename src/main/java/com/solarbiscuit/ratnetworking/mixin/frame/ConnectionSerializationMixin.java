package com.solarbiscuit.ratnetworking.mixin.frame;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.solarbiscuit.ratnetworking.Features;
import com.solarbiscuit.ratnetworking.net.RNFrameDecoder;
import com.solarbiscuit.ratnetworking.net.RNLengthPrepender;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Swaps the vanilla splitter/prepender for RatNetworking's right after the pipeline is built (by name, so nothing else
 * about the pipeline changes). Handlers another mod put there instead of the vanilla ones are left alone.
 */
@Mixin(Connection.class)
public abstract class ConnectionSerializationMixin {
    @Inject(method = "configureSerialization", at = @At("TAIL"))
    private static void ratnetworking$replaceFraming(ChannelPipeline pipeline, PacketFlow receiving, CallbackInfo ci) {
        if (Features.FRAME_DECODER && pipeline.get("splitter") != null && pipeline.get("splitter").getClass() == Varint21FrameDecoder.class) {
            pipeline.replace("splitter", "splitter", new RNFrameDecoder(receiving));
        }
        if (pipeline.get("prepender") != null && pipeline.get("prepender").getClass() == Varint21LengthFieldPrepender.class) {
            pipeline.replace("prepender", "prepender", new RNLengthPrepender(receiving.getOpposite(), Features.PREPENDER));
        }
    }
}
