import com.choplab.desktop.source.DesktopAudioDecoder;
import com.choplab.sampler.model.PcmAudio;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/** Generated asymmetric stereo fixtures; no user audio or network access. */
public class CodecCheck {
  static double amplitude(PcmAudio audio, int channel, int frequency) {
    double real = 0, imaginary = 0;
    int begin = 24000, end = Math.min(audio.getFrameCount() - 24000, 120000);
    for (int frame = begin; frame < end; frame++) {
      double value = audio.getSamples()[frame * 2 + channel];
      double phase = 2 * Math.PI * frequency * frame / audio.getSampleRate();
      real += value * Math.cos(phase);
      imaginary += value * Math.sin(phase);
    }
    return 2 * Math.hypot(real, imaginary) / (end - begin);
  }

  public static void main(String[] args) {
    File directory = new File(args[0]);
    PcmAudio reference = DesktopAudioDecoder.INSTANCE.decode(new File(directory, "reference.wav"));
    File[] fixtures = Objects.requireNonNull(directory.listFiles(
        (parent, name) -> name.matches("(lossless|lossy|video|long|bad)\\..+")));
    Arrays.sort(fixtures, Comparator.comparing(File::getName));
    if (fixtures.length != 12)
      throw new AssertionError("Incomplete codec fixtures");
    for (File file : fixtures) {
      long start = System.nanoTime();
      try {
        PcmAudio audio = DesktopAudioDecoder.INSTANCE.decode(file);
        if (file.getName().startsWith("bad"))
          throw new AssertionError("Corrupt input accepted");
        if (audio.getSampleRate() != 48000 || audio.getChannelCount() != 2) {
          throw new AssertionError("Format changed: " + file.getName());
        }
        if (file.getName().startsWith("lossless")
            && !Arrays.equals(reference.getSamples(), audio.getSamples())) {
          throw new AssertionError("Lossless PCM mismatch: " + file.getName());
        }
        int expected = file.getName().equals("long.flac") ? 590 * 48000 : 3 * 48000;
        int padding = audio.getFrameCount() - expected;
        // Raw ADTS has no container gapless metadata; report, do not silently trim, its padding.
        if (file.getName().equals("lossy.aac") ? padding < 0 || padding > 2048 : padding != 0) {
          throw new AssertionError("Duration changed: " + file.getName() + " padding=" + padding);
        }
        double left = amplitude(audio, 0, 701), right = amplitude(audio, 1, 1703);
        double leftLeak = amplitude(audio, 0, 1703), rightLeak = amplitude(audio, 1, 701);
        if (left < 6500 || right < 3000 || leftLeak > 100 || rightLeak > 100) {
          throw new AssertionError("Channel identity: " + file.getName());
        }
        System.out.printf(Locale.ROOT,
            "PASS %s frames=%d padding=%d left=%.1f right=%.1f leakage=%.1f/%.1f ms=%.1f%n",
            file.getName(), audio.getFrameCount(), padding, left, right, leftLeak, rightLeak,
            (System.nanoTime() - start) / 1e6);
      } catch (IllegalStateException | IllegalArgumentException error) {
        if (!file.getName().startsWith("bad"))
          throw error;
        System.out.println("PASS corrupt rejected");
      }
    }
  }
}
