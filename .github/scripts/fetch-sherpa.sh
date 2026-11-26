#!/usr/bin/env bash
# Fetches the offline voice engine (sherpa-onnx 1.13.6, Apache-2.0, ONNX Runtime linked in) for
# arm64 phones and the x86_64 emulator into app/voice-libs/<abi>/libsherpa-onnx-jni.so.
# The archive's SHA-256 is pinned. Usage: fetch-sherpa.sh
set -euo pipefail
VERSION=1.13.6
SHA=5e74af755c96e7167db4bbb7eb3218e3af502c65eea10f9ee6bd685c0344a8a3
url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-v$VERSION-android-static-link-onnxruntime.tar.bz2"
tmp=$(mktemp -d)
curl -fsSL --retry 3 -o "$tmp/s.tar.bz2" "$url"
echo "$SHA  $tmp/s.tar.bz2" | sha256sum -c -
tar xjf "$tmp/s.tar.bz2" -C "$tmp" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so ./jniLibs/x86_64/libsherpa-onnx-jni.so
for abi in arm64-v8a x86_64; do
  mkdir -p "app/voice-libs/$abi"
  cp "$tmp/jniLibs/$abi/libsherpa-onnx-jni.so" "app/voice-libs/$abi/"
done
rm -rf "$tmp"
ls -la app/voice-libs/*/
