#!/usr/bin/env python3
"""Run N independent async client processes against the server, report combined ops/s.
If combined throughput scales with N processes, the single-client was the bottleneck.
"""
import asyncio
import multiprocessing as mp
import os
import sys
import time

HOST = "127.0.0.1"
PORT = 8080
PATH = "/ping"
REQ = (f"GET {PATH} HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nConnection: keep-alive\r\n\r\n").encode()
DURATION = 12
CONCURRENCY_PER_PROC = 8


async def read_response(reader):
    buf = b""
    while b"\r\n\r\n" not in buf:
        data = await reader.read(4096)
        if not data:
            return False
        buf += data
    head, _, rest = buf.partition(b"\r\n\r\n")
    clen = 0
    for line in head.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            clen = int(line.split(b":", 1)[1].strip())
            break
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
    except Exception:
        pass
    results.append(n)


async def run_one():
    results = []
    stop_at = time.monotonic() + DURATION
    tasks = [asyncio.ensure_future(worker(results, stop_at)) for _ in range(CONCURRENCY_PER_PROC)]
    await asyncio.gather(*tasks)
    return sum(results)


def proc_worker(q):
    total = asyncio.run(run_one())
    q.put(total)


def main():
    nprocs = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    print(f"{nprocs} client processes x {CONCURRENCY_PER_PROC} conns each, {DURATION}s")
    mp.set_start_method("spawn", force=True)
    q = mp.Queue()
    procs = [mp.Process(target=proc_worker, args=(q,)) for _ in range(nprocs)]
    for p in procs:
        p.start()
    total = 0
    for _ in procs:
        total += q.get(timeout=DURATION + 30)
    for p in procs:
        p.join()
    print(f"combined: {total / DURATION:.0f} ops/s  ({total} requests)")


if __name__ == "__main__":
    main()
