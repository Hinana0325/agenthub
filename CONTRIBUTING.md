# Contributing to AgentHub

Thank you for your interest in contributing! Here's how to get started.

## Development Setup

```bash
# Clone the repository
git clone https://github.com/Hinana0325/agenthub.git
cd agenthub/android
```

**Requirements:**
- JDK 17+
- Android SDK (compileSdk 36)
- Android Studio (recommended)

### Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests
./gradlew connectedDebugAndroidTest
```

## Project Structure

```
android/app/src/main/java/com/agenthub/app/
├── data/           # Data layer: Room DB, DataStore, repositories
│   ├── local/      # Room entities, DAOs, database
│   ├── model/      # Domain models
│   ├── repository/ # ChatRepository
│   ├── settings/   # SettingsDataStore
│   └── plugin/     # Plugin system
├── provider/       # Transport layer: AgentTransport, WebSocket, OpenAI HTTP
├── ui/             # Compose UI screens & ViewModels
│   ├── chat/       # Chat screen & related
│   ├── agents/     # Agent management
│   ├── settings/   # Settings screen
│   └── theme/      # Material 3 theming (incl. Liquid Glass)
├── navigation/     # Navigation graph
└── util/           # Utilities: CryptoManager, VoiceInput, LocalModel, etc.
```

## Code Style

### Kotlin
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Compose Material3 components
- All screens must support adaptive layouts (phone + tablet / foldable)
- Use `StateFlow` + `collectAsState()` for reactive UI
- Prefer `sealed interface` for UI state

### Testing
- Unit tests: `src/test/` — JUnit 4 + kotlinx-coroutines-test
- Instrumented tests: `src/androidTest/` — Espresso + Compose Testing
- Run `./gradlew testDebugUnitTest` before submitting

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
