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
repo_root="$(cd "${deploy_dir}/../.." && pwd)"
image_repository="${1:-}"
image_tag="${2:-$(git -C "${repo_root}" rev-parse --short=12 HEAD)}"
target_platform="${3:-linux/amd64}"
maven_command="${TB_MVN_CMD:-mvn}"
release_dir="${deploy_dir}/release"
docker_base_image="${TB_DOCKER_BASE_IMAGE:-thingsboard/openjdk25:trixie-slim}"

fail() {
  echo "错误：$*" >&2
  exit 1
}

checksum_value() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

[[ -n "${image_repository}" ]] \
  || fail "用法：$0 <镜像仓库前缀> [版本标签] [linux/amd64|linux/arm64]"
[[ "${target_platform}" == "linux/amd64" || "${target_platform}" == "linux/arm64" ]] \
  || fail "目标架构只支持 linux/amd64 或 linux/arm64"
command -v docker >/dev/null 2>&1 || fail "未安装 Docker"
docker buildx version >/dev/null 2>&1 || fail "未安装 Docker Buildx"
command -v git >/dev/null 2>&1 || fail "未安装 Git"

if [[ "${maven_command}" == */* && "${maven_command}" != /* ]]; then
  maven_command="${repo_root}/${maven_command}"
fi
[[ -x "${maven_command}" ]] || command -v "${maven_command}" >/dev/null 2>&1 \
  || fail "找不到 Maven 命令 ${maven_command}"

if [[ -n "$(git -C "${repo_root}" status --porcelain --untracked-files=no)" \
      && "${ALLOW_DIRTY_BUILD:-false}" != "true" ]]; then
  fail "工作区存在已修改的跟踪文件；请先提交或还原，或显式设置 ALLOW_DIRTY_BUILD=true"
fi

core_image="${image_repository%/}/tb-node:${image_tag}"
wan_image="${image_repository%/}/tb-wan-transport:${image_tag}"
archive_name="thingsboard-images-${image_tag}-${target_platform#linux/}.tar.gz"

if [[ "${TB_BUILD_LOCAL_BASE:-false}" == "true" ]]; then
  [[ -z "${TB_DOCKER_BASE_IMAGE:-}" ]] \
    || fail "TB_BUILD_LOCAL_BASE 与 TB_DOCKER_BASE_IMAGE 不能同时设置"
  docker_base_image="${image_repository%/}/openjdk25:${image_tag}-${target_platform#linux/}"
  "${script_dir}/build-base-image.sh" "${docker_base_image}" "${target_platform}"
fi

echo "正在构建应用安装包……"
"${maven_command}" -f "${repo_root}/pom.xml" \
  -pl msa/tb-node,msa/transport/wan -am clean install \
  -DskipTests -Ddockerfile.skip=true \
  -Ddocker.base.image="${docker_base_image}"

echo "正在使用基础镜像 ${docker_base_image} 构建 ${target_platform} 镜像……"
docker buildx build --platform "${target_platform}" --load \
  --tag "${core_image}" "${repo_root}/msa/tb-node/target"
docker buildx build --platform "${target_platform}" --load \
  --tag "${wan_image}" "${repo_root}/msa/transport/wan/target"

mkdir -p "${release_dir}"
docker save "${core_image}" "${wan_image}" | gzip > "${release_dir}/${archive_name}"
printf '%s  %s\n' "$(checksum_value "${release_dir}/${archive_name}")" "${archive_name}" \
  > "${release_dir}/${archive_name}.sha256"

cat <<EOF
镜像构建完成：
  ${core_image}
  ${wan_image}
离线镜像包：
  ${release_dir}/${archive_name}
  ${release_dir}/${archive_name}.sha256
EOF
