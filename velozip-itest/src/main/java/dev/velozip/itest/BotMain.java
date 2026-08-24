package dev.velozip.itest;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal offline-login bot for the VeloZip E2E test: joins through Velocity,
 * waits, and disconnects. A successful join (ClientboundLoginPacket) is what
 * triggers VeloZip negotiation on the proxy side (ServerConnectedEvent).
 *
 * <p>Run: ./gradlew :velozip-itest:bot -PbotHost=127.0.0.1 -PbotPort=25565
 */
public final class BotMain {

    private static final Logger log = LoggerFactory.getLogger(BotMain.class);

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("bot.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("bot.port", "25565"));
        int seconds = Integer.parseInt(System.getProperty("bot.seconds", "20"));
        String username = System.getProperty("bot.name", "VeloZipBot");

        ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                .setAddress(host, port)
                .setProtocol(new MinecraftProtocol(username))
                .create();

        final Object playReached = new Object();
        SessionListener listener = new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                log.info("BOT connected (login phase started)");
            }

            @Override
            public void packetReceived(org.geysermc.mcprotocollib.network.Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    log.info("BOT reached PLAY state (join game received)");
                    synchronized (playReached) {
                        playReached.notifyAll();
                    }
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                log.info("BOT disconnected: {}", event.getReason());
                synchronized (playReached) {
                    playReached.notifyAll();
                }
            }
        };
        session.addListener(listener);
        session.connect();

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        synchronized (playReached) {
            while (System.currentTimeMillis() < deadline) {
                playReached.wait(1000);
            }
        }
        log.info("BOT finished waiting, disconnecting");
        session.disconnect(Component.text("E2E test complete"));
        Thread.sleep(1000);
        System.exit(0);
    }
}
