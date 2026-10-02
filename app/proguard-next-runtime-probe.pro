# Only choplabNextSizeProbe enables this test-to-app API boundary.
# R8 TraceReferences 9.4.24 traced the AGP 9.4.1 classfile inputs of the narrow
# PreviewAndroidTest program (runtime smoke, codec, offline TTS, four-stem
# production fixtures and runner libraries)
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
-keep enum ai.onnxruntime.OrtException$OrtErrorCode {
}
-keep class ai.onnxruntime.OrtException {
  public ai.onnxruntime.OrtException$OrtErrorCode getCode();
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
-keep class com.choplab.core.Action$Edit {
  public <init>(com.choplab.core.edit.Intent, java.lang.Long);
}
-keep class com.choplab.core.Action$Export {
  public <init>(com.choplab.core.ExportRequest, com.choplab.core.PlaybackTarget);
}
-keep class com.choplab.core.Action$New {
  public <init>(com.choplab.core.model.Project);
}
-keep class com.choplab.core.Action$Redo {
  com.choplab.core.Action$Redo INSTANCE;
}
-keep class com.choplab.core.Action$Undo {
  com.choplab.core.Action$Undo INSTANCE;
}
-keep interface com.choplab.core.Action {
}
-keep class com.choplab.core.ActionResult {
  public boolean getAccepted();
}
-keep class com.choplab.core.DocumentState {
  public com.choplab.core.model.Project getProject();
  public long getRevision();
}
-keep interface com.choplab.core.ExportPort {
}
-keep class com.choplab.core.ExportRequest {
  public <init>(com.choplab.core.Location, int, int, int, int, com.choplab.core.ExportTailMode, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep enum com.choplab.core.ExportTailMode {
}
-keep interface com.choplab.core.ImportPort {
}
-keep class com.choplab.core.Location {
  public <init>(java.lang.String);
  public java.lang.String getHandle();
}
-keep class com.choplab.core.PlaybackTarget$Arrangement {
  public <init>(com.choplab.core.model.FrozenList, long, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep interface com.choplab.core.PlaybackTarget {
}
-keep class com.choplab.core.ProgramCompiler {
}
-keep interface com.choplab.core.ProjectPort {
}
-keep interface com.choplab.core.StemExportPort {
}
-keep class com.choplab.core.Studio {
  public java.lang.Object dispatch(com.choplab.core.Action, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getDocument();
  public kotlinx.coroutines.flow.StateFlow getWork();
}
-keep class com.choplab.core.WorkState {
  public java.lang.Long getJobId();
}
-keep enum com.choplab.core.ai.FlowMode {
  com.choplab.core.ai.FlowMode ONE_BAR;
}
-keep class com.choplab.core.ai.FlowPlan {
  public com.choplab.core.model.FrozenList getRows();
}
-keep class com.choplab.core.ai.FlowPlanner {
  public static com.choplab.core.ai.FlowResult plan$default(com.choplab.core.ai.FlowPlanner, com.choplab.core.model.Project, long, com.choplab.core.ai.FlowMode, java.util.Map, int, java.lang.Object);
  com.choplab.core.ai.FlowPlanner INSTANCE;
}
-keep class com.choplab.core.ai.FlowPlannerKt {
  public static com.choplab.core.ai.StructuredLyricPlacement placeStructured(com.choplab.core.ai.LyricProposal, long, int, java.lang.String);
}
-keep class com.choplab.core.ai.FlowResult$Ready {
  public com.choplab.core.ai.FlowPlan getPlan();
}
-keep interface com.choplab.core.ai.FlowResult {
}
-keep class com.choplab.core.ai.FlowRow {
}
-keep enum com.choplab.core.ai.LyricLanguage {
  com.choplab.core.ai.LyricLanguage ENGLISH;
  com.choplab.core.ai.LyricLanguage JAPANESE;
}
-keep class com.choplab.core.ai.LyricProposal {
  public <init>(java.lang.String, com.choplab.core.ai.LyricLanguage, com.choplab.core.model.FrozenList);
}
-keep enum com.choplab.core.ai.LyricSectionKind {
  com.choplab.core.ai.LyricSectionKind VERSE;
}
-keep class com.choplab.core.ai.PreparedVocalLine {
  public com.choplab.core.model.Asset getAsset();
  public com.choplab.core.model.LyricLine getLine();
  public double getSpeed();
}
-keep class com.choplab.core.ai.ProposalLine$Companion {
  public com.choplab.core.ai.ProposalLine create(java.lang.String, java.lang.String, com.choplab.core.ai.LyricLanguage);
}
-keep class com.choplab.core.ai.ProposalLine {
  com.choplab.core.ai.ProposalLine$Companion Companion;
}
-keep class com.choplab.core.ai.ProposalSection {
  public <init>(java.lang.String, com.choplab.core.ai.LyricSectionKind, int, com.choplab.core.model.FrozenList);
}
-keep class com.choplab.core.ai.StructuredLyricPlacement {
  public com.choplab.core.model.FrozenList getLines();
  public com.choplab.core.model.LyricStructure getStructure();
}
-keep class com.choplab.core.ai.TtsAudio {
}
-keep class com.choplab.core.ai.TtsFailure {
  public com.choplab.core.ai.TtsProblem getProblem();
  public java.lang.Double getSpeedRequired();
}
-keep enum com.choplab.core.ai.TtsProblem {
  com.choplab.core.ai.TtsProblem CANNOT_FIT;
  com.choplab.core.ai.TtsProblem NO_OFFLINE_VOICE;
  com.choplab.core.ai.TtsProblem UNAVAILABLE;
}
-keep interface com.choplab.core.ai.TtsProvider {
  public void close();
  public java.lang.Object synthesize(com.choplab.core.ai.TtsRequest, kotlin.coroutines.Continuation);
  public java.lang.Object voices(kotlin.coroutines.Continuation);
}
-keep class com.choplab.core.ai.TtsRequest {
}
-keep class com.choplab.core.ai.TtsResult$Failure {
  public com.choplab.core.ai.TtsFailure getFailure();
}
-keep class com.choplab.core.ai.TtsResult$Success {
  public java.lang.Object getValue();
}
-keep interface com.choplab.core.ai.TtsResult {
}
-keep class com.choplab.core.ai.TtsSettings {
  public <init>(int, int, int, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.ai.TtsVoice {
  public com.choplab.core.ai.LyricLanguage getLanguage();
  public boolean getOffline();
}
-keep class com.choplab.core.ai.VocalGuideEditKt {
  public static com.choplab.core.ai.TtsResult guideEdit(com.choplab.core.ai.FlowPlan, com.choplab.core.model.Project, java.util.List, java.lang.String);
}
-keep class com.choplab.core.edit.Intent$ApplyVocalGuide {
}
-keep interface com.choplab.core.edit.Intent {
}
-keep class com.choplab.core.model.Asset {
  public <init>(java.lang.String, java.lang.String, long, int, int, long, java.lang.String, com.choplab.core.model.AssetRole, boolean, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static com.choplab.core.model.Asset copy$default(com.choplab.core.model.Asset, java.lang.String, java.lang.String, long, int, int, long, java.lang.String, com.choplab.core.model.AssetRole, boolean, java.lang.String, int, java.lang.Object);
  public long getFrames();
  public java.lang.String getHash();
}
-keep enum com.choplab.core.model.AssetRole {
}
-keep class com.choplab.core.model.Clip {
  public com.choplab.core.model.FrameRange getRange();
}
-keep class com.choplab.core.model.FrameRange {
  public <init>(long, long);
}
-keep class com.choplab.core.model.FrozenList {
}
-keep class com.choplab.core.model.LyricLine {
  public <init>(java.lang.String, java.lang.String, long, long, com.choplab.core.model.FrozenList, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public com.choplab.core.model.FrozenList getWords();
}
-keep class com.choplab.core.model.LyricStructure {
}
-keep class com.choplab.core.model.LyricWord {
  public java.lang.String getText();
  public com.choplab.core.model.WordTimingOrigin getTimingOrigin();
}
-keep class com.choplab.core.model.Project {
  public <init>(java.lang.String, java.lang.String, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.Source, com.choplab.engine.Tempo, int, com.choplab.engine.MixSettings, com.choplab.core.model.LyricStructure, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static com.choplab.core.model.Project copy$default(com.choplab.core.model.Project, java.lang.String, java.lang.String, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.Source, com.choplab.engine.Tempo, int, com.choplab.engine.MixSettings, com.choplab.core.model.LyricStructure, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, int, java.lang.Object);
  public com.choplab.core.model.FrozenList getAssets();
  public com.choplab.core.model.FrozenList getClips();
  public com.choplab.core.model.FrozenList getLyrics();
  public com.choplab.core.model.Source getSource();
  public com.choplab.engine.Tempo getTempo();
  public com.choplab.core.model.FrozenList getTracks();
}
-keep class com.choplab.core.model.ProjectKt {
  public static com.choplab.core.model.FrozenList frozenListOf(java.lang.Object[]);
}
-keep class com.choplab.core.model.Source {
  public <init>(java.lang.String, com.choplab.core.model.FrameRange, com.choplab.core.model.FrozenList, double, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.model.Track {
  public com.choplab.core.model.TrackKind getKind();
  public boolean getMute();
}
-keep enum com.choplab.core.model.TrackKind {
  com.choplab.core.model.TrackKind GUIDE;
}
-keep enum com.choplab.core.model.WordTimingOrigin {
  com.choplab.core.model.WordTimingOrigin ESTIMATED;
}
-keep interface com.choplab.core.separation.FourStemPort {
  public void cancel();
  public void close();
  public com.choplab.core.separation.SeparationMemoryReceipt memoryReceipt();
  public static java.lang.Object prepare$default(com.choplab.core.separation.FourStemPort, com.choplab.core.model.Asset, boolean, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation, int, java.lang.Object);
  public java.lang.Object prepare(com.choplab.core.model.Asset, boolean, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
}
-keep class com.choplab.core.separation.PreparedFourStems {
  public java.lang.String getModelSha256();
  public com.choplab.core.model.FrozenList getStems();
  public com.choplab.core.separation.SeparationResult placement(com.choplab.core.model.Project, long, com.choplab.core.separation.StemMix, java.lang.String);
}
-keep class com.choplab.core.separation.SeparatedStem {
  public com.choplab.core.model.Asset getAsset();
  public com.choplab.core.separation.StemPart getPart();
}
-keep class com.choplab.core.separation.SeparationFailure {
  public com.choplab.core.separation.SeparationProblem getProblem();
}
-keep class com.choplab.core.separation.SeparationMemoryReceipt {
  public <init>(com.choplab.core.separation.SeparationMemorySource, long, long, boolean, long);
  public long getAvailableBytes();
  public boolean getLowMemory();
  public long getMeasuredAtEpochMillis();
  public com.choplab.core.separation.SeparationMemorySource getSource();
  public long getTotalBytes();
}
-keep enum com.choplab.core.separation.SeparationMemorySource {
  com.choplab.core.separation.SeparationMemorySource ANDROID_ACTIVITY_MANAGER;
}
-keep enum com.choplab.core.separation.SeparationProblem {
  com.choplab.core.separation.SeparationProblem CANCELLED;
  com.choplab.core.separation.SeparationProblem CLOSED;
  com.choplab.core.separation.SeparationProblem INVALID_INPUT;
  com.choplab.core.separation.SeparationProblem STALE_DOCUMENT;
}
-keep class com.choplab.core.separation.SeparationProgress {
  public long getCompletedFrames();
}
-keep class com.choplab.core.separation.SeparationResult$Failure {
  public com.choplab.core.separation.SeparationFailure getFailure();
}
-keep class com.choplab.core.separation.SeparationResult$Success {
  public java.lang.Object getValue();
}
-keep interface com.choplab.core.separation.SeparationResult {
}
-keep enum com.choplab.core.separation.StemMix {
  com.choplab.core.separation.StemMix INSTRUMENTAL;
}
-keep enum com.choplab.core.separation.StemPart {
  public static kotlin.enums.EnumEntries getEntries();
}
-keep class com.choplab.engine.MixSettings {
}
-keep class com.choplab.engine.Tempo {
  public <init>(int, int, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public int getMilliBpm();
}
-keep class com.choplab.jvm.ArchiveCodec {
  public <init>(com.choplab.jvm.ArchiveLimits, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static com.choplab.core.model.Project read$default(com.choplab.jvm.ArchiveCodec, java.io.InputStream, com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function0, int, java.lang.Object);
  public static void write$default(com.choplab.jvm.ArchiveCodec, com.choplab.core.model.Project, com.choplab.jvm.FileAssetStore, java.io.OutputStream, kotlin.jvm.functions.Function0, int, java.lang.Object);
}
-keep class com.choplab.jvm.ArchiveLimits {
}
-keep interface com.choplab.jvm.AudioSink {
}
-keep class com.choplab.jvm.AutosaveStore {
  public <init>(java.nio.file.Path, com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function0, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public com.choplab.jvm.Recovery recover();
}
-keep class com.choplab.jvm.EditorBackend$Companion {
  public static com.choplab.jvm.EditorBackend create$default(com.choplab.jvm.EditorBackend$Companion, java.nio.file.Path, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function2, com.choplab.jvm.OriginalAudioDecoder, int, java.lang.Object);
}
-keep class com.choplab.jvm.EditorBackend {
  public com.choplab.core.separation.FourStemPort createFourStemWorker(com.choplab.jvm.separation.FourStemSessionFactory, kotlin.jvm.functions.Function0);
  public java.lang.Object flushAutosave(kotlin.coroutines.Continuation);
  public com.choplab.jvm.FileAssetStore getAssets();
  public com.choplab.core.Studio getStudio();
  public static java.lang.Object shutdown$default(com.choplab.jvm.EditorBackend, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
  com.choplab.jvm.EditorBackend$Companion Companion;
}
-keep class com.choplab.jvm.FileAssetStore {
  public <init>(java.nio.file.Path, long, com.choplab.jvm.OriginalAudioDecoder, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static void adopt$default(com.choplab.jvm.FileAssetStore, com.choplab.core.model.Asset, java.nio.file.Path, kotlin.jvm.functions.Function0, int, java.lang.Object);
  public java.nio.file.Path getDirectory();
  public java.lang.Object read(com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public long storedBytes();
}
-keep class com.choplab.jvm.FileProjectPort {
  public <init>(com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function1, com.choplab.jvm.ArchiveCodec, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.HostFileServices {
  public <init>(com.choplab.core.ImportPort, com.choplab.core.ProjectPort, com.choplab.core.ExportPort, com.choplab.core.StemExportPort, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep interface com.choplab.jvm.OriginalAudioDecoder {
}
-keep class com.choplab.jvm.PcmMemoryBudget$Companion {
  public com.choplab.jvm.PcmMemoryBudget getShared();
}
-keep class com.choplab.jvm.PcmMemoryBudget {
  public java.lang.Object statistics(kotlin.coroutines.Continuation);
  com.choplab.jvm.PcmMemoryBudget$Companion Companion;
}
-keep class com.choplab.jvm.PcmMemoryStats {
  public long getLimitBytes();
  public long getPeakBytes();
  public long getUsedBytes();
}
-keep class com.choplab.jvm.Recovery {
  public com.choplab.core.model.Project getProject();
}
-keep class com.choplab.jvm.StreamingEnginePort {
  public <init>(com.choplab.core.ProgramCompiler, kotlin.jvm.functions.Function0, int, long, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.WavAudio {
  public com.choplab.jvm.WavInfo getInfo();
  public float[] getSamples();
}
-keep class com.choplab.jvm.WavCodec {
  public static com.choplab.jvm.WavAudio read$default(com.choplab.jvm.WavCodec, java.io.InputStream, long, long, int, java.lang.Object);
  public static void writePcm$default(com.choplab.jvm.WavCodec, java.io.OutputStream, float[], int, int, int, int, boolean, int, java.lang.Object);
  com.choplab.jvm.WavCodec INSTANCE;
}
-keep class com.choplab.jvm.WavExportPort {
  public <init>(com.choplab.core.ProgramCompiler, kotlin.jvm.functions.Function1);
}
-keep class com.choplab.jvm.WavImportPort {
  public <init>(com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.WavInfo {
  public int getBits();
  public int getChannels();
  public boolean getFloatingPoint();
  public long getFrames();
  public int getSampleRate();
}
-keep class com.choplab.jvm.ai.TtsCache {
  public <init>(java.nio.file.Path, long, com.choplab.jvm.PcmMemoryBudget, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.ai.VocalTtsService {
  public <init>(com.choplab.core.ai.TtsProvider, com.choplab.jvm.ai.TtsCache, com.choplab.jvm.FileAssetStore);
  public void close();
  public java.lang.Object prepare(com.choplab.core.ai.FlowRow, com.choplab.engine.Tempo, com.choplab.core.ai.TtsVoice, com.choplab.core.ai.TtsSettings, boolean, kotlin.coroutines.Continuation);
  public java.lang.Object voices(kotlin.coroutines.Continuation);
}
-keep interface com.choplab.jvm.separation.FourStemInference {
  public void cancel();
  public void infer(float[], kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function1);
}
-keep class com.choplab.jvm.separation.FourStemModelStore$Companion {
  public static void verify$default(com.choplab.jvm.separation.FourStemModelStore$Companion, java.nio.file.Path, kotlin.jvm.functions.Function0, int, java.lang.Object);
}
-keep class com.choplab.jvm.separation.FourStemModelStore$ModelDownload {
  public <init>(long, java.io.InputStream, kotlin.jvm.functions.Function0);
}
-keep class com.choplab.jvm.separation.FourStemModelStore {
  public <init>(java.nio.file.Path, kotlin.jvm.functions.Function1);
  public static java.nio.file.Path ensure$default(com.choplab.jvm.separation.FourStemModelStore, boolean, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function0, int, java.lang.Object);
  public java.nio.file.Path ensure(boolean, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function0);
  public java.nio.file.Path getModel();
  com.choplab.jvm.separation.FourStemModelStore$Companion Companion;
}
-keep interface com.choplab.jvm.separation.FourStemSessionFactory {
  public com.choplab.jvm.separation.FourStemInference open(com.choplab.jvm.PcmMemoryBudget, com.choplab.jvm.separation.SeparationMemory, boolean, kotlin.jvm.functions.Function0);
}
-keep class com.choplab.jvm.separation.OnnxFourStemFactory {
  public <init>(com.choplab.jvm.separation.FourStemModelStore);
  public com.choplab.jvm.separation.FourStemInference open(com.choplab.jvm.PcmMemoryBudget, com.choplab.jvm.separation.SeparationMemory, boolean, kotlin.jvm.functions.Function0);
}
-keep class com.choplab.jvm.separation.SeparationMemory {
  public <init>(long, long, boolean, com.choplab.core.separation.SeparationMemoryReceipt);
  public com.choplab.core.separation.SeparationProblem refusal();
}
-keep class com.choplab.sampler.next.AndroidTtsProvider {
  public <init>(android.content.Context);
  public java.lang.Object synthesize(com.choplab.core.ai.TtsRequest, kotlin.coroutines.Continuation);
  public java.lang.Object voices(kotlin.coroutines.Continuation);
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
-keep class kotlin.KotlinNothingValueException {
  public <init>();
}
-keep interface kotlin.Lazy {
  public java.lang.Object getValue();
}
-keep class kotlin.LazyKt {
}
-keep class kotlin.LazyKt__LazyJVMKt {
  public static kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.NoWhenBranchMatchedException {
  public <init>();
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
-keep class kotlin.collections.AbstractCollection {
  public int size();
}
-keep class kotlin.collections.AbstractList {
  public java.util.Iterator iterator();
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
  public static java.util.List distinct(java.lang.Iterable);
  public static java.lang.Object first(java.util.List);
  public static java.lang.Object firstOrNull(java.util.List);
  public static java.lang.String joinToString$default(java.lang.Iterable, java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.lang.Object last(java.util.List);
  public static java.util.List minus(java.lang.Iterable, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Iterable);
  public static java.util.List plus(java.util.Collection, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Object[]);
  public static java.lang.Object single(java.util.List);
  public static java.util.List sortedWith(java.lang.Iterable, java.util.Comparator);
  public static int[] toIntArray(java.util.Collection);
  public static java.util.Set toSet(java.lang.Iterable);
}
-keep class kotlin.collections.IntIterator {
  public int nextInt();
}
-keep class kotlin.collections.MapsKt {
}
-keep class kotlin.collections.MapsKt__MapsJVMKt {
  public static java.util.Map mapOf(kotlin.Pair);
}
-keep class kotlin.collections.MapsKt__MapsKt {
  public static java.util.Map emptyMap();
  public static java.lang.Object getValue(java.util.Map, java.lang.Object);
  public static java.util.LinkedHashMap linkedMapOf(kotlin.Pair[]);
  public static java.util.Map mapOf(kotlin.Pair[]);
  public static java.util.Map plus(java.util.Map, java.util.Map);
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
-keep class kotlin.coroutines.jvm.internal.Boxing {
  public static java.lang.Boolean boxBoolean(boolean);
  public static java.lang.Integer boxInt(int);
  public static java.lang.Long boxLong(long);
}
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl {
  public <init>(kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt {
  public static void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.SpillingKt {
  public static java.lang.Object nullOutSpilledVariable(java.lang.Object);
}
-keep interface kotlin.coroutines.jvm.internal.SuspendFunction {
}
-keep class kotlin.coroutines.jvm.internal.SuspendLambda {
  public <init>(int, kotlin.coroutines.Continuation);
  public kotlin.coroutines.Continuation create(java.lang.Object, kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep interface kotlin.enums.EnumEntries {
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
-keep class kotlin.jvm.internal.AdaptedFunctionReference {
  public <init>(int, java.lang.Object, java.lang.Class, java.lang.String, java.lang.String, int);
  java.lang.Object receiver;
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
  public static void throwUninitializedPropertyAccessException(java.lang.String);
}
-keep class kotlin.jvm.internal.Lambda {
  public <init>(int);
}
-keep class kotlin.jvm.internal.Ref$BooleanRef {
  public <init>();
  boolean element;
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
-keep class kotlin.text.CharsKt {
}
-keep class kotlin.text.CharsKt__CharJVMKt {
  public static int checkRadix(int);
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
  public static java.lang.String repeat(java.lang.CharSequence, int);
  public static java.lang.String replace$default(java.lang.String, java.lang.String, java.lang.String, boolean, int, java.lang.Object);
  public static boolean startsWith$default(java.lang.String, java.lang.String, boolean, int, java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringsKt {
  public static boolean contains$default(java.lang.CharSequence, java.lang.CharSequence, boolean, int, java.lang.Object);
  public static boolean contains(java.lang.CharSequence, java.lang.CharSequence, boolean);
  public static boolean isBlank(java.lang.CharSequence);
  public static java.util.List lines(java.lang.CharSequence);
  public static java.lang.String padStart(java.lang.String, int, char);
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
  public static kotlinx.coroutines.Deferred async$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
  public static kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2);
  public static java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2);
  public static java.lang.Object runBlockingK$default(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2, int, java.lang.Object);
  public static java.lang.Object withContext(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
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
-keep class kotlinx.coroutines.DelayKt {
  public static java.lang.Object delay(long, kotlin.coroutines.Continuation);
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
-keep class kotlinx.coroutines.JobKt {
  public static void cancel$default(kotlin.coroutines.CoroutineContext, java.util.concurrent.CancellationException, int, java.lang.Object);
}
-keep class kotlinx.coroutines.MainCoroutineDispatcher {
  public kotlinx.coroutines.MainCoroutineDispatcher getImmediate();
}
-keep class kotlinx.coroutines.SupervisorKt {
  public static java.lang.Object supervisorScope(kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.TimeoutCancellationException {
}
-keep class kotlinx.coroutines.TimeoutKt {
  public static java.lang.Object withTimeout(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
  public static java.lang.Object withTimeout-KLykuaI(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep interface kotlinx.coroutines.flow.StateFlow {
  public java.lang.Object getValue();
}
-keeppackagenames androidx.concurrent.futures
