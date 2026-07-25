# Infrastructure

当前 Compose 提供 MySQL 与 Java 后端。Python Agent、Redis 和向量库会在下一阶段按真实需求加入。

```bash
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/docker-compose.yml up --build
```

后端健康检查地址为 `http://localhost:8080/actuator/health`。

如果希望从 IntelliJ IDEA 启动 Java，只启动数据库即可：

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d mysql
```

然后在 IDEA 的运行配置中设置：

```text
SPRING_PROFILES_ACTIVE=local
INTERNAL_SERVICE_TOKEN=local-dev-internal-token
```

`application-local.properties` 仅包含本机演示数据库的默认账号。公开部署时必须通过环境变量提供独立强密码和内部服务令牌。
