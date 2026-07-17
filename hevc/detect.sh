#!/system/bin/sh

BASE_DIR="${HEVC_BASE_DIR:-/dev/hevc}"
VENDOR_ETC="${HEVC_VENDOR_ETC:-/vendor/etc}"
MOUNT_TABLE="${HEVC_MOUNT_TABLE:-/proc/self/mountinfo}"

TARGET_CODECS="$VENDOR_ETC/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="$VENDOR_ETC/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="$VENDOR_ETC/media_profiles_msmnile.xml"
TARGET_SPECS="$VENDOR_ETC/video_system_specs.json"
TARGET_MSMNILE_SPECS="$VENDOR_ETC/media_msmnile/video_system_specs.json"

DIREWOLF_CODECS="$VENDOR_ETC/media_codecs_direwolf.xml"
DIREWOLF_PERFORMANCE="$VENDOR_ETC/media_codecs_performance_direwolf.xml"
DIREWOLF_PROFILES="$VENDOR_ETC/media_profiles_direwolf.xml"
DIREWOLF_SPECS="$VENDOR_ETC/media_direwolf/video_system_specs.json"

target_paths_for_key() {
    case "$1" in
        codecs) echo "$TARGET_CODECS" ;;
        performance) echo "$TARGET_PERFORMANCE" ;;
        profiles) echo "$TARGET_PROFILES" ;;
        specs) echo "$TARGET_SPECS $TARGET_MSMNILE_SPECS" ;;
        *) return 1 ;;
    esac
}

matches_manifest_profile() {
    name="$1"
    source_dir="$BASE_DIR/$name"
    manifest="$source_dir/profile.manifest"
    matched=0

    [ -f "$manifest" ] || return 1
    while IFS='=' read -r key file || [ -n "$key$file" ]; do
        case "$key" in
            ""|\#*) continue ;;
        esac
        case "$file" in
            ""|*/*|*" "*) return 1 ;;
        esac
        source_file="$source_dir/$file"
        [ -f "$source_file" ] || return 1
        targets="$(target_paths_for_key "$key")" || return 1
        for target in $targets; do
            cmp -s "$target" "$source_file" || return 1
        done
        matched=1
    done < "$manifest"

    [ "$matched" = "1" ]
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

for name in ultra max min; do
    if matches_manifest_profile "$name"; then
        echo "variant:$name"
        exit 0
    fi
done

if [ -f "$DIREWOLF_CODECS" ] &&
    [ -f "$DIREWOLF_PERFORMANCE" ] &&
    [ -f "$DIREWOLF_PROFILES" ] &&
    [ -f "$DIREWOLF_SPECS" ] &&
    cmp -s "$TARGET_CODECS" "$DIREWOLF_CODECS" &&
    cmp -s "$TARGET_PERFORMANCE" "$DIREWOLF_PERFORMANCE" &&
    cmp -s "$TARGET_PROFILES" "$DIREWOLF_PROFILES" &&
    cmp -s "$TARGET_SPECS" "$DIREWOLF_SPECS" &&
    cmp -s "$TARGET_MSMNILE_SPECS" "$DIREWOLF_SPECS"; then
    echo "variant:direwolf"
    exit 0
fi

echo "variant:unknown"
echo "$mounted_targets"
