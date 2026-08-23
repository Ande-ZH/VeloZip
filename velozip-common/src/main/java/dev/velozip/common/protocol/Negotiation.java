package dev.velozip.common.protocol;

import dev.velozip.common.VeloZip;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Wire format of the negotiation plugin messages on {@code velozip:negotiate}.
 *
 * <pre>
 * REQ (Velocity -> Backend):
 *   u8     0x01 (REQ)
 *   u8     transportProtocol
 *   u8     algorithm (1 = zstd)
 *   u8     level
 *   byte[16] nonce
 *   u8     flags (bit 0: HMAC present)
 *   [byte[32] HMAC-SHA256(secret, nonce || transportProtocol || algorithm || level)]
 *   varint+utf8 requester plugin version (diagnostics only; never compared)
 *
 * REFUSE (Backend -> Velocity):
 *   u8     0x02 (REFUSE)
 *   u8     reason code
 *   varint+utf8 human-readable reason
 * </pre>
 *
 * The shared secret itself is never transmitted; the nonce defeats replay.
 */
public final class Negotiation {

    public static final int MSG_REQ = 0x01;
    public static final int MSG_REFUSE = 0x02;

    public static final int REASON_DISABLED = 1;
    public static final int REASON_PROTO_MISMATCH = 2;
    public static final int REASON_PARAM_MISMATCH = 3;
    public static final int REASON_AUTH_FAILED = 4;
    public static final int REASON_NOT_PROXY = 5;
    public static final int REASON_INTERNAL = 6;

    public static final int ALGO_ZSTD = 1;
    public static final int NONCE_LEN = 16;
    public static final int HMAC_LEN = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Negotiation() {
    }

    public record Req(int transportProtocol, int algorithm, int level,
                      byte[] nonce, byte[] hmac, String requesterVersion) {
    }

    public record Refuse(int reasonCode, String message) {
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static byte[] hmacSha256(byte[] secret, byte[] nonce, int transportProtocol, int algorithm, int level) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(nonce);
            mac.update((byte) transportProtocol);
            mac.update((byte) algorithm);
            mac.update((byte) level);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] encodeReq(int transportProtocol, int algorithm, int level,
                                   byte[] nonce, byte[] hmac, String requesterVersion) {
        int versionBytes = requesterVersion.getBytes(StandardCharsets.UTF_8).length;
        byte[] out = new byte[1 + 1 + 1 + 1 + NONCE_LEN + 1 + (hmac == null ? 0 : HMAC_LEN)
                + VarInts.varIntLen(versionBytes) + versionBytes];
        int i = 0;
        out[i++] = MSG_REQ;
        out[i++] = (byte) transportProtocol;
        out[i++] = (byte) algorithm;
        out[i++] = (byte) level;
        System.arraycopy(nonce, 0, out, i, NONCE_LEN);
        i += NONCE_LEN;
        out[i++] = (byte) (hmac == null ? 0 : 1);
        if (hmac != null) {
            if (hmac.length != HMAC_LEN) {
                throw new IllegalArgumentException("bad hmac length");
            }
            System.arraycopy(hmac, 0, out, i, HMAC_LEN);
            i += HMAC_LEN;
        }
        // varint length + utf8 version
        int len = versionBytes;
        while ((len & ~0x7F) != 0) {
            out[i++] = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
        }
        out[i++] = (byte) len;
        byte[] v = requesterVersion.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(v, 0, out, i, v.length);
        return out;
    }

    /** @throws IllegalArgumentException on malformed input */
    public static Req decodeReq(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            if (buf.readableBytes() < 6 || buf.readByte() != MSG_REQ) {
                throw malformed("REQ");
            }
            int proto = buf.readUnsignedByte();
            int algo = buf.readUnsignedByte();
            int level = buf.readUnsignedByte();
            if (buf.readableBytes() < NONCE_LEN) {
                throw malformed("REQ nonce");
            }
            byte[] nonce = new byte[NONCE_LEN];
            buf.readBytes(nonce);
            if (!buf.isReadable()) {
                throw malformed("REQ flags");
            }
            int flags = buf.readUnsignedByte();
            byte[] hmac = null;
            if ((flags & 1) != 0) {
                if (buf.readableBytes() < HMAC_LEN) {
                    throw malformed("REQ hmac");
                }
                hmac = new byte[HMAC_LEN];
                buf.readBytes(hmac);
            }
            int versionLen = VarInts.tryReadVarInt(buf, 3, 256);
            if (versionLen < 0 || buf.readableBytes() < versionLen) {
                throw malformed("REQ version");
            }
            String version = buf.readCharSequence(versionLen, StandardCharsets.UTF_8).toString();
            return new Req(proto, algo, level, nonce, hmac, version);
        } catch (dev.velozip.common.frame.VeloZipFrameException e) {
            throw malformed("REQ varint");
        }
    }

    public static byte[] encodeRefuse(Refuse refuse) {
        byte[] msg = refuse.message().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + VarInts.varIntLen(msg.length) + msg.length];
        out[0] = MSG_REFUSE;
        out[1] = (byte) refuse.reasonCode();
        int i = 2;
        int len = msg.length;
        while ((len & ~0x7F) != 0) {
            out[i++] = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
        }
        out[i++] = (byte) len;
        System.arraycopy(msg, 0, out, i, msg.length);
        return out;
    }

    /** @throws IllegalArgumentException on malformed input */
    public static Refuse decodeRefuse(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        if (buf.readableBytes() < 2 || buf.readUnsignedByte() != MSG_REFUSE) {
            throw malformed("REFUSE");
        }
        int code = buf.readUnsignedByte();
        try {
            int len = VarInts.tryReadVarInt(buf, 3, 1024);
            if (len < 0 || buf.readableBytes() < len) {
                throw malformed("REFUSE message");
            }
            return new Refuse(code, buf.readCharSequence(len, StandardCharsets.UTF_8).toString());
        } catch (dev.velozip.common.frame.VeloZipFrameException e) {
            throw malformed("REFUSE varint");
        }
    }

    /**
     * Validates a decoded REQ against local expectations.
     *
     * @return null if OK, otherwise a REFUSE to send back
     */
    public static Refuse validateReq(Req req, String localSecret, boolean enabled) {
        if (!enabled) {
            return new Refuse(REASON_DISABLED, "VeloZip is disabled on this backend");
        }
        if (req.transportProtocol() != VeloZip.TRANSPORT_PROTOCOL) {
            return new Refuse(REASON_PROTO_MISMATCH,
                    "transport protocol mismatch (local=" + VeloZip.TRANSPORT_PROTOCOL
                            + ", remote=" + req.transportProtocol() + ")");
        }
        if (req.algorithm() != ALGO_ZSTD || req.level() != VeloZip.COMPRESSION_LEVEL) {
            return new Refuse(REASON_PARAM_MISMATCH,
                    "unsupported compression parameters (algo=" + req.algorithm()
                            + ", level=" + req.level() + "; require zstd/1)");
        }
        String secret = localSecret == null ? "" : localSecret;
        boolean localAuth = !secret.isEmpty();
        boolean remoteAuth = req.hmac() != null;
        if (localAuth != remoteAuth) {
            return new Refuse(REASON_AUTH_FAILED,
                    localAuth ? "this backend requires a shared secret" : "this backend has no secret configured");
        }
        if (localAuth) {
            byte[] expected = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), req.nonce(),
                    req.transportProtocol(), req.algorithm(), req.level());
            if (!MessageDigest.isEqual(expected, req.hmac())) {
                return new Refuse(REASON_AUTH_FAILED, "HMAC verification failed");
            }
        }
        return null;
    }

    private static IllegalArgumentException malformed(String what) {
        return new IllegalArgumentException("malformed VeloZip " + what + " message");
    }
}
