# Only choplabNextSizeProbe enables this test-to-app API boundary.
# R8 TraceReferences 9.4.24 traced the AGP 9.4.1 classfile inputs of the narrow
# PreviewAndroidTest program (runtime smoke + codec fixture and runner libraries)
# into Preview. Keep only named holders and referenced members; app/test are
# optimized separately, so app R8 otherwise removes APIs still used by tests.
# Regenerate and inspect this boundary when probe fixtures/shared dependencies
# change. Annotation-only references are excluded: tests do not invoke them,
# and keeping kotlin.Metadata would preserve metadata across the entire app.
# Do not replace these with package-wide Kotlin, Compose or app keeps.
-keep class ai.onnxruntime.OnnxTensor {
  public static ai.onnxruntime.OnnxTensor createTensor(ai.onnxruntime.OrtEnvironment, java.nio.FloatBuffer, long[]);
  public java.nio.FloatBuffer getFloatBuffer();
}
-keep class ai.onnxruntime.OrtEnvironment {
  public static ai.onnxruntime.OrtEnvironment getEnvironment();
}
-keep class androidx.concurrent.futures.AbstractResolvableFuture {
  public void addListener(java.lang.Runnable, java.util.concurrent.Executor);
  public boolean cancel(boolean);
  public java.lang.Object get();
  public java.lang.Object get(long, java.util.concurrent.TimeUnit);
  static java.lang.Object getUninterruptibly(java.util.concurrent.Future);
  public boolean isCancelled();
  public boolean isDone();
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter$Completer {
  public boolean set(java.lang.Object);
  public boolean setException(java.lang.Throwable);
}
-keep interface androidx.concurrent.futures.CallbackToFutureAdapter$Resolver {
  public java.lang.Object attachCompleter(androidx.concurrent.futures.CallbackToFutureAdapter$Completer);
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter {
  public static com.google.common.util.concurrent.ListenableFuture getFuture(androidx.concurrent.futures.CallbackToFutureAdapter$Resolver);
}
-keep enum androidx.concurrent.futures.DirectExecutor {
  androidx.concurrent.futures.DirectExecutor INSTANCE;
}
-keep class androidx.concurrent.futures.ResolvableFuture {
  public static androidx.concurrent.futures.ResolvableFuture create();
  public boolean set(java.lang.Object);
  public boolean setException(java.lang.Throwable);
}
-keep enum androidx.lifecycle.Lifecycle$State {
  public static androidx.lifecycle.Lifecycle$State[] values();
  androidx.lifecycle.Lifecycle$State CREATED;
  androidx.lifecycle.Lifecycle$State DESTROYED;
  androidx.lifecycle.Lifecycle$State RESUMED;
  androidx.lifecycle.Lifecycle$State STARTED;
}
-keep class androidx.lifecycle.Lifecycle {
}
-keep class androidx.tracing.Trace {
  public static void beginSection(java.lang.String);
  public static void endSection();
  public static void forceEnableAppTracing();
}
-keep class com.choplab.sampler.next.NextActivity {
}
-keep interface com.google.common.util.concurrent.ListenableFuture {
  public void addListener(java.lang.Runnable, java.util.concurrent.Executor);
}
-keep class com.yausername.ffmpeg.FFmpeg {
  public static com.yausername.ffmpeg.FFmpeg getInstance();
  public void init(android.content.Context);
}
-keep class com.yausername.youtubedl_android.YoutubeDL {
  public boolean destroyProcessById(java.lang.String);
  public static com.yausername.youtubedl_android.YoutubeDLResponse execute$default(com.yausername.youtubedl_android.YoutubeDL, com.yausername.youtubedl_android.YoutubeDLRequest, java.lang.String, kotlin.jvm.functions.Function3, int, java.lang.Object);
  public com.yausername.youtubedl_android.mapper.VideoInfo getInfo(com.yausername.youtubedl_android.YoutubeDLRequest);
  public static com.yausername.youtubedl_android.YoutubeDL getInstance();
  public void init(android.content.Context);
}
-keep class com.yausername.youtubedl_android.YoutubeDLRequest {
  public <init>(java.lang.String);
  public com.yausername.youtubedl_android.YoutubeDLRequest addCommands(java.util.List);
  public com.yausername.youtubedl_android.YoutubeDLRequest addOption(java.lang.String);
}
-keep class com.yausername.youtubedl_android.YoutubeDLResponse {
  public java.lang.String getOut();
}
-keep class com.yausername.youtubedl_android.mapper.VideoFormat {
  public java.lang.String getExt();
  public java.lang.String getFormatId();
  public java.lang.String getUrl();
}
-keep class com.yausername.youtubedl_android.mapper.VideoInfo {
  public java.lang.String getExt();
  public java.lang.String getFormatId();
  public java.util.ArrayList getFormats();
  public java.lang.String getId();
  public java.util.ArrayList getThumbnails();
  public java.lang.String getTitle();
  public java.lang.String getUrl();
}
-keep class com.yausername.youtubedl_android.mapper.VideoThumbnail {
  public java.lang.String getId();
  public java.lang.String getUrl();
}
-keep interface kotlin.Lazy {
  public java.lang.Object getValue();
}
-keep class kotlin.LazyKt {
}
-keep class kotlin.LazyKt__LazyJVMKt {
  public static kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.Pair {
  public java.lang.Object component1();
  public java.lang.Object component2();
}
-keep class kotlin.Result$Companion {
}
-keep class kotlin.Result {
  public static java.lang.Object constructor-impl(java.lang.Object);
  public static java.lang.Throwable exceptionOrNull-impl(java.lang.Object);
  kotlin.Result$Companion Companion;
}
-keep class kotlin.ResultKt {
  public static java.lang.Object createFailure(java.lang.Throwable);
  public static void throwOnFailure(java.lang.Object);
}
-keep class kotlin.TuplesKt {
  public static kotlin.Pair to(java.lang.Object, java.lang.Object);
}
-keep class kotlin.Unit {
  kotlin.Unit INSTANCE;
}
-keep class kotlin.collections.ArraysKt {
}
-keep class kotlin.collections.ArraysKt___ArraysJvmKt {
  public static byte[] copyOfRange(byte[], int, int);
}
-keep class kotlin.collections.ArraysKt___ArraysKt {
  public static java.lang.String joinToString$default(byte[], java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.lang.String joinToString$default(java.lang.Object[], java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.util.List take(byte[], int);
  public static java.util.List toList(java.lang.Object[]);
}
-keep class kotlin.collections.CollectionsKt {
}
-keep class kotlin.collections.CollectionsKt__CollectionsJVMKt {
  public static java.util.List listOf(java.lang.Object);
}
-keep class kotlin.collections.CollectionsKt__CollectionsKt {
  public static java.util.List emptyList();
  public static java.util.List listOf(java.lang.Object[]);
  public static java.util.List listOfNotNull(java.lang.Object[]);
}
# Its package-private facade superclass is extended by a repackaged R8 child.
# Permit only this holder's access widening; keep the shared method signatures.
-keep,allowaccessmodification class kotlin.collections.CollectionsKt__IterablesKt {
  public static int collectionSizeOrDefault(java.lang.Iterable, int);
  public static java.util.List flatten(java.lang.Iterable);
}
-keep class kotlin.collections.CollectionsKt__MutableCollectionsKt {
  public static boolean addAll(java.util.Collection, java.lang.Iterable);
}
-keep class kotlin.collections.CollectionsKt___CollectionsKt {
  public static java.lang.Object firstOrNull(java.util.List);
  public static java.lang.String joinToString$default(java.lang.Iterable, java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.util.List minus(java.lang.Iterable, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Iterable);
  public static java.util.List plus(java.util.Collection, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Object[]);
  public static java.util.List sortedWith(java.lang.Iterable, java.util.Comparator);
  public static int[] toIntArray(java.util.Collection);
  public static java.util.Set toSet(java.lang.Iterable);
}
-keep class kotlin.collections.IntIterator {
  public int nextInt();
}
-keep class kotlin.collections.SetsKt {
}
-keep class kotlin.collections.SetsKt__SetsKt {
  public static java.util.Set setOf(java.lang.Object[]);
}
-keep class kotlin.comparisons.ComparisonsKt {
}
-keep class kotlin.comparisons.ComparisonsKt__ComparisonsKt {
  public static int compareValues(java.lang.Comparable, java.lang.Comparable);
}
-keep class kotlin.concurrent.ThreadsKt {
  public static java.lang.Thread thread$default(boolean, boolean, java.lang.ClassLoader, java.lang.String, int, kotlin.jvm.functions.Function0, int, java.lang.Object);
}
-keep interface kotlin.coroutines.Continuation {
  public kotlin.coroutines.CoroutineContext getContext();
  public void resumeWith(java.lang.Object);
}
-keep class kotlin.coroutines.ContinuationKt {
  public static kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
}
-keep interface kotlin.coroutines.CoroutineContext {
}
-keep class kotlin.coroutines.EmptyCoroutineContext {
  kotlin.coroutines.EmptyCoroutineContext INSTANCE;
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt {
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt {
  public static kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
  public static java.lang.Object getCOROUTINE_SUSPENDED();
}
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl {
  public <init>(kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt {
  public static void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep interface kotlin.coroutines.jvm.internal.SuspendFunction {
}
-keep class kotlin.coroutines.jvm.internal.SuspendLambda {
  public <init>(int, kotlin.coroutines.Continuation);
  public kotlin.coroutines.Continuation create(java.lang.Object, kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep class kotlin.io.ByteStreamsKt {
  public static long copyTo$default(java.io.InputStream, java.io.OutputStream, int, int, java.lang.Object);
}
-keep class kotlin.io.CloseableKt {
  public static void closeFinally(java.io.Closeable, java.lang.Throwable);
}
-keep class kotlin.io.FilesKt {
}
-keep class kotlin.io.FilesKt__FileReadWriteKt {
  public static byte[] readBytes(java.io.File);
  public static java.lang.String readText$default(java.io.File, java.nio.charset.Charset, int, java.lang.Object);
  public static void writeText$default(java.io.File, java.lang.String, java.nio.charset.Charset, int, java.lang.Object);
}
-keep class kotlin.io.FilesKt__UtilsKt {
  public static boolean deleteRecursively(java.io.File);
  public static java.lang.String getNameWithoutExtension(java.io.File);
}
-keep class kotlin.io.TextStreamsKt {
  public static kotlin.sequences.Sequence lineSequence(java.io.BufferedReader);
  public static java.lang.String readText(java.io.Reader);
}
-keep class kotlin.jdk7.AutoCloseableKt {
  public static void closeFinally(java.lang.AutoCloseable, java.lang.Throwable);
}
-keep interface kotlin.jvm.functions.Function0 {
  public java.lang.Object invoke();
}
-keep interface kotlin.jvm.functions.Function1 {
  public java.lang.Object invoke(java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function2 {
  public java.lang.Object invoke(java.lang.Object, java.lang.Object);
}
-keep interface kotlin.jvm.functions.Function3 {
}
-keep class kotlin.jvm.internal.CallableReference {
  java.lang.Object receiver;
}
-keep class kotlin.jvm.internal.DefaultConstructorMarker {
}
-keep class kotlin.jvm.internal.FunctionReferenceImpl {
  public <init>(int, java.lang.Object, java.lang.Class, java.lang.String, java.lang.String, int);
}
-keep class kotlin.jvm.internal.Intrinsics {
  public static boolean areEqual(java.lang.Object, java.lang.Object);
  public static void checkNotNull(java.lang.Object);
  public static void checkNotNull(java.lang.Object, java.lang.String);
  public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
  public static void checkNotNullParameter(java.lang.Object, java.lang.String);
}
-keep class kotlin.jvm.internal.Lambda {
  public <init>(int);
}
-keep class kotlin.jvm.internal.Ref$IntRef {
  public <init>();
  int element;
}
-keep class kotlin.jvm.internal.Ref$LongRef {
  public <init>();
  long element;
}
-keep class kotlin.jvm.internal.Ref$ObjectRef {
  public <init>();
  java.lang.Object element;
}
-keep class kotlin.jvm.internal.Ref {
}
-keep class kotlin.jvm.internal.SpreadBuilder {
  public <init>(int);
  public void add(java.lang.Object);
  public void addSpread(java.lang.Object);
  public int size();
  public java.lang.Object[] toArray(java.lang.Object[]);
}
-keep class kotlin.jvm.internal.StringCompanionObject {
  kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keep class kotlin.math.MathKt {
}
-keep class kotlin.math.MathKt__MathJVMKt {
  public static int roundToInt(double);
}
-keep class kotlin.random.Random$Default {
  public int nextInt(int, int);
}
-keep class kotlin.random.Random {
  kotlin.random.Random$Default Default;
}
-keep class kotlin.ranges.IntRange {
}
-keep class kotlin.ranges.RangesKt {
}
-keep class kotlin.ranges.RangesKt___RangesKt {
  public static int coerceIn(int, int, int);
  public static kotlin.ranges.IntRange until(int, int);
}
-keep interface kotlin.sequences.Sequence {
  public java.util.Iterator iterator();
}
-keep class kotlin.text.Charsets {
  java.nio.charset.Charset UTF_8;
}
-keep class kotlin.text.MatchResult$Destructured {
  public kotlin.text.MatchResult getMatch();
}
-keep interface kotlin.text.MatchResult {
  public kotlin.text.MatchResult$Destructured getDestructured();
  public java.util.List getGroupValues();
}
-keep class kotlin.text.Regex {
  public <init>(java.lang.String);
  public boolean containsMatchIn(java.lang.CharSequence);
  public static kotlin.text.MatchResult find$default(kotlin.text.Regex, java.lang.CharSequence, int, int, java.lang.Object);
  public boolean matches(java.lang.CharSequence);
}
-keep class kotlin.text.StringsKt {
}
-keep class kotlin.text.StringsKt__StringsJVMKt {
  public static java.lang.String replace$default(java.lang.String, java.lang.String, java.lang.String, boolean, int, java.lang.Object);
  public static boolean startsWith$default(java.lang.String, java.lang.String, boolean, int, java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringsKt {
  public static boolean contains$default(java.lang.CharSequence, java.lang.CharSequence, boolean, int, java.lang.Object);
  public static boolean contains(java.lang.CharSequence, java.lang.CharSequence, boolean);
  public static boolean isBlank(java.lang.CharSequence);
  public static java.util.List lines(java.lang.CharSequence);
  public static java.util.List split$default(java.lang.CharSequence, java.lang.String[], boolean, int, int, java.lang.Object);
  public static java.lang.String substringAfter$default(java.lang.String, java.lang.String, java.lang.String, int, java.lang.Object);
  public static java.lang.Boolean toBooleanStrictOrNull(java.lang.String);
  public static java.lang.CharSequence trim(java.lang.CharSequence);
}
-keep class kotlin.time.Duration$Companion {
}
-keep class kotlin.time.Duration {
  public static java.lang.String toString-impl(long);
  kotlin.time.Duration$Companion Companion;
}
-keep class kotlin.time.DurationKt {
  public static long toDuration(int, kotlin.time.DurationUnit);
  public static long toDuration(long, kotlin.time.DurationUnit);
}
-keep enum kotlin.time.DurationUnit {
  kotlin.time.DurationUnit MILLISECONDS;
  kotlin.time.DurationUnit SECONDS;
}
-keep class kotlinx.coroutines.BuildersKt {
  public static kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2);
  public static java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2);
}
-keep class kotlinx.coroutines.CancellableContinuation$DefaultImpls {
  public static boolean cancel$default(kotlinx.coroutines.CancellableContinuation, java.lang.Throwable, int, java.lang.Object);
}
-keep interface kotlinx.coroutines.CancellableContinuation {
  public void invokeOnCancellation(kotlin.jvm.functions.Function1);
  public void resume(java.lang.Object, kotlin.jvm.functions.Function1);
}
-keep class kotlinx.coroutines.CancellableContinuationImpl {
  public <init>(kotlin.coroutines.Continuation, int);
  public java.lang.Object getResult();
  public void initCancellability();
}
-keep class kotlinx.coroutines.CoroutineDispatcher {
}
-keep interface kotlinx.coroutines.CoroutineScope {
  public kotlin.coroutines.CoroutineContext getCoroutineContext();
}
-keep enum kotlinx.coroutines.CoroutineStart {
  kotlinx.coroutines.CoroutineStart DEFAULT;
  kotlinx.coroutines.CoroutineStart UNDISPATCHED;
}
-keep interface kotlinx.coroutines.Deferred {
  public java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.Dispatchers {
  public static kotlinx.coroutines.MainCoroutineDispatcher getMain();
  public static kotlinx.coroutines.CoroutineDispatcher getUnconfined();
}
-keep class kotlinx.coroutines.ExecutorsKt {
  public static kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}
-keep class kotlinx.coroutines.Job$DefaultImpls {
  public static void cancel$default(kotlinx.coroutines.Job, java.util.concurrent.CancellationException, int, java.lang.Object);
}
-keep interface kotlinx.coroutines.Job {
}
-keep class kotlinx.coroutines.MainCoroutineDispatcher {
}
-keep class kotlinx.coroutines.TimeoutKt {
  public static java.lang.Object withTimeout-KLykuaI(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keeppackagenames androidx.concurrent.futures
