#!/usr/bin/env python3
"""
从 dev-sidecar 的 app.asar 解包出可运行目录（含 app.asar.unpacked 的补充文件）。

用法：
    python port/extract-asar.py "<dev-sidecar-resources>" "<output-directory>"

为什么需要：dev-sidecar 的核心 JS 在 asar 里，asar 只是个拼接档，
解包后就能用普通 Node 直接跑，无需 Electron。
"""
import json
import os
import shutil
import struct
import sys


def extract(resources, out):
    asar = os.path.join(resources, 'app.asar')
    f = open(asar, 'rb')
    head = f.read(16)
    # 结构: u32(=4) | u32(header pickle 长度) | u32(json 长度) | json
    json_len = struct.unpack('<I', head[12:16])[0]
    f.seek(16)
    header = json.loads(f.read(json_len).decode('utf-8'))
    base = 16 + json_len

    count = 0

    def walk(node, cur):
        nonlocal count
        for name, item in node.get('files', {}).items():
            target = os.path.join(cur, name)
            if 'files' in item:
                os.makedirs(target, exist_ok=True)
                walk(item, target)
            else:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                if item.get('unpacked'):
                    continue
                f.seek(base + int(item['offset']))
                with open(target, 'wb') as o:
                    o.write(f.read(item['size']))
                count += 1

    walk(header, out)

    # 补上 asar 外的大文件（原生模块等）
    unpacked = os.path.join(resources, 'app.asar.unpacked')
    if os.path.isdir(unpacked):
        shutil.copytree(unpacked, out, dirs_exist_ok=True)

    print(f'解包完成: {count} 个文件 → {out}')


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    extract(sys.argv[1], sys.argv[2])
