# Contributing to AgentHub

Thank you for your interest in contributing! Here's how to get started.

## Development Setup

```bash
# Clone the repository
git clone https://github.com/Hinana0325/agenthub.git
cd agenthub

# Install dependencies
npm install

# Run PWA locally
npx serve .

# Run tests
node tests.js
```

### Android Development

```bash
# Sync Capacitor
npx cap sync android

# Build debug APK
cd android && ./gradlew assembleDebug

# Open in Android Studio
npx cap open android
```

**Requirements:**
- JDK 17+
- Android SDK (compileSdk 36)
- Android Studio (recommended)

## Project Structure

- **Web layer** (PWA): `*.js`, `*.css`, `*.html` — vanilla JS, zero dependencies
- **Android layer**: `android/app/src/main/java/` — Kotlin + Jetpack Compose
- **Tests**: `tests.js` — 47 unit tests, run with `node tests.js`

## Code Style

### Kotlin
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Compose Material3 components
- All screens must support adaptive layouts (phone + tablet)

### JavaScript
- Vanilla JS, no frameworks
- Use `escapeHtml()` for all user-generated content (XSS protection)
- Run tests before submitting: `node tests.js`

## Submitting Changes

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit with clear messages: `git commit -m "feat: add my feature"`
4. Push to your fork: `git push origin feature/my-feature`
5. Open a Pull Request

### Commit Convention

We use [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` — new feature
- `fix:` — bug fix
- `docs:` — documentation only
- `refactor:` — code change that neither fixes a bug nor adds a feature
- `test:` — adding or fixing tests
- `chore:` — build process, tooling, etc.

## Reporting Bugs

Use the [Bug Report](https://github.com/Hinana0325/agenthub/issues/new?template=bug_report.md) template.

## Feature Requests

Use the [Feature Request](https://github.com/Hinana0325/agenthub/issues/new?template=feature_request.md) template.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
