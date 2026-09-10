package dev.velozip.backend.adapter;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerChannelsTest {
    static class Parent { private Channel obfuscated; static Channel ignored; }
    static class Child extends Parent { String irrelevant; }
    static class Ambiguous extends Parent { Channel second; }

    @Test void findsInheritedPrivateFieldByTypeIgnoringStatics() throws Exception {
        Child value = new Child();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            var field = PlayerChannels.uniqueField(Child.class, Channel.class::isAssignableFrom);
            field.set(value, channel);
            assertSame(channel, field.get(value));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void refusesAmbiguousOrMissingConnectionInsteadOfGuessing() {
        assertThrows(IllegalStateException.class,
                () -> PlayerChannels.uniqueField(Ambiguous.class, Channel.class::isAssignableFrom));
        assertThrows(IllegalStateException.class,
                () -> PlayerChannels.uniqueField(String.class, Channel.class::isAssignableFrom));
    }
}
