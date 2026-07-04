#!/system/bin/sh

BASE_DIR="/dev/hevc"

TARGET_CODECS="/vendor/etc/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="/vendor/etc/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="/vendor/etc/media_profiles_msmnile.xml"
TARGET_SPECS="/vendor/etc/video_system_specs.json"
TARGET_MSMNILE_SPECS="/vendor/etc/media_msmnile/video_system_specs.json"

DIREWOLF_CODECS="/vendor/etc/media_codecs_direwolf.xml"
DIREWOLF_PERFORMANCE="/vendor/etc/media_codecs_performance_direwolf.xml"
DIREWOLF_PROFILES="/vendor/etc/media_profiles_direwolf.xml"
DIREWOLF_SPECS="/vendor/etc/media_direwolf/video_system_specs.json"

kill_if_running() {
    pid="$(pidof "$1")"
    if [ -n "$pid" ]; then
        kill -9 $pid
    fi
}

restart_media() {
    kill_if_running media.swcodec
    kill_if_running media.hwcodec
    kill_if_running media.codec
    kill_if_running mediaserver
}

restore_default() {
    umount "$TARGET_CODECS" 2>/dev/null
    umount "$TARGET_PERFORMANCE" 2>/dev/null
    umount "$TARGET_PROFILES" 2>/dev/null
    umount "$TARGET_SPECS" 2>/dev/null
    umount "$TARGET_MSMNILE_SPECS" 2>/dev/null
    restart_media
}

apply_direwolf() {
    umount "$TARGET_CODECS" 2>/dev/null
    umount "$TARGET_PERFORMANCE" 2>/dev/null
    umount "$TARGET_PROFILES" 2>/dev/null
    umount "$TARGET_SPECS" 2>/dev/null
    umount "$TARGET_MSMNILE_SPECS" 2>/dev/null

    mount --bind "$DIREWOLF_CODECS" "$TARGET_CODECS"
    mount --bind "$DIREWOLF_PERFORMANCE" "$TARGET_PERFORMANCE"
    mount --bind "$DIREWOLF_PROFILES" "$TARGET_PROFILES"
    mount --bind "$DIREWOLF_SPECS" "$TARGET_SPECS"
    mount --bind "$DIREWOLF_SPECS" "$TARGET_MSMNILE_SPECS"
    restart_media
}

require_config() {
    if [ ! -f "$2" ]; then
        echo "Missing $1 config in $SRC_DIR"
        exit 1
    fi
}

apply_folder() {
    NAME="$1"
    SRC_DIR="$BASE_DIR/$NAME"

    CODECS_SRC="$SRC_DIR/media_codecs_$NAME.xml"
    PERFORMANCE_SRC="$SRC_DIR/media_codecs_performance_$NAME.xml"
    PROFILES_SRC="$SRC_DIR/media_profiles_$NAME.xml"
    SPECS_SRC="$SRC_DIR/video_system_specs_$NAME.json"

    require_config media_codecs "$CODECS_SRC"
    require_config media_codecs_performance "$PERFORMANCE_SRC"
    require_config media_profiles "$PROFILES_SRC"
    require_config video_system_specs "$SPECS_SRC"

    chcon u:object_r:vendor_configs_file:s0 "$SRC_DIR"/*

    umount "$TARGET_CODECS" 2>/dev/null
    umount "$TARGET_PERFORMANCE" 2>/dev/null
    umount "$TARGET_PROFILES" 2>/dev/null
    umount "$TARGET_SPECS" 2>/dev/null
    umount "$TARGET_MSMNILE_SPECS" 2>/dev/null

    mount --bind "$CODECS_SRC" "$TARGET_CODECS"
    mount --bind "$PERFORMANCE_SRC" "$TARGET_PERFORMANCE"
    mount --bind "$PROFILES_SRC" "$TARGET_PROFILES"
    mount --bind "$SPECS_SRC" "$TARGET_SPECS"
    mount --bind "$SPECS_SRC" "$TARGET_MSMNILE_SPECS"
    restart_media
}

case "$1" in
    direwolf)
        apply_direwolf
        ;;
    restore|default|msmnile|unmount|umount)
        restore_default
        ;;
    ""|-h|--help)
        echo "Usage:"
        echo "  $0 <folder-name>"
        echo "  $0 direwolf"
        echo "  $0 msmnile"
        echo "  $0 restore"
        exit 1
        ;;
    *)
        apply_folder "$1"
        ;;
esac
