# HCV 学习项目

学习通过 HashiCorp Vault 管理 PostgreSQL 凭据，并由客户端使用证书登录后通过 Token 读取密码。

```text
hcv/
├── vault_server/   # Vault 启动、配置、验证脚本及操作说明
└── README.md
```

后续 Spring Boot 应用可作为独立目录添加到项目根目录，与 `vault_server/` 并列。

Vault 环境配置、两步认证调用、数据库密码轮换和 Bruno 所需证书说明见
[vault_server/README.md](vault_server/README.md)。运行其中命令前，先进入 `vault_server/`。

本地证书、私钥、管理员 Token 和运行数据位于 `vault_server/.local/`，不纳入 Git。
Python 缓存、环境变量文件、日志及生成的证书文件同样由根目录 `.gitignore` 排除。
