#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Fetches the pinned third-party sources and prebuilt libraries into
# engine/third_party/ (which is not committed). Safe to run again.
#
#   bash tools/fetch_third_party.sh
set -euo pipefail

cd "$(dirname "$0")/.."
dest=engine/third_party
mkdir -p "$dest"

WHISPER_REPO=https://github.com/ggml-org/whisper.cpp
WHISPER_TAG=v1.9.5
WHISPER_SHA=d1be6fde11ac6e0407606b4e42fe72d34add8037

LLAMA_REPO=https://github.com/ggml-org/llama.cpp
LLAMA_TAG=v1.0.0
LLAMA_SHA=b1b770d90d94cad9e9ea5b993bdbc383a522fb28

SPM_REPO=https://github.com/google/sentencepiece
SPM_TAG=v0.2.0
SPM_SHA=17d7580d6407802f85855d2cc9190634e2c95624

# C headers of the ONNX Runtime that sherpa-onnx ships (libonnxruntime.so reports 1.28.2).
ORT_HEADERS_URL=https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/include/onnxruntime/core/session
ORT_HEADERS="onnxruntime_c_api.h:b69a133143c0da61782b2b02cc8a620d21ac139231fd211719776d10385135c1
onnxruntime_ep_c_api.h:db86df0f846b8d3bdbf429a5c76c5805293196575ca3c475878612591cd18e51
onnxruntime_error_code.h:5ce3b054e798eced8d14f5b86e98692fd33470463f96194ce0700a2d53dd8721"

SHERPA_VERSION=v1.13.8
SHERPA_ANDROID_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION-android.tar.bz2
SHERPA_ANDROID_SHA256=2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44
SHERPA_HEADER_URL=https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$SHERPA_VERSION/sherpa-onnx/c-api/c-api.h
SHERPA_HEADER_SHA256=2a1b95084be8fd1deb3228fcad2fd3f7f0258b64582f7402281ec174c7b7f4ce

clone_pinned() { # dir repo tag sha
    local dir=$1 repo=$2 tag=$3 sha=$4
    if [ ! -d "$dir/.git" ]; then
        git clone --depth 1 --branch "$tag" "$repo" "$dir"
    fi
    local have
    have=$(git -C "$dir" rev-parse HEAD)
    if [ "$have" != "$sha" ]; then
        echo "error: $dir is at $have, expected $sha ($tag)" >&2
        exit 1
    fi
    echo "ok  $dir @ $tag"
}

verify() { # file sha256
    echo "$2  $1" | sha256sum -c - >/dev/null || {
        echo "error: checksum mismatch for $1" >&2
        exit 1
    }
}

clone_pinned "$dest/whisper.cpp" "$WHISPER_REPO" "$WHISPER_TAG" "$WHISPER_SHA"
clone_pinned "$dest/llama.cpp" "$LLAMA_REPO" "$LLAMA_TAG" "$LLAMA_SHA"
clone_pinned "$dest/sentencepiece" "$SPM_REPO" "$SPM_TAG" "$SPM_SHA"

mkdir -p "$dest/onnxruntime/include"
for entry in $ORT_HEADERS; do
    name=${entry%%:*}
    file=$dest/onnxruntime/include/$name
    [ -f "$file" ] || curl -fL --retry 3 -o "$file" "$ORT_HEADERS_URL/$name"
    verify "$file" "${entry##*:}"
done
echo "ok  $dest/onnxruntime headers @ v1.28.2"

sherpa=$dest/sherpa-onnx
if [ ! -f "$sherpa/android/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so" ]; then
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    curl -fL --retry 3 -o "$tmp/android.tar.bz2" "$SHERPA_ANDROID_URL"
    verify "$tmp/android.tar.bz2" "$SHERPA_ANDROID_SHA256"
    mkdir -p "$sherpa/android"
    tar -xjf "$tmp/android.tar.bz2" -C "$sherpa/android"
fi
header=$sherpa/include/sherpa-onnx/c-api/c-api.h
if [ ! -f "$header" ]; then
    mkdir -p "$(dirname "$header")"
    curl -fL --retry 3 -o "$header" "$SHERPA_HEADER_URL"
fi
verify "$header" "$SHERPA_HEADER_SHA256"
test -f "$sherpa/android/jniLibs/arm64-v8a/libonnxruntime.so"
echo "ok  $sherpa @ $SHERPA_VERSION"
