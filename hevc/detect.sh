#!/system/bin/sh

BASE_DIR="${HEVC_BASE_DIR:-/dev/hevc}"
VENDOR_ETC="${HEVC_VENDOR_ETC:-/vendor/etc}"
MOUNT_TABLE="${HEVC_MOUNT_TABLE:-/proc/self/mountinfo}"

TARGET_CODECS="$VENDOR_ETC/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="$VENDOR_ETC/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="$VENDOR_ETC/media_profiles_msmnile.xml"
TARGET_SPECS="$VENDOR_ETC/video_system_specs.json"
TARGET_MSMNILE_SPECS="$VENDOR_ETC/media_msmnile/video_system_specs.json"

manifest_mount_count() {
    manifest="$BASE_DIR/$1/profile.manifest"
    count=0

    [ -f "$manifest" ] || return 1
    while IFS='=' read -r key file || [ -n "$key$file" ]; do
        case "$key" in
            ""|\#*) continue ;;
            codecs|performance|profiles) count=$((count + 1)) ;;
            specs) count=$((count + 2)) ;;
            *) return 1 ;;
        esac
    done < "$manifest"
    echo "$count"
}

for target in \
    "$TARGET_CODECS" \
    "$TARGET_PERFORMANCE" \
    "$TARGET_PROFILES" \
    "$TARGET_SPECS" \
    "$TARGET_MSMNILE_SPECS"; do
    if [ ! -f "$target" ]; then
        echo "variant:unknown"
        echo "missing:$target"
        exit 0
    fi
done

if [ ! -r "$MOUNT_TABLE" ]; then
    echo "variant:unknown"
    echo "reason:mount_table_unavailable"
    exit 0
fi

mounted_targets="$(grep -E "/vendor/etc/(media_codecs_msmnile.xml|media_codecs_performance_msmnile.xml|media_profiles_msmnile.xml|video_system_specs.json|media_msmnile/video_system_specs.json)" "$MOUNT_TABLE" 2>/dev/null || true)"
if [ -z "$mounted_targets" ]; then
    echo "variant:msmnile"
    exit 0
fi

mounted_count="$(printf '%s\n' "$mounted_targets" | grep -c .)"
for name in ultra max min; do
    profile_mounts="$(printf '%s\n' "$mounted_targets" | grep -F "/hevc/$name/" || true)"
    [ "$profile_mounts" = "$mounted_targets" ] || continue
    expected_count="$(manifest_mount_count "$name")" || continue
    if [ "$mounted_count" = "$expected_count" ]; then
        echo "variant:$name"
        exit 0
    fi
done

direwolf_mounts="$(printf '%s\n' "$mounted_targets" | grep -F 'direwolf' || true)"
if [ "$direwolf_mounts" = "$mounted_targets" ] && [ "$mounted_count" = "5" ]; then
    echo "variant:direwolf"
    exit 0
fi

echo "variant:unknown"
echo "$mounted_targets"
