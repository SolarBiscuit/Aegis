package com.solarbiscuit.aegis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.solarbiscuit.aegis.compat.ModCompat;
import com.solarbiscuit.aegis.net.Limits;
import com.solarbiscuit.aegis.net.NativeSupport;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(Aegis.MOD_ID)
public class Aegis {
    public static final String MOD_ID = "aegis";
    private static final Logger LOGGER = LogManager.getLogger("Aegis");

    public Aegis() {
        Features.init();
        if (Features.COMPRESSION || Features.ENCRYPTION) {
            NativeSupport.warmUpAsync();
        }
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        LOGGER.info("Aegis loaded ({})", Features.describe());
    }

    private void registerCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(MOD_ID)
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    source.sendSuccess(() -> Component.literal("Aegis modules: " + Features.describe()), false);
                    if (Features.COMPRESSION) {
                        source.sendSuccess(() -> Component.literal("Compression: " + NativeSupport.compressionName()), false);
                    }
                    if (Features.ENCRYPTION) {
                        source.sendSuccess(() -> Component.literal("Encryption: " + NativeSupport.encryptionName()), false);
                    }
                    source.sendSuccess(() -> Component.literal("Limits: clientbound " + Limits.clientbound() + " B, serverbound (play) "
                            + Limits.serverboundPlay() + " B"), false);
                    for (String note : ModCompat.notes()) {
                        source.sendSuccess(() -> Component.literal(note), false);
                    }
                    return 1;
                }));
    }
}
