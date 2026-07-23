# Agent Control Center TLS 证书锁定规范

本文件定义 Agent Control Center 的 TLS 证书锁定（Certificate Pinning）策略，作为 Android（Kotlin）与 iOS（Swift）双端共享的永久契约。pin 列表以本文件为单一事实来源，双端必须保持一致。

## 1. 背景与目标

证书锁定用于防止中间人攻击（MITM）。即使攻击者持有受信任 CA 签发的证书，只要其证书公钥不在 pin 列表中，连接即被拒绝。

Agent Control Center 连接多种 Agent 端点，其中公网固定 API 端点应锁定，本地端点和用户自定义端点不应锁定。

## 2. 锁定范围

### 2.1 应锁定的域名（公网固定 API）

| 域名 | 用途 | 备注 |
|------|------|------|
| `api.openai.com` | OpenAI / OpenAI 兼容 AgentType | 所有走 OpenAI 协议的 Agent 共用 |

### 2.2 候选锁定域名（Marketplace 公网请求）

| 域名 | 用途 | 备注 |
|------|------|------|
| `openclaw.supplies` | OpenClaw Marketplace API | 可选，加固 Marketplace 请求 |
| `clawhub.ai` | ClawHub API | 可选，加固 Marketplace 请求 |

### 2.3 不锁定的域名

以下端点**不应锁定**，因为地址不固定或为本地服务：

- `127.0.0.1` / `localhost`（ComfyUI `:8188`、OpenWebUI `:3000`、Ollama `:11434` 等本地端点）
- `10.x` / `172.16-31.x` / `192.168.x`（局域网自部署服务）
- 用户自定义 serverUrl（地址不固定）
- WebSocket Agent 端点（Hermes/OpenClaw/OpenCode，serverUrl 用户自配）

## 3. Pin 策略

### 3.1 Pin 格式

使用 SPKI（Subject Public Key Info）SHA-256 Base64 编码，前缀 `sha256/`：

```
sha256/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUV=
```

### 3.2 Primary + Backup Pin

每个域名配置 **2 个 pin**：

- **Primary pin**：当前证书公钥的 SPKI hash
- **Backup pin**：备用密钥的 SPKI hash（用于密钥轮换时无缝切换）

> ⚠️ **重要**：必须配置 backup pin。服务端密钥轮换时若仅有 primary pin 会导致连接失败。

### 3.3 Pin 获取方法

```sh
# 获取某域名的 SPKI SHA-256（primary pin）
echo | openssl s_client -connect api.openai.com:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64

# 输出示例（非真实值，需实际获取）:
# aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789aBcDeFg=
```

使用时加 `sha256/` 前缀。

## 4. Pin 列表（单一事实来源）

> ⚠️ **待填入实际值**：以下 pin 为占位，需通过第 3.3 节方法获取真实值后填入。

### api.openai.com

```
# Primary（待获取）
sha256/REPLACE_WITH_ACTUAL_PRIMARY_PIN=

# Backup（待获取）
sha256/REPLACE_WITH_ACTUAL_BACKUP_PIN=
```

## 5. 实现规范

### 5.1 Android（Kotlin）

- 使用 OkHttp `CertificatePinner`
- `CertificatePinnerFactory` 构建 pin map，注入 OkHttpClient
- `isPublicEndpoint(url)` 判断是否应锁定（排除 localhost / 局域网）
- 用户设置开关（Settings → 安全 → 证书锁定），默认开启

### 5.2 iOS（Swift）

- 实现 `URLSessionDelegate`，在 `urlSession(_:didReceive:completionHandler:)` 中校验 `SecTrust` 叶子证书 SPKI
- 注入到 `OpenAIHTTPTransport` / `WebSocketTransport` / `McpClient` 的 `URLSession(configuration:delegate:)`
- pin 列表从本文件同步，与 Android 保持一致

## 6. 密钥轮换流程

1. 服务端生成新密钥对，获取新证书
2. 用新证书公钥计算新 SPKI hash
3. 将新 hash 作为 backup pin 加入列表（此时 primary 仍为旧 pin）
4. 服务端切换到新证书
5. 确认客户端连接正常后，将新 hash 提升为 primary，旧 hash 降级为 backup
6. 旧证书过期后移除旧 pin

> ⚠️ 跳过第 3-4 步直接更换 pin 会导致客户端连接失败。

## 7. 故障处理

- Pin 校验失败时，连接必须被拒绝，不得回退到系统 CA 验证
- UI 应提示"证书锁定校验失败，连接已被拒绝"
- 用户可在 Settings → 安全 中临时关闭证书锁定（降级到系统 CA 验证）

## 8. 版本与变更

- 本文件为协议层永久契约
- Pin 列表变更须双端同步发布
- 新增域名锁定须先在本文档登记，再在双端实现
