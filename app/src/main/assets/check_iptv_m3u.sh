#!/bin/sh
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 nullstride
# Independent POSIX playlist inspector. Input is treated as data, never shell code.
set -eu
input=iptv.m3u
destination=.
seconds=5
proxy=
scan=false
subnet=239.3.1
range=1-254
ports=${SCAN_PORTS:-1234 8000}
screenshots=true
while [ "$#" -gt 0 ]; do
    case "$1" in
        -f|--file) input=$2; shift 2 ;;
        -o|--output) destination=$2; shift 2 ;;
        -t|--timeout) seconds=$2; shift 2 ;;
        -u|--udpxy) proxy=$2; shift 2 ;;
        --scan|-s) scan=true; shift ;;
        --subnet) subnet=$2; shift 2 ;;
        --range) range=$2; shift 2 ;;
        --no-screenshot) screenshots=false; shift ;;
        --mode) shift 2 ;; # Compatibility: SCAN_PORTS selects the actual ports.
        -h|--help)
            echo 'Usage: check_iptv_m3u.sh -f list.m3u [-o output] [-t seconds] [-u udpxy]'
            echo '       [--scan --subnet 239.3.1 --range 1-254] [--no-screenshot]'
            echo 'SCAN_PORTS supplies a space-separated port list. Probe only networks you may access.'
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done
case "$seconds" in ''|*[!0-9]*) echo 'Timeout must be a positive integer' >&2; exit 2 ;; esac
[ "$seconds" -ge 1 ] && [ "$seconds" -le 120 ] || exit 2
for port in $ports; do
    case "$port" in ''|*[!0-9]*) exit 2 ;; esac
    [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || exit 2
done
[ -f "$input" ] || { echo 'Input playlist does not exist' >&2; exit 2; }
output="$destination/iptv_$(date +%Y%m%d_%H%M%S)_$$"
mkdir -p "$output/screenshots"
printf '\357\273\277编号,频道名称,video track,audio track,screenshot_name,连接flag,分辨率flag\n' > "$output/results.csv"
printf '#EXTM3U\n' > "$output/modified_iptv.m3u"
: > "$output/mismatch.log"
: > "$output/script.log"
trap 'rm -f "$output/.probe" "$output/.streams" "$output/.input"' EXIT HUP INT TERM
awk '
    /^#EXTINF:/ { name=$0; sub(/^.*,/ , "", name); gsub(/\r|\t/, " ", name); next }
    /^(http|https|rtp|udp):\/\// {
        sub(/\r$/, ""); if (name == "") name="Channel";
        print name "\t" $0; name=""
    }
' "$input" > "$output/.input"

csv_field() { printf '%s' "$1" | sed 's/"/""/g'; }
inspect() {
    name=$1
    original=$2
    request=$original
    case "$original" in
        rtp://*|udp://*)
            if [ -n "$proxy" ]; then
                case "$proxy" in http://*|https://*) base=$proxy ;; *) base="http://$proxy" ;; esac
                request="${base%/}/rtp/${original#*://}"
            fi ;;
    esac
    connected=false
    resolution=错误
    video=
    audio=
    : > "$output/.probe"
    if command -v ffprobe >/dev/null 2>&1; then
        if command -v timeout >/dev/null 2>&1; then
            timeout "$seconds" ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,r_frame_rate,sample_rate,channels \
                -of compact=p=0:nk=0 "$request" > "$output/.streams" 2>/dev/null || true
        else
            ffprobe -v error -rw_timeout "${seconds}000000" -show_entries stream=codec_type,codec_name,width,height,r_frame_rate,sample_rate,channels \
                -of compact=p=0:nk=0 "$request" > "$output/.streams" 2>/dev/null || true
        fi
        if [ -s "$output/.streams" ]; then
            connected=true
            video=$(awk -F '|' '/codec_type=video/ { print; exit }' "$output/.streams")
            audio=$(awk -F '|' '/codec_type=audio/ { print; exit }' "$output/.streams")
            height=$(printf '%s\n' "$video" | tr '|' '\n' | sed -n 's/^height=//p')
            case "$height" in ''|*[!0-9]*) resolution=其它 ;;
                *) if [ "$height" -ge 2160 ]; then resolution=4K
                   elif [ "$height" -ge 720 ]; then resolution=高清
                   elif [ "$height" -gt 0 ]; then resolution=标清
                   else resolution=其它; fi ;;
            esac
        fi
    elif command -v curl >/dev/null 2>&1; then
        case "$request" in http://*|https://*)
            curl -fsSL --max-time "$seconds" --range 0-65535 "$request" -o "$output/.probe" 2>/dev/null || true
            if [ -s "$output/.probe" ]; then
                first=$(od -An -tu1 -N1 "$output/.probe" | tr -d ' ')
                if [ "$first" = 71 ] || head -c 7 "$output/.probe" | grep -q '^#EXTM3U'; then
                    connected=true; resolution=其它
                fi
            fi ;;
        esac
    fi
    # The Android reader consumes these tokens; all output artifacts remain local.
    clean_name=$(printf '%s' "$name" | tr '"\r\n' "'  ")
    printf '[PROGRESS] name="%s" url="%s" connected=%s resolution=%s video="%s" audio="%s"\n' \
        "$clean_name" "$original" "$connected" "$resolution" "$video" "$audio"
    printf '%s,"%s","%s","%s",,%s,%s\n' "$index" "$(csv_field "$name")" "$(csv_field "$video")" "$(csv_field "$audio")" \
        "$connected" "$resolution" >> "$output/results.csv"
    if [ "$connected" = true ]; then
        printf '#EXTINF:-1,%s\n%s\n' "$name" "$original" >> "$output/modified_iptv.m3u"
        case "$name" in
            *4K*) expected=4K ;;
            *高清*) expected=高清 ;;
            *标清*) expected=标清 ;;
            *) expected=$resolution ;;
        esac
        if [ "$resolution" != 其它 ] && [ "$expected" != "$resolution" ]; then
            printf '%s: %s -> %s\n' "$name" "$expected" "$resolution" >> "$output/mismatch.log"
        fi
        if [ "$screenshots" = true ] && command -v timeout >/dev/null 2>&1 && command -v ffmpeg >/dev/null 2>&1; then
            timeout "$seconds" ffmpeg -loglevel error -i "$request" -frames:v 1 "$output/screenshots/$index.png" 2>/dev/null || true
        fi
    fi
}
index=0
tab=$(printf '\t')
while IFS="$tab" read -r name original; do
    index=$((index + 1))
    inspect "$name" "$original"
done < "$output/.input"
if [ "$scan" = true ]; then
    printf '%s\n' "$subnet" | awk -F. 'NF != 3 {exit 1} {for (i=1;i<=3;i++) if ($i !~ /^[0-9]+$/ || $i>255) exit 1}' || exit 2
    first=${range%-*}; last=${range#*-}
    case "$first:$last" in *[!0-9:]*|:*) exit 2 ;; esac
    [ "$first" -ge 1 ] && [ "$last" -le 254 ] && [ "$first" -le "$last" ] || exit 2
    host=$first
    while [ "$host" -le "$last" ]; do
        for port in $ports; do
            index=$((index + 1))
            inspect "扫描频道$host:$port" "rtp://$subnet.$host:$port"
        done
        printf '[SCAN_PROGRESS] host=%s\n' "$host"
        host=$((host + 1))
    done
fi
printf 'Results: %s\n' "$output"
