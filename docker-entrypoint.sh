#!/bin/sh
# InSitu Ledger container entrypoint.
#
# Goals, in priority order:
#   1. The server can always write to /data, however the volume or bind mount
#      was created on the host.
#   2. Files in a bind-mounted /data stay owned by the host user, so backups and
#      inspection work without sudo.
#   3. The server never runs as root.
#
# Target UID/GID:
#   a) PUID/PGID when set;
#   b) otherwise the current non-root owner of /data (e.g. data copied from an
#      existing install keeps its owner);
#   c) otherwise 1000:1000.
# /data is chowned only when its top-level owner differs from the target. Skip
# that entirely with INSITU_SKIP_CHOWN=1.
#
# If the container is already started as a non-root user (docker run --user,
# compose `user:`), ownership is left alone and the command runs as-is.

set -e

DATA_DIR="${INSITU_DATA_DIR:-/data}"

if [ "$(id -u)" != "0" ]; then
	exec "$@"
fi

TARGET_UID="${PUID:-}"
TARGET_GID="${PGID:-}"

if [ -z "$TARGET_UID" ] || [ -z "$TARGET_GID" ]; then
	if [ -d "$DATA_DIR" ]; then
		DIR_UID=$(stat -c '%u' "$DATA_DIR" 2>/dev/null || echo 0)
		DIR_GID=$(stat -c '%g' "$DATA_DIR" 2>/dev/null || echo 0)
		if [ "$DIR_UID" -ne 0 ]; then
			[ -z "$TARGET_UID" ] && TARGET_UID="$DIR_UID"
			[ -z "$TARGET_GID" ] && TARGET_GID="$DIR_GID"
		fi
	fi
	TARGET_UID="${TARGET_UID:-1000}"
	TARGET_GID="${TARGET_GID:-$TARGET_UID}"
fi

mkdir -p "$DATA_DIR"

if [ "${INSITU_SKIP_CHOWN:-0}" != "1" ]; then
	CURRENT_OWNER=$(stat -c '%u:%g' "$DATA_DIR" 2>/dev/null || echo "0:0")
	if [ "$CURRENT_OWNER" != "${TARGET_UID}:${TARGET_GID}" ]; then
		chown -R "${TARGET_UID}:${TARGET_GID}" "$DATA_DIR"
	fi
fi

exec su-exec "${TARGET_UID}:${TARGET_GID}" "$@"
