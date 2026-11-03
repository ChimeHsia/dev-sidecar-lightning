#!/usr/bin/env python3
"""
生成 Android 侧的内嵌 Node 运行时（P1）。

做三件事：
  1. 从 Termux 镜像取 node(LTS) 与它的依赖 so（官方 nodejs-mobile 停在 Node 12，太老）；
  2. 从 dev-sidecar 的 app.asar 取出原版核心，剔除纯界面依赖；
  3. 拼成 app 需要的 assets/*.zip 与 jniLibs/arm64-v8a/libnodebin.so。

用法：
    # 先运行 Gradle :netcore-js:jsNodeProductionLibraryDistribution
    python port/build-payload.py --resources "<dev-sidecar-resources>"

产物（均已写入 .gitignore，体积约 170MB）：
    app/src/main/assets/nodejs-project.zip   main.js + core/
    app/src/main/assets/node-libs.zip        node 的依赖库
    app/src/main/assets/cacert.pem           CA 包（Termux 的 openssl 默认路径不可用）
    app/src/main/jniLibs/arm64-v8a/libnodebin.so
"""
import argparse
import io
import json
import lzma
import os
import shutil
import struct
import sys
import tarfile
import urllib.request
import zipfile
from kotlin_payload import (
    helper_entries, patch_core_file, PATCH_TARGETS,
)

TUNA = 'https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/pool/main'
NODE_DEB = f'{TUNA}/n/nodejs-lts/nodejs-lts_24.22.1_aarch64.deb'
DEPS = [
    f'{TUNA}/libc/libc%2B%2B/libc%2B%2B_30_aarch64.deb',
    f'{TUNA}/o/openssl/openssl_1%3A3.6.5_aarch64.deb',
    f'{TUNA}/c/c-ares/c-ares_1.34.9_aarch64.deb',
    f'{TUNA}/libi/libicu/libicu_78.3_aarch64.deb',
    f'{TUNA}/libs/libsqlite/libsqlite_3.54.1_aarch64.deb',
    f'{TUNA}/z/zlib/zlib_1.3.2_aarch64.deb',
    f'{TUNA}/libf/libffi/libffi_3.8.0_aarch64.deb',
]
# node 的 DT_NEEDED 用的是 soname，deb 里往往是版本化文件名，需要对齐
ALIAS = {
    'libz.so.1': 'libz.so.1.3.2',
    'libsqlite3.so': 'libsqlite3.so.3.54.1',
    'libicui18n.so.78': 'libicui18n.so.78.3',
    'libicuuc.so.78': 'libicuuc.so.78.3',
    'libicudata.so.78': 'libicudata.so.78.3',
    'libffi.so.8': 'libffi.so',
}
NEEDED = ['libz.so.1', 'libcares.so', 'libsqlite3.so', 'libcrypto.so.3', 'libssl.so.3',
          'libicui18n.so.78', 'libicuuc.so.78', 'libicudata.so.78', 'libc++_shared.so', 'libffi.so.8']

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(ROOT, 'port', '.build')


def download(url, dest):
    if os.path.exists(dest):
        return dest
    print('下载', os.path.basename(dest))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    urllib.request.urlretrieve(url, dest)
    return dest


def read_ar(path):
    """debian .deb = ar 归档，取里面的 data.tar.xz"""
    data = open(path, 'rb').read()
    assert data[:8] == b'!<arch>\n'
    i = 8
    while i < len(data):
        hdr = data[i:i + 60]
        name = hdr[0:16].decode('ascii', 'replace').strip().rstrip('/')
        size = int(hdr[48:58].decode('ascii').strip())
        if name.startswith('data.tar'):
            return data[i + 60:i + 60 + size]
        i += 60 + size + (1 if size % 2 else 0)
    raise RuntimeError('找不到 data.tar：' + path)


def deb_files(path, prefix='data/data/com.termux/files/usr/'):
    out = {}
    tar = tarfile.open(fileobj=io.BytesIO(lzma.decompress(read_ar(path))))
    for m in tar.getmembers():
        n = m.name.lstrip('./')
        if n.startswith(prefix) and m.isfile():
            out[os.path.basename(n)] = tar.extractfile(m).read()
    return out


def extract_asar(resources, dest):
    if os.path.isdir(os.path.join(dest, 'node_modules')):
        return dest
    asar = os.path.join(resources, 'app.asar')
    print('解包', asar)
    f = open(asar, 'rb')
    head = f.read(16)
    json_len = struct.unpack('<I', head[12:16])[0]
    header = json.loads(f.read(json_len).decode('utf-8'))
    base = 16 + json_len

    def walk(node, cur):
        for name, item in node.get('files', {}).items():
            target = os.path.join(cur, name)
            if 'files' in item:
                os.makedirs(target, exist_ok=True)
                walk(item, target)
            elif not item.get('unpacked'):
                os.makedirs(os.path.dirname(target), exist_ok=True)
                f.seek(base + int(item['offset']))
                with open(target, 'wb') as o:
                    o.write(f.read(item['size']))

    walk(header, dest)
    unpacked = os.path.join(resources, 'app.asar.unpacked')
    if os.path.isdir(unpacked):
        shutil.copytree(unpacked, dest, dirs_exist_ok=True)
    return dest


SKIP = {'ant-design-vue', 'ace-builds', '@ant-design', '@parcel', 'jsoneditor', '@vue',
        'sass', 'core-js', '@babel', 'vue', '@koromix', '@starknt', 'keytar'}


def build(args):
    kotlin_entries = helper_entries()
    app = args.app
    project = os.path.join(WORK, 'core')
    if not os.path.isdir(project):
        full = extract_asar(args.resources, os.path.join(WORK, 'full'))
        os.makedirs(os.path.join(project, 'node_modules/@blue-frontier'), exist_ok=True)
        shutil.copytree(os.path.join(full, 'node_modules/@blue-frontier/dev-sidecar'),
                        os.path.join(project, 'node_modules/@blue-frontier/dev-sidecar'))
        shutil.copytree(os.path.join(full, 'node_modules/@blue-frontier/mitmproxy'),
                        os.path.join(project, 'node_modules/@blue-frontier/mitmproxy'))
        for name in os.listdir(os.path.join(full, 'node_modules')):
            if name in SKIP:
                continue
            shutil.copytree(os.path.join(full, 'node_modules', name),
                            os.path.join(project, 'node_modules', name),
                            dirs_exist_ok=True)
        print('核心目录体积 %.0f MB' % (sum(
            os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(project) for f in fs) / 1048576))

    # --- Node 运行时 ---
    debs = os.path.join(WORK, 'debs')
    node_deb = download(NODE_DEB, os.path.join(debs, 'nodejs-lts.deb'))
    libs = {}
    for url in DEPS:
        d = download(url, os.path.join(debs, os.path.basename(url).replace('%2B', 'p').replace('%3A', '_')))
        libs.update(deb_files(d))
    for k, v in list(ALIAS.items()):
        if k not in libs and v in libs:
            libs[k] = libs[v]
    missing = [n for n in NEEDED if n not in libs]
    if missing:
        sys.exit('缺少依赖库：%s' % missing)

    node_bin = deb_files(node_deb)['node']
    jni = os.path.join(app, 'src/main/jniLibs/arm64-v8a')
    os.makedirs(jni, exist_ok=True)
    open(os.path.join(jni, 'libnodebin.so'), 'wb').write(node_bin)

    assets = os.path.join(app, 'src/main/assets')
    os.makedirs(assets, exist_ok=True)
    with zipfile.ZipFile(os.path.join(assets, 'node-libs.zip'), 'w', zipfile.ZIP_DEFLATED) as z:
        for n in NEEDED:
            z.writestr(n, libs[n])

    with zipfile.ZipFile(os.path.join(assets, 'nodejs-project.zip'), 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in kotlin_entries.items():
            z.writestr(name, data)
        for r, _, fs in os.walk(project):
            for f in fs:
                p = os.path.join(r, f)
                archive_name = 'core/' + os.path.relpath(p, project).replace(os.sep, '/')
                if archive_name in PATCH_TARGETS:
                    with open(p, encoding='utf-8') as source:
                        z.writestr(archive_name, patch_core_file(archive_name, source.read()))
                else:
                    z.write(p, archive_name)

    ca = os.path.join(assets, 'cacert.pem')
    if not os.path.exists(ca):
        download('https://curl.se/ca/cacert.pem', ca)
    print('完成：assets 与 jniLibs 已就绪')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--resources', required=True,
                    help='dev-sidecar 安装目录下的 resources')
    ap.add_argument('--app', default=os.path.join(ROOT, 'app'))
    build(ap.parse_args())
