import com.choplab.jvm.separation.FourStemModelStore;
import com.choplab.jvm.separation.FourStemSpec;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.DefaultConstructorMarker;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit real HTTPS acceptance in a newly created private fixture directory. */
public class FourStemDownloadCheck {
  static FourStemModelStore store(Path directory) throws Exception {
    return FourStemModelStore.class.getConstructor(Path.class, Function1.class, int.class,
        DefaultConstructorMarker.class).newInstance(directory, null, 2, null);
  }
  static String hash(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
  static void noParts(Path directory) throws Exception {
    if (!Files.exists(directory)) return;
    try (var files = Files.list(directory)) {
      if (files.anyMatch(p -> p.getFileName().toString().endsWith(".part")))
        throw new AssertionError("Partial download survived");
    }
  }
  static void refused(FourStemModelStore store, String problem) {
    try {
      store.ensure(false, (n, total) -> Unit.INSTANCE, () -> Unit.INSTANCE);
      throw new AssertionError("Missing refusal: " + problem);
    } catch (IllegalStateException e) {
      if (!problem.equals(e.getMessage())) throw e;
    }
  }
  static long cancelled(FourStemModelStore store) {
    var bytes = new AtomicLong();
    try {
      store.ensure(true, (n, total) -> { bytes.set(n); return Unit.INSTANCE; }, () -> {
        if (bytes.get() >= 1048576) throw new CancellationException("Owned test cancellation");
        return Unit.INSTANCE;
      });
      throw new AssertionError("Download ignored cancellation");
    } catch (CancellationException expected) {
      if (bytes.get() < 1048576) throw new AssertionError("Cancellation did not reach network data");
      return bytes.get();
    }
  }
  static void download(FourStemModelStore store, String phase) {
    var last = new AtomicLong(-1);
    store.ensure(true, (n, total) -> {
      long block = n / (32L * 1024 * 1024);
      if (last.getAndSet(block) != block) System.out.println(phase + " receivedBytes=" + n);
      return Unit.INSTANCE;
    }, () -> Unit.INSTANCE);
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !args[0].equals("--allow-real-https-download"))
      throw new IllegalArgumentException("Explicit real HTTPS opt-in and new fixture path required");
    Path directory = Path.of(args[1]).toAbsolutePath();
    Files.createDirectory(directory); // Existing directories are never modified.
    String started = java.time.Instant.now().toString();
    var store = store(directory);
    Path model = store.getModel();
    refused(store, "MODEL_MISSING");
    long firstCancellation = cancelled(store);
    if (Files.exists(model)) throw new AssertionError("Cancelled first download published");
    noParts(directory);
    download(store, "initial");
    if (!hash(model).equals(FourStemSpec.MODEL_SHA256)) throw new AssertionError("Wrong initial model");
    System.setProperty("https.proxyHost", "127.0.0.1");
    System.setProperty("https.proxyPort", "9");
    store.ensure(false, (n, total) -> { throw new AssertionError("Cache requested network"); }, () -> Unit.INSTANCE);
    System.clearProperty("https.proxyHost");
    System.clearProperty("https.proxyPort");
    try (var file = FileChannel.open(model, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      var one = ByteBuffer.allocate(1);
      file.read(one); one.flip();
      byte previous = one.get();
      file.position(0); file.write(ByteBuffer.wrap(new byte[] {(byte)(previous ^ 1)})); file.force(true);
    }
    String corruptHash = hash(model);
    refused(store, "MODEL_INVALID");
    long repairCancellation = cancelled(store);
    if (!hash(model).equals(corruptHash)) throw new AssertionError("Cancelled repair changed prior model");
    noParts(directory);
    download(store, "repair");
    String resultHash = hash(model);
    if (!resultHash.equals(FourStemSpec.MODEL_SHA256)) throw new AssertionError("Wrong repaired model");
    noParts(directory);
    String receipt = "{\n  \"status\": \"PASS\",\n  \"scope\": \"FourStemModelStore real HTTPS first download and repair\",\n"
      + "  \"startedAt\": \"" + started + "\",\n  \"finishedAt\": \"" + java.time.Instant.now() + "\",\n"
      + "  \"modelBytes\": " + Files.size(model) + ",\n  \"modelSha256\": \"" + resultHash + "\",\n"
      + "  \"initialCancellationBytes\": " + firstCancellation + ",\n  \"repairCancellationBytes\": " + repairCancellation + ",\n"
      + "  \"offlineCacheVerified\": true,\n  \"corruptCachePreservedOnCancellation\": true,\n"
      + "  \"partialFilesRemain\": false,\n  \"inferenceVerified\": false,\n  \"deviceAudioVerified\": false\n}\n";
    Files.writeString(directory.resolve("receipt.json"), receipt, StandardOpenOption.CREATE_NEW);
    System.out.println(receipt);
  }
}
