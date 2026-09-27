import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;

/** Loaded only into an explicitly launched isolated NEXT. Records sizes, never display or device identifiers. */
public final class NextWindowBounds {
  public static void premain(String receipt, Instrumentation ignored) {
    Thread check = new Thread(() -> {
      try {
        AtomicReference<Frame> found = new AtomicReference<>();
        for (int attempt = 0; attempt < 120 && found.get() == null; attempt++) {
          EventQueue.invokeAndWait(() -> {
            for (Frame frame : Frame.getFrames()) {
              if (frame.isShowing() && frame.getWidth() >= 600 && frame.getHeight() >= 400) found.set(frame);
            }
          });
          if (found.get() == null) Thread.sleep(500);
        }
        Frame frame = found.get();
        if (frame == null) throw new IllegalStateException("NEXT window did not appear");
        Thread.sleep(2000);
        AtomicReference<String> result = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
          Rectangle screen = frame.getGraphicsConfiguration().getBounds();
          Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
          Rectangle work = new Rectangle(screen.x + insets.left, screen.y + insets.top,
              screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
          Rectangle outer = frame.getBounds();
          Rectangle content = frame instanceof JFrame swing ? swing.getContentPane().getBounds() : frame.getBounds();
          result.set("{\"scope\":\"owned-native-window-bounds\",\"outerWidth\":" + outer.width
              + ",\"outerHeight\":" + outer.height + ",\"contentWidth\":" + content.width
              + ",\"contentHeight\":" + content.height + ",\"workWidth\":" + work.width
              + ",\"workHeight\":" + work.height + ",\"contained\":" + work.contains(outer)
              + ",\"scale\":" + frame.getGraphicsConfiguration().getDefaultTransform().getScaleX()
              + ",\"nativeAudio\":false,\"humanAcceptance\":false}");
        });
        Files.writeString(Path.of(receipt), result.get() + "\n");
        System.out.println(result.get());
        EventQueue.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
      } catch (Throwable failure) {
        failure.printStackTrace();
        Runtime.getRuntime().halt(71);
      }
    }, "next-window-bounds");
    check.setDaemon(true);
    check.start();
  }
}
