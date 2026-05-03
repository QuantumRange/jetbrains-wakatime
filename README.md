This fork implements issues from the original repository that went stale.
Most of the changes implemented here are written by AI.
This is because this is all temporary.
This only exists until the fixes are implemented in the original repository.

## Development

Open the repository root as a Gradle project in IntelliJ IDEA. Do not use `./gradlew openIdea`; that generates legacy `.ipr` project files and bypasses the Gradle-based plugin project setup.

For local plugin development:

- use the bundled `Run IDE with Plugin` run configuration from `.run/`
- or run `./gradlew runIde`
- use a Gradle JVM on Java 21 or newer when opening the project

Useful tasks:

- `./gradlew build`
- `./gradlew prepareSandbox`
- `./gradlew buildPlugin`
