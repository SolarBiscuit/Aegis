package com.solarbiscuit.ratnetworking.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Early (mixin-time) detection of mods that patch the same code. Uses FML's loading mod list, which exists before any
 * mixin config is processed. Fabric mods loaded through Sinytra Connector show up here too.
 */
public final class ModCompat {
    public static final boolean PACKET_FIXER = present("packetfixer");
    // Krypton Reno is "kreno". Forks that bundle velocity-native cannot even start next to RatNetworking (two modules
    // export com.velocitypowered.natives); mods.toml turns that into a readable error. This covers forks without natives.
    public static final boolean KRYPTON = present("krypton") || present("kreno") || present("kryptonfnp") || present("krypton_fnp") || present("pluto");
    public static final boolean VMP = present("vmp");
    public static final boolean RAKNETIFY = present("raknetify");
    public static final boolean IMMERSIVE_PORTALS = present("immersive_portals") || present("imm_ptl_core");
    /** Connectivity (ATM9, BMC4, Valhelsia 6...): its own timeouts, outgoing packet limit and disconnect wait. */
    public static final boolean CONNECTIVITY = present("connectivity");

    private static final List<String> NOTES = new ArrayList<>();

    static {
        if (PACKET_FIXER) {
            NOTES.add("Packet Fixer is installed: RatNetworking's packet limits, timeouts, frame decoder/prepender and compression handlers are disabled so the two do not fight. RatNetworking covers everything Packet Fixer does (with its bugs fixed); remove Packet Fixer on both server and clients to get them back.");
        }
        if (KRYPTON) {
            NOTES.add("Krypton (or a fork) is installed: RatNetworking's pipeline, flush and VarInt patches are disabled. RatNetworking includes Krypton's optimizations; remove Krypton to use them.");
        }
        if (VMP) {
            NOTES.add("VMP is installed: RatNetworking's flush consolidation, disconnect, event loop and entity tracker patches are disabled.");
        }
        if (CONNECTIVITY) {
            NOTES.add("Connectivity is installed: RatNetworking's timeouts, outgoing packet size limit and non-blocking disconnect are disabled (Connectivity patches the same code; its own settings apply). Everything else stays on.");
        }
        if (IMMERSIVE_PORTALS) {
            NOTES.add("Immersive Portals is installed: the chunk entity lookup is disabled (it sends entities across portals differently).");
        }
    }

    private ModCompat() {
    }

    private static boolean present(String modId) {
        try {
            LoadingModList list = FMLLoader.getLoadingModList();
            return list != null && list.getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> notes() {
        return Collections.unmodifiableList(NOTES);
    }
}
