# Vault 客户端示例

该项目通过依赖 `dev.cheng:hcv-spring-boot-starter:0.0.1-SNAPSHOT` 接入 Vault。
认证、Token 生命周期、缓存刷新、KV 读取和数据库连接适配都在
[`../vault_spring_boot_starter`](../vault_spring_boot_starter/README.md)，客户端不再复制这些代码。
这里仅保留 JPA、Flyway、用户接口、密码版本展示接口及业务配置。

## 启动

先按 [服务端说明](../vault_server/README.md) 启动 Vault/PostgreSQL 并完成初始化。
在项目根目录安装 starter 后，再从客户端目录运行：

```bash
./vault_client/mvnw -pl vault_spring_boot_starter -am install
cd vault_client
./mvnw spring-boot:run
```

全仓库构建及测试（从项目根目录执行）：

```bash
./vault_client/mvnw clean verify
```

默认证书路径相对于 `vault_client`，可使用以下环境变量指定绝对资源路径：

| 环境变量 | 默认值 |
| --- | --- |
| `VAULT_ADDR` | `https://127.0.0.1:8200` |
| `VAULT_CA` | `file:../vault_server/.local/server-tls/vault-ca.pem` |
| `VAULT_CLIENT_CERT` | `file:../vault_server/.local/client/client.crt` |
| `VAULT_CLIENT_KEY` | `file:../vault_server/.local/client/client.key` |

不需要管理员 Token，不从 `SPRING_DATASOURCE_PASSWORD` 读取密码。
数据库 URL 仍可用 `SPRING_DATASOURCE_URL` 覆盖。启动时数据库凭据和 KV 密码都必须可读。

## 客户端配置

```properties
hcv.vault.enabled=true
hcv.vault.uri=${VAULT_ADDR:https://127.0.0.1:8200}
hcv.vault.cert-role=spring-boot-demo
hcv.vault.database.enabled=true
hcv.vault.database.path=database/static-creds/hcv-demo
hcv.vault.database.poll-interval=30s
hcv.vault.kv.demo-service.mount=app-secrets
hcv.vault.kv.demo-service.path=demo-service
hcv.vault.kv.demo-service.poll-interval=5s
```

完整 SSL bundle 配置见 `src/main/resources/application.properties`。
原来的 `app.vault.*` 已迁移为 starter 的 `hcv.vault.*`，不提供旧配置名别名。
角色、路径、证书文件名和 HTTP 接口都属于本示例，未写死在 starter 内。

## 使用与更新 KV 密码

业务代码注入 starter 的公共 API：

```java
import dev.cheng.vaultstarter.VaultSecrets;

var snapshot = secrets.kv("demo-service").get();
String password = snapshot.value();
int version = snapshot.version();
```

每次外部服务操作获取当前快照，不要在构造器中永久缓存 `value()`。
`ServiceSecretController` 演示通过该 API 读取版本，接口不返回密码。

```bash
# 从项目根目录更新演示密码。setup 保留已有值；rotate 写入新随机值。
python3 vault_server/service_secret.py setup
python3 vault_server/service_secret.py rotate
curl http://localhost:8080/api/demo-service/secret-status
```

KV 不自动改动真实第三方系统的密码；这里验证的是 Vault 存储和客户端动态读取。
数据库继续由 Vault 每 10 秒自动轮换。

## 验证

```bash
# starter 单元测试和自动配置测试，不依赖外部服务
./vault_client/mvnw -pl vault_spring_boot_starter -am test
# 全部常规测试，需要本地 Vault/PostgreSQL
./vault_client/mvnw test
# 同一客户端进程中验证 KV、数据库和 Token 真实更新
python3 vault_server/verify-client-rotation.py
```

联合测试使用 Maven reactor 构建并引用本次源码中的 starter，无需预先安装 JAR。
外部 Python 管理进程根据 Java 测试的就绪信号更新两次 KV 密码；客户端只用只读 Token。
随后验证三轮数据库自动刷新，以及暂停刷新后旧密码认证失败的恢复。每轮驱逐连接并
检查 PostgreSQL PID 改变，再访问 `/api/users`。KV 状态接口检查新版本且不泄露密码。
测试期间 Token TTL 临时设为 20 秒，结束后恢复 15 分钟；数据库仍保持 10 秒轮换。

单元测试已移到 starter 模块，依赖真实服务的集成测试保留在本模块。
集成测试位于 `dev.cheng.vaultstarter` 测试包以检查内部缓存状态，并显式指定客户端启动类；
生产业务代码只依赖公共 `VaultSecrets` / `KvSecret` API。
