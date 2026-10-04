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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
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
        final long holdClock = System.nanoTime();
        final java.util.concurrent.atomic.AtomicLong lastChunkNanos = new java.util.concurrent.atomic.AtomicLong();
        var joined = new java.util.concurrent.atomic.AtomicBoolean();
        var worldReady = new java.util.concurrent.atomic.AtomicBoolean();
        var disconnected = new java.util.concurrent.atomic.AtomicBoolean();
        SessionListener listener = new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                log.info("BOT connected (login phase started)");
            }

            @Override
            public void packetReceived(org.geysermc.mcprotocollib.network.Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    joined.set(true);
                    log.info("BOT reached PLAY state (join game received)");
                    synchronized (playReached) {
                        playReached.notifyAll();
                    }
                } else if (packet instanceof ClientboundLevelChunkWithLightPacket) {
                    lastChunkNanos.set(System.nanoTime());
                    if (worldReady.compareAndSet(false, true)) {
                        log.info("BOT first world chunk after {} ms",
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdClock));
                        synchronized (playReached) {
                            playReached.notifyAll();
                        }
                    }
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                disconnected.set(true);
                log.info("BOT disconnected: {}", event.getReason());
                synchronized (playReached) {
                    playReached.notifyAll();
                }
            }
        };
        session.addListener(listener);
        session.connect();

        long loginDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        synchronized (playReached) {
            while (!joined.get() && !disconnected.get() && System.nanoTime() < loginDeadline) {
                playReached.wait(100);
            }
            // The healthy hold starts at the first world chunk: a session that
            // never renders terrain must not count as healthy.
            long worldDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds + 30);
            while (joined.get() && !worldReady.get() && !disconnected.get() && System.nanoTime() < worldDeadline) {
                playReached.wait(100);
            }
            if (joined.get() && worldReady.get() && !disconnected.get()) {
                long holdStart = System.nanoTime();
                long holdDeadline = holdStart + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
                while (!disconnected.get() && System.nanoTime() < holdDeadline) {
                    playReached.wait(100);
                }
                log.info("BOT healthy hold milliseconds={}",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdStart));
                // Wait for the chunk stream to go quiet so no in-flight tail is
                // lost and end-to-end byte counters can be compared exactly.
                long quietStart = System.nanoTime();
                while (!disconnected.get() && System.nanoTime() - lastChunkNanos.get()
                        < java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                        && System.nanoTime() - quietStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                    playReached.wait(100);
                }
                log.info("BOT stream quiet after {} ms",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - quietStart));
            } else if (joined.get() && !worldReady.get() && !disconnected.get()) {
                log.info("BOT no world chunk before deadline");
            }
        }
        boolean success = joined.get() && worldReady.get() && !disconnected.get();
        log.info("BOT finished: success={}", success);
        session.disconnect(Component.text("E2E test complete"));
        Thread.sleep(1000);
        System.exit(success ? 0 : 1);
    }
}
