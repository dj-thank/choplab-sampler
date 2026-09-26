"""Real Mac bundle codec acceptance using generated audio in a temporary directory.

System capture is separately opt-in: two short quiet tones are played; raw capture
stays in memory. Pause unrelated audio before using --system-audio.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import wave
import math
import struct

ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True, help='Build JDK, only used to compile the acceptance harness')
    parser.add_argument('--system-audio', action='store_true')
    args = parser.parse_args()
    app = args.app.resolve()
    libs = app / 'Contents/app'
    tools = libs / 'tools'
    java = app / 'Contents/runtime/Contents' / 'Home' / 'bin/java'
    environment = dict(os.environ, PATH='/usr/bin:/bin')
    with tempfile.TemporaryDirectory(prefix='choplab-mac-acceptance-') as temporary:
        directory = Path(temporary)
        sample = b''.join(struct.pack('<hh', round(8000 * math.sin(2 * math.pi * 701 * i / 48000)),
                                     round(4000 * math.sin(2 * math.pi * 1703 * i / 48000))) for i in range(48000))
        reference = directory / 'reference.wav'
        with wave.open(str(reference), 'wb') as output:
            output.setnchannels(2)
            output.setsampwidth(2)
            output.setframerate(48000)
            output.writeframes(sample * 3)
        formats = {
            'lossless.flac': ['-c:a', 'flac'], 'lossless.m4a': ['-c:a', 'alac'],
            'lossless.aiff': ['-c:a', 'pcm_s16be'], 'lossy.mp3': ['-c:a', 'libmp3lame', '-b:a', '320k'],
            'lossy.m4a': ['-c:a', 'aac', '-b:a', '256k'], 'lossy.aac': ['-c:a', 'aac', '-b:a', '256k'],
            'lossy.opus': ['-c:a', 'libopus', '-b:a', '192k'],
            'video.mp4': ['-c:a', 'aac'], 'video.webm': ['-c:a', 'libopus'],
        }
        for name, codec in formats.items():
            run(tools / 'ffmpeg', '-v', 'error', '-y', '-i', reference, *codec, directory / name, env=environment)
        # Use the reference Vorbis encoder; FFmpeg's experimental native encoder is unsuitable for this fidelity fixture.
        oggenc = shutil.which('oggenc') or '/opt/homebrew/bin/oggenc'
        run(oggenc, '-Q', '-q', '6', '-o', directory / 'lossy.ogg', reference)
        run(tools / 'ffmpeg', '-v', 'error', '-y', '-stream_loop', '-1', '-i', reference,
            '-t', '590', '-c:a', 'flac', directory / 'long.flac', env=environment)
        (directory / 'bad.mp3').write_text('synthetic invalid audio')
        run(args.java_home / 'bin/javac', '-cp', str(libs / '*'), '-d', directory,
            ROOT / 'scripts/acceptance/CodecCheck.java', ROOT / 'scripts/acceptance/SystemCaptureCheck.java')
        base = [java, '-Dchoplab.mediaTools=' + str(tools), '-cp', str(libs / '*') + ':' + str(directory)]
        run(*base, 'CodecCheck', directory, env=environment, cwd=directory)
        if args.system_audio:
            run(*base, 'SystemCaptureCheck', libs / 'choplab-sck-audio', env=environment, cwd=directory, timeout=40)
    print('MAC_BUNDLE_ACCEPTANCE_PASS; human listening, OAuth, and notarization remain separate')


if __name__ == '__main__':
    main()
