#!/bin/sh
# SPDX-License-Identifier: MIT
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
scratch=$(mktemp -d /tmp/kankan-source-test.XXXXXX)
cleanup() { case "$scratch" in /tmp/kankan-source-test.*) rm -rf -- "$scratch" ;; esac; }
trap cleanup EXIT HUP INT TERM
mkdir "$scratch/bin"
cat > "$scratch/bin/ffprobe" <<'STUB'
#!/bin/sh
case "$*" in *broken*) exit 1 ;; esac
printf 'codec_name=hevc|codec_type=video|width=3840|height=2160|r_frame_rate=50/1\n'
printf 'codec_name=mp2|codec_type=audio|sample_rate=48000|channels=2\n'
STUB
chmod +x "$scratch/bin/ffprobe"
cat > "$scratch/input.m3u" <<'FIXTURE'
#EXTM3U
#EXTINF:-1,测试频道4K
http://example.test/good
#EXTINF:-1,无效频道
http://example.test/broken
#EXTINF:-1,Channel $(touch SHOULD_NOT_EXIST)
http://example.test/good;touch SHOULD_NOT_EXIST
FIXTURE
PATH="$scratch/bin:$PATH" sh "$root/tools/playlist-inspect.sh" -f "$scratch/input.m3u" -o "$scratch/output with spaces" --no-screenshot > "$scratch/progress"
grep -q 'connected=true resolution=4K' "$scratch/progress"
grep -q 'connected=false resolution=错误' "$scratch/progress"
[ "$(grep -c '^\[PROGRESS\]' "$scratch/progress")" -eq 3 ]
result=$(find "$scratch/output with spaces" -name modified_iptv.m3u)
grep -q 'example.test/good' "$result"
if grep -q 'example.test/broken' "$result"; then exit 1; fi
[ ! -e SHOULD_NOT_EXIST ]
if sh "$root/tools/playlist-inspect.sh" -f "$scratch/input.m3u" -t invalid >/dev/null 2>&1; then exit 1; fi
cmp "$root/tools/playlist-inspect.sh" "$root/check_iptv_m3u.sh"
cmp "$root/tools/playlist-inspect.sh" "$root/app/src/main/assets/check_iptv_m3u.sh"
echo 'Playlist inspector: stream results, failures, quoting, argument validation, and packaged copies passed.'
