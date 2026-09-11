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
source_container="${SOURCE_TB_CONTAINER:-thingsboard-local-thingsboard-1}"
source_database="${SOURCE_TB_DATABASE:-thingsboard}"
source_user="${SOURCE_TB_DATABASE_USER:-thingsboard}"
output_dir="${1:-${deploy_dir}/backups}"
timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
dump_file="${output_dir}/thingsboard-${timestamp}.dump"
temporary_dump=""

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

cleanup() {
  if [[ -n "${temporary_dump}" && -f "${temporary_dump}" ]]; then
    rm -f "${temporary_dump}"
  fi
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || fail "未安装 Docker"
docker inspect "${source_container}" >/dev/null 2>&1 || fail "找不到源容器 ${source_container}"
[[ "$(docker inspect "${source_container}" --format '{{.State.Running}}')" == "true" ]] \
  || fail "源容器 ${source_container} 未运行"
docker exec "${source_container}" sh -lc 'command -v pg_dump' >/dev/null 2>&1 || fail "源容器缺少 pg_dump"

mkdir -p "${output_dir}"
chmod 700 "${output_dir}"
temporary_dump="$(mktemp "${output_dir}/.thingsboard-${timestamp}.XXXXXX")"

echo "正在从 ${source_container} 创建 PostgreSQL 一致性快照……"
docker exec "${source_container}" pg_dump \
  --username="${source_user}" \
  --dbname="${source_database}" \
  --format=custom \
  --no-owner \
  --no-privileges > "${temporary_dump}"

[[ -s "${temporary_dump}" ]] || fail "数据库备份为空"
docker exec -i "${source_container}" pg_restore --list < "${temporary_dump}" >/dev/null
mv "${temporary_dump}" "${dump_file}"
temporary_dump=""
chmod 600 "${dump_file}"
printf '%s  %s\n' "$(checksum_value "${dump_file}")" "$(basename "${dump_file}")" \
  > "${dump_file}.sha256"
chmod 600 "${dump_file}.sha256"

echo "备份完成：${dump_file}"
echo "校验文件：${dump_file}.sha256"
echo "注意：最终割接前仍需停止外部写入并重新执行一次本脚本。"
