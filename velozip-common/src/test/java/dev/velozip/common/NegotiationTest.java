package dev.velozip.common;

import dev.velozip.common.protocol.Negotiation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NegotiationTest {

    private static final String SECRET = "correct-horse-battery";

    private byte[] buildReq(String secret, boolean sendHmac, int proto, int algo, int level) {
        byte[] nonce = Negotiation.newNonce();
        byte[] hmac = sendHmac
                ? Negotiation.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), nonce, proto, algo, level)
                : null;
        return Negotiation.encodeReq(proto, algo, level, nonce, hmac, "0.1.0-test");
    }

    @Test
    void reqRoundTripWithHmac() {
        byte[] wire = buildReq(SECRET, true, 1, 1, 1);
        Negotiation.Req req = Negotiation.decodeReq(wire);
        assertEquals(1, req.transportProtocol());
        assertEquals(1, req.algorithm());
        assertEquals(1, req.level());
        assertNotNull(req.hmac());
        assertEquals("0.1.0-test", req.requesterVersion());
        assertNull(Negotiation.validateReq(req, SECRET, true));
    }

    @Test
    void reqRoundTripWithoutHmac() {
        byte[] wire = buildReq("", false, 1, 1, 1);
        Negotiation.Req req = Negotiation.decodeReq(wire);
        assertNull(req.hmac());
        assertNull(Negotiation.validateReq(req, "", true));
    }

    @Test
    void refuseReasonDisabled() {
        byte[] wire = buildReq(SECRET, true, 1, 1, 1);
        Negotiation.Refuse refuse = Negotiation.validateReq(Negotiation.decodeReq(wire), SECRET, false);
        assertNotNull(refuse);
        assertEquals(Negotiation.REASON_DISABLED, refuse.reasonCode());
    }

    @Test
    void refuseReasonProtoMismatch() {
        byte[] wire = buildReq(SECRET, true, 9, 1, 1);
        Negotiation.Refuse refuse = Negotiation.validateReq(Negotiation.decodeReq(wire), SECRET, true);
        assertEquals(Negotiation.REASON_PROTO_MISMATCH, refuse.reasonCode());
    }

    @Test
    void refuseReasonParamMismatch() {
        byte[] wire = buildReq(SECRET, true, 1, 1, 3); // level 3 not supported
        Negotiation.Refuse refuse = Negotiation.validateReq(Negotiation.decodeReq(wire), SECRET, true);
        assertEquals(Negotiation.REASON_PARAM_MISMATCH, refuse.reasonCode());
    }

    @Test
    void refuseWhenOneSideHasSecret() {
        byte[] wire = buildReq("", false, 1, 1, 1);
        Negotiation.Refuse refuse = Negotiation.validateReq(Negotiation.decodeReq(wire), SECRET, true);
        assertEquals(Negotiation.REASON_AUTH_FAILED, refuse.reasonCode());

        wire = buildReq(SECRET, true, 1, 1, 1);
        refuse = Negotiation.validateReq(Negotiation.decodeReq(wire), "", true);
        assertEquals(Negotiation.REASON_AUTH_FAILED, refuse.reasonCode());
    }

    @Test
    void refuseOnTamperedHmac() {
        byte[] good = buildReq(SECRET, true, 1, 1, 1);
        good[17 + 16 + 1 + 5] ^= 0x01; // inside HMAC (after magic,proto,algo,level,nonce,flags)
        Negotiation.Refuse refuse = Negotiation.validateReq(Negotiation.decodeReq(good), SECRET, true);
        assertEquals(Negotiation.REASON_AUTH_FAILED, refuse.reasonCode());
    }

    @Test
    void wrongSecretRejected() {
        byte[] wire = buildReq(SECRET, true, 1, 1, 1);
        Negotiation.Refuse refuse = Negotiation.validateReq(Negotiation.decodeReq(wire), "other-secret", true);
        assertEquals(Negotiation.REASON_AUTH_FAILED, refuse.reasonCode());
    }

    @Test
    void refuseRoundTrip() {
        Negotiation.Refuse refuse = new Negotiation.Refuse(Negotiation.REASON_PROTO_MISMATCH,
                "transport protocol mismatch (local=1, remote=2)");
        byte[] wire = Negotiation.encodeRefuse(refuse);
        Negotiation.Refuse decoded = Negotiation.decodeRefuse(wire);
        assertEquals(refuse.reasonCode(), decoded.reasonCode());
        assertEquals(refuse.message(), decoded.message());
    }

    @Test
    void hmacDeterministic() {
        byte[] nonce = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] a = Negotiation.hmacSha256(SECRET.getBytes(StandardCharsets.UTF_8), nonce, 1, 1, 1);
        byte[] b = Negotiation.hmacSha256(SECRET.getBytes(StandardCharsets.UTF_8), nonce, 1, 1, 1);
        assertArrayEquals(a, b);
        assertEquals(32, a.length);
    }

    @Test
    void malformedReqRejected() {
        assertThrows(IllegalArgumentException.class, () -> Negotiation.decodeReq(new byte[]{9, 1, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> Negotiation.decodeReq(new byte[]{1, 1, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> Negotiation.decodeRefuse(new byte[]{5, 1, 0}));
    }
}
