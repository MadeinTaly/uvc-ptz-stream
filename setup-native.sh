#!/usr/bin/env bash
# Clona e configura i sorgenti terze-parti del modulo nativo (libusb + libuvc),
# NON versionati nel repo (vedi .gitignore). Idempotente: fa solo cio' che manca.
# Funziona su Linux e su Windows (git-bash). Richiesto prima della prima build
# (build.sh lo invoca da solo se le directory mancano).
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -d libusb ]]; then
  echo ">> clone libusb"
  git clone --depth 1 https://github.com/libusb/libusb
fi
if [[ ! -d libuvc ]]; then
  echo ">> clone libuvc"
  git clone --depth 1 https://github.com/libuvc/libuvc
fi

# libusb: version_describe.h e' generato dal build autotools/cmake upstream;
# per il nostro build CMake basta la define vuota (core.c la include).
if [[ ! -f libusb/libusb/version_describe.h ]]; then
  echo ">> genera libusb/libusb/version_describe.h"
  echo '#define LIBUSB_DESCRIBE ""' > libusb/libusb/version_describe.h
fi

# libuvc: config header normalmente generato da CMake upstream. Versione letta
# dal CMakeLists di libuvc; LIBUVC_HAS_JPEG NON definito (la cam e' solo YUYV,
# il JPEG lo fa Android con YuvImage).
if [[ ! -f libuvc/include/libuvc/libuvc_config.h ]]; then
  echo ">> genera libuvc/include/libuvc/libuvc_config.h"
  VER="$(sed -nE 's/.*VERSION[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' libuvc/CMakeLists.txt | head -1)"
  VER="${VER:-0.0.7}"
  MAJ="${VER%%.*}"; REST="${VER#*.}"; MIN="${REST%%.*}"; PAT="${VER##*.}"
  cat > libuvc/include/libuvc/libuvc_config.h <<EOF
#ifndef LIBUVC_CONFIG_H
#define LIBUVC_CONFIG_H

#define LIBUVC_VERSION_MAJOR $MAJ
#define LIBUVC_VERSION_MINOR $MIN
#define LIBUVC_VERSION_PATCH $PAT
#define LIBUVC_VERSION_STR "$VER"
#define LIBUVC_VERSION_INT (($MAJ << 16) | ($MIN << 8) | ($PAT))

#define LIBUVC_VERSION_GTE(major, minor, patch)                         \\
  (LIBUVC_VERSION_INT >= (((major) << 16) | ((minor) << 8) | (patch)))

/* niente LIBUVC_HAS_JPEG: cam solo YUYV, JPEG via YuvImage Android */

#endif // !def(LIBUVC_CONFIG_H)
EOF
fi

# PATCH OBBLIGATORIA: libuvc usa timeout=0 (INFINITO)
# su libusb_control_transfer; su USB instabile un SET PTZ pianta il thread
# ptz-sender (video vivo, cam ferma). Forza 1000 ms.
if grep -qE '^    0\);$' libuvc/src/ctrl-gen.c; then
  N="$(grep -cE '^    0\);$' libuvc/src/ctrl-gen.c)"
  sed -i -E 's/^    0\);$/    1000);/' libuvc/src/ctrl-gen.c
  echo ">> patch timeout control transfer 0->1000ms in ctrl-gen.c ($N occorrenze)"
fi

echo ">> setup nativo OK (libusb + libuvc pronti per il build CMake)"
