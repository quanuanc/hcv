# 项目说明

- `vault_server/`：本地 HashiCorp Vault Server，启动后地址为 `https://127.0.0.1:8200`，管理 PostgreSQL `hcv_demo` 数据库账号的密码。
- 获取数据库凭据分两步：
  1. 调用 `POST /v1/auth/cert/login`，提供客户端证书及私钥，JSON 请求体为 `{"name":"spring-boot-demo"}`，从响应的 `auth.client_token` 获取 Token。
  2. 调用 `GET /v1/database/static-creds/hcv-demo`，通过 `X-Vault-Token` 请求头提供 Token，无需客户端证书，从响应的 `data.username` 和 `data.password` 获取当前用户名和密码。
- 两个接口均使用 HTTPS，客户端需要信任 Vault 的服务器 CA。启动与配置步骤见 `vault_server/README.md`。
- `vault_client/`：准备用于编写 HCV 接入 Spring Boot 案例的客户端代码，演示证书登录及通过 Token 获取数据库凭据。
