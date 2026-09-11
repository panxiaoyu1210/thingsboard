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
env_file="${deploy_dir}/.env"
compose_file="${deploy_dir}/compose.yml"

fail() {
  echo "错误：$*" >&2
  exit 1
}

read_env() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" "${env_file}" | tail -n 1 || true)"
  [[ -n "${line}" ]] || fail "${env_file} 缺少 ${key}"
  printf '%s' "${line#*=}"
}

require_value() {
  local key="$1"
  local value
  value="$(read_env "${key}")"
  [[ -n "${value}" ]] || fail "${key} 不能为空"
  [[ "${value}" != *CHANGE_ME* ]] || fail "${key} 仍是模板占位值"
}

command -v docker >/dev/null 2>&1 || fail "未安装 Docker"
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"
docker compose version >/dev/null 2>&1 || fail "未安装 Docker Compose V2"
[[ -f "${env_file}" ]] || fail "请先执行 cp .env.example .env 并填写配置"
[[ -f "${compose_file}" ]] || fail "缺少 ${compose_file}"

for key in \
  THINGSBOARD_IMAGE WAN_TRANSPORT_IMAGE POSTGRES_DB POSTGRES_USER \
  POSTGRES_PASSWORD VALKEY_PASSWORD WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY \
  UI_MAP_TIANDITU_API_KEY; do
  require_value "${key}"
done

wan_key="$(read_env WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY)"
[[ ${#wan_key} -ge 32 ]] || fail "WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY 长度至少应为 32 个字符"

postgres_database="$(read_env POSTGRES_DB)"
postgres_user="$(read_env POSTGRES_USER)"
[[ "${postgres_database}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
  || fail "POSTGRES_DB 只能包含字母、数字和下划线，且不能以数字开头"
[[ "${postgres_user}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
  || fail "POSTGRES_USER 只能包含字母、数字和下划线，且不能以数字开头"
case "${postgres_database}" in
  postgres | template0 | template1)
    fail "POSTGRES_DB 不能使用 PostgreSQL 维护数据库 ${postgres_database}"
    ;;
esac

if command -v stat >/dev/null 2>&1 && stat -c '%a' "${env_file}" >/dev/null 2>&1; then
  env_mode="$(stat -c '%a' "${env_file}")"
  [[ "${env_mode}" == "600" ]] || fail "${env_file} 权限应为 600，当前为 ${env_mode}"
fi

docker compose --env-file "${env_file}" -f "${compose_file}" config --quiet

case "$(uname -m)" in
  x86_64) expected_arch="amd64" ;;
  aarch64 | arm64) expected_arch="arm64" ;;
  *) expected_arch="" ;;
esac

for image_key in THINGSBOARD_IMAGE WAN_TRANSPORT_IMAGE; do
  image_name="$(read_env "${image_key}")"
  if docker image inspect "${image_name}" >/dev/null 2>&1; then
    image_arch="$(docker image inspect "${image_name}" --format '{{.Architecture}}')"
    if [[ -n "${expected_arch}" && "${image_arch}" != "${expected_arch}" ]]; then
      fail "${image_name} 架构为 ${image_arch}，服务器架构为 ${expected_arch}"
    fi
  else
    echo "提示：本机尚无 ${image_name}，部署前需要 docker pull 或 docker load。"
  fi
done

echo "服务器配置校验通过。"
