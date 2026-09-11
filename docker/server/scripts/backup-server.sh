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
output_dir="${1:-${deploy_dir}/backups}"
timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
dump_file="${output_dir}/thingsboard-server-${timestamp}.dump"
temporary_dump=""

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

checksum_value() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

cleanup() {
  if [[ -n "${temporary_dump}" && -f "${temporary_dump}" ]]; then
    rm -f "${temporary_dump}"
  fi
}
trap cleanup EXIT

"${script_dir}/validate.sh"
compose=(docker compose --env-file "${env_file}" -f "${compose_file}")
"${compose[@]}" up -d postgres >/dev/null
for _attempt in $(seq 1 60); do
  if "${compose[@]}" exec -T postgres pg_isready >/dev/null 2>&1; then
    break
  fi
  [[ "${_attempt}" != "60" ]] || fail "等待 PostgreSQL 超时"
  sleep 2
done

database_user="$(read_env POSTGRES_USER)"
database_name="$(read_env POSTGRES_DB)"
[[ -n "${database_user}" && -n "${database_name}" ]] || fail "无法读取数据库配置"

mkdir -p "${output_dir}"
chmod 700 "${output_dir}"
temporary_dump="$(mktemp "${output_dir}/.thingsboard-server-${timestamp}.XXXXXX")"

echo "正在创建服务器 PostgreSQL 一致性快照……"
"${compose[@]}" exec -T postgres pg_dump \
  --username="${database_user}" \
  --dbname="${database_name}" \
  --format=custom \
  --no-owner \
  --no-privileges > "${temporary_dump}"

[[ -s "${temporary_dump}" ]] || fail "数据库备份为空"
"${compose[@]}" exec -T postgres pg_restore --list < "${temporary_dump}" >/dev/null
mv "${temporary_dump}" "${dump_file}"
temporary_dump=""
chmod 600 "${dump_file}"
printf '%s  %s\n' "$(checksum_value "${dump_file}")" "$(basename "${dump_file}")" \
  > "${dump_file}.sha256"
chmod 600 "${dump_file}.sha256"

echo "服务器备份完成：${dump_file}"
echo "请将备份和校验文件复制到服务器之外的备份存储。"
