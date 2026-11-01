# HCV 学习项目

通过 HashiCorp Vault 管理数据库凭据及 KV 密码，使用证书登录、Token 读取和自动刷新。

```text
hcv/
├── pom.xml                    # Maven reactor：按依赖顺序构建两个 Java 模块
├── vault_spring_boot_starter/  # 可复用的 Spring Boot starter
├── vault_client/               # 引用 starter 的 JPA / HTTP 演示应用
└── vault_server/               # 本地 Vault 启动、配置及端到端验证脚本
```

- [starter 接入及公共 API](vault_spring_boot_starter/README.md)
- [客户端运行说明](vault_client/README.md)
- [Vault 服务端配置](vault_server/README.md)

```bash
# 只构建/安装 starter，无需 Vault 或数据库
./vault_client/mvnw -pl vault_spring_boot_starter -am install
# 全仓库验证：先启动并配置 Vault/PostgreSQL
./vault_client/mvnw clean verify
# 真实 KV 更新、数据库轮换、Token 过期验证
python3 vault_server/verify-client-rotation.py
```

本地证书、私钥、管理员 Token 和运行数据位于 `vault_server/.local/`，不纳入 Git。
