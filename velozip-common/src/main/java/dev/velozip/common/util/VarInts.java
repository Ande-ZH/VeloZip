package dev.velozip.common.util;

import dev.velozip.common.frame.VeloZipFrameException;
import io.netty.buffer.ByteBuf;

/** LEB128 varints as used by the Minecraft protocol and the VeloZip frame header. */
public final class VarInts {

    private VarInts() {
    }

    /** Reads a varint, enforcing both a byte-width and value cap before returning. */
    public static int readVarInt(ByteBuf in, int maxBytes, int maxValue) {
        int value = 0;
        int bytes = 0;
        while (true) {
            if (!in.isReadable()) {
                throw new VeloZipFrameException("truncated varint");
            }
            byte b = in.readByte();
            value |= (b & 0x7F) << (bytes * 7);
            if ((b & 0x80) == 0) {
                break;
            }
            if (++bytes >= maxBytes) {
                throw new VeloZipFrameException("varint too big (>= " + maxBytes + " bytes)");
            }
        }
        if (value < 0 || value > maxValue) {
            throw new VeloZipFrameException("varint value " + value + " exceeds limit " + maxValue);
        }
        return value;
    }

    /**
     * Reads a varint that must be fully present; returns -1 if more bytes are
     * needed. Used where partial input is normal (stream framing).
     */
    public static int tryReadVarInt(ByteBuf in, int maxBytes, int maxValue) {
        int value = 0;
        int bytes = 0;
        while (true) {
            if (!in.isReadable()) {
                return -1;
            }
            byte b = in.readByte();
            value |= (b & 0x7F) << (bytes * 7);
            if ((b & 0x80) == 0) {
                if (value < 0 || value > maxValue) {
                    throw new VeloZipFrameException("varint value " + value + " exceeds limit " + maxValue);
                }
                return value;
            }
            if (++bytes >= maxBytes) {
                throw new VeloZipFrameException("varint too big (>= " + maxBytes + " bytes)");
            }
        }
    }

    public static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int varIntLen(int value) {
        int len = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            len++;
        }
        return len;
    }
}
