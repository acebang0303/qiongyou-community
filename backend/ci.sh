#!/usr/bin/env bash
#
# 一键校验脚本（本地 / 任意 CI 平台复用）：编译 + 打包 + 跑测试
#
# 用法：
#   ./ci.sh               全量校验（含集成测试；需要本机 Docker 可用）
#   ./ci.sh --skip-tests  只编译打包（不需要 Docker）
#
# 说明：集成测试用 Testcontainers 临时起 MySQL/Redis/RabbitMQ 容器，
#       不会碰你本地正在跑的中间件与数据（见 AbstractIntegrationTest）。
#
set -euo pipefail
cd "$(dirname "$0")"

SKIP_TESTS=false
if [[ "${1:-}" == "--skip-tests" ]]; then
  SKIP_TESTS=true
fi

if [[ "$SKIP_TESTS" == "false" ]]; then
  if ! docker info >/dev/null 2>&1; then
    echo "ERROR: 集成测试需要可用的 Docker（Testcontainers 要起临时中间件）。" >&2
    echo "       只想校验编译的话用：./ci.sh --skip-tests" >&2
    exit 1
  fi
  echo "==> mvn clean verify（含 Testcontainers 集成测试）"
  mvn -B clean verify
else
  echo "==> mvn clean package -DskipTests"
  mvn -B clean package -DskipTests
fi

echo "==> 校验通过"
