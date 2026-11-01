# HCV Spring Boot Starter

可复用的证书认证与密码热更新组件。当前验证环境：Java 21、Spring Boot 4.1.2、
Spring Vault 4.1.1、PostgreSQL。一个普通 JAR 同时包含公共 API、自动配置和依赖声明；
不是可执行 Spring Boot 应用，也不依赖演示客户端。

## 构建与引用

在仓库根目录执行（使用项目已有的 Maven Wrapper）：

```bash
# 构建、测试并安装 starter 及其 parent 到本机 Maven 仓库；不需要 Vault/PostgreSQL。
./vault_client/mvnw -pl vault_spring_boot_starter -am install
# 构建并验证整个项目；客户端集成测试需要本地 Vault/PostgreSQL。
./vault_client/mvnw clean verify
```

其他 Spring Boot 项目引用：

```xml
<dependency>
    <groupId>dev.cheng</groupId>
    <artifactId>hcv-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

无需 `@Import` 或额外 `@ComponentScan`。自动配置通过
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册。
默认关闭，添加依赖不会发起 Vault 请求。若发布到私有 Maven 仓库，也需发布
根目录的 `dev.cheng:hcv-parent` POM；本项目没有执行远程发布。

## 启用及证书配置

```properties
hcv.vault.enabled=true
hcv.vault.uri=https://127.0.0.1:8200
hcv.vault.cert-role=my-app

spring.ssl.bundle.pem.vault-login.truststore.certificate=file:/certs/vault-ca.pem
spring.ssl.bundle.pem.vault-login.keystore.certificate=file:/certs/client.crt
spring.ssl.bundle.pem.vault-login.keystore.private-key=file:/certs/client.key
spring.ssl.bundle.pem.vault-read.truststore.certificate=file:/certs/vault-ca.pem
```

`vault-login` 用于证书登录；`vault-read` 只包含服务器 CA，用于 Token 请求。
支持通过 `hcv.vault.login-ssl-bundle` / `read-ssl-bundle` 修改 bundle 名称。
服务器地址必须为 HTTPS，不跳过证书验证。

## 数据库静态凭据（可选）

应用需自行依赖 `spring-boot-starter-jdbc` 或 JPA starter，以及相应 JDBC 驱动。
starter 的 JDBC 依赖为 optional，KV-only 使用者不会被强制引入连接池。

```properties
hcv.vault.database.enabled=true
hcv.vault.database.path=database/static-creds/my-app
hcv.vault.database.poll-interval=30s
spring.datasource.url=jdbc:postgresql://localhost:5432/my_database
spring.datasource.hikari.maximum-pool-size=10
```

没有应用自定义 DataSource 时，starter 在 Boot 默认 DataSource 自动配置之前创建 Hikari。
它使用 `RefreshingDataSource` 在新建物理连接时提供当前凭据。出现认证失败时刷新并重试
一次，不重试 SQL 或事务，不强制终止旧连接。应用提供自己的 DataSource 时，这整个
数据库适配配置退让，**不会自动改造应用已有的 DataSource**。

默认密码认证 SQLState 为 PostgreSQL `28P01`，配置项为
`hcv.vault.database.authentication-sql-state`。其他数据库需验证驱动语义后使用；
当前只验证了 PostgreSQL。支持的是 `/database/static-creds/...`，不是临时账号租约管理。

## 多个 KV v2 密码

```properties
hcv.vault.kv.mail.mount=app-secrets
hcv.vault.kv.mail.path=mail
hcv.vault.kv.mail.field=password
hcv.vault.kv.mail.poll-interval=5s

hcv.vault.kv.payment.mount=app-secrets
hcv.vault.kv.payment.path=payment
hcv.vault.kv.payment.field=api-key
hcv.vault.kv.payment.poll-interval=10s
```

`field` 默认 `password`，`poll-interval` 默认 `5s`。所有配置的条目都被视为必需，
启动时立即加载；字段为空、密码缺失或无读取权限会使启动失败。

通过公共 `VaultSecrets` API 获取命名的 `KvSecret`：

```java
public class MailClient {
    private final KvSecret password;

    public MailClient(VaultSecrets secrets) {
        this.password = secrets.kv("mail");
    }

    public void send() {
        var snapshot = password.get();
        String currentPassword = snapshot.value();
        // 将当前密码交给邮件客户端；不要打印或长期保存这个快照。
    }
}
```

`get()` 读取内存快照；后台自动更新。`version()` 返回 Vault KV 版本。
`refreshAfterFailure(snapshot)` 可在目标服务明确返回认证失败时触发一次合并刷新，
业务调用是否可重试由调用方判断。KV 不自动修改第三方系统密码，应由目标系统的轮换
流程更新 Vault。

## 生命周期与边界

- 同一上下文共用证书认证、Token 和 VaultTemplate。Token 按登录 TTL 缓存，提前最多
  5 秒视为失效，下次取密重新登录；403 时丢弃 Token 并重试一次。不要求续租权限。
- 数据库和 KV 复用 `RefreshingVaultSecret<T>` 的缓存、刷新调度、并发刷新合并及错误处理。
  数据库按 `min(ttl, poll-interval)` 检查；KV 按 poll interval 检查，实际最短间隔为 1 秒。
- starter 自有后台调度器，不对应用启用 `@EnableScheduling`。`refresh-tick` 默认 1 秒，
  `http-timeout` 默认 5 秒；两者均位于 `hcv.vault` 下。
- 关闭上下文时停止调度器和 HTTP 客户端；Hikari DataSource 由 Spring 关闭。
- 刷新失败保留最后成功值并继续尝试，失败的一个条目不会跳过其他条目，但网络超时会
  延迟同一轮的后续刷新。目前没有指数退避，也没有证书/CA 文件热重载。
- 密码不输出到日志。KV 被删除时也会保留最后成功值，这不是撤销通知机制。
- 只获取并缓存密码不等于目标服务自动生效：数据库已内置连接适配，其他业务必须每次
  使用当前快照，而不能在启动时读取一次后永久缓存。

自动配置测试覆盖默认关闭、无 JDBC 的多 KV 配置、自定义 DataSource 退让、缺失密码
启动失败；单元测试覆盖公共刷新与认证重试。真实轮换测试留在 `vault_client`，避免
library 的正常构建依赖本地 Vault/PostgreSQL。
