package dev.velozip.backend.adapter;

import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

/** Mapping-independent CraftPlayer -> player listener -> connection -> channel access. */
final class PlayerChannels {
    private PlayerChannels() {}

    private static final ClassValue<Field> LISTENER = fields(type -> named(type,
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.server.network.PlayerConnection",
            "net.minecraft.server.v1_16_R1.PlayerConnection",
            "net.minecraft.server.v1_16_R2.PlayerConnection",
            "net.minecraft.server.v1_16_R3.PlayerConnection"));
    private static final ClassValue<Field> CONNECTION = fields(type -> named(type,
            "net.minecraft.network.Connection", "net.minecraft.network.NetworkManager",
            "net.minecraft.server.v1_16_R1.NetworkManager",
            "net.minecraft.server.v1_16_R2.NetworkManager",
            "net.minecraft.server.v1_16_R3.NetworkManager"));
    private static final ClassValue<Field> CHANNEL = fields(Channel.class::isAssignableFrom);

    static Channel find(Object craftPlayer) throws ReflectiveOperationException {
        Object handle = craftPlayer.getClass().getMethod("getHandle").invoke(craftPlayer);
        Object listener = LISTENER.get(handle.getClass()).get(handle);
        if (listener == null) return null;
        Object connection = CONNECTION.get(listener.getClass()).get(listener);
        return connection == null ? null : (Channel) CHANNEL.get(connection.getClass()).get(connection);
    }

    private static boolean named(Class<?> type, String... names) {
        String actual = type.getName();
        for (String name : names) {
            if (actual.equals(name)) return true;
        }
        return false;
    }

    private static ClassValue<Field> fields(Predicate<Class<?>> match) {
        return new ClassValue<>() {
            @Override protected Field computeValue(Class<?> type) {
                return uniqueField(type, match);
            }
        };
    }

    // Includes superclasses (the connection moved into a superclass in 1.20.2).
    // Never pick by obfuscated field name, position, or a recursive object crawl.
    static Field uniqueField(Class<?> type, Predicate<Class<?>> match) {
        Field found = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !match.test(field.getType())) continue;
                if (found != null) throw new IllegalStateException("Ambiguous connection fields in " + type.getName());
                found = field;
            }
        }
        if (found == null) {
            throw new IllegalStateException("No connection field in " + type.getName());
        }
        if (!found.trySetAccessible()) {
            throw new IllegalStateException("Connection field is not accessible in " + type.getName());
        }
        return found;
    }
}
