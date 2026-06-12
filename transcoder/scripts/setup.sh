#!/bin/bash
# Fetch RAFT-Stereo code + Middlebury weights. Run once from transcoder/.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -d third_party/RAFT-Stereo ]; then
    git clone --depth 1 https://github.com/princeton-vl/RAFT-Stereo.git third_party/RAFT-Stereo
fi

if [ ! -f models/raftstereo-middlebury.pth ]; then
    mkdir -p models
    curl -LsS -o /tmp/raft_models.zip "https://www.dropbox.com/s/ftveifyqcomiwaq/models.zip?dl=1"
    unzip -o /tmp/raft_models.zip -d models/
    rm -f /tmp/raft_models.zip
fi

echo "done. install deps with: uv sync --extra ml"
