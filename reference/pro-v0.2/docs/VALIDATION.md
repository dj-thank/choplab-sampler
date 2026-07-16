# Pro v0.2 reference validation status

This directory contains historical, partial Pro implementation artifacts. It is intentionally isolated from the Android build.

The original Pro delivery described host-side C++/Kotlin checks, but this workspace does not contain sufficient Gradle, Android resource, manifest, CMake, JNI integration, UI, or test infrastructure to reproduce a complete Pro build from this directory alone.

Treat every Pro feature as **unverified reference material** until it is reimplemented under `app/` and passes the repository gates described in `AGENTS.md` and `docs/DEFINITION_OF_DONE.md`.
