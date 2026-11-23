#!/usr/bin/env bash
# Cross compiles redlib for Android and stages it in app/src/main/jniLibs/<abi>/
# as libredlib.so, which the app executes at runtime.
#
# Usage: scripts/build-redlib.sh [--fetch-only]
#
# Environment overrides (defaults come from versions.env):
#   REDLIB_ABIS        comma separated ABIs, default arm64-v8a
#                      (arm64-v8a, armeabi-v7a, x86_64, x86)
#   REDLIB_REPO        git URL of the redlib repository (upstream or a fork)
#   REDLIB_REF         tag, branch or commit in REDLIB_REPO to build
#   REDLIB_SRC         use an existing redlib checkout as is (no git operations)
#   ANDROID_NDK_HOME   NDK to use; otherwise <sdk>/ndk/$NDK_VERSION is searched
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs"

die() { echo "build-redlib: error: $*" >&2; exit 1; }
log() { echo "build-redlib: $*" >&2; }

# Load versions.env without overriding values already set in the environment.
while IFS='=' read -r key value; do
  case "$key" in ''|\#*) continue ;; esac
  value="${value%$'\r'}"
  if [ -z "${!key:-}" ]; then export "$key=$value"; fi
done < "$ROOT/versions.env"
: "${REDLIB_REPO:?missing from versions.env}" "${REDLIB_REF:?missing from versions.env}" \
  "${NDK_VERSION:?missing from versions.env}" \
  "${ANDROID_API_LEVEL:?missing from versions.env}"

FETCH_ONLY=0
case "${1:-}" in
  --fetch-only) FETCH_ONLY=1 ;;
  "") ;;
  *) die "unknown argument: $1" ;;
esac

export PATH="$HOME/.cargo/bin:$PATH"

# ---------------------------------------------------------------- source

fetch_source() {
  local src="$ROOT/build/redlib-src" wanted current
  # A checkout of a different repository (REDLIB_REPO changed) is replaced, never reused.
  if [ -d "$src/.git" ] && [ "$(git -C "$src" remote get-url origin)" != "$REDLIB_REPO" ]; then
    log "REDLIB_REPO changed, replacing the old checkout"
    rm -rf "$src"
  fi
  if [ ! -d "$src/.git" ]; then
    log "cloning $REDLIB_REPO"
    mkdir -p "$ROOT/build"
    git clone --quiet --filter=blob:none "$REDLIB_REPO" "$src"
  fi
  # Only a full commit hash can be used without fetching; branches and tags move.
  if [[ "$REDLIB_REF" =~ ^[0-9a-f]{40}$ ]] && git -C "$src" cat-file -e "$REDLIB_REF^{commit}" 2>/dev/null; then
    wanted="$REDLIB_REF"
  else
    git -C "$src" fetch --quiet --force --tags origin || die "could not fetch $REDLIB_REPO"
    if [[ "$REDLIB_REF" =~ ^[0-9a-f]{40}$ ]]; then
      # A commit no branch points at any more can still be fetched by hash.
      git -C "$src" cat-file -e "$REDLIB_REF^{commit}" 2>/dev/null \
        || git -C "$src" fetch --quiet origin "$REDLIB_REF" || true
    fi
    wanted="$(git -C "$src" rev-parse --verify --quiet "refs/remotes/origin/$REDLIB_REF^{commit}" \
      || git -C "$src" rev-parse --verify --quiet "refs/tags/$REDLIB_REF^{commit}" \
      || git -C "$src" rev-parse --verify --quiet "$REDLIB_REF^{commit}" \
      || true)"
    [ -n "$wanted" ] || die "redlib ref '$REDLIB_REF' not found in $REDLIB_REPO"
  fi
  current="$(git -C "$src" rev-parse HEAD)"
  if [ "$current" != "$wanted" ]; then
    log "checking out redlib $REDLIB_REF ($wanted)"
    git -C "$src" -c advice.detachedHead=false checkout --quiet --force "$wanted"
  fi
  echo "$src"
}

if [ -n "${REDLIB_SRC:-}" ]; then
  [ -f "$REDLIB_SRC/Cargo.toml" ] || die "REDLIB_SRC=$REDLIB_SRC is not a redlib checkout"
  SRC="$REDLIB_SRC"
else
  SRC="$(fetch_source)"
fi
[ "$FETCH_ONLY" = 1 ] && { log "source ready at $SRC"; exit 0; }

# ---------------------------------------------------------------- toolchain

find_ndk() {
  local c sdk=""
  for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    if [ -n "$c" ] && [ -d "$c/toolchains/llvm/prebuilt" ]; then echo "$c"; return 0; fi
  done
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ]; then
    # local.properties escapes ':' and '\' (Windows paths).
    sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | sed 's/\\:/:/g; s/\\\\/\\/g')"
  fi
  for c in "$sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$c" ] && [ -d "$c/ndk/$NDK_VERSION" ]; then echo "$c/ndk/$NDK_VERSION"; return 0; fi
  done
  return 1
}

NDK="$(find_ndk)" || die "Android NDK $NDK_VERSION not found. Install it with:
  sdkmanager \"ndk;$NDK_VERSION\"
or point ANDROID_NDK_HOME at an NDK (r28 or newer)."
export ANDROID_NDK_HOME="$NDK"

PREBUILT=""
for c in "$NDK"/toolchains/llvm/prebuilt/*; do [ -d "$c" ] && { PREBUILT="$c"; break; }; done
[ -n "$PREBUILT" ] || die "no prebuilt LLVM toolchain inside $NDK"
READELF="$PREBUILT/bin/llvm-readelf"

command -v cargo >/dev/null || die "cargo not found; install Rust from https://rustup.rs"
command -v cargo-ndk >/dev/null || die "cargo-ndk not found; run: cargo install cargo-ndk --locked"
command -v cmake >/dev/null || die "cmake not found (needed to build BoringSSL)"

triple_for() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;;
    x86) echo i686-linux-android ;;
    *) die "unknown ABI '$1' (use arm64-v8a, armeabi-v7a, x86_64 or x86)" ;;
  esac
}

# Directory name of the ABI inside the NDK sysroot (differs for 32 bit ARM).
sysroot_dir_for() {
  case "$1" in
    armeabi-v7a) echo arm-linux-androideabi ;;
    *) triple_for "$1" ;;
  esac
}

# ---------------------------------------------------------------- build

TARGET_DIR="${CARGO_TARGET_DIR:-$SRC/target}"
ABIS="${REDLIB_ABIS:-arm64-v8a}"

IFS=',' read -r -a ABI_LIST <<< "$ABIS"
for abi in "${ABI_LIST[@]}"; do  # validate every ABI before the slow builds start
  abi="$(echo "$abi" | tr -d '[:space:]')"
  [ -z "$abi" ] || triple_for "$abi" >/dev/null
done

for abi in "${ABI_LIST[@]}"; do
  abi="$(echo "$abi" | tr -d '[:space:]')"
  [ -n "$abi" ] || continue
  triple="$(triple_for "$abi")"

  if command -v rustup >/dev/null; then
    rustup target list --installed | grep -qx "$triple" || rustup target add "$triple"
  fi

  log "building redlib for $abi (API $ANDROID_API_LEVEL)"
  (cd "$SRC" && cargo ndk -t "$abi" -P "$ANDROID_API_LEVEL" build --release --locked --bin redlib)

  bin="$TARGET_DIR/$triple/release/redlib"
  [ -f "$bin" ] || die "expected build output at $bin"

  out="$JNI_DIR/$abi"
  mkdir -p "$out"
  # The file must live in jniLibs and be named lib*.so so the installer
  # extracts it into nativeLibraryDir, the one app location Android 10+
  # still allows executing from.
  cp "$bin" "$out/libredlib.so"
  chmod 755 "$out/libredlib.so"

  # Ship any NDK runtime library the binary needs; refuse anything else.
  needs_cxx=0
  for lib in $("$READELF" -d "$out/libredlib.so" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p'); do
    case "$lib" in
      libc.so|libm.so|libdl.so|liblog.so) ;;
      libc++_shared.so) needs_cxx=1 ;;
      *) die "libredlib.so ($abi) depends on $lib, which Android does not provide" ;;
    esac
  done
  if [ "$needs_cxx" = 1 ]; then
    cp "$PREBUILT/sysroot/usr/lib/$(sysroot_dir_for "$abi")/libc++_shared.so" "$out/"
  else
    rm -f "$out/libc++_shared.so"
  fi

  # Android 15+ devices may use 16 KB pages; 64 bit binaries must be aligned for them.
  case "$abi" in
    arm64-v8a|x86_64)
      for f in "$out"/*.so; do
        for align in $("$READELF" -lW "$f" | awk '$1 == "LOAD" { print $NF }'); do
          if [ $((align)) -lt 16384 ]; then
            die "$(basename "$f") ($abi) has $align aligned LOAD segments; 16 KB devices need 0x4000. Use NDK r28 or newer."
          fi
        done
      done
      ;;
  esac

  size="$(du -h "$out/libredlib.so" | cut -f1)"
  log "staged $abi: libredlib.so ($size)$([ "$needs_cxx" = 1 ] && echo ' + libc++_shared.so')"
done
