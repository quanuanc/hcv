# Vault：证书登录 + Token 读取数据库密码

当前入口为 **https://127.0.0.1:8200**，仅本机访问。Vault 仍为学习用开发模式，
数据保存在内存中。原来的 HTTP 入口和 `dev-only-root` Token 已停用。
Postgres.app PostgreSQL 17 中的 `hcv_demo` 数据库和数据保留，
`hcv_demo_app` 的密码继续由 Vault 每 10 秒轮换（便于本地测试）。

## 两步认证

| 步骤 | 接口 | 客户端提交 | 服务端行为 |
| --- | --- | --- | --- |
| 1 | `POST /v1/auth/cert/login` | 客户端证书 + 私钥，JSON 中的证书角色名 | 验证登记的证书及私钥持有证明，签发 15 分钟 Token |
| 2 | `GET /v1/database/static-creds/hcv-demo` | `X-Vault-Token`，不提交客户端证书 | 校验 Token 及读取权限，返回当前数据库用户名和密码 |

两个步骤都使用 HTTPS，客户端都必须校验 Vault 的服务器证书。
私钥不会作为 HTTP 请求参数发送，而是在 TLS 握手时用于证明持有对应密钥。
第一步返回的 Token 是 Bearer Token，持有它即可在有效期内调用获授权接口。

**监听器允许不带客户端证书建立 TLS 连接，由证书登录接口强制验证客户端证书。**
因此缺少证书时登录会返回 HTTP 错误，而不是在 TLS 握手阶段失败。
TLS 握手发生在 HTTP 路径被解析之前，不能在同一监听器上按路径决定是否强制 mTLS。
该方式满足“登录必须证明客户端证书身份，后续请求只用 Token”的流程。

对应普通 Vault listener 的关键选项为以下默认值：

```hcl
tls_disable = false
tls_disable_client_certs = false
tls_require_and_verify_client_cert = false
```

最后一项不能设为 `true`，否则第二步也必须携带客户端证书。
当前使用 `vault server -dev -dev-tls` 提供 TLS listener，并启用 `cert` auth。

## 证书、密钥及权限

| 文件 / 配置 | 用途 |
| --- | --- |
| `.local/server-tls/vault-ca.pem` | 客户端用于验证 Vault 服务端证书的 CA |
| `.local/client/client.crt` | `spring-boot-demo` 的客户端证书，包含公钥 |
| `.local/client/client.key` | 客户端私钥，仅配置给需要登录的客户端 |
| `.local/admin-token` | 随机生成的本机管理员 Token，仅用于管理实验环境 |
| `auth/cert/certs/spring-boot-demo` | Vault 登记的客户端证书角色 |
| `hcv-demo-read.hcl` | 仅允许读取演示账号凭据的策略 |

客户端证书为 90 天有效的自签名证书，包含 `clientAuth` 扩展。
Vault 登记并信任这张具体证书；另一个使用相同 CN 的证书也不能登录。
证书到期前需重新签发，并由管理员更新 Vault 的证书登记。
`.local` 目录权限为 `0700`，私钥和管理员 Token 仅当前用户可读写，并已加入 `.gitignore`。
应用只需要 CA、客户端证书和客户端私钥，不需要管理员 Token 或服务端私钥。

登录后签发的 Token 仅含 `hcv-demo-read` 策略，不附带默认策略，
最长有效期为 15 分钟。客户端到期后重新进行证书登录；本演示不通过续租延长 Token 生命周期。
数据库密码每 10 秒更换，与 Token 的有效期独立。

## 使用 curl 完整演示

先进入项目目录：

```bash
cd /Users/jonas/Developer/Ongoing/hcv/vault_server
export VAULT_ADDR='https://127.0.0.1:8200'
export VAULT_CACERT="$PWD/.local/server-tls/vault-ca.pem"
unset VAULT_TOKEN VAULT_CLIENT_CERT VAULT_CLIENT_KEY
```

第一步：带客户端证书和私钥登录，提取 Token，不需要预先提供任何 Token：

```bash
CLIENT_TOKEN=$(curl --noproxy '*' --fail-with-body --silent --show-error \
  --cacert "$VAULT_CACERT" \
  --cert .local/client/client.crt \
  --key .local/client/client.key \
  --header 'Content-Type: application/json' \
  --data '{"name":"spring-boot-demo"}' \
  "$VAULT_ADDR/v1/auth/cert/login" | jq -er '.auth.client_token')
```

第二步：只带 Token，不配置客户端证书或私钥，读取当前凭据：

```bash
curl --noproxy '*' --fail-with-body --silent --show-error \
  --cacert "$VAULT_CACERT" \
  --header "X-Vault-Token: $CLIENT_TOKEN" \
  "$VAULT_ADDR/v1/database/static-creds/hcv-demo" | jq '.data'
```

这里的 `--cacert` 用来验证服务器身份，不是客户端证书。
不要使用 `-k` 跳过服务器证书验证。

## Python 示例与连接数据库

`two_step_client.py` 用标准库实现两个独立 TLS 上下文：
登录上下文加载客户端证书及私钥，取密上下文只加载服务端 CA。
两步使用独立连接，不复用登录时的 mTLS 会话。

```bash
# 跑通两个步骤，只输出成功信息和非敏感元数据
python3 two_step_client.py demo

# 分开执行；第二个进程不加载客户端证书或私钥
export VAULT_TOKEN="$(python3 two_step_client.py login)"
python3 two_step_client.py read

# 使用上述应用 Token 自动取用当前密码并进入 psql
python3 hcv_demo.py connect
```

进入 psql 后可以运行 `SELECT current_user, current_database();`，通过 `\q` 退出。
应用新建数据库连接时应取用最新密码，或在认证失败后刷新凭据并重试。
密码轮换不会断开已经认证成功的 PostgreSQL 连接。

## Spring Boot 接入方式

同样配置两个 HTTP 客户端 / TLS 上下文：

1. 登录客户端的 trust 配置加载 `vault-ca.pem`，key 配置加载 `client.crt` 和 `client.key`，
   向证书登录接口提交 `{"name":"spring-boot-demo"}`，取出 `auth.client_token`。
2. 读取客户端只配置 `vault-ca.pem`，不配置客户端 key material，
   请求读取接口时添加 `X-Vault-Token` 请求头，解析 `data.username` 和 `data.password`。

需要 Java keystore 时可将客户端证书及私钥转换成 PKCS#12；服务端 CA 放入独立 truststore。
不要给两个客户端共用配置了客户端私钥的 SSLContext。
若使用 Spring Vault / Spring Cloud Vault 的自动配置，要确认其 HTTP 客户端配置是否会
在所有请求上发送客户端证书；要严格做到第二步不提交证书，应为两步提供独立的 HTTP 客户端。

## 管理与重启

管理员操作时才加载管理员 Token：

```bash
export VAULT_ADDR='https://127.0.0.1:8200'
export VAULT_CACERT="$PWD/.local/server-tls/vault-ca.pem"
export VAULT_TOKEN="$(cat .local/admin-token)"

vault read database/static-roles/hcv-demo
python3 hcv_demo.py status
vault write -force database/rotate-role/hcv-demo
```

服务运行在 Rex 的 **Vault-TLS** 标签页，按 `Ctrl+C` 停止。
重新启动：

```bash
./start-vault-tls.sh
```

在另一个终端进入本目录，清除旧环境变量，然后重建数据库和证书认证配置：

```bash
unset VAULT_TOKEN VAULT_ADDR VAULT_CACERT
python3 configure-tls-auth.py
```

该脚本仅限管理员执行，会读取本机管理员 Token 文件并重建数据库连接、轮换角色、
读取策略、证书登录角色，再运行认证测试。数据库已有数据会保留。
Vault 开发模式重启会清空配置和 KV 数据，且重新生成服务器 CA；客户端需要使用更新后的 CA。
客户端身份的证书和私钥保持不变。若存在旧配置的客户端，需重新加载 CA 并重新登录。
Vault 与 PostgreSQL 必须持续运行，Mac 休眠期间不能保证轮换按时执行。

本次切换前的学习 KV 当前值已备份到 `.local/kv-current-backup.json` 并恢复到新服务器；
该备份不是完整的 Vault 快照，也不包含 KV 历史版本。

## 验证结果

已实测以下行为：

- 不带客户端证书登录失败。
- 未登记的证书，即使 CN 相同，也登录失败。
- 合法证书登录成功，签发 15 分钟只读 Token。
- 不带客户端证书，通过新 HTTPS 连接可用 Token 读取凭据。
- 无 Token、无效 Token、已撤销 Token、旧 `dev-only-root` Token 读取均失败。
- 只带证书、不带 Token 读取失败。
- 应用 Token 无法调用管理员 API 或触发密码轮换。
- 取出的密码可连接 `hcv_demo`，轮换配置为 10 秒。

数据库轮换实验另可由管理员执行 `python3 hcv_demo.py verify`，
它临时改用 10 秒周期验证自动轮换和新旧密码，然后恢复执行前的轮换周期。

## 数据库权限

`hcv_demo_app` 是非超级用户，拥有 `hcv_demo`。
`hcv_vault_manager` 是 Vault 专用非超级用户，通过 `CREATEROLE` 和对应用角色的
`ADMIN OPTION` 执行轮换，不继承应用角色，也不允许直接 `SET ROLE`。
这两个账号仅能连接演示库，Unix socket 和回环 TCP 都要求 SCRAM 密码认证；
其他账号原有认证规则保留。`pg_hba.conf` 的原始备份位于 Postgres.app 的 `var-17` 目录。
PostgreSQL 侧仍沿用本机回环连接 `sslmode=disable`。

参考：[Vault 证书认证](https://developer.hashicorp.com/vault/docs/auth/cert)、
[证书认证 API](https://developer.hashicorp.com/vault/api-docs/auth/cert)、
[listener TLS 配置](https://developer.hashicorp.com/vault/docs/configuration/listener/tcp)、
[数据库静态角色](https://developer.hashicorp.com/vault/api-docs/secret/databases#create-static-role)。


## 客户端真实轮换端到端测试

```bash
# 在项目根目录执行；需要 Vault 与 PostgreSQL 已启动。
python3 vault_server/verify-client-rotation.py
```

脚本将数据库密码轮换周期设为 10 秒，并临时将证书角色签发的 Token TTL 设为
20 秒，然后启动真实 Spring Boot HTTP 服务运行专用测试。管理员 Token 仅供 Python
脚本调整服务端配置，不传给 Java 客户端。测试不手动触发密码轮换，等待 Vault 调度器执行。

测试检查三轮密码变化：旧密码被 PostgreSQL 拒绝、客户端后台任务取得新密码、
驱逐连接池连接后 PostgreSQL 后端 PID 改变、真实 `/api/users` 请求返回 200。
第四轮暂停客户端后台任务，让缓存保持旧密码，验证物理连接认证失败后的取密重试。
最后检查应用 Token 已重新获取。Token 和密码不写入测试日志。

正常结束或测试失败时，脚本都会在 `finally` 中恢复原来的证书 Token TTL，
数据库密码仍保持每 10 秒轮换。测试通常需要约一分钟；默认 Maven 测试不会执行该耗时测试。
不要同时运行多份该脚本，避免临时 Token TTL 配置互相覆盖。
