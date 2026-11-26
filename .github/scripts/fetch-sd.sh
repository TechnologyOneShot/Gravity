#!/usr/bin/env bash
# Fetches stable-diffusion.cpp for the on-phone picture engine at the commit Gravity was tested
# with (only its ggml submodule: no web UI, WebP/WebM or CUDA code). Usage: fetch-sd.sh <dir>
set -euo pipefail
SD_COMMIT=52491bd
dest="${1:-engine-image/src/main/cpp/stable-diffusion.cpp}"
git clone -q https://github.com/leejet/stable-diffusion.cpp "$dest"
git -C "$dest" checkout -q "$SD_COMMIT"
git -C "$dest" submodule update -q --init ggml
echo "stable-diffusion.cpp $(git -C "$dest" log -1 --format='%h %cd') at $dest"
