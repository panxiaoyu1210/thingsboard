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
confirm_overwrite=false
dump_file=""

usage() {
  echo "用法：$0 <thingsboard.dump> [--confirm-overwrite]" >&2
}

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

wait_for_command() {
  local description="$1"
  shift
  for _attempt in $(seq 1 90); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  fail "等待${description}超时"
}

for argument in "$@"; do
  case "${argument}" in
    --confirm-overwrite) confirm_overwrite=true ;;
    --*) usage; fail "未知参数 ${argument}" ;;
    *)
      [[ -z "${dump_file}" ]] || { usage; fail "只能指定一个备份文件"; }
      dump_file="${argument}"
      ;;
  esac
done

[[ -n "${dump_file}" ]] || { usage; exit 2; }
[[ -f "${dump_file}" ]] || fail "找不到备份文件 ${dump_file}"
[[ -f "${env_file}" ]] || fail "缺少 ${env_file}"

"${script_dir}/validate.sh"

checksum_file="${dump_file}.sha256"
if [[ -f "${checksum_file}" ]]; then
  expected_checksum="$(awk 'NR == 1 {print $1}' "${checksum_file}")"
  actual_checksum="$(checksum_value "${dump_file}")"
  [[ "${expected_checksum}" == "${actual_checksum}" ]] || fail "备份文件 SHA-256 校验失败"
else
  fail "未找到 ${checksum_file}，拒绝恢复未经完整性校验的备份"
fi

compose=(docker compose --env-file "${env_file}" -f "${compose_file}")

"${compose[@]}" stop wan-transport thingsboard >/dev/null 2>&1 || true
"${compose[@]}" up -d postgres kafka zookeeper valkey
wait_for_command "PostgreSQL" "${compose[@]}" exec -T postgres pg_isready

database_user="$(read_env POSTGRES_USER)"
database_name="$(read_env POSTGRES_DB)"
[[ -n "${database_user}" && -n "${database_name}" ]] || fail "无法读取目标数据库配置"

database_exists="$("${compose[@]}" exec -T postgres psql \
  --username="${database_user}" \
  --dbname=postgres \
  --tuples-only --no-align \
  --command="SELECT count(*) FROM pg_database WHERE datname = '${database_name}';" | tr -d '[:space:]')"
if [[ "${database_exists}" == "0" ]]; then
  "${compose[@]}" exec -T postgres createdb \
    --username="${database_user}" \
    --owner="${database_user}" \
    "${database_name}"
fi

table_count="$("${compose[@]}" exec -T postgres psql \
  --username="${database_user}" \
  --dbname="${database_name}" \
  --tuples-only --no-align \
  --command="SELECT count(*) FROM pg_tables WHERE schemaname = 'public';" | tr -d '[:space:]')"

if [[ "${table_count}" != "0" ]]; then
  [[ "${confirm_overwrite}" == "true" ]] \
    || fail "目标数据库已有 ${table_count} 张业务表；确认覆盖时增加 --confirm-overwrite"

  mkdir -p "${deploy_dir}/backups"
  chmod 700 "${deploy_dir}/backups"
  safety_backup="${deploy_dir}/backups/pre-restore-$(date -u '+%Y%m%dT%H%M%SZ').dump"
  echo "正在创建目标数据库覆盖前备份：${safety_backup}"
  "${compose[@]}" exec -T postgres pg_dump \
    --username="${database_user}" \
    --dbname="${database_name}" \
    --format=custom \
    --no-owner \
    --no-privileges > "${safety_backup}"
  [[ -s "${safety_backup}" ]] || fail "目标数据库覆盖前备份为空"
  "${compose[@]}" exec -T postgres pg_restore --list < "${safety_backup}" >/dev/null
  chmod 600 "${safety_backup}"
  printf '%s  %s\n' "$(checksum_value "${safety_backup}")" "$(basename "${safety_backup}")" \
    > "${safety_backup}.sha256"
  chmod 600 "${safety_backup}.sha256"

  echo "正在重建目标数据库 ${database_name}……"
  "${compose[@]}" exec -T postgres dropdb \
    --username="${database_user}" \
    --force \
    --if-exists \
    "${database_name}"
  "${compose[@]}" exec -T postgres createdb \
    --username="${database_user}" \
    --owner="${database_user}" \
    "${database_name}"
fi

echo "正在恢复 ${dump_file} 到 ${database_name}……"
"${compose[@]}" exec -T postgres pg_restore \
  --username="${database_user}" \
  --dbname="${database_name}" \
  --exit-on-error \
  --no-owner \
  --no-privileges < "${dump_file}"
"${compose[@]}" exec -T postgres vacuumdb \
  --username="${database_user}" \
  --dbname="${database_name}" \
  --analyze-only >/dev/null

"${compose[@]}" up -d thingsboard
wait_for_command "ThingsBoard Core" "${compose[@]}" exec -T thingsboard \
  /bin/bash -c ': > /dev/tcp/127.0.0.1/8080'

"${compose[@]}" up -d wan-transport
wait_for_command "WAN Transport" "${compose[@]}" exec -T wan-transport \
  /bin/bash -c ': > /dev/tcp/127.0.0.1/8086'

echo "数据库恢复和服务启动完成。"
"${compose[@]}" ps
