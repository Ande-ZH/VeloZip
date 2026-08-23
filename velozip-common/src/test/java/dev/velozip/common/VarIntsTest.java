package dev.velozip.common;

import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VarIntsTest {

    @Test
    void roundTrip() {
        int[] values = {0, 1, 127, 128, 255, 256, 16383, 16384, 2097151, 65536, 1 << 20};
        for (int v : values) {
            ByteBuf buf = Unpooled.buffer();
            VarInts.writeVarInt(buf, v);
            assertEquals(VarInts.varIntLen(v), buf.readableBytes());
            assertEquals(v, VarInts.readVarInt(buf, 5, Integer.MAX_VALUE));
            buf.release();
        }
    }

    @Test
    void tryReadReturnsMinusOneWhenTruncated() {
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeVarInt(buf, 300);
        ByteBuf partial = buf.readRetainedSlice(1); // first byte has continuation bit
        assertEquals(-1, VarInts.tryReadVarInt(partial, 5, Integer.MAX_VALUE));
        partial.release();
        buf.release();
    }

    @Test
    void valueCapThrows() {
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeVarInt(buf, 5000);
        assertThrows(VeloZipFrameException.class, () -> VarInts.tryReadVarInt(buf, 5, 4096));
        buf.release();
    }

    @Test
    void widthCapThrows() {
        ByteBuf buf = Unpooled.buffer();
        // 5-byte (31-bit) varint for 0xFFFFFFFF-ish value
        buf.writeByte(0xFF).writeByte(0xFF).writeByte(0xFF).writeByte(0xFF).writeByte(0x07);
        assertThrows(VeloZipFrameException.class, () -> VarInts.tryReadVarInt(buf, 3, Integer.MAX_VALUE));
        buf.release();
    }
}
