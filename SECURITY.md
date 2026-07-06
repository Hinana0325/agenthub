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

- XSS protection: `escapeHtml()` on all user-generated content
- E2E encryption: AES-256-GCM for message content
- Protocol filtering: blocks `javascript:`, `data:`, `vbscript:` URIs
- No CSP violations: pure local storage, no external data leaks
