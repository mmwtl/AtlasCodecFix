#!/system/bin/sh

TARGET_CODECS="/vendor/etc/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="/vendor/etc/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="/vendor/etc/media_profiles_msmnile.xml"
TARGET_SPECS="/vendor/etc/video_system_specs.json"
TARGET_MSMNILE_SPECS="/vendor/etc/media_msmnile/video_system_specs.json"

DIREWOLF_CODECS="/vendor/etc/media_codecs_direwolf.xml"
DIREWOLF_PERFORMANCE="/vendor/etc/media_codecs_performance_direwolf.xml"
DIREWOLF_PROFILES="/vendor/etc/media_profiles_direwolf.xml"
DIREWOLF_SPECS="/vendor/etc/media_direwolf/video_system_specs.json"

BASE_DIR="/dev/hevc"
COMMAND="$1"

score=0
hard_fail=0
root_ok=0
reasons=""

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

detect_variant() {
    if [ "$root_ok" != "1" ] || [ "$hard_fail" = "1" ]; then
        echo "variant:unknown"
        return
    fi

    mounted_targets="$(mount | grep -E "/vendor/etc/(media_codecs_msmnile.xml|media_codecs_performance_msmnile.xml|media_profiles_msmnile.xml|video_system_specs.json|media_msmnile/video_system_specs.json)" 2>/dev/null)"

    if [ -z "$mounted_targets" ]; then
        echo "variant:msmnile"
        return
    fi

    for name in ultra max min; do
        src="$BASE_DIR/$name"
        if [ -f "$src/media_codecs_$name.xml" ] &&
            [ -f "$src/media_codecs_performance_$name.xml" ] &&
            [ -f "$src/media_profiles_$name.xml" ] &&
            [ -f "$src/video_system_specs_$name.json" ] &&
            cmp -s "$TARGET_CODECS" "$src/media_codecs_$name.xml" 2>/dev/null &&
            cmp -s "$TARGET_PERFORMANCE" "$src/media_codecs_performance_$name.xml" 2>/dev/null &&
            cmp -s "$TARGET_PROFILES" "$src/media_profiles_$name.xml" 2>/dev/null &&
            cmp -s "$TARGET_SPECS" "$src/video_system_specs_$name.json" 2>/dev/null &&
            cmp -s "$TARGET_MSMNILE_SPECS" "$src/video_system_specs_$name.json" 2>/dev/null; then
            echo "variant:$name"
            return
        fi
    done

    if [ -f "$DIREWOLF_CODECS" ] &&
        [ -f "$DIREWOLF_PERFORMANCE" ] &&
        [ -f "$DIREWOLF_PROFILES" ] &&
        [ -f "$DIREWOLF_SPECS" ] &&
        cmp -s "$TARGET_CODECS" "$DIREWOLF_CODECS" 2>/dev/null &&
        cmp -s "$TARGET_PERFORMANCE" "$DIREWOLF_PERFORMANCE" 2>/dev/null &&
        cmp -s "$TARGET_PROFILES" "$DIREWOLF_PROFILES" 2>/dev/null &&
        cmp -s "$TARGET_SPECS" "$DIREWOLF_SPECS" 2>/dev/null &&
        cmp -s "$TARGET_MSMNILE_SPECS" "$DIREWOLF_SPECS" 2>/dev/null; then
        echo "variant:direwolf"
        return
    fi

    echo "variant:unknown"
}

finish() {
    status="$1"
    auto_apply="$2"

    detect_variant
    echo "score:$score"
    echo "status:$status"
    echo "auto_apply:$auto_apply"
    echo "reason:${reasons:-ok}"
    echo "ro.board.platform:$(prop ro.board.platform)"
    echo "ro.boot.hardware:$(prop ro.boot.hardware)"
    echo "ro.hardware:$(prop ro.hardware)"
    echo "ro.soc.model:$(prop ro.soc.model)"
    echo "ro.product.board:$(prop ro.product.board)"
    echo "ro.product.device:$(prop ro.product.device)"
    echo "ro.product.vendor.device:$(prop ro.product.vendor.device)"
    echo "ro.product.model:$(prop ro.product.model)"
    echo "ro.product.manufacturer:$(prop ro.product.manufacturer)"
    echo "ro.product.brand:$(prop ro.product.brand)"

    if [ "$COMMAND" = "--guard" ] && [ "$status" = "unsupported" ]; then
        exit 1
    fi
    exit 0
}

echo "hevc_preflight:1"

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    add_reason "not_root"
    finish unsupported no
fi

root_ok=1

require_file "$TARGET_CODECS"
require_file "$TARGET_PERFORMANCE"
require_file "$TARGET_PROFILES"
require_file "$TARGET_SPECS"
require_file "$TARGET_MSMNILE_SPECS"

if [ "$hard_fail" = "0" ]; then
    if grep -Eq "c2\.qti\.hevc|OMX\.qcom\.video\.(encoder|decoder)\.hevc" "$TARGET_CODECS" 2>/dev/null; then
        score=$((score + 4))
    else
        add_reason "qti_hevc_codec_not_found"
        hard_fail=1
    fi

    if grep -qi "hevc" "$TARGET_PROFILES" 2>/dev/null; then
        score=$((score + 1))
    else
        add_reason "hevc_profile_not_found"
    fi
fi

props="
$(prop ro.board.platform)
$(prop ro.boot.hardware)
$(prop ro.hardware)
$(prop ro.soc.model)
$(prop ro.product.board)
$(prop ro.product.device)
$(prop ro.product.vendor.device)
$(prop ro.build.fingerprint)
$(prop ro.vendor.build.fingerprint)
$(prop ro.product.manufacturer)
$(prop ro.product.brand)
$(prop ro.product.model)
"

if contains_ci "$props" "msmnile"; then
    score=$((score + 4))
else
    add_reason "prop_msmnile_not_found"
fi

if contains_ci "$props" "qcom|qualcomm"; then
    score=$((score + 1))
fi

if contains_ci "$props" "geely|ecarx|zeekr|lynk"; then
    score=$((score + 1))
fi

if [ "$hard_fail" = "1" ]; then
    finish unsupported no
fi

if [ "$score" -ge 8 ]; then
    finish supported yes
fi

finish risky no
