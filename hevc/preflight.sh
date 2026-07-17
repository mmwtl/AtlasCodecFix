#!/system/bin/sh

VENDOR_ETC="${HEVC_VENDOR_ETC:-/vendor/etc}"

TARGET_CODECS="$VENDOR_ETC/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="$VENDOR_ETC/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="$VENDOR_ETC/media_profiles_msmnile.xml"
TARGET_SPECS="$VENDOR_ETC/video_system_specs.json"
TARGET_MSMNILE_SPECS="$VENDOR_ETC/media_msmnile/video_system_specs.json"

COMMAND="$1"

hard_fail=0
reasons=""
platform=""

add_reason() {
    if [ -z "$reasons" ]; then
        reasons="$1"
    else
        reasons="$reasons,$1"
    fi
}

require_file() {
    if [ ! -f "$1" ]; then
        add_reason "missing:$1"
        hard_fail=1
        return 1
    fi
    return 0
}

prop() {
    getprop "$1" 2>/dev/null
}

contains_ci() {
    printf '%s\n' "$1" | grep -Eiq "$2"
}

finish() {
    status="$1"
    auto_apply="$2"

    echo "status:$status"
    echo "auto_apply:$auto_apply"
    echo "reason:${reasons:-ok}"
    echo "platform:${platform:-unknown}"
    echo "phase:complete"

    if [ "$COMMAND" = "--guard" ] && [ "$status" = "unsupported" ]; then
        exit 1
    fi
    exit 0
}

echo "hevc_preflight:1"
echo "phase:root_check"

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    add_reason "not_root"
    finish unsupported no
fi

echo "phase:file_check"

require_file "$TARGET_CODECS"
require_file "$TARGET_PERFORMANCE"
require_file "$TARGET_PROFILES"
require_file "$TARGET_SPECS"
require_file "$TARGET_MSMNILE_SPECS"

if [ "$hard_fail" = "0" ]; then
    echo "phase:codec_check"
    if grep -Eq "c2\.qti\.hevc|OMX\.qcom\.video\.(encoder|decoder)\.hevc" "$TARGET_CODECS" 2>/dev/null; then
        :
    else
        add_reason "qti_hevc_codec_not_found"
        hard_fail=1
    fi
fi

if [ "$hard_fail" = "1" ]; then
    finish unsupported no
fi

echo "phase:platform_check"
platform="$(prop ro.board.platform)"
[ -n "$platform" ] || platform="$(prop ro.soc.model)"

if contains_ci "$platform" "msmnile"; then
    finish supported yes
fi

add_reason "platform_not_msmnile"
finish risky no
