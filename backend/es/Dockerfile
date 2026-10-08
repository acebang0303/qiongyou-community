# ★ P2-5：把 IK 中文分词插件固化进 ES 镜像
#   之前插件是 docker exec 装进运行中的容器里的 —— 容器一旦重建（compose down/up）就丢，
#   而且换机器部署还得手动重装。写进镜像后每次都是同样的结果。
#   插件版本必须与 ES 版本严格一致。
FROM docker.elastic.co/elasticsearch/elasticsearch:8.8.2

RUN bin/elasticsearch-plugin install -b https://get.infini.cloud/elasticsearch/analysis-ik/8.8.2
