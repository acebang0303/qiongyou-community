#!/usr/bin/env bash
# 一次性压测执行脚本：同一个 bench.jmx 分别打基线(8082)与优化版(8081)。
# 基线用 X-User-Id 头（老认证方式），优化版用 Authorization: Bearer <jwt>；
# 除这个请求头与目标端口外，其余参数（线程数/循环/路径/方法）完全一致。
set -u
cd "$(dirname "$0")"

TOKEN=$(cat /tmp/bench_token.txt)
THREADS=${THREADS:-50}
RAMP=${RAMP:-5}
LOOPS=${LOOPS:-20}

run() {
  local ver=$1 port=$2 hname=$3 hvalue=$4 scen=$5 method=$6 path=$7
  cat > bench.props <<EOF
port=$port
path=$path
method=$method
hname=$hname
hvalue=$hvalue
threads=$THREADS
ramp=$RAMP
loops=$LOOPS
EOF
  jmeter -n -t bench.jmx -l "out-$ver-$scen.jtl" -q bench.props >/dev/null 2>&1
  echo "  完成 $ver / $scen"
}

for scen in detail list hot search like; do
  case $scen in
    detail) M=GET;  P=/api/notes/30 ;;
    list)   M=GET;  P="/api/notes/list?page=1&size=10" ;;
    hot)    M=GET;  P=/api/notes/hot ;;
    search) M=GET;  P="/api/notes/search?keyword=%E4%B8%89%E4%BA%9A" ;;
    like)   M=POST; P=/api/notes/30/like ;;
  esac
  run base 8082 X-User-Id 5                   "$scen" "$M" "$P"
  run opt  8081 Authorization "Bearer $TOKEN" "$scen" "$M" "$P"
done

echo "全部完成"
