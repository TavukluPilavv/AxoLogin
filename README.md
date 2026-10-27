# AxoLogin

AxoLogin is a Java 21 Velocity plugin project targeting Velocity 3.x and Minecraft 1.21.x proxy networks.

## Build

Install JDK 21 and Gradle, then run:

```bash
gradle build
```

The jar is created in `build/libs/AxoLogin-1.0.0.jar`. Put it in the **Velocity proxy's** `plugins` folder, not in the Paper lobby.

## Required network setup

1. Register a dedicated authentication/limbo backend in `velocity.toml` under the name `limbo` (or change `authServer` in `AxoLogin.java`).
2. Set the normal lobby backend name to `lobby` (or change the `lobby` field in code).
3. Keep the Paper lobby and TrapPvP server settings unchanged. Restart the proxy after installing.
4. The current source blocks connections to normal backends before authentication, but **does not create a void world or freeze player movement itself**. A compatible LimboAPI integration or dedicated limbo server is required for that part.

## Commands currently implemented

- `/register <password> <repeat-password> <email>` — password is hashed with BCrypt and account data is stored in SQLite.
- `/login <password>` — checks the stored password, limits failed attempts, and grants a three-hour session for the same IP.
- `/forgot` and `/reset` are placeholders until SMTP verification-code handling is added. Do not advertise password reset as active yet.

## Important security notes

- Register/login command arguments are private to the command sender in Minecraft chat, but avoid sharing server logs or screenshots containing credentials.
- Do not expose SMTP credentials in a public repository. Use a Gmail app password stored only in the private server config.
- A syntactically valid email address is not proof that the player owns it. Email ownership verification and password reset are not implemented yet.
- The configuration is copied to `plugins/AxoLogin/config.yml`; currently the Java defaults must be edited in source to change server names and timers.
