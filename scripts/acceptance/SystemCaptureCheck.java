import java.awt.*;
import java.io.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;
public class SystemCaptureCheck {
  static void tone(int leftHz, int rightHz, int millis) throws Exception {
    var fmt = new AudioFormat(48000, 16, 2, true, false);
    try (var line = AudioSystem.getSourceDataLine(fmt)) {
      line.open(fmt);
      line.start();
      byte[] b = new byte[1920];
      int n = millis * 48;
      for (int offset = 0; offset < n; offset += 480) {
        for (int i = 0; i < 480; i++) {
          for (int c = 0; c < 2; c++) {
            int hz = c == 0 ? leftHz : rightHz;
            short v = (short) (Math.sin(2 * Math.PI * hz * (offset + i) / 48000) * 900);
            b[i * 4 + c * 2] = (byte) v;
            b[i * 4 + c * 2 + 1] = (byte) (v >> 8);
          }
        }
        line.write(b, 0, b.length);
      }
      line.drain();
    }
  }
  static double amplitude(byte[] b, int hz, int channel, boolean floating) {
    int frameBytes = floating ? 8 : 4;
    int frames = b.length / frameBytes;
    int start = Math.min(frames / 5, 24000), end = frames - start;
    double re = 0, im = 0;
    for (int i = start; i < end; i++) {
      int offset = i * frameBytes + channel * (floating ? 4 : 2);
      double v;
      if (floating) {
        int bits = (b[offset] & 255) | ((b[offset + 1] & 255) << 8)
            | ((b[offset + 2] & 255) << 16) | (b[offset + 3] << 24);
        v = Float.intBitsToFloat(bits) * 32768.0;
        if (!Double.isFinite(v))
          throw new AssertionError("non-finite captured sample");
      } else {
        v = (short) ((b[offset] & 255) | (b[offset + 1] << 8));
      }
      double a = 2 * Math.PI * hz * i / 48000;
      re += v * Math.cos(a);
      im += v * Math.sin(a);
    }
    return Math.hypot(re, im) * 2 / (end - start);
  }
  public static void main(String[] a) throws Exception {
    if (a[0].equals("tone")) {
      tone(Integer.parseInt(a[1]), Integer.parseInt(a[2]), 5000);
      return;
    }
    boolean floating = a.length > 1 && a[1].equals("--float32");
    int frameBytes = floating ? 8 : 4;
    Frame frame = new Frame("ChopLab isolated system audio acceptance");
    EventQueue.invokeAndWait(() -> {
      frame.setSize(420, 100);
      frame.setVisible(true);
    });
    Thread.sleep(500);
    Process helper = null, external = null;
    ExecutorService pool = Executors.newCachedThreadPool();
    try {
      helper = new ProcessBuilder(floating ? new String[] {a[0], "--float32"} : new String[] {a[0]})
                   .redirectError(ProcessBuilder.Redirect.INHERIT).start();
      final Process h = helper;
      Future<byte[]> read = pool.submit(() -> {
        var in = h.getInputStream();
        var head = new ByteArrayOutputStream();
        for (int x; (x = in.read()) != -1 && x != 10;) {
          if (head.size() >= 240)
            throw new IOException("oversized helper header");
          head.write(x);
        }
        System.out.println("header=" + head.toString());
        if (!head.toString().equals((floating ? "CHOPLAB-FLOAT32" : "CHOPLAB-PCM") + " 48000 2"))
          throw new IOException("header failed");
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1;) {
          out.write(buf, 0, n);
          if (out.size() > 48000 * frameBytes * 10)
            throw new IOException("bound exceeded");
        }
        return out.toByteArray();
      });
      Thread.sleep(1400);
      external = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
          System.getProperty("java.class.path"), "SystemCaptureCheck", "tone", "1703", "2459")
                     .inheritIO()
                     .start();
      Future<?> own = pool.submit(() -> {
        try {
          tone(997, 997, 5000);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      own.get(10, TimeUnit.SECONDS);
      if (!external.waitFor(10, TimeUnit.SECONDS))
        throw new IOException("external timeout");
      h.getOutputStream().close();
      if (!h.waitFor(5, TimeUnit.SECONDS))
        h.destroyForcibly();
      byte[] b = read.get(10, TimeUnit.SECONDS);
      if (b.length < 48000 * frameBytes || b.length % frameBytes != 0)
        throw new AssertionError("insufficient or partial PCM frames");
      double excluded = Math.max(amplitude(b, 997, 0, floating), amplitude(b, 997, 1, floating));
      double left = amplitude(b, 1703, 0, floating), right = amplitude(b, 2459, 1, floating);
      double included = Math.min(left, right);
      double leakage = Math.max(amplitude(b, 2459, 0, floating), amplitude(b, 1703, 1, floating));
      double db = 20 * Math.log10(Math.max(excluded, 1e-9) / Math.max(included, 1e-9));
      System.out.printf(
          "format=%s frames=%d parent_amplitude=%.6f left=%.6f right=%.6f leakage=%.6f exclusion_db=%.2f exit=%d%n",
          floating ? "float32" : "pcm16", b.length / frameBytes, excluded, left, right, leakage, db, h.exitValue());
      if (!Double.isFinite(included) || !Double.isFinite(db) || included < 100 || db > -40
          || !Double.isFinite(leakage) || leakage / included > 0.01 || h.exitValue() != 0)
        throw new AssertionError("system audio routing failed");
      System.out.println("SYSTEM_CAPTURE_STEREO_PARENT_EXCLUSION_PASS");
    } finally {
      if (helper != null && helper.isAlive())
        helper.destroyForcibly();
      if (external != null && external.isAlive())
        external.destroyForcibly();
      pool.shutdownNow();
      EventQueue.invokeAndWait(frame::dispose);
    }
  }
}
