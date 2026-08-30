"""Minimal RCON client for E2E stats collection."""
import socket
import struct
import sys
import time


def pkt(req_id, ptype, body):
    data = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read_pkt(s):
    raw = s.recv(4)
    if len(raw) < 4:
        raise EOFError
    (ln,) = struct.unpack("<i", raw)
    buf = b""
    while len(buf) < ln:
        chunk = s.recv(ln - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    rid, ptype = struct.unpack("<ii", buf[:8])
    return rid, ptype, buf[8:-2].decode("utf-8", "replace")


def rcon(host, port, pw, cmds):
    s = socket.create_connection((host, port), timeout=10)
    try:
        s.sendall(pkt(1, 3, pw))
        rid, ptype, _ = read_pkt(s)
        if rid == -1:
            raise SystemExit("auth failed")
        out = []
        for i, cmd in enumerate(cmds):
            s.sendall(pkt(i + 2, 2, cmd))
            time.sleep(0.4)
            rid, ptype, body = read_pkt(s)
            out.append((cmd, body))
        return out
    finally:
        s.close()


if __name__ == "__main__":
    host = sys.argv[1]
    port = int(sys.argv[2])
    pw = sys.argv[3]
    for cmd, body in rcon(host, port, pw, sys.argv[4:]):
        print(f"$ {cmd}")
        print(body)