# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 2.0.x   | ✅ Active support  |
| 1.0.x   | ❌ End of life     |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT** open a public GitHub issue
2. Email the maintainer directly or use GitHub's private vulnerability reporting
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

## Response Timeline

- **Acknowledgment**: within 48 hours
- **Initial assessment**: within 1 week
- **Fix release**: depends on severity

## Security Features

### Data-at-Rest Protection
- **Android Keystore (AES-256-GCM)**: API keys (`apiKey`) and E2E encryption keys (`e2eKey`) are encrypted using hardware-backed keys stored in the Android Keystore. Keys never leave the secure hardware (TEE/StrongBox).
- **Room Database**: Session and message data are stored locally. Sensitive fields (API keys) are encrypted before persistence.
- **Migration**: Legacy plaintext data is automatically re-encrypted on first read (`decryptOrRaw` pattern).

### Data-in-Transit Protection
- **TLS by default**: All network connections require HTTPS. Cleartext HTTP is only permitted for local model endpoints (`127.0.0.1`, `10.0.2.2`, `192.168.*`).
- **E2E Encryption (AES-256-GCM)**: Message content can be end-to-end encrypted between AgentHub instances using PBKDF2-derived keys with random salt/IV per message.
- **WebSocket + HTTP/SSE**: Transport layer supports both WebSocket and HTTP with Server-Sent Events for real-time streaming.

### Application Security
- **XSS protection**: `escapeHtml()` on all user-generated content
- **Protocol filtering**: blocks `javascript:`, `data:`, `vbscript:` URIs
- **No CSP violations**: pure local storage, no external data leaks
- **Network security config**: Restricts cleartext traffic, trusts only system CA certificates in release builds

### Permissions
| Permission | Purpose |
|:---|:---|
| `INTERNET` | Network access for agent connections |
| `POST_NOTIFICATIONS` | Chat messages and connection status |
| `FOREGROUND_SERVICE` | Maintain persistent agent connections |
| `RECORD_AUDIO` | Voice input / voice chat mode |
| `READ_MEDIA_IMAGES` | Attach images to messages |
| `REQUEST_INSTALL_PACKAGES` | Self-update via GitHub Releases (optional) |

### Known Limitations
- `usesCleartextTraffic` is fully controlled via `network_security_config.xml` (default: denied)
- `REQUEST_INSTALL_PACKAGES` is only needed for self-update; remove if distributing via app stores
- User CA certificates are only trusted in debug builds for development/testing
