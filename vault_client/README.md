# Vault 数据库凭据接入

客户端使用 Spring Vault 4.1.1、Spring Boot SSL bundles 和 HikariCP。
数据库密码不再从 `SPRING_DATASOURCE_PASSWORD` 读取。运行前请按
[`../vault_server/README.md`](../vault_server/README.md) 启动并配置 Vault 和 PostgreSQL。

```bash
cd vault_client
./mvnw spring-boot:run
```

默认路径相对于 `vault_client`。从其他目录启动 jar 时，使用环境变量覆盖证书位置，
值可以是绝对 `file:/...` 资源路径：

| 环境变量 | 默认值 |
| --- | --- |
| `VAULT_ADDR` | `https://127.0.0.1:8200` |
| `VAULT_CA` | `file:../vault_server/.local/server-tls/vault-ca.pem` |
| `VAULT_CLIENT_CERT` | `file:../vault_server/.local/client/client.crt` |
| `VAULT_CLIENT_KEY` | `file:../vault_server/.local/client/client.key` |

应用不需要管理员 Token。数据库 URL 仍可通过 `SPRING_DATASOURCE_URL` 覆盖。
Vault 角色、凭据路径和轮询间隔分别使用 `app.vault.cert-role`、
`app.vault.credentials-path` 和 `app.vault.poll-interval` 配置。
Hikari 连接池参数仍使用 `spring.datasource.hikari.*`；不要配置连接池自己的凭据，
实际物理连接的凭据由 Vault 提供。

## 生命周期

- Spring Vault `ClientCertificateAuthentication` 负责证书登录，`VaultTemplate` 负责读取。
  登录和读取使用两个独立 JDK HTTP 客户端及 SSLContext，读取客户端不加载客户端私钥。
  Spring Boot SSL bundles 负责 PEM 解析和信任配置，不关闭 HTTPS 主机名校验。
- 当前服务端策略不允许 Token 续租、查询自身或撤销自身，因此用小型
  `ExpiringSessionManager` 缓存登录结果，根据登录 TTL 提前最多 5 秒失效，
  下次取密时重新登录。读取返回 403 时，丢弃 Token 并重新登录重试一次。
  不使用会自动调用续租接口的默认生命周期策略。
- 启动时先取密再创建 DataSource，Vault 不可用则启动失败，避免 Flyway/JPA 使用空密码。
- 后台按 `min(data.ttl, poll-interval)` 重新读取，最短间隔 1 秒，默认最多 30 秒检查一次。
  TTL 到期不代表一定已经产生新密码；会继续检查。手动提前轮换也能被后续检查发现。
- `RefreshingDataSource` 放在 Hikari **下面**。Hikari 新建物理连接时，从内存读取
  当前凭据；每次业务借用连接不访问 Vault。这样无需重建连接池或反复刷新 Spring Context。
- PostgreSQL 返回密码认证错误 `28P01` 时，重新取密并重试连接一次。
  并发使用同一份旧凭据的失败请求合并刷新。其他 SQLState 不触发取密；不重试 SQL 或事务。
- 后台刷新失败保留最后成功的凭据并继续重试，日志不记录密码、Token 或 Vault 响应体。
  已认证的数据库连接可以继续工作；Vault 长时间不可用且缓存密码已失效时，无法保证新连接成功。
- 不强制驱逐现有连接，避免中断事务。凭据保留在进程内存，不写入文件。

## 验证

```bash
# 单元测试：不依赖 Vault/PostgreSQL
./mvnw -Dtest=VaultCredentialsTests test

# 全部测试：需要本地 Vault、证书和 PostgreSQL
./mvnw test
```

测试覆盖 Token TTL 重新登录、密码 TTL 刷新、读取 403、Vault 故障时保留缓存、
旧凭据失败后的刷新合并、认证重试上限，以及其他连接错误不触发刷新。
集成测试使用真实 Vault 登录取得当前密码，并通过故意提供错误密码验证真实 PostgreSQL
物理连接的认证失败恢复；不会更改 Vault 服务端配置或数据库密码。
普通集成测试不等待服务端真实轮换。

要人工验证真实轮换，可在应用持续运行时由管理员按服务端 README 执行
`database/rotate-role/hcv-demo`。随后需验证新建物理连接，而不只是复用连接执行查询。
测试环境可临时缩短 Hikari `max-lifetime`（不低于 30000 毫秒），让池中的连接自然更换。


### 跨越真实轮换和 Token 过期

```bash
# 从项目根目录执行
python3 vault_server/verify-client-rotation.py
```

该入口运行默认跳过的 `VaultRotationE2ETests`，使用真实 Vault、PostgreSQL 和随机端口
Spring Boot HTTP 服务。三轮验证后台取密；第四轮通过暂停后台刷新验证旧密码连接失败后
的自动恢复。每轮强制连接池建立新连接，检查 `pg_backend_pid()` 改变并请求 `/api/users`。
同时使用服务端临时签发的 20 秒 Token 验证重新登录，结束后恢复证书角色的原始 Token TTL。
数据库密码保持 10 秒轮换。该测试不会将管理员 Token 传给客户端。

2026-11-01 本机实测通过（Vault 每 10 秒自动轮换，测试进程全程不重启）：

| 阶段 | PostgreSQL 连接 PID | 结果 |
| --- | --- | --- |
| 初始连接 | 17720 | 初始化成功 |
| 自动轮换 1 | 17731 | 旧密码拒绝、后台刷新、新连接成功、HTTP 200 |
| 自动轮换 2 | 17738 | 旧密码拒绝、后台刷新、新连接成功、HTTP 200 |
| 自动轮换 3 | 17750 | 旧密码拒绝、后台刷新、新连接成功、HTTP 200 |
| 暂停后台刷新后的自动轮换 4 | 17760 | 旧缓存认证失败后自动取密重连、HTTP 200 |

应用 Token 在真实 20 秒有效期后发生变化，重新登录成功。测试结束已回读确认：
数据库 `rotation_period=10`，证书角色三个 Token TTL 配置均恢复为 `900` 秒。
