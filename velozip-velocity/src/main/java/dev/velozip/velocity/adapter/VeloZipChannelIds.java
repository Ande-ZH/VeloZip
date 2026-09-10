package dev.velozip.velocity.adapter;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.velozip.common.VeloZip;

/** Channel identifier used for the negotiation plugin messages. */
public final class VeloZipChannelIds {

    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create(VeloZip.CHANNEL_NAMESPACE, VeloZip.CHANNEL_NAME);
    public static final MinecraftChannelIdentifier REGISTER =
            MinecraftChannelIdentifier.create("minecraft", "register");

    private VeloZipChannelIds() {
    }
}
