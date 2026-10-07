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
          if (Boolean.getBoolean("choplab.expectNextMenus")) {
            var awt = owned.getMenuBar();
            var swing = owned instanceof javax.swing.JFrame
                ? ((javax.swing.JFrame) owned).getJMenuBar() : null;
            int count = awt != null ? awt.getMenuCount() : swing != null ? swing.getMenuCount() : 0;
            if (count < 3 || owned.getDropTarget() == null || !owned.getDropTarget().isActive())
              throw new IllegalStateException("NEXT native menus or file drop are not installed");
            boolean importEnabled = awt != null ? awt.getMenu(0).getItem(0).isEnabled()
                : swing.getMenu(0).getItem(0).isEnabled();
            if (!importEnabled)
              throw new IllegalStateException("Idle NEXT window must enable file import");
            System.out.println("NATIVE_NEXT_MENUS_AND_FILE_DROP_READY");
          }
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
