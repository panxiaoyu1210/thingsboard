#!/usr/bin/env bash
#
# Copyright © 2016-2026 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(cd "${script_dir}/.." && pwd)"
dockerfile_dir="${deploy_dir}/base/openjdk25"
image_name="${1:-}"
target_platform="${2:-linux/amd64}"
debian_mirror="${TB_DEBIAN_MIRROR:-http://deb.debian.org/debian}"
debian_security_mirror="${TB_DEBIAN_SECURITY_MIRROR:-http://deb.debian.org/debian-security}"
openjdk_debian_mirror="${TB_OPENJDK_DEBIAN_MIRROR:-${debian_mirror}}"
openjdk_debian_security_mirror="${TB_OPENJDK_DEBIAN_SECURITY_MIRROR:-${debian_security_mirror}}"

fail() {
  echo "错误：$*" >&2
  exit 1
}

[[ -n "${image_name}" ]] || fail "用法：$0 <基础镜像标签> [linux/amd64|linux/arm64]"
[[ "${target_platform}" == "linux/amd64" || "${target_platform}" == "linux/arm64" ]] \
  || fail "目标架构只支持 linux/amd64 或 linux/arm64"
[[ "${debian_mirror}" =~ ^https?://[^[:space:]]+$ ]] || fail "TB_DEBIAN_MIRROR 不是有效的 HTTP(S) URL"
[[ "${debian_security_mirror}" =~ ^https?://[^[:space:]]+$ ]] \
  || fail "TB_DEBIAN_SECURITY_MIRROR 不是有效的 HTTP(S) URL"
[[ "${openjdk_debian_mirror}" =~ ^https?://[^[:space:]]+$ ]] \
  || fail "TB_OPENJDK_DEBIAN_MIRROR 不是有效的 HTTP(S) URL"
[[ "${openjdk_debian_security_mirror}" =~ ^https?://[^[:space:]]+$ ]] \
  || fail "TB_OPENJDK_DEBIAN_SECURITY_MIRROR 不是有效的 HTTP(S) URL"
command -v docker >/dev/null 2>&1 || fail "未安装 Docker"
docker buildx version >/dev/null 2>&1 || fail "未安装 Docker Buildx"

echo "Debian 软件源：${debian_mirror}"
echo "Debian 安全软件源：${debian_security_mirror}"
echo "OpenJDK 软件源：${openjdk_debian_mirror}"
echo "OpenJDK 安全软件源：${openjdk_debian_security_mirror}"
docker buildx build \
  --platform "${target_platform}" \
  --load \
  --tag "${image_name}" \
  --build-arg "DEBIAN_MIRROR=${debian_mirror}" \
  --build-arg "DEBIAN_SECURITY_MIRROR=${debian_security_mirror}" \
  --build-arg "OPENJDK_DEBIAN_MIRROR=${openjdk_debian_mirror}" \
  --build-arg "OPENJDK_DEBIAN_SECURITY_MIRROR=${openjdk_debian_security_mirror}" \
  "${dockerfile_dir}"

case "${target_platform}" in
  linux/amd64) expected_arch="amd64" ;;
  linux/arm64) expected_arch="arm64" ;;
esac

actual_arch="$(docker image inspect "${image_name}" --format '{{.Architecture}}')"
[[ "${actual_arch}" == "${expected_arch}" ]] \
  || fail "基础镜像架构为 ${actual_arch}，期望 ${expected_arch}"

docker run --rm --platform "${target_platform}" --entrypoint /bin/bash "${image_name}" -lc \
  'test "$(id -u thingsboard)" = "799" && test "$(id -g thingsboard)" = "799" && java -version'

echo "本地 OpenJDK 25 基础镜像已就绪：${image_name} (${actual_arch})"
