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
APPLY_IN_PROGRESS=0
COMMAND_TIMEOUT_SECONDS="${HEVC_COMMAND_TIMEOUT_SECONDS:-3}"

run_bounded() {
    timeout_seconds="$1"
    shift

    "$@" &
    command_pid="$!"
    (
        sleep "$timeout_seconds"
        kill -TERM "$command_pid" 2>/dev/null || exit 0
        sleep 1
        kill -KILL "$command_pid" 2>/dev/null || true
    ) &
    watchdog_pid="$!"

    wait "$command_pid"
    command_status="$?"
    kill "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
    return "$command_status"
}

kill_if_running() {
    pid="$(pidof "$1" 2>/dev/null)"
    if [ -n "$pid" ]; then
        # Multiple PIDs are intentionally word-split here.
        kill -9 $pid 2>/dev/null || true
    fi
}

restart_media() {
    kill_if_running media.swcodec
    kill_if_running media.hwcodec
    kill_if_running media.codec
    kill_if_running mediaserver
}

is_target_mounted() {
    [ -r "$MOUNT_TABLE" ] && grep -F "$1" "$MOUNT_TABLE" >/dev/null 2>&1
}

unmount_target() {
    target="$1"
    attempts=0

    if ! is_target_mounted "$target"; then
        return 0
    fi

    while is_target_mounted "$target"; do
        attempts=$((attempts + 1))
        echo "phase:unmount:$target:attempt:$attempts"
        if [ "$attempts" -gt 3 ]; then
            return 1
        fi
        if run_bounded "$COMMAND_TIMEOUT_SECONDS" umount -l "$target" 2>/dev/null; then
            continue
        fi
        run_bounded "$COMMAND_TIMEOUT_SECONDS" umount "$target" 2>/dev/null || return 1
    done
    return 0
}

unmount_all() {
    result=0
    for target in \
        "$TARGET_CODECS" \
        "$TARGET_PERFORMANCE" \
        "$TARGET_PROFILES" \
        "$TARGET_SPECS" \
        "$TARGET_MSMNILE_SPECS"; do
        unmount_target "$target" || result=1
    done
    return "$result"
}

emit_error() {
    echo "status:error"
    echo "reason:$1"
}

fail_before_change() {
    emit_error "$1"
    return 1
}

rollback_and_fail() {
    reason="$1"
    rollback_status=ok
    unmount_all || rollback_status=failed
    restart_media
    APPLY_IN_PROGRESS=0
    emit_error "$reason"
    echo "rollback:$rollback_status"
    return 1
}

handle_signal() {
    trap - HUP INT TERM
    if [ "$APPLY_IN_PROGRESS" = "1" ]; then
        unmount_all || true
        restart_media
        APPLY_IN_PROGRESS=0
    fi
    emit_error "interrupted"
    exit 130
}

trap handle_signal HUP INT TERM

guard_supported_target() {
    if [ "${PREFLIGHT_VERIFIED:-0}" = "1" ]; then
        echo "status:verified"
        echo "reason:preflight_already_passed"
        return 0
    fi

    if [ "${SKIP_PREFLIGHT:-0}" = "1" ]; then
        echo "status:skipped"
        echo "reason:skip_preflight"
        return 0
    fi

    if [ -f "$BASE_DIR/preflight.sh" ]; then
        sh "$BASE_DIR/preflight.sh" --guard
        return "$?"
    fi

    for target in \
        "$TARGET_CODECS" \
        "$TARGET_PERFORMANCE" \
        "$TARGET_PROFILES" \
        "$TARGET_SPECS" \
        "$TARGET_MSMNILE_SPECS"; do
        if [ ! -f "$target" ]; then
            echo "status:unsupported"
            echo "reason:missing:$target"
            return 1
        fi
    done

    if ! grep -Eq "c2\.qti\.hevc|OMX\.qcom\.video\.(encoder|decoder)\.hevc" "$TARGET_CODECS" 2>/dev/null; then
        echo "status:unsupported"
        echo "reason:qti_hevc_codec_not_found"
        return 1
    fi
    return 0
}

target_paths_for_key() {
    case "$1" in
        codecs) echo "$TARGET_CODECS" ;;
        performance) echo "$TARGET_PERFORMANCE" ;;
        profiles) echo "$TARGET_PROFILES" ;;
        specs) echo "$TARGET_SPECS $TARGET_MSMNILE_SPECS" ;;
        *) return 1 ;;
    esac
}

validate_manifest() {
    manifest="$1"
    source_dir="$2"
    seen_keys=""

    [ -f "$manifest" ] || return 1
    while IFS='=' read -r key file || [ -n "$key$file" ]; do
        case "$key" in
            ""|\#*) continue ;;
        esac
        case "$file" in
            ""|*/*|*" "*) return 1 ;;
        esac
        target_paths_for_key "$key" >/dev/null 2>&1 || return 1
        case " $seen_keys " in
            *" $key "*) return 1 ;;
        esac
        [ -f "$source_dir/$file" ] || return 1
        seen_keys="$seen_keys $key"
    done < "$manifest"

    [ -n "$seen_keys" ]
}

label_manifest_sources() {
    manifest="$1"
    source_dir="$2"

    if ! command -v chcon >/dev/null 2>&1; then
        return 0
    fi

    while IFS='=' read -r key file || [ -n "$key$file" ]; do
        case "$key" in
            ""|\#*) continue ;;
        esac
        chcon u:object_r:vendor_configs_file:s0 "$source_dir/$file" || return 1
    done < "$manifest"
}

bind_one() {
    source_file="$1"
    target_file="$2"

    mount --bind "$source_file" "$target_file" || return 1
    is_target_mounted "$target_file" || return 1
    cmp -s "$source_file" "$target_file" || return 1
    return 0
}

bind_key() {
    key="$1"
    source_file="$2"
    targets="$(target_paths_for_key "$key")" || return 1

    for target in $targets; do
        bind_one "$source_file" "$target" || return 1
    done
}

apply_manifest() {
    name="$1"
    source_dir="$2"
    manifest="$3"

    APPLY_IN_PROGRESS=1
    if ! unmount_all; then
        rollback_and_fail "unable_to_clear_previous_mounts"
        return 1
    fi

    while IFS='=' read -r key file || [ -n "$key$file" ]; do
        case "$key" in
            ""|\#*) continue ;;
        esac
        if ! bind_key "$key" "$source_dir/$file"; then
            rollback_and_fail "bind_failed:$key"
            return 1
        fi
    done < "$manifest"

    restart_media
    APPLY_IN_PROGRESS=0
    echo "status:ok"
    echo "variant:$name"
    return 0
}

restore_default() {
    result=0
    APPLY_IN_PROGRESS=1
    echo "phase:restore_unmount"
    unmount_all || result=1
    echo "phase:restore_restart_media"
    restart_media
    APPLY_IN_PROGRESS=0

    if [ "$result" -ne 0 ]; then
        emit_error "restore_unmount_failed"
        return 1
    fi
    echo "status:ok"
    echo "variant:msmnile"
    return 0
}

apply_direwolf() {
    guard_supported_target || return 1

    for source_file in \
        "$DIREWOLF_CODECS" \
        "$DIREWOLF_PERFORMANCE" \
        "$DIREWOLF_PROFILES" \
        "$DIREWOLF_SPECS"; do
        [ -f "$source_file" ] || {
            fail_before_change "missing:$source_file"
            return 1
        }
    done

    APPLY_IN_PROGRESS=1
    if ! unmount_all; then
        rollback_and_fail "unable_to_clear_previous_mounts"
        return 1
    fi

    bind_key codecs "$DIREWOLF_CODECS" || {
        rollback_and_fail "bind_failed:codecs"
        return 1
    }
    bind_key performance "$DIREWOLF_PERFORMANCE" || {
        rollback_and_fail "bind_failed:performance"
        return 1
    }
    bind_key profiles "$DIREWOLF_PROFILES" || {
        rollback_and_fail "bind_failed:profiles"
        return 1
    }
    bind_key specs "$DIREWOLF_SPECS" || {
        rollback_and_fail "bind_failed:specs"
        return 1
    }

    restart_media
    APPLY_IN_PROGRESS=0
    echo "status:ok"
    echo "variant:direwolf"
}

apply_folder() {
    name="$1"
    source_dir="$BASE_DIR/$name"
    manifest="$source_dir/profile.manifest"

    guard_supported_target || return 1
    validate_manifest "$manifest" "$source_dir" || {
        fail_before_change "invalid_profile_manifest:$name"
        return 1
    }
    label_manifest_sources "$manifest" "$source_dir" || {
        fail_before_change "selinux_label_failed:$name"
        return 1
    }
    apply_manifest "$name" "$source_dir" "$manifest"
}

case "${1:-}" in
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
