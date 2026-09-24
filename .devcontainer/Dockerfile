FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git python3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/android-sdk \
    && chown -R vscode:vscode /opt/android-sdk

# The checked-in dev-setup.sh owns pinned SDK downloads and validates their hashes.
# No native engine is built, so NDK/CMake are not installed preemptively.

USER vscode
