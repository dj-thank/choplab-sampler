#!/usr/bin/env python3
"""Actual-tool synthetic codec checks; no microphone, endpoint or provider result.

Pass the rebuilt FFmpeg/FFprobe, and optionally the matching full tool as the
fixture encoder. This program creates only owned synthetic audio in --work-dir.
"""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import platform
import struct
import subprocess
import sys
import wave


def run(tool, *args, ok=True):
    process = subprocess.run([str(tool), *map(str, args)], capture_output=True, timeout=45)
    if (process.returncode == 0) != ok:
        raise AssertionError(process.stderr.decode(errors='replace')[-1500:])
    return process.stdout


def source(path, rate=48000, frames=96000, channels=2):
    pcm = bytearray()
    floats = bytearray()
    for frame in range(frames):
        for channel in range(channels):
            value = round((.28 if channel == 0 else -.14) * math.sin(2 * math.pi * (701 if channel == 0 else 1703) * frame / rate) * 8388608)
            value += frame % 5 - 2
            pcm.extend((value & 0xffffff).to_bytes(3, 'little'))
            floats.extend(struct.pack('<f', value / 8388608))
    with wave.open(str(path), 'wb') as output:
        output.setparams((channels, 3, rate, frames, 'NONE', 'not compressed'))
        output.writeframes(pcm)
    return bytes(floats)


def amplitude(data, channel, frequency):
    values = array.array('f', data)
    if sys.byteorder != 'little':
        values.byteswap()
    begin, end = 24000, min(len(values) // 2 - 24000, 72000)
    real = imaginary = 0.
    for frame in range(begin, end):
        phase = 2 * math.pi * frequency * frame / 48000
        real += values[frame * 2 + channel] * math.cos(phase)
        imaginary += values[frame * 2 + channel] * math.sin(phase)
    return 2 * math.hypot(real, imaginary) / (end - begin)


def correlation(expected, actual, channel, delay):
    reference, decoded = array.array('f', expected), array.array('f', actual)
    if sys.byteorder != 'little':
        reference.byteswap()
        decoded.byteswap()
    x = reference[24000 * 2 + channel:72000 * 2:2]
    y = decoded[(24000 + delay) * 2 + channel:(72000 + delay) * 2:2]
    return sum(a * b for a, b in zip(x, y)) / math.sqrt(sum(a * a for a in x) * sum(b * b for b in y))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', type=Path, required=True)
    parser.add_argument('--ffprobe', type=Path, required=True)
    parser.add_argument('--encoder', type=Path)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    original = args.work_dir / 'reference.wav'
    expected = source(original)
    original_hash = hashlib.sha256(original.read_bytes()).hexdigest()
    encoder = args.encoder or args.ffmpeg
    fixtures = [
        ('flac', 'flac', ['-sample_fmt', 's32'], True), ('mp3', 'libmp3lame', [], False),
        ('m4a', 'aac', [], False), ('aac', 'aac', [], False), ('ogg', 'libvorbis', ['-q:a', '5'], False),
        ('opus', 'libopus', [], False), ('alac.m4a', 'alac', [], True),
        ('aiff', 'pcm_s24be', [], True), ('aif', 'pcm_s24be', [], True),
        ('mp4', 'aac', [], False), ('webm', 'libopus', [], False),
    ]
    rows = []
    for extension, codec, extra, lossless in fixtures:
        path = args.work_dir / ('precision.' + extension)
        run(encoder, '-nostdin', '-v', 'error', '-y', '-i', original, '-c:a', codec, *extra, path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        info = json.loads(run(args.ffprobe, '-v', 'error', '-select_streams', 'a:0', '-show_streams', '-of', 'json', path))['streams'][0]
        assert int(info['sample_rate']) == 48000 and info['channels'] == 2
        decoded = run(args.ffmpeg, '-nostdin', '-v', 'error', '-protocol_whitelist', 'file,pipe', '-i', path,
                      '-map', '0:a:0', '-vn', '-c:a', 'pcm_f32le', '-f', 'f32le', 'pipe:1')
        frames = len(decoded) // 8
        assert 96000 <= frames < 100000, (extension, frames)
        if lossless:
            assert decoded == expected, (extension, '24-bit/first-last/stereo/polarity changed')
        if extension in ('mp3', 'opus', 'webm'):
            assert frames == 96000, (extension, 'gapless delay/end padding changed')
        left, right = amplitude(decoded, 0, 701), amplitude(decoded, 1, 1703)
        leak = max(amplitude(decoded, 0, 1703), amplitude(decoded, 1, 701))
        assert left > .23 and right > .11 and leak < .01, (extension, left, right, leak)
        # Raw ADTS has no container edit-list for AAC's 1024-frame priming.
        # All other fixtures carry their trimming metadata. Positive signed
        # correlation detects a polarity reversal that amplitude alone misses.
        correlations = [correlation(expected, decoded, channel, 1024 if extension == 'aac' else 0) for channel in (0, 1)]
        assert min(correlations) > .98, (extension, 'polarity or timing changed', correlations)
        # The legacy converter's exact options must retain stereo and bounded PCM.
        legacy = args.work_dir / 'legacy.wav'
        run(args.ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-protocol_whitelist', 'file,pipe',
            '-i', path, '-map', '0:a:0', '-vn', '-c:a', 'pcm_s16le', '-ar', '48000', '-ac', '2', '-y', legacy)
        with wave.open(str(legacy), 'rb') as converted:
            assert converted.getnchannels() == 2 and converted.getsampwidth() == 2 and converted.getframerate() == 48000
            assert converted.getnframes() == frames
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest
        rows.append({'format': extension, 'codec': codec, 'frames': frames, 'lossless24BitExact': lossless,
                     'firstLastExact': lossless, 'leftAmplitude': left, 'rightAmplitude': right, 'maximumLeakage': leak,
                     'signedChannelCorrelations': correlations,
                     'sourceBytes': path.stat().st_size, 'sourceSha256': digest})
    flac = args.work_dir / 'precision.flac'
    for start, count in ((6000, 1000), (95520, 480)):
        decoded = run(args.ffmpeg, '-nostdin', '-v', 'error', '-ss', f'{start / 48000:.12f}', '-i', flac,
                      '-t', f'{count / 48000:.12f}', '-map', '0:a:0', '-vn', '-c:a', 'pcm_f32le', '-f', 'f32le', 'pipe:1')
        assert decoded == expected[start * 8:(start + count) * 8], ('range seek/last frame', start)
    mono = args.work_dir / 'mono44100.wav'
    source(mono, 44100, 44101, 1)
    stereo = run(args.ffmpeg, '-nostdin', '-v', 'error', '-i', mono, '-ar', '48000', '-ac', '2', '-c:a', 'pcm_f32le', '-f', 'f32le', 'pipe:1')
    assert len(stereo) == 48002 * 8
    assert all(stereo[p:p + 4] == stereo[p + 4:p + 8] for p in range(0, len(stereo), 8))
    broken = args.work_dir / 'broken.flac'
    broken.write_bytes(b'not audio')
    run(args.ffmpeg, '-nostdin', '-v', 'error', '-i', broken, '-f', 'null', '-', ok=False)
    assert hashlib.sha256(original.read_bytes()).hexdigest() == original_hash
    receipt = {'status': 'LOCAL_PASS', 'scope': 'synthetic-native-codec-cli', 'platform': platform.system(),
               'ffmpegSha256': hashlib.sha256(args.ffmpeg.read_bytes()).hexdigest(),
               'ffprobeSha256': hashlib.sha256(args.ffprobe.read_bytes()).hexdigest(), 'formats': rows,
               'encoderSha256': hashlib.sha256(encoder.read_bytes()).hexdigest(),
               'ffmpegVersion': run(args.ffmpeg, '-version').decode().splitlines()[0],
               'resample44100To48000': True, 'rangeSeekExact': True, 'corruptRejected': True,
               'originalBytesUnchanged': True, 'audioDeviceVerified': False, 'providerVerified': False}
    args.receipt.write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'status': 'LOCAL_PASS', 'formats': len(rows), 'rangeSeekExact': True, 'platform': platform.system(),
                      'targetAndroidOrWindowsVerified': platform.system() == 'Windows', 'audioDeviceVerified': False}))


if __name__ == '__main__':
    main()
