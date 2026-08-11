#!/system/bin/sh

VENDOR_ETC="${HEVC_VENDOR_ETC:-/vendor/etc}"
MOUNT_TABLE="${HEVC_MOUNT_TABLE:-/proc/self/mountinfo}"

TARGET_CODECS="$VENDOR_ETC/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="$VENDOR_ETC/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="$VENDOR_ETC/media_profiles_msmnile.xml"
TARGET_SPECS="$VENDOR_ETC/video_system_specs.json"
TARGET_MSMNILE_SPECS="$VENDOR_ETC/media_msmnile/video_system_specs.json"

is_target_mounted() {
    [ -r "$MOUNT_TABLE" ] && grep -F "$1" "$MOUNT_TABLE" >/dev/null 2>&1
}

unmount_target() {
    target="$1"
    attempts=0

    while is_target_mounted "$target"; do
        attempts=$((attempts + 1))
        echo "phase:unmount:$target:attempt:$attempts"
        [ "$attempts" -le 3 ] || return 1
        umount -l "$target" </dev/null || return 1
    done
}

kill_if_running() {
    pid="$(pidof "$1" 2>/dev/null)"
    if [ -n "$pid" ]; then
        # Multiple PIDs are intentionally word-split here.
        kill -9 $pid 2>/dev/null || true
    fi
}

echo "phase:restore_start"
result=0
for target in \
    "$TARGET_CODECS" \
    "$TARGET_PERFORMANCE" \
    "$TARGET_PROFILES" \
    "$TARGET_SPECS" \
    "$TARGET_MSMNILE_SPECS"; do
    unmount_target "$target" || result=1
done

echo "phase:restore_restart_media"
kill_if_running media.hwcodec
kill_if_running mediaserver

if [ "$result" -ne 0 ]; then
    echo "status:error"
    echo "reason:restore_unmount_failed"
    exit 1
fi

echo "status:ok"
echo "variant:msmnile"
