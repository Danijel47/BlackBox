# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/example/blackbox` contains the Spring Boot application bootstrap plus domain packages. WoW-specific code lives under `wow` with subpackages like `bot`, `blizzard`, `client`, `controller`, `service`, `config`, `helper`, and `properties`.
- `src/main/resources/application.properties` holds runtime configuration with environment-variable placeholders.
- `target/` is Maven build output (generated).
- `Dockerfile` and `docker-compose.yaml` define container builds and runtime config.

## Build, Test, and Development Commands
- `./mvnw clean package` builds the runnable JAR into `target/`.
- `./mvnw -DskipTests package` builds faster by skipping tests (used in `Dockerfile`).
- `./mvnw spring-boot:run` runs the app locally with your environment variables.
- `docker compose up --build` builds and runs the bot container on port `8100`.

## Coding Style & Naming Conventions
- Java code uses standard conventions: 4-space indentation, `UpperCamelCase` classes, `lowerCamelCase` methods/fields, and lowercase package names.
- Keep layers consistent with existing packages (e.g., Telegram command handling in `wow.bot`, Blizzard API integrations in `wow.blizzard`, external HTTP clients in `wow.client`). Put future APIs in sibling domain packages instead of mixing them into `wow`.
- Lombok is enabled; use annotations consistently with existing patterns.
- No formatter/linter is configured in this repo; match the surrounding style.

## Testing Guidelines
- No `src/test` directory is currently present and no test framework is configured in `pom.xml`.
- If you add tests, place them under `src/test/java` and run with `./mvnw test`.

## Commit & Pull Request Guidelines
- Git history has no commits yet, so no established commit message convention exists. Use concise, imperative messages (e.g., "Add title prediction cache").
- PRs should include: a short summary, configuration changes (if any), and how to run or verify locally. Include screenshots only if you change UI or logs that users see.

## Configuration & Secrets
- The app relies on env vars referenced in `application.properties`, including `TELEGRAM_RIO_BOT_TOKEN` and Blizzard API settings.
- When running locally, export these variables or provide them via your shell or `docker-compose.yaml`.
