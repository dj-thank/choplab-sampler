#!/usr/bin/env python3
"""Apply only the reviewed startup/waveform edits to an exact, checked source baseline.

This one-shot branch preparation tool is removed after the scoped GitHub Actions job.
It refuses changed baselines and checks every replacement before writing any file.
"""
from pathlib import Path
import hashlib

TARGETS = {
    "app/src/main/java/com/choplab/sampler/SamplerViewModel.kt": "c2c3f180ab174976a03015fa999603256c0bae1c",
    "shared/src/commonMain/kotlin/com/choplab/sampler/ui/WaveformEditor.kt": "67d7ef137e714e35a708edbbc5f65669d4e19f40",
    "docs/PROJECT_STATE.md": "9ae7a62f0849c108061eff5ded5be6844bce47da",
    "docs/VALIDATION.md": "6ea7b195e387c2b29dbbb11be6811a76d119d4a1",
}

VM_EDITS = [
    (
        "import com.choplab.sampler.audio.SamplerPlaybackEngine\n",
        "import com.choplab.sampler.audio.SamplerPlaybackEngine\nimport com.choplab.sampler.audio.prepareSamplerStartup\n",
    ),
    (
        "        engine.start()\n        observePlaybackCapture()\n        pollTransportStep()\n        recoverAutosave()\n",
        "        observePlaybackCapture()\n        pollTransportStep()\n        recoverAutosave()\n",
    ),
    (
        "            val result = withContext(Dispatchers.IO) { runCatching { autosaveStore.loadWithRevision() } }\n",
        "            val result = prepareSamplerStartup(\n"
        "                startAudio = { engine.start() },\n"
        "                stopAudio = engine::shutdown,\n"
        "                loadProject = { runCatching { autosaveStore.loadWithRevision() } },\n"
        "            )\n",
    ),
]

WAVEFORM_EDITS = [
    (
        "    val haptics = LocalHapticFeedback.current\n    val initialViewport = remember(\n",
        "    val haptics = LocalHapticFeedback.current\n"
        "    val displayGain = rememberWaveformDisplayGain(audio)\n"
        "    val initialViewport = remember(\n",
    ),
    (
        "                        stateDescription = waveformViewportStateDescription(viewport)\n",
        "                        stateDescription = waveformViewportStateDescription(viewport) +\n"
        '                            "。${waveformDisplayGainLabel(displayGain)}。録音音量は変更しません"\n',
    ),
    (
        "                drawWaveformEnvelope(\n                    envelope = waveformEnvelope,\n                    color = waveformColor,\n                )\n",
        "                drawWaveformEnvelope(\n                    envelope = waveformEnvelope,\n                    color = waveformColor,\n                    displayGain = displayGain,\n                )\n",
    ),
    (
        "            if (showInteractionHint) {\n",
        "            Text(\n"
        "                text = waveformDisplayGainLabel(displayGain),\n"
        "                modifier = Modifier\n"
        "                    .align(Alignment.TopEnd)\n"
        "                    .padding(top = 28.dp, end = 8.dp)\n"
        "                    .background(\n"
        "                        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),\n"
        "                        RoundedCornerShape(6.dp),\n"
        "                    )\n"
        "                    .padding(horizontal = 7.dp, vertical = 3.dp),\n"
        "                color = resolvedReadoutColor,\n"
        "                fontSize = 11.sp,\n"
        "                maxLines = 1,\n"
        "                overflow = TextOverflow.Ellipsis,\n"
        "            )\n\n"
        "            if (showInteractionHint) {\n",
    ),
    (
        "            var sum = 0\n"
        "            repeat(channelCount) { channel -> sum += samples[frame * channelCount + channel].toInt() }\n"
        "            val value = (sum / channelCount) / 32_768f\n"
        "            if (value < minimum) minimum = value\n"
        "            if (value > maximum) maximum = value\n",
        "            // Keep channel extrema: averaging can erase opposite-phase stereo audio.\n"
        "            repeat(channelCount) { channel ->\n"
        "                val value = samples[frame * channelCount + channel] / 32_768f\n"
        "                if (value < minimum) minimum = value\n"
        "                if (value > maximum) maximum = value\n"
        "            }\n",
    ),
    (
        "internal fun DrawScope.drawWaveformEnvelope(\n"
        "    envelope: WaveformEnvelope,\n"
        "    color: Color,\n"
        ") {\n",
        "internal fun DrawScope.drawWaveformEnvelope(\n"
        "    envelope: WaveformEnvelope,\n"
        "    color: Color,\n"
        "    displayGain: Float = 1f,\n"
        ") {\n",
    ),
    (
        "    val amplitude = size.height * 0.46f\n    var bucket = 0\n",
        "    val amplitude = size.height * 0.46f\n"
        "    val gain = sanitizeWaveformDisplayGain(displayGain)\n"
        "    var bucket = 0\n",
    ),
    (
        "            start = Offset(x.toFloat(), centerY - envelope.maximums[bucket] * amplitude),\n"
        "            end = Offset(x.toFloat(), centerY - envelope.minimums[bucket] * amplitude),\n",
        "            start = Offset(x.toFloat(), centerY - (envelope.maximums[bucket] * gain).coerceIn(-1f, 1f) * amplitude),\n"
        "            end = Offset(x.toFloat(), centerY - (envelope.minimums[bucket] * gain).coerceIn(-1f, 1f) * amplitude),\n",
    ),
]

STATE_NOTE = """
## Current snapshot — 2026-09-10 scoped startup / waveform repair

Branch: `fix/startup-waveform-20260910`, based on `main@bed7a550a71b1ae91556b2b2af25d7c482083c98`. This is a requested bug/performance repair, not a release or merge receipt. Prior receipts below keep their original revision scope.

- Android output warmup and autosave reading use concurrent IO work; the existing recovery/loading gate opens only after both complete. Cancellation joins an in-flight open before cleanup so late output initialization cannot outlive teardown.
- Quiet waveforms get bounded display-only gain (up to 16x), derived once from the whole immutable source on a worker. The canvas labels it `表示のみ`; silent/near-silent and full-scale inputs stay at unity. PCM, PAD gain, recorder input policy, exports and project schema are unchanged.
- Stereo drawing preserves extrema from either channel instead of cancelling opposing channels by averaging. Viewport decimation remains bounded; this is not a new full-resolution waveform pyramid.
- Validation: 16 Kotlin host assertions passed in the editing environment (11 waveform, 5 startup/cancellation). The host runner strips test annotations only and compiles the production startup/gain helpers plus the exact extracted envelope seam. This is NOT an Android/Compose build, device test or measured startup improvement. Full Gradle/CI and physical timing gates remain separate.

Plan: `plans/active/startup-waveform-20260910.md`.

"""

VALIDATION_NOTE = """
## 2026-09-10 — startup / quiet waveform scoped host validation

Scope: new `SamplerStartup.kt`, `WaveformDisplayPolicy.kt`, and the reviewed channel-extrema envelope change on `fix/startup-waveform-20260910` (baseline `bed7a550a71b1ae91556b2b2af25d7c482083c98`).

Environment: OpenJDK 21 and installed Kotlin compiler/coroutines/test jars. No Android SDK or Gradle checkout/cache was available in the editing container. GitHub reads/writes used the connected repository tools.

Commands: `kotlinc` compiled the two production helpers, the exact extracted envelope seam and annotation-stripped copies of `WaveformDisplayPolicyTest`/`SamplerStartupTest`; `java ... HostMainKt` invoked their 16 test bodies. Result: **16/16 PASS**. Test annotations/JUnit discovery and Compose UI compilation were NOT exercised by this host runner.

Covered: quiet/silent/loud gain, Short.MIN_VALUE, whole-source off-grid peaks, cancellation checks, unchanged source PCM, opposing/one-sided stereo, mono envelope amplitude, invalid input; overlapping off-caller-thread startup IO, readiness gating, cancellation during open/recovery, failure cleanup, and unchanged recovery failure Result policy.

Required remaining gates: `./scripts/validate_project.sh`, `:shared:desktopTest`, `:shared:testAndroidHostTest`, `:app:testDebugUnitTest`, Android lint/assemble, full existing CI, and physical cold-start/first-play/recording checks. No APK, measured TTID/TTFD reduction, microphone dB improvement or release is claimed here.

"""


def replace_once(text, old, new, path):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one reviewed anchor, found {count}: {old[:70]!r}")
    return text.replace(old, new, 1)


def git_blob_sha(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def main():
    pending = {}
    for name, expected in TARGETS.items():
        path = Path(name)
        raw = path.read_bytes()
        actual = git_blob_sha(raw)
        if actual != expected:
            raise RuntimeError(f"Refusing changed baseline: {name}: expected {expected}, found {actual}")
        text = raw.decode("utf-8")
        if name.endswith("SamplerViewModel.kt"):
            for old, new in VM_EDITS:
                text = replace_once(text, old, new, name)
        elif name.endswith("WaveformEditor.kt"):
            for old, new in WAVEFORM_EDITS:
                text = replace_once(text, old, new, name)
        elif name.endswith("PROJECT_STATE.md"):
            text = replace_once(text, "# Project state\n", "# Project state\n" + STATE_NOTE, name)
        else:
            text = replace_once(text, "# 検証記録\n", "# 検証記録\n" + VALIDATION_NOTE, name)
        pending[path] = text.encode("utf-8")
    # No file changes occur unless every baseline and every edit has been verified.
    for path, raw in pending.items():
        path.write_bytes(raw)
        print(f"Applied {path}: {git_blob_sha(raw)}")


if __name__ == "__main__":
    main()
