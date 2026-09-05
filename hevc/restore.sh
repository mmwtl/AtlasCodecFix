#!/system/bin/sh

VENDOR_ETC="${HEVC_VENDOR_ETC:-/vendor/etc}"
MOUNT_TABLE="${HEVC_MOUNT_TABLE:-/proc/self/mountinfo}"
COMMAND_TIMEOUT_SECONDS="${HEVC_COMMAND_TIMEOUT_SECONDS:-3}"
PHASE_BUDGET_SECONDS="${HEVC_PHASE_BUDGET_SECONDS:-35}"
PHASE_START_TS=""

TARGET_CODECS="$VENDOR_ETC/media_codecs_msmnile.xml"
TARGET_PERFORMANCE="$VENDOR_ETC/media_codecs_performance_msmnile.xml"
TARGET_PROFILES="$VENDOR_ETC/media_profiles_msmnile.xml"
TARGET_SPECS="$VENDOR_ETC/video_system_specs.json"
TARGET_MSMNILE_SPECS="$VENDOR_ETC/media_msmnile/video_system_specs.json"

is_process_active() {
    process_pid="$1"
    process_stat="/proc/$process_pid/stat"

    if [ -r "$process_stat" ]; then
        process_state="$(sed -n 's/^.*) \([^ ]\).*/\1/p' "$process_stat" 2>/dev/null)"
        case "$process_state" in
            Z|X) return 1 ;;
        esac
    fi
    kill -0 "$process_pid" 2>/dev/null
}

run_bounded() {
    timeout_seconds="$1"
    shift

    "$@" </dev/null >/dev/null 2>&1 &
    command_pid="$!"
    polls="$timeout_seconds"

    while is_process_active "$command_pid" && [ "$polls" -gt 0 ]; do
        sleep 1
        polls=$((polls - 1))
    done

    if ! is_process_active "$command_pid"; then
        wait "$command_pid"
        return "$?"
    fi

    kill -TERM "$command_pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$command_pid" 2>/dev/null || true

    if ! is_process_active "$command_pid"; then
        wait "$command_pid" 2>/dev/null || true
    fi
    echo "phase:command_timeout:$*"
    return 124
}

phase_budget_start() {
    PHASE_START_TS="$(date +%s 2>/dev/null || echo 0)"
}

remaining_phase_budget() {
    if [ -z "$PHASE_START_TS" ]; then
        PHASE_START_TS="$(date +%s 2>/dev/null || echo 0)"
    fi
    now="$(date +%s 2>/dev/null || echo 0)"
    remaining=$(( PHASE_START_TS + PHASE_BUDGET_SECONDS - now ))
    if [ "$remaining" -lt 0 ]; then
        remaining=0
    fi
    echo "$remaining"
}

bounded_timeout() {
    budget="$(remaining_phase_budget)"
    if [ "$budget" -le 0 ]; then
        echo 0
    elif [ "$budget" -lt "$COMMAND_TIMEOUT_SECONDS" ]; then
        echo "$budget"
    else
        echo "$COMMAND_TIMEOUT_SECONDS"
    fi
}

is_target_mounted() {
    target="$1"
    [ -r "$MOUNT_TABLE" ] || return 1
    while IFS= read -r line; do
        case "$line" in
            *" $target "*|*"|$target|"*) return 0 ;;
        esac
    done < "$MOUNT_TABLE"
    return 1
}

unmount_target() {
    target="$1"
    attempts=0

    while is_target_mounted "$target"; do
        attempts=$((attempts + 1))
        echo "phase:unmount:$target:attempt:$attempts"
        [ "$attempts" -le 3 ] || return 1
        timeout="$(bounded_timeout)"
        if [ "$timeout" -le 0 ]; then
            echo "phase:budget_exhausted:$target"
            return 1
        fi
        if run_bounded "$timeout" umount -l "$target"; then
            continue
        fi
        timeout="$(bounded_timeout)"
        if [ "$timeout" -le 0 ]; then
            echo "phase:budget_exhausted:$target"
            return 1
        fi
        run_bounded "$timeout" umount "$target" || return 1
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
phase_budget_start
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
