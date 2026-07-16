FROM mcr.microsoft.com/devcontainers/java:1-17-bookworm

ARG ANDROID_CMDLINE_TOOLS_VERSION=14742923
ARG ANDROID_PLATFORM=36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=29.0.14206865
ARG ANDROID_CMAKE=3.22.1

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git make ninja-build clang python3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/cmdline-tools.zip \
    && mkdir -p /tmp/android-tools \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && (yes | sdkmanager --licenses >/dev/null || true)

RUN sdkmanager \
      "platform-tools" \
      "platforms;android-${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" \
      "ndk;${ANDROID_NDK}" \
      "cmake;${ANDROID_CMAKE}" \
    && chown -R vscode:vscode /opt/android-sdk

USER vscode
