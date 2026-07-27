#!/usr/bin/env python3
"""Async keep-alive saturation test — measures the real server ceiling.

Each of N workers holds a persistent keep-alive connection and fires requests
back-to-back. Fully non-blocking. Reports ops/s at each concurrency level.
"""
import asyncio
import sys
import time

HOST = "127.0.0.1"
PORT = 8080
PATH = "/ping"
REQ = (
    f"GET {PATH} HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nConnection: keep-alive\r\n\r\n"
).encode()
DURATION = 15


async def read_response(reader):
    """Read one full HTTP/1.1 response with a Content-Length body. Returns True on success."""
    buf = b""
    # Read headers.
    while b"\r\n\r\n" not in buf:
        data = await reader.read(4096)
        if not data:
            return False
        buf += data
    head, _, rest = buf.partition(b"\r\n\r\n")
    # Find Content-Length.
    clen = 0
    for line in head.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            clen = int(line.split(b":", 1)[1].strip())
            break
    # Read body.
    while len(rest) < clen:
        data = await reader.read(4096)
        if not data:
            return False
        rest += data
    return True


async def worker(results, stop_at):
    n = 0
    try:
        reader, writer = await asyncio.open_connection(HOST, PORT)
        while time.monotonic() < stop_at:
            writer.write(REQ)
            await writer.drain()
            if not await read_response(reader):
                break
            n += 1
    except Exception as e:
        print(f"worker err: {e}", file=sys.stderr)
    results.append(n)


async def run_test(concurrency):
    results = []
    stop_at = time.monotonic() + DURATION
    tasks = [asyncio.ensure_future(worker(results, stop_at)) for _ in range(concurrency)]
    await asyncio.gather(*tasks)
    return sum(results)


def main():
    print(f"Server: {HOST}:{PORT}{PATH}, duration={DURATION}s/level (async keep-alive)")
    print(f"{'concurrency':>11} {'ops/s':>10} {'requests':>10}")
    for c in (1, 4, 8, 16, 32, 64, 128, 256):
        try:
            total = asyncio.run(run_test(c))
            ops_s = total / DURATION
            print(f"{c:>11} {ops_s:>10.0f} {total:>10}")
        except Exception as e:
            print(f"{c:>11} FAILED: {e}")
            break


if __name__ == "__main__":
    main()
