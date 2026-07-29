# StudyPilot 本地容器栈

Compose 提供 MySQL、Spring Boot、单进程 FastAPI、Vue/Nginx；Prometheus 是可选
profile。浏览器只通过 Nginx 同源访问 Java `/api/**`，不会暴露 Python
`/internal/**`。

```bash
cp infra/.env.example infra/.env
# 填写数据库密码，并按注释生成三个稳定密钥
docker compose --env-file infra/.env -f infra/docker-compose.yml up --build
```

打开 `http://localhost:5173`。健康检查：

```text
http://localhost:5173/health
http://localhost:8080/actuator/health
```

启用仅监听回环地址的 Prometheus：

```bash
docker compose --profile observability \
  --env-file infra/.env -f infra/docker-compose.yml up --build
```

首次启动 FastEmbed 会下载模型，所需时间取决于网络。MySQL、Agent SQLite、
Qdrant 和 Hugging Face 缓存均使用命名卷持久化。当前限流与 SQLite 都面向单实例；
横向扩容时需迁移到 Redis 和服务化存储。

只希望在 IDEA 中运行 Java 时，可以单独启动 MySQL：

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d mysql
```
