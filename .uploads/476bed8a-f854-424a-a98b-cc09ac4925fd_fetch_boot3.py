#!/usr/bin/env python3
"""payload-dumper + HTTP Range 虚拟文件，只提取 boot.img。"""
import io, subprocess, sys, urllib.request
from payload_dumper.dumper import Dumper

URL = sys.argv[1]

class RangeFile(io.RawIOBase):
    OFFSET = 61  # zip 内 payload.bin 的 local header + name + extra
    def __init__(self, url):
        self.url = url
        req = urllib.request.Request(url, method="HEAD")
        self.size = int(urllib.request.urlopen(req, timeout=60).headers["Content-Length"]) - self.OFFSET
        self.pos = 0
    def seekable(self): return True
    def readable(self): return True
    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos
    def tell(self): return self.pos
    def readinto(self, b):
        if self.pos >= self.size: return 0
        end = min(self.pos + len(b), self.size) - 1
        r = subprocess.run(["curl", "-sS", "--retry", "5", "--retry-delay", "2",
                            "--retry-all-errors", "-L",
                            "-r", f"{self.pos + self.OFFSET}-{end + self.OFFSET}", self.url], capture_output=True)
        got = len(r.stdout)
        b[:got] = r.stdout
        self.pos += got
        return got

import os
os.makedirs("/home/lingxi/workspace/boot_out", exist_ok=True)
f = RangeFile(URL)
print(f"payload {f.size/1e9:.2f} GB，按需 Range 读取")
d = Dumper(payloadfile=f, out="/home/lingxi/workspace/boot_out", images="boot", workers=1)
d.run()
print("完成")
