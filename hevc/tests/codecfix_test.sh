#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
HEVC_DIR="$(dirname "$SCRIPT_DIR")"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/atlas-codecfix-test.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' 0 HUP INT TERM

BIN_DIR="$TMP_ROOT/bin"
STATE_DIR="$TMP_ROOT/state"
STATE_FILE="$STATE_DIR/mounts"
VENDOR_DIR="$TMP_ROOT/vendor/etc"
BASE_DIR="$TMP_ROOT/hevc"
mkdir -p \
    "$BIN_DIR" \
    "$STATE_DIR/backups" \
    "$VENDOR_DIR/media_msmnile" \
    "$BASE_DIR/min" \
    "$BASE_DIR/max"
: > "$STATE_FILE"

cat > "$BIN_DIR/mount" <<'EOF'
#!/bin/sh
set -eu
if [ "$#" -eq 0 ]; then
    while IFS='|' read -r source target backup; do
        [ -n "$target" ] && echo "$source on $target type none (rw,bind)"
    done < "$STATE_FILE"
    exit 0
fi

[ "$1" = "--bind" ] || exit 2
source_file="$2"
target_file="$3"
[ "${FAIL_BIND_TARGET:-}" != "$target_file" ] || exit 32
if [ "${SIGNAL_BIND_TARGET:-}" = "$target_file" ]; then
    kill -TERM "$PPID"
    sleep 1
    exit 143
fi

key="$(printf '%s' "$target_file" | tr '/.' '__')"
backup="$STATE_DIR/backups/$key"
if ! grep -F "|$target_file|" "$STATE_FILE" >/dev/null 2>&1; then
    cp "$target_file" "$backup"
fi
cp "$source_file" "$target_file"
printf '%s|%s|%s\n' "$source_file" "$target_file" "$backup" >> "$STATE_FILE"
EOF

cat > "$BIN_DIR/umount" <<'EOF'
#!/bin/sh
set -eu
lazy=0
if [ "${1:-}" = "-l" ]; then
    lazy=1
    shift
fi
target_file="$1"
if [ "$lazy" = "1" ] && [ "${HANG_LAZY_UMOUNT_TARGET:-}" = "$target_file" ]; then
    trap 'exit 124' TERM
    while :; do sleep 1; done
fi
line="$(grep -F "|$target_file|" "$STATE_FILE" | tail -n 1)" || exit 1
old_ifs="$IFS"
IFS='|'
set -- $line
IFS="$old_ifs"
backup="$3"
cp "$backup" "$target_file"
grep -Fv "|$target_file|" "$STATE_FILE" > "$STATE_FILE.tmp" || true
mv "$STATE_FILE.tmp" "$STATE_FILE"
exit 0
EOF

cat > "$BIN_DIR/chcon" <<'EOF'
#!/bin/sh
exit 0
EOF

cat > "$BIN_DIR/pidof" <<'EOF'
#!/bin/sh
exit 1
EOF

cat > "$BIN_DIR/kill" <<'EOF'
#!/bin/sh
exit 0
EOF

cat > "$BIN_DIR/id" <<'EOF'
#!/bin/sh
[ "${1:-}" = "-u" ] || exit 2
echo 0
EOF

cat > "$BIN_DIR/getprop" <<'EOF'
#!/bin/sh
case "${1:-}" in
    ro.board.platform) echo "${TEST_PLATFORM:-msmnile}" ;;
    ro.soc.model) echo Qualcomm ;;
    ro.product.device|ro.product.vendor.device) echo atlas ;;
    ro.product.manufacturer) echo Geely ;;
    *) echo test ;;
esac
EOF

chmod 0755 \
    "$BIN_DIR/mount" \
    "$BIN_DIR/umount" \
    "$BIN_DIR/chcon" \
    "$BIN_DIR/pidof" \
    "$BIN_DIR/kill" \
    "$BIN_DIR/id" \
    "$BIN_DIR/getprop"

TARGET_CODECS="$VENDOR_DIR/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="$VENDOR_DIR/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="$VENDOR_DIR/media_profiles_msmnile.xml"
TARGET_SPECS="$VENDOR_DIR/video_system_specs.json"
TARGET_MSMNILE_SPECS="$VENDOR_DIR/media_msmnile/video_system_specs.json"

printf '%s\n' 'stock-codecs c2.qti.hevc' > "$TARGET_CODECS"
printf '%s\n' 'stock-performance' > "$TARGET_PERFORMANCE"
printf '%s\n' 'stock-profiles hevc' > "$TARGET_PROFILES"
printf '%s\n' 'stock-specs' > "$TARGET_SPECS"
printf '%s\n' 'stock-specs-msmnile' > "$TARGET_MSMNILE_SPECS"

printf '%s\n' 'min-codecs c2.qti.hevc' > "$BASE_DIR/min/media_codecs_min.xml"
printf '%s\n' 'codecs=media_codecs_min.xml' > "$BASE_DIR/min/profile.manifest"
printf '%s\n' 'max-codecs c2.qti.hevc' > "$BASE_DIR/max/media_codecs_max.xml"
printf '%s\n' 'max-performance' > "$BASE_DIR/max/media_codecs_performance_max.xml"
cat > "$BASE_DIR/max/profile.manifest" <<'EOF'
codecs=media_codecs_max.xml
performance=media_codecs_performance_max.xml
EOF

export PATH="$BIN_DIR:$PATH"
export STATE_DIR STATE_FILE
export HEVC_BASE_DIR="$BASE_DIR"
export HEVC_VENDOR_ETC="$VENDOR_DIR"
export HEVC_MOUNT_TABLE="$STATE_FILE"
export SKIP_PREFLIGHT=1

assert_contains() {
    needle="$1"
    file="$2"
    grep -F "$needle" "$file" >/dev/null || {
        echo "Expected '$needle' in $file" >&2
        exit 1
    }
}

assert_file_text() {
    expected="$1"
    file="$2"
    actual="$(sed -n '1p' "$file")"
    [ "$actual" = "$expected" ] || {
        echo "Expected '$expected' in $file, got '$actual'" >&2
        exit 1
    }
}

sh "$HEVC_DIR/codecfix.sh" min > "$TMP_ROOT/min.out"
assert_contains 'status:ok' "$TMP_ROOT/min.out"
assert_contains 'variant:min' "$TMP_ROOT/min.out"
assert_file_text 'min-codecs c2.qti.hevc' "$TARGET_CODECS"
assert_file_text 'stock-performance' "$TARGET_PERFORMANCE"
[ "$(wc -l < "$STATE_FILE" | tr -d ' ')" = "1" ]

sh "$HEVC_DIR/detect.sh" > "$TMP_ROOT/detect-min.out"
assert_contains 'variant:min' "$TMP_ROOT/detect-min.out"

sh "$HEVC_DIR/preflight.sh" > "$TMP_ROOT/preflight-min.out"
assert_contains 'status:supported' "$TMP_ROOT/preflight-min.out"
assert_contains 'auto_apply:yes' "$TMP_ROOT/preflight-min.out"

TEST_PLATFORM=unknown sh "$HEVC_DIR/preflight.sh" > "$TMP_ROOT/preflight-risky.out"
assert_contains 'status:risky' "$TMP_ROOT/preflight-risky.out"
assert_contains 'reason:platform_not_msmnile' "$TMP_ROOT/preflight-risky.out"

export HEVC_COMMAND_TIMEOUT_SECONDS=1
export HANG_LAZY_UMOUNT_TARGET="$TARGET_CODECS"
sh "$HEVC_DIR/codecfix.sh" restore > "$TMP_ROOT/restore.out"
unset HANG_LAZY_UMOUNT_TARGET HEVC_COMMAND_TIMEOUT_SECONDS
assert_contains 'variant:msmnile' "$TMP_ROOT/restore.out"
assert_contains "phase:unmount:$TARGET_CODECS:attempt:1" "$TMP_ROOT/restore.out"
assert_file_text 'stock-codecs c2.qti.hevc' "$TARGET_CODECS"
[ ! -s "$STATE_FILE" ]

export FAIL_BIND_TARGET="$TARGET_PERFORMANCE"
if sh "$HEVC_DIR/codecfix.sh" max > "$TMP_ROOT/max.out" 2>&1; then
    echo "Expected max apply to fail" >&2
    exit 1
fi
unset FAIL_BIND_TARGET
assert_contains 'reason:bind_failed:performance' "$TMP_ROOT/max.out"
assert_contains 'rollback:ok' "$TMP_ROOT/max.out"
assert_file_text 'stock-codecs c2.qti.hevc' "$TARGET_CODECS"
assert_file_text 'stock-performance' "$TARGET_PERFORMANCE"
[ ! -s "$STATE_FILE" ]

export SIGNAL_BIND_TARGET="$TARGET_PERFORMANCE"
if sh "$HEVC_DIR/codecfix.sh" max > "$TMP_ROOT/signal.out" 2>&1; then
    echo "Expected interrupted max apply to fail" >&2
    exit 1
fi
unset SIGNAL_BIND_TARGET
assert_contains 'reason:interrupted' "$TMP_ROOT/signal.out"
assert_file_text 'stock-codecs c2.qti.hevc' "$TARGET_CODECS"
assert_file_text 'stock-performance' "$TARGET_PERFORMANCE"
[ ! -s "$STATE_FILE" ]

echo "codecfix shell tests passed"
