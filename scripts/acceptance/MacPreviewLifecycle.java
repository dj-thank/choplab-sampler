import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Test agent inside one explicitly launched Preview; never enumerates other applications. */
public final class MacPreviewLifecycle {
  public static void premain(String receipt, Instrumentation ignored) {
    Thread check = new Thread(() -> {
      try {
        Frame frame = null;
        for (int retry = 0; retry < 120 && frame == null; retry++) {
          AtomicReference<Frame> visible = new AtomicReference<>();
          EventQueue.invokeAndWait(() -> {
            for (Frame candidate : Frame.getFrames()) {
              if (candidate.isShowing() && candidate.getWidth() >= 600
                  && candidate.getHeight() >= 400) {
                visible.set(candidate);
              }
            }
          });
          frame = visible.get();
          if (frame == null)
            Thread.sleep(500);
        }
        if (frame == null)
          throw new IllegalStateException("No visible Preview frame");
        Thread.sleep(2000);
        Frame owned = frame;
        EventQueue.invokeAndWait(() -> {
          if (!owned.isShowing())
            throw new IllegalStateException("Preview disappeared");
          System.out.println(
              "NATIVE_WINDOW_RESPONSIVE " + owned.getWidth() + "x" + owned.getHeight());
        });
        Files.writeString(Path.of(receipt), "responsive; normal close requested\n");
        EventQueue.invokeAndWait(
            () -> owned.dispatchEvent(new WindowEvent(owned, WindowEvent.WINDOW_CLOSING)));
      } catch (Throwable failure) {
        failure.printStackTrace();
        Runtime.getRuntime().halt(71);
      }
    }, "preview-lifecycle-check");
    check.setDaemon(true);
    check.start();
  }
}
