# Only choplabNextSizeProbe enables this test-to-app API boundary.
# R8 TraceReferences 9.4.24 traced the AGP 9.4.1 classfile inputs of the narrow
# PreviewAndroidTest program (runtime smoke, codec, offline TTS, four-stem
# production fixtures, synthetic whole-creation and runner libraries)
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
  public <init>(com.choplab.core.edit.Intent, java.lang.Long, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.Action$Export {
  public <init>(com.choplab.core.ExportRequest, com.choplab.core.PlaybackTarget);
}
-keep class com.choplab.core.Action$Import {
  public <init>(com.choplab.core.Location, java.lang.Long, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.Action$New {
  public <init>(com.choplab.core.model.Project);
}
-keep class com.choplab.core.Action$Open {
  public <init>(com.choplab.core.Location);
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
  com.choplab.core.ExportTailMode EXACT;
}
-keep interface com.choplab.core.ImportPort {
}
-keep class com.choplab.core.Location {
  public <init>(java.lang.String);
  public java.lang.String getHandle();
}
-keep interface com.choplab.core.LoopOverdubCapture {
  public com.choplab.engine.LoopOverdub getTake();
}
-keep class com.choplab.core.Notice$Cancelled {
  public com.choplab.core.Operation getOperation();
}
-keep class com.choplab.core.Notice$Completed {
  public com.choplab.core.Operation getOperation();
}
-keep class com.choplab.core.Notice$Failed {
  public com.choplab.core.Operation getOperation();
}
-keep interface com.choplab.core.Notice {
}
-keep enum com.choplab.core.Operation {
  com.choplab.core.Operation EXPORT;
}
-keep class com.choplab.core.PlaybackTarget$Arrangement {
  public <init>(com.choplab.core.model.FrozenList, long, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep interface com.choplab.core.PlaybackTarget {
}
-keep class com.choplab.core.ProgramCompiler$Companion {
  public long tickToFrame(long, int);
}
-keep class com.choplab.core.ProgramCompiler {
  com.choplab.core.ProgramCompiler$Companion Companion;
}
-keep interface com.choplab.core.ProjectPort {
}
-keep enum com.choplab.core.StemExportPhase {
}
-keep interface com.choplab.core.StemExportPort {
  public java.lang.Object export(com.choplab.core.model.Project, com.choplab.core.PlaybackTarget, com.choplab.core.StemExportRequest, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
}
-keep class com.choplab.core.StemExportProgress {
  public int getCompletedStems();
  public com.choplab.core.StemExportPhase getPhase();
  public int getTotalStems();
}
-keep class com.choplab.core.StemExportReceipt {
}
-keep class com.choplab.core.StemExportRequest {
  public <init>(com.choplab.core.Location, int, int, com.choplab.core.StemSampleFormat, int, com.choplab.core.ExportTailMode, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep enum com.choplab.core.StemSampleFormat {
}
-keep class com.choplab.core.Studio {
  public java.lang.Object dispatch(com.choplab.core.Action, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getDocument();
  public kotlinx.coroutines.flow.SharedFlow getNotices();
  public kotlinx.coroutines.flow.StateFlow getWork();
}
-keep class com.choplab.core.TransportState {
  public int getCountInBeatsRemaining();
  public long getFrame();
}
-keep class com.choplab.core.VoiceTake {
}
-keep class com.choplab.core.WorkState {
  public java.lang.Long getJobId();
  public java.lang.Long getPreparationId();
}
-keep enum com.choplab.core.ai.FlowMode {
  com.choplab.core.ai.FlowMode ONE_BAR;
}
-keep class com.choplab.core.ai.FlowPlan {
  public boolean getHasDensityAdvice();
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
-keep class com.choplab.core.ai.TtsAudio$Companion {
  public static com.choplab.core.ai.TtsAudio fromPcm$default(com.choplab.core.ai.TtsAudio$Companion, float[], int, int, java.util.List, int, java.lang.Object);
}
-keep class com.choplab.core.ai.TtsAudio {
  com.choplab.core.ai.TtsAudio$Companion Companion;
}
-keep class com.choplab.core.ai.TtsEngine {
  public <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
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
  public <init>(java.lang.Object);
  public java.lang.Object getValue();
}
-keep interface com.choplab.core.ai.TtsResult {
}
-keep class com.choplab.core.ai.TtsSettings {
  public <init>(int, int, int, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.ai.TtsVoice {
  public <init>(com.choplab.core.ai.TtsEngine, java.lang.String, java.lang.String, java.lang.String, java.lang.String, com.choplab.core.ai.LyricLanguage, boolean, boolean, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public com.choplab.core.ai.LyricLanguage getLanguage();
  public boolean getOffline();
}
-keep class com.choplab.core.ai.VocalGuideEditKt {
  public static com.choplab.core.ai.TtsResult guideEdit(com.choplab.core.ai.FlowPlan, com.choplab.core.model.Project, java.util.List, java.lang.String);
}
-keep enum com.choplab.core.ai.VocalPreviewOwner {
  com.choplab.core.ai.VocalPreviewOwner PRACTICE;
}
-keep interface com.choplab.core.ai.VocalPreviewPort {
  public java.lang.Object stop(kotlin.coroutines.Continuation);
}
-keep class com.choplab.core.ai.VocalPreviewState {
  public com.choplab.core.ai.VocalPreviewOwner getOwner();
}
-keep interface com.choplab.core.ai.VocalSynthesisPort {
}
-keep class com.choplab.core.analysis.KeyCandidate {
  public com.choplab.core.analysis.KeyMode getMode();
  public int getTonic();
}
-keep enum com.choplab.core.analysis.KeyMode {
  com.choplab.core.analysis.KeyMode MAJOR;
}
-keep class com.choplab.core.analysis.SourceMusicResult {
  public com.choplab.core.model.FrozenList getKeys();
  public com.choplab.core.model.FrozenList getTempos();
}
-keep class com.choplab.core.analysis.TempoCandidate {
  public int getMilliBpm();
}
-keep enum com.choplab.core.chop.AutoChopMode {
  com.choplab.core.chop.AutoChopMode ATTACK;
}
-keep interface com.choplab.core.chop.AutoChopPort {
}
-keep enum com.choplab.core.chop.AutoChopProblem {
}
-keep class com.choplab.core.chop.AutoChopSettings {
  public <init>(com.choplab.core.chop.AutoChopMode, int, int, int, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.chop.LiveChopOutput {
}
-keep interface com.choplab.core.chop.LiveChopProbe {
}
-keep class com.choplab.core.edit.Intent$ApplyVocalGuide {
}
-keep class com.choplab.core.edit.Intent$AssignSlice {
  public <init>(int, int);
}
-keep class com.choplab.core.edit.Intent$SetPad {
  public <init>(com.choplab.core.model.Pad, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.core.edit.Intent$SetStructuredLyrics {
  public <init>(com.choplab.core.model.FrozenList, com.choplab.core.model.LyricStructure);
}
-keep class com.choplab.core.edit.Intent$SetTempo {
  public <init>(com.choplab.engine.Tempo, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep interface com.choplab.core.edit.Intent {
}
-keep class com.choplab.core.edit.StretchDraft {
}
-keep class com.choplab.core.lyrics.LrcCodec {
  public static com.choplab.core.lyrics.LyricResult parse$default(com.choplab.core.lyrics.LrcCodec, java.lang.String, com.choplab.core.lyrics.LyricTiming, long, com.choplab.core.lyrics.LrcLimits, java.lang.Long, int, java.lang.Object);
  com.choplab.core.lyrics.LrcCodec INSTANCE;
}
-keep enum com.choplab.core.lyrics.LrcFormat {
}
-keep class com.choplab.core.lyrics.LrcImport {
  public com.choplab.core.model.FrozenList getLines();
}
-keep class com.choplab.core.lyrics.LrcLimits {
}
-keep class com.choplab.core.lyrics.LyricIssue {
  public com.choplab.core.lyrics.LyricProblem getProblem();
}
-keep enum com.choplab.core.lyrics.LyricProblem {
}
-keep class com.choplab.core.lyrics.LyricResult$Failure {
  public com.choplab.core.lyrics.LyricIssue getIssue();
}
-keep class com.choplab.core.lyrics.LyricResult$Success {
  public java.lang.Object getValue();
}
-keep interface com.choplab.core.lyrics.LyricResult {
}
-keep class com.choplab.core.lyrics.LyricTiming {
  public <init>(int);
}
-keep class com.choplab.core.model.Asset {
  public <init>(java.lang.String, java.lang.String, long, int, int, long, java.lang.String, com.choplab.core.model.AssetRole, boolean, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static com.choplab.core.model.Asset copy$default(com.choplab.core.model.Asset, java.lang.String, java.lang.String, long, int, int, long, java.lang.String, com.choplab.core.model.AssetRole, boolean, java.lang.String, int, java.lang.Object);
  public long getFrames();
  public java.lang.String getHash();
}
-keep enum com.choplab.core.model.AssetRole {
}
-keep class com.choplab.core.model.Bank {
  public java.lang.String getTrackId();
}
-keep class com.choplab.core.model.Clip {
  public float getGain();
  public java.lang.String getId();
  public float getPan();
  public com.choplab.core.model.FrameRange getRange();
  public long getStartTick();
  public java.lang.String getTrackId();
}
-keep class com.choplab.core.model.FrameRange {
  public <init>(long, long);
  public long getEnd();
  public long getStart();
}
-keep class com.choplab.core.model.FrozenList {
  public java.lang.Object get(int);
}
-keep class com.choplab.core.model.LyricLine {
  public <init>(java.lang.String, java.lang.String, long, long, com.choplab.core.model.FrozenList, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static com.choplab.core.model.LyricLine copy$default(com.choplab.core.model.LyricLine, java.lang.String, java.lang.String, long, long, com.choplab.core.model.FrozenList, int, java.lang.Object);
  public long getEndTick();
  public java.lang.String getId();
  public long getStartTick();
  public java.lang.String getText();
  public com.choplab.core.model.FrozenList getWords();
}
-keep class com.choplab.core.model.LyricStructure {
}
-keep class com.choplab.core.model.LyricWord {
  public static com.choplab.core.model.LyricWord copy$default(com.choplab.core.model.LyricWord, java.lang.String, long, long, com.choplab.core.model.WordTimingOrigin, int, java.lang.Object);
  public long getEndTick();
  public long getStartTick();
  public java.lang.String getText();
  public com.choplab.core.model.WordTimingOrigin getTimingOrigin();
}
-keep class com.choplab.core.model.Note {
  public int getPadId();
  public int getTick();
}
-keep class com.choplab.core.model.Pad {
  public static com.choplab.core.model.Pad copy$default(com.choplab.core.model.Pad, int, java.lang.String, com.choplab.core.model.FrameRange, java.lang.String, com.choplab.engine.PlayMode, double, float, float, boolean, int, int, int, int, int, float, float, int, java.lang.Object);
}
-keep class com.choplab.core.model.Pattern {
  public com.choplab.core.model.FrozenList getNotes();
}
-keep class com.choplab.core.model.Project {
  public <init>(java.lang.String, java.lang.String, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.Source, com.choplab.engine.Tempo, int, com.choplab.engine.MixSettings, com.choplab.core.model.LyricStructure, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public com.choplab.core.model.Asset asset(java.lang.String);
  public static com.choplab.core.model.Project copy$default(com.choplab.core.model.Project, java.lang.String, java.lang.String, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.Source, com.choplab.engine.Tempo, int, com.choplab.engine.MixSettings, com.choplab.core.model.LyricStructure, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, com.choplab.core.model.FrozenList, int, java.lang.Object);
  public com.choplab.core.model.FrozenList getAssets();
  public com.choplab.core.model.FrozenList getBanks();
  public com.choplab.core.model.FrozenList getBeatStretches();
  public com.choplab.core.model.FrozenList getClips();
  public com.choplab.core.model.LyricStructure getLyricStructure();
  public com.choplab.core.model.FrozenList getLyrics();
  public com.choplab.core.model.FrozenList getPads();
  public com.choplab.core.model.FrozenList getPatterns();
  public com.choplab.core.model.FrozenList getPitchCorrections();
  public com.choplab.core.model.Source getSource();
  public com.choplab.core.model.FrozenList getTakes();
  public com.choplab.engine.Tempo getTempo();
  public com.choplab.core.model.FrozenList getTracks();
  public com.choplab.core.model.FrozenList getVocalComps();
}
-keep class com.choplab.core.model.ProjectKt {
  public static com.choplab.core.model.FrozenList frozen(java.lang.Iterable);
  public static com.choplab.core.model.FrozenList frozenListOf(java.lang.Object[]);
}
-keep class com.choplab.core.model.Source {
  public <init>(java.lang.String, com.choplab.core.model.FrameRange, com.choplab.core.model.FrozenList, double, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public java.lang.String getAssetHash();
  public com.choplab.core.model.FrozenList getMarkers();
}
-keep enum com.choplab.core.model.StretchKind {
  com.choplab.core.model.StretchKind PAD;
}
-keep class com.choplab.core.model.StretchTarget {
  public <init>(com.choplab.core.model.StretchKind, java.lang.String);
}
-keep class com.choplab.core.model.Take {
  public java.lang.String getAssetHash();
  public java.lang.String getId();
  public com.choplab.core.model.FrameRange getRange();
}
-keep class com.choplab.core.model.Track {
  public java.lang.String getId();
  public com.choplab.core.model.TrackKind getKind();
  public boolean getMute();
}
-keep enum com.choplab.core.model.TrackKind {
  com.choplab.core.model.TrackKind GUIDE;
}
-keep class com.choplab.core.model.VocalComp {
  public com.choplab.core.model.FrozenList getSegments();
}
-keep class com.choplab.core.model.VocalCompKt {
  public static long correctedEndFrame(com.choplab.core.model.Take, com.choplab.core.model.Asset);
  public static long correctedStartFrame(com.choplab.core.model.Take);
}
-keep class com.choplab.core.model.VocalCompSegment {
  public java.lang.String getId();
  public java.lang.String getTakeId();
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
-keep enum com.choplab.core.vocal.CoachProblem {
}
-keep enum com.choplab.core.vocal.CoachVoiceInput {
  com.choplab.core.vocal.CoachVoiceInput VOICE_ONLY;
}
-keep enum com.choplab.core.vocal.PracticeProblem {
}
-keep class com.choplab.core.vocal.PracticeProgress {
}
-keep enum com.choplab.core.vocal.PunchProblem {
}
-keep interface com.choplab.core.vocal.VocalCoachAnalyzer {
}
-keep class com.choplab.core.vocal.VocalCoachReport {
  public com.choplab.core.model.FrozenList getLines();
}
-keep class com.choplab.core.vocal.VocalCompDraft {
  public com.choplab.core.model.FrozenList getSegments();
}
-keep class com.choplab.core.vocal.VocalCompEdits {
  public com.choplab.core.vocal.VocalCompDraft lines(com.choplab.core.model.Project, java.lang.String, java.lang.String);
  com.choplab.core.vocal.VocalCompEdits INSTANCE;
}
-keep class com.choplab.core.vocal.VocalPitchDraft {
}
-keep interface com.choplab.core.vocal.VocalPracticeRenderer {
}
-keep enum com.choplab.core.vocal.VocalProblem {
  com.choplab.core.vocal.VocalProblem TAKE_TOO_SHORT;
}
-keep interface com.choplab.core.vocal.VocalPunchPort {
}
-keep class com.choplab.engine.LoopOverdub {
  public int getAcceptedPresses();
  public long getElapsedFrames();
  public int getFrames();
  public int getRouteCount();
}
-keep class com.choplab.engine.LoopOverdubRoute {
}
-keep class com.choplab.engine.MixSettings {
}
-keep class com.choplab.engine.MixerSnapshot {
}
-keep enum com.choplab.engine.PcmReadStatus {
}
-keep enum com.choplab.engine.PitchCorrectionPhase {
}
-keep class com.choplab.engine.PitchCorrectionReport {
}
-keep enum com.choplab.engine.PlayMode {
  com.choplab.engine.PlayMode GATE;
}
-keep class com.choplab.engine.Tempo {
  public <init>(int, int);
  public <init>(int, int, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public int getMilliBpm();
  public int getSwingPermille();
}
-keep class com.choplab.jvm.ArchiveCodec {
  public <init>(com.choplab.jvm.ArchiveLimits, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static com.choplab.core.model.Project read$default(com.choplab.jvm.ArchiveCodec, java.io.InputStream, com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function0, int, java.lang.Object);
  public static void write$default(com.choplab.jvm.ArchiveCodec, com.choplab.core.model.Project, com.choplab.jvm.FileAssetStore, java.io.OutputStream, kotlin.jvm.functions.Function0, int, java.lang.Object);
}
-keep class com.choplab.jvm.ArchiveLimits {
}
-keep interface com.choplab.jvm.AudioSink {
  public int bufferFrames();
  public void close();
  public com.choplab.jvm.SinkEncoding getEncoding();
  public long pendingFrames();
  public int timingChannels();
  public long timingEpoch();
  public int timingSampleRate();
  public int underruns();
  public int write(byte[], int, int);
}
-keep class com.choplab.jvm.AutosaveStore {
  public <init>(java.nio.file.Path, com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function0, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public com.choplab.jvm.Recovery recover();
}
-keep class com.choplab.jvm.BeatStretchRenderer {
  public java.lang.Object original(com.choplab.core.model.Project, com.choplab.core.edit.StretchDraft, java.lang.String, kotlin.coroutines.Continuation);
  public java.lang.Object render(com.choplab.core.model.Project, com.choplab.core.edit.StretchDraft, java.lang.String, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep enum com.choplab.jvm.DriverPhase {
  com.choplab.jvm.DriverPhase ATTACHED;
}
-keep class com.choplab.jvm.DriverPlayback {
  public long getSequenceRenderFrames();
}
-keep class com.choplab.jvm.DriverStatus {
  public com.choplab.jvm.DriverPhase getPhase();
}
-keep class com.choplab.jvm.EditorBackend$Companion {
  public static com.choplab.jvm.EditorBackend create$default(com.choplab.jvm.EditorBackend$Companion, java.nio.file.Path, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function2, com.choplab.jvm.OriginalAudioDecoder, int, java.lang.Object);
}
-keep class com.choplab.jvm.EditorBackend {
  public java.lang.Object analyseSource(com.choplab.core.model.Asset, com.choplab.core.model.FrameRange, kotlin.coroutines.Continuation);
  public com.choplab.core.vocal.VocalCoachAnalyzer coachAnalyzer();
  public com.choplab.core.separation.FourStemPort createFourStemWorker(com.choplab.jvm.separation.FourStemSessionFactory, kotlin.jvm.functions.Function0);
  public java.lang.Object createLoopOverdub(long, int, int[], java.util.List, kotlin.coroutines.Continuation);
  public java.lang.Object flushAutosave(kotlin.coroutines.Continuation);
  public com.choplab.jvm.FileAssetStore getAssets();
  public com.choplab.jvm.SourceAuditionController getAudition();
  public com.choplab.core.chop.AutoChopPort getAutoChop();
  public com.choplab.jvm.StreamingEnginePort getEngine();
  public com.choplab.core.Studio getStudio();
  public static java.lang.Object loadPeaks$default(com.choplab.jvm.EditorBackend, com.choplab.core.model.Asset, int, kotlin.coroutines.Continuation, int, java.lang.Object);
  public com.choplab.jvm.VocalPitchRenderer pitchRenderer();
  public com.choplab.core.vocal.VocalPracticeRenderer practiceRenderer();
  public java.lang.Object renderNoteRepeat(com.choplab.core.model.Pad, com.choplab.core.model.Asset, com.choplab.engine.Tempo, int, int, int, java.lang.Integer, kotlin.coroutines.Continuation);
  public java.lang.Object renderPad(com.choplab.core.model.Pad, com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public java.lang.Object renderPerformance(com.choplab.core.model.Pad, com.choplab.core.model.Asset, java.lang.Integer, int, java.lang.Integer, kotlin.coroutines.Continuation);
  public java.lang.Object renderVocalComp(com.choplab.core.model.Project, com.choplab.core.vocal.VocalCompDraft, java.lang.String, kotlin.coroutines.Continuation);
  public static java.lang.Object shutdown$default(com.choplab.jvm.EditorBackend, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
  public com.choplab.jvm.BeatStretchRenderer stretchRenderer();
  com.choplab.jvm.EditorBackend$Companion Companion;
}
-keep class com.choplab.jvm.FileAssetStore {
  public <init>(java.nio.file.Path, long, com.choplab.jvm.OriginalAudioDecoder, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public static void adopt$default(com.choplab.jvm.FileAssetStore, com.choplab.core.model.Asset, java.nio.file.Path, kotlin.jvm.functions.Function0, int, java.lang.Object);
  public java.nio.file.Path getDirectory();
  public java.lang.Object read(com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public long storedBytes();
}
-keep class com.choplab.jvm.FileAssetStoreKt {
  public static java.lang.String sha256(byte[]);
}
-keep class com.choplab.jvm.FileProjectPort {
  public <init>(com.choplab.jvm.FileAssetStore, kotlin.jvm.functions.Function1, com.choplab.jvm.ArchiveCodec, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.FileStemExportPort {
  public <init>(com.choplab.core.ProgramCompiler, kotlin.jvm.functions.Function1);
  public java.lang.Object export(com.choplab.core.model.Project, com.choplab.core.PlaybackTarget, com.choplab.core.StemExportRequest, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
}
-keep class com.choplab.jvm.HostFileServices {
  public <init>(com.choplab.core.ImportPort, com.choplab.core.ProjectPort, com.choplab.core.ExportPort, com.choplab.core.StemExportPort);
  public <init>(com.choplab.core.ImportPort, com.choplab.core.ProjectPort, com.choplab.core.ExportPort, com.choplab.core.StemExportPort, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.LrcTextIO {
  public void write(java.lang.String, kotlin.jvm.functions.Function0);
  com.choplab.jvm.LrcTextIO INSTANCE;
}
-keep interface com.choplab.jvm.MicInput {
  public void close();
  public java.lang.Integer getBufferFrames();
  public int getChannels();
  public long getRouteRevision();
  public int getSampleRate();
  public void onCaptureThread();
  public int read(float[]);
  public void stop();
}
-keep interface com.choplab.jvm.OriginalAudioDecoder {
}
-keep class com.choplab.jvm.OriginalPlayback {
  public boolean getPlaying();
}
-keep class com.choplab.jvm.PcmMemoryBudget$Companion {
  public com.choplab.jvm.PcmMemoryBudget getShared();
}
-keep class com.choplab.jvm.PcmMemoryBudget$Reservation {
}
-keep class com.choplab.jvm.PcmMemoryBudget {
  public long getLimitBytes();
  public java.lang.Object reserve(long, kotlin.coroutines.Continuation);
  public java.lang.Object statistics(kotlin.coroutines.Continuation);
  com.choplab.jvm.PcmMemoryBudget$Companion Companion;
}
-keep class com.choplab.jvm.PcmMemoryStats {
  public int getLeasedAssets();
  public long getLimitBytes();
  public long getPeakBytes();
  public long getUsedBytes();
}
-keep class com.choplab.jvm.PcmPlayback {
  public long getDroppedRequests();
  public com.choplab.engine.PcmReadStatus getStatus();
  public long getUnderrunFrames();
}
-keep class com.choplab.jvm.Recovery {
  public com.choplab.core.model.Project getProject();
}
-keep enum com.choplab.jvm.SinkEncoding {
  com.choplab.jvm.SinkEncoding FLOAT32;
}
-keep class com.choplab.jvm.SourceAuditionController {
  public void cancelPreparation();
  public java.lang.Object clear(kotlin.coroutines.Continuation);
  public java.lang.Object handGain(float, kotlin.coroutines.Continuation);
  public long nativeFrame();
  public double nativeHandFrame();
  public java.lang.Object originalGain(float, kotlin.coroutines.Continuation);
  public java.lang.Object pause(kotlin.coroutines.Continuation);
  public static java.lang.Object play$default(com.choplab.jvm.SourceAuditionController, com.choplab.core.model.Asset, boolean, com.choplab.core.model.FrameRange, kotlin.coroutines.Continuation, int, java.lang.Object);
  public java.lang.Object scratchCut(float, kotlin.coroutines.Continuation);
  public java.lang.Object scratchEnd(kotlin.coroutines.Continuation);
  public java.lang.Object scratchStart(com.choplab.core.model.Asset, long, long, long, kotlin.coroutines.Continuation);
  public java.lang.Object scratchTo(double, int, kotlin.coroutines.Continuation);
  public static java.lang.Object seek$default(com.choplab.jvm.SourceAuditionController, com.choplab.core.model.Asset, long, boolean, com.choplab.core.model.FrameRange, kotlin.coroutines.Continuation, int, java.lang.Object);
  public java.lang.Object songGain(float, kotlin.coroutines.Continuation);
}
-keep class com.choplab.jvm.StreamingEnginePort {
  public <init>(com.choplab.core.ProgramCompiler, kotlin.jvm.functions.Function0, int, long, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public boolean copyMixerReadout(com.choplab.engine.MixerSnapshot);
  public java.lang.Long estimatedOutputNanos(long);
  public kotlinx.coroutines.flow.StateFlow getStatus();
  public com.choplab.core.chop.LiveChopOutput liveChopOutput();
  public com.choplab.core.chop.LiveChopProbe liveChopProbe();
  public com.choplab.jvm.OriginalPlayback originalPlayback();
  public com.choplab.jvm.PcmPlayback pcmPlayback();
  public com.choplab.jvm.DriverPlayback playback();
  public java.util.Set playingPads();
  public com.choplab.core.TransportState snapshot();
}
-keep class com.choplab.jvm.VocalPitchRenderResult$Rendered {
  public com.choplab.core.model.Asset getAsset();
}
-keep interface com.choplab.jvm.VocalPitchRenderResult {
  public com.choplab.engine.PitchCorrectionReport getReport();
}
-keep class com.choplab.jvm.VocalPitchRenderer {
  public java.lang.Object original(com.choplab.core.model.Project, com.choplab.core.vocal.VocalPitchDraft, java.lang.String, kotlin.coroutines.Continuation);
  public java.lang.Object render(com.choplab.core.model.Project, com.choplab.core.vocal.VocalPitchDraft, java.lang.String, kotlin.jvm.functions.Function3, kotlin.coroutines.Continuation);
}
-keep class com.choplab.jvm.VocalPunchCapture {
  public <init>(com.choplab.core.Studio, com.choplab.jvm.StreamingEnginePort, com.choplab.jvm.VoiceTakes, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.jvm.VoiceCaptureWindow {
}
-keep enum com.choplab.jvm.VoiceTakes$Start {
  public static com.choplab.jvm.VoiceTakes$Start[] values();
  com.choplab.jvm.VoiceTakes$Start NO_INPUT;
  com.choplab.jvm.VoiceTakes$Start NO_ROOM;
  com.choplab.jvm.VoiceTakes$Start STARTED;
}
-keep class com.choplab.jvm.VoiceTakes {
  public <init>(com.choplab.jvm.FileAssetStore, java.nio.file.Path, long, kotlin.jvm.functions.Function1, int, kotlin.jvm.functions.Function0, com.choplab.jvm.PcmMemoryBudget, kotlin.jvm.functions.Function1, int, kotlin.jvm.internal.DefaultConstructorMarker);
  public java.lang.Object close(kotlin.coroutines.Continuation);
  public void cue();
  public boolean cueAt(long);
  public java.lang.Object discard(kotlin.coroutines.Continuation);
  public boolean getArmingTimedOut();
  public boolean getFull();
  public boolean getInterrupted();
  public long getRecordedMillis();
  public static java.lang.Object start$default(com.choplab.jvm.VoiceTakes, int, boolean, com.choplab.jvm.VoiceCaptureWindow, int, kotlin.coroutines.Continuation, int, java.lang.Object);
  public java.lang.Object stop(java.lang.String, kotlin.coroutines.Continuation);
}
-keep class com.choplab.jvm.WavAudio {
  public com.choplab.jvm.WavInfo getInfo();
  public float[] getSamples();
}
-keep class com.choplab.jvm.WavCodec {
  public static com.choplab.jvm.WavInfo inspect$default(com.choplab.jvm.WavCodec, java.io.InputStream, long, int, java.lang.Object);
  public static com.choplab.jvm.WavAudio read$default(com.choplab.jvm.WavCodec, java.io.InputStream, long, long, int, java.lang.Object);
  public static void writeFloat$default(com.choplab.jvm.WavCodec, java.io.OutputStream, float[], int, int, int, java.lang.Object);
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
-keep class com.choplab.jvm.ai.SourceVocalPreview {
  public <init>(com.choplab.jvm.EditorBackend, kotlinx.coroutines.CoroutineScope);
  public java.lang.Object close(kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
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
-keep enum com.choplab.ui.ContinuousCapability {
  com.choplab.ui.ContinuousCapability TEMPO;
}
-keep class com.choplab.ui.ContinuousChopGesture {
}
-keep class com.choplab.ui.ContinuousDiagnostics {
}
-keep class com.choplab.ui.ContinuousEditorAction$AutoChop {
  com.choplab.ui.ContinuousEditorAction$AutoChop INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CancelLoopOverdub {
  com.choplab.ui.ContinuousEditorAction$CancelLoopOverdub INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseAutoChop {
  com.choplab.ui.ContinuousEditorAction$CloseAutoChop INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseBeatStretch {
  com.choplab.ui.ContinuousEditorAction$CloseBeatStretch INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseFourStems {
  com.choplab.ui.ContinuousEditorAction$CloseFourStems INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseSourceAnalysis {
  com.choplab.ui.ContinuousEditorAction$CloseSourceAnalysis INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseStepPatterns {
  com.choplab.ui.ContinuousEditorAction$CloseStepPatterns INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseVocalGuide {
  com.choplab.ui.ContinuousEditorAction$CloseVocalGuide INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseVocalPitch {
  com.choplab.ui.ContinuousEditorAction$CloseVocalPitch INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseVocalPractice {
  com.choplab.ui.ContinuousEditorAction$CloseVocalPractice INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseVocalPunch {
  com.choplab.ui.ContinuousEditorAction$CloseVocalPunch INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$CloseVocalTakes {
  com.choplab.ui.ContinuousEditorAction$CloseVocalTakes INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$ExportStems {
  com.choplab.ui.ContinuousEditorAction$ExportStems INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$ExportWav {
  com.choplab.ui.ContinuousEditorAction$ExportWav INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$HoldPad {
  public <init>(int);
}
-keep class com.choplab.ui.ContinuousEditorAction$Lyrics {
  public <init>(com.choplab.ui.LyricAction);
}
-keep class com.choplab.ui.ContinuousEditorAction$Mixer {
  public <init>(com.choplab.ui.mixer.MixerAction);
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenBeatStretch {
  public <init>(com.choplab.core.model.StretchTarget);
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenFourStems {
  com.choplab.ui.ContinuousEditorAction$OpenFourStems INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenSourceAnalysis {
  com.choplab.ui.ContinuousEditorAction$OpenSourceAnalysis INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenStepPatterns {
  com.choplab.ui.ContinuousEditorAction$OpenStepPatterns INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenVocalCoach {
  com.choplab.ui.ContinuousEditorAction$OpenVocalCoach INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenVocalGuide {
  com.choplab.ui.ContinuousEditorAction$OpenVocalGuide INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenVocalPitch {
  com.choplab.ui.ContinuousEditorAction$OpenVocalPitch INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenVocalPractice {
  com.choplab.ui.ContinuousEditorAction$OpenVocalPractice INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenVocalPunch {
  com.choplab.ui.ContinuousEditorAction$OpenVocalPunch INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$OpenVocalTakes {
  com.choplab.ui.ContinuousEditorAction$OpenVocalTakes INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$PlayOriginal {
  com.choplab.ui.ContinuousEditorAction$PlayOriginal INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$RecordLoopOverdub {
  public <init>(int);
}
-keep class com.choplab.ui.ContinuousEditorAction$RecordVoice {
  com.choplab.ui.ContinuousEditorAction$RecordVoice INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$RecordingGuide {
  public <init>(com.choplab.ui.RecordingGuideAction);
}
-keep class com.choplab.ui.ContinuousEditorAction$Redo {
  com.choplab.ui.ContinuousEditorAction$Redo INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$ReleasePad {
  public <init>(int);
}
-keep class com.choplab.ui.ContinuousEditorAction$SaveProject {
  com.choplab.ui.ContinuousEditorAction$SaveProject INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$SeekSong {
  public <init>(long);
}
-keep class com.choplab.ui.ContinuousEditorAction$SetGrid {
  public <init>(com.choplab.ui.ContinuousGrid);
}
-keep class com.choplab.ui.ContinuousEditorAction$SetNoteRepeat {
  public <init>(com.choplab.ui.ContinuousNoteRepeat);
}
-keep class com.choplab.ui.ContinuousEditorAction$StopAll {
  com.choplab.ui.ContinuousEditorAction$StopAll INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$StopHits {
  com.choplab.ui.ContinuousEditorAction$StopHits INSTANCE;
}
-keep class com.choplab.ui.ContinuousEditorAction$Undo {
  com.choplab.ui.ContinuousEditorAction$Undo INSTANCE;
}
-keep interface com.choplab.ui.ContinuousEditorAction {
}
-keep interface com.choplab.ui.ContinuousEditorPorts {
  public java.lang.Object analyseSource(com.choplab.core.model.Asset, com.choplab.core.model.FrameRange, kotlin.coroutines.Continuation);
  public void cancelOriginalPreparation();
  public java.lang.Object chooseAudio(kotlin.coroutines.Continuation);
  public java.lang.Object chooseExport(long, kotlin.coroutines.Continuation);
  public java.lang.Object chooseLibrary(kotlin.coroutines.Continuation);
  public java.lang.Object chooseOnline(kotlin.coroutines.Continuation);
  public java.lang.Object chooseOpen(kotlin.coroutines.Continuation);
  public java.lang.Object chooseSave(kotlin.coroutines.Continuation);
  public java.lang.Object chooseStems(long, kotlin.coroutines.Continuation);
  public java.lang.Object copyText(java.lang.String, kotlin.coroutines.Continuation);
  public java.lang.Object createLoopOverdub(long, int, int[], java.util.List, kotlin.coroutines.Continuation);
  public void cueVoice();
  public com.choplab.ui.ContinuousDiagnostics diagnostics();
  public java.lang.Object discardVoice(kotlin.coroutines.Continuation);
  public java.lang.Object drumKit(java.lang.String, kotlin.coroutines.Continuation);
  public com.choplab.core.chop.AutoChopPort getAutoChop();
  public com.choplab.ui.stretch.BeatStretchHost getBeatStretch();
  public boolean getDrumKitsAvailable();
  public com.choplab.ui.separation.FourStemFactory getFourStems();
  public boolean getLibraryAvailable();
  public boolean getLoopOverdubAvailable();
  public com.choplab.ui.LyricFiles getLyricFiles();
  public com.choplab.ui.ai.LyricProposalPort getLyricProposal();
  public boolean getNoteRepeatAvailable();
  public boolean getOnlineAvailable();
  public com.choplab.ui.source.OnlineSourceHost getOnlineSource();
  public boolean getOriginalAvailable();
  public boolean getPadRenderAvailable();
  public com.choplab.ui.RecordingCuePort getRecordingCue();
  public boolean getSeparationAvailable();
  public boolean getSourceAnalysisAvailable();
  public com.choplab.core.ai.VocalPreviewPort getSourcePreview();
  public boolean getSpotifyMetadataAvailable();
  public boolean getStemsAvailable();
  public boolean getStepPatternsAvailable();
  public com.choplab.ui.SystemAudioCapture getSystemAudioCapture();
  public com.choplab.ui.vocal.VocalCoachHost getVocalCoach();
  public com.choplab.ui.ai.VocalGuidePort getVocalGuide();
  public com.choplab.ui.vocal.VocalPitchHost getVocalPitch();
  public com.choplab.ui.vocal.VocalPracticePort getVocalPractice();
  public com.choplab.core.vocal.VocalPunchPort getVocalPunch();
  public com.choplab.ui.vocal.VocalTakePort getVocalTakes();
  public boolean getVoiceAvailable();
  public com.choplab.core.chop.LiveChopOutput liveChopOutput();
  public com.choplab.core.chop.LiveChopProbe liveChopProbe();
  public java.lang.Object openSpotifyMetadata(kotlin.coroutines.Continuation);
  public java.lang.Boolean originalPlaying();
  public java.lang.Object peaks(com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public java.lang.Object playOriginal(com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public java.util.Set playingPads();
  public boolean readMixer(com.choplab.engine.MixerSnapshot);
  public com.choplab.ui.ContinuousEditorReadout readout();
  public java.lang.Object renderNoteRepeat(com.choplab.core.model.Pad, com.choplab.core.model.Asset, com.choplab.engine.Tempo, int, int, int, java.lang.Integer, kotlin.coroutines.Continuation);
  public java.lang.Object renderPad(com.choplab.core.model.Pad, com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public java.lang.Object renderPerformance(com.choplab.core.model.Pad, com.choplab.core.model.Asset, java.lang.Integer, int, java.lang.Integer, kotlin.coroutines.Continuation);
  public java.lang.Object resetOriginal(kotlin.coroutines.Continuation);
  public java.lang.Object scratchOriginalCut(float, kotlin.coroutines.Continuation);
  public java.lang.Object scratchOriginalEnd(kotlin.coroutines.Continuation);
  public java.lang.Object scratchOriginalStart(com.choplab.core.model.Asset, long, long, long, kotlin.coroutines.Continuation);
  public java.lang.Object scratchOriginalTo(double, int, kotlin.coroutines.Continuation);
  public java.lang.Object seekOriginal(long, kotlin.coroutines.Continuation);
  public java.lang.Object separateSource(com.choplab.core.model.Asset, kotlin.coroutines.Continuation);
  public java.lang.Object setHandMonitorGain(float, kotlin.coroutines.Continuation);
  public java.lang.Object setOriginalMonitorGain(float, kotlin.coroutines.Continuation);
  public java.lang.Object setSongMonitorGain(float, kotlin.coroutines.Continuation);
  public java.lang.Object startVoice(int, kotlin.coroutines.Continuation);
  public java.lang.Object stopOriginal(kotlin.coroutines.Continuation);
  public java.lang.Object stopVoice(java.lang.String, kotlin.coroutines.Continuation);
  public boolean voiceFull();
  public boolean voiceInterrupted();
  public long voiceRecordedMillis();
}
-keep class com.choplab.ui.ContinuousEditorPresenter {
  public <init>(com.choplab.core.Studio, kotlinx.coroutines.CoroutineScope, com.choplab.ui.ContinuousEditorPorts);
  public java.lang.Object close(kotlin.coroutines.Continuation);
  public java.lang.Object dispatch(com.choplab.ui.ContinuousEditorAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getAutoChop();
  public kotlinx.coroutines.flow.StateFlow getBeatStretch();
  public kotlinx.coroutines.flow.StateFlow getFourStems();
  public kotlinx.coroutines.flow.StateFlow getSourceAnalysis();
  public kotlinx.coroutines.flow.StateFlow getState();
  public kotlinx.coroutines.flow.StateFlow getStepPatterns();
  public kotlinx.coroutines.flow.StateFlow getVocalCoach();
  public kotlinx.coroutines.flow.StateFlow getVocalGuide();
  public kotlinx.coroutines.flow.StateFlow getVocalPitch();
  public kotlinx.coroutines.flow.StateFlow getVocalPractice();
  public kotlinx.coroutines.flow.StateFlow getVocalPunch();
  public kotlinx.coroutines.flow.StateFlow getVocalTakes();
  public void onAction(com.choplab.ui.ContinuousEditorAction);
}
-keep class com.choplab.ui.ContinuousEditorReadout {
  public <init>(long, long, float, long, double, int, com.choplab.ui.ContinuousChopGesture, com.choplab.ui.ContinuousPcmReadout, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.ui.ContinuousEditorState {
  public com.choplab.ui.ContinuousStatus getStatus();
  public boolean permits(com.choplab.ui.ContinuousCapability);
}
-keep enum com.choplab.ui.ContinuousGrid {
  com.choplab.ui.ContinuousGrid FREE;
  com.choplab.ui.ContinuousGrid SIXTEENTH_TRIPLET;
}
-keep enum com.choplab.ui.ContinuousNoteRepeat {
  com.choplab.ui.ContinuousNoteRepeat OFF;
  com.choplab.ui.ContinuousNoteRepeat SIXTEENTH_TRIPLET;
}
-keep class com.choplab.ui.ContinuousPcmReadout {
  public <init>(com.choplab.engine.PcmReadStatus, long, long);
}
-keep enum com.choplab.ui.ContinuousStatus {
  com.choplab.ui.ContinuousStatus PLACE_NO_ROOM;
}
-keep class com.choplab.ui.LyricAction$Close {
  com.choplab.ui.LyricAction$Close INSTANCE;
}
-keep class com.choplab.ui.LyricAction$Export {
  public <init>(com.choplab.core.lyrics.LrcFormat, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.ui.LyricAction$Open {
  com.choplab.ui.LyricAction$Open INSTANCE;
}
-keep interface com.choplab.ui.LyricAction {
}
-keep interface com.choplab.ui.LyricFiles {
  public java.lang.Object exportLrc(java.lang.String, kotlin.coroutines.Continuation);
  public java.lang.Object importLrc(kotlin.coroutines.Continuation);
}
-keep interface com.choplab.ui.RecordingCuePort {
  public boolean armingTimedOut();
  public boolean cueVoiceAt(long);
  public java.lang.Object startArmedVoice(int, kotlin.coroutines.Continuation);
}
-keep class com.choplab.ui.RecordingGuideAction$CountInBars {
  public <init>(int);
}
-keep interface com.choplab.ui.RecordingGuideAction {
}
-keep interface com.choplab.ui.SystemAudioCapture {
}
-keep enum com.choplab.ui.VoiceStart {
  com.choplab.ui.VoiceStart NO_ROOM;
  com.choplab.ui.VoiceStart STARTED;
  com.choplab.ui.VoiceStart UNAVAILABLE;
}
-keep interface com.choplab.ui.ai.LyricProposalPort {
}
-keep class com.choplab.ui.ai.VocalGuideController {
  public java.lang.Object apply(kotlin.coroutines.Continuation);
  public java.lang.Object confirmDensity(boolean, kotlin.coroutines.Continuation);
  public com.choplab.core.ai.VocalPreviewPort getPreview();
  public kotlinx.coroutines.flow.StateFlow getState();
  public java.lang.Object listen(java.lang.String, kotlin.coroutines.Continuation);
  public java.lang.Object mode(java.lang.String, com.choplab.core.ai.FlowMode, kotlin.coroutines.Continuation);
  public static java.lang.Object prepare$default(com.choplab.ui.ai.VocalGuideController, java.lang.String, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
}
-keep enum com.choplab.ui.ai.VocalGuidePhase {
  com.choplab.ui.ai.VocalGuidePhase PREPARING;
  com.choplab.ui.ai.VocalGuidePhase READY;
}
-keep interface com.choplab.ui.ai.VocalGuidePort {
  public com.choplab.core.ai.VocalSynthesisPort createSynthesis();
  public com.choplab.core.ai.VocalPreviewPort getPreview();
}
-keep class com.choplab.ui.ai.VocalGuideRow {
  public com.choplab.core.ai.TtsFailure getFailure();
}
-keep class com.choplab.ui.ai.VocalGuideState {
  public com.choplab.core.ai.TtsFailure getFailure();
  public boolean getLoadingVoices();
  public com.choplab.ui.ai.VocalGuidePhase getPhase();
  public com.choplab.core.ai.FlowPlan getPlan();
  public java.util.List getRows();
}
-keep class com.choplab.ui.analysis.SourceAnalysisAction$Analyse {
  com.choplab.ui.analysis.SourceAnalysisAction$Analyse INSTANCE;
}
-keep class com.choplab.ui.analysis.SourceAnalysisAction$Apply {
  com.choplab.ui.analysis.SourceAnalysisAction$Apply INSTANCE;
}
-keep class com.choplab.ui.analysis.SourceAnalysisAction$SelectTempo {
  public <init>(int);
}
-keep interface com.choplab.ui.analysis.SourceAnalysisAction {
}
-keep class com.choplab.ui.analysis.SourceAnalysisController {
  public java.lang.Object dispatch(com.choplab.ui.analysis.SourceAnalysisAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep class com.choplab.ui.analysis.SourceAnalysisState {
  public boolean getEditable();
  public com.choplab.core.analysis.SourceMusicResult getResult();
  public java.lang.Integer getSelectedMilliBpm();
}
-keep class com.choplab.ui.chop.AutoChopAction$Apply {
  com.choplab.ui.chop.AutoChopAction$Apply INSTANCE;
}
-keep class com.choplab.ui.chop.AutoChopAction$Prepare {
  com.choplab.ui.chop.AutoChopAction$Prepare INSTANCE;
}
-keep class com.choplab.ui.chop.AutoChopAction$Preview {
  com.choplab.ui.chop.AutoChopAction$Preview INSTANCE;
}
-keep class com.choplab.ui.chop.AutoChopAction$Settings {
  public <init>(com.choplab.core.chop.AutoChopSettings);
}
-keep class com.choplab.ui.chop.AutoChopAction$StopPreview {
  com.choplab.ui.chop.AutoChopAction$StopPreview INSTANCE;
}
-keep interface com.choplab.ui.chop.AutoChopAction {
}
-keep class com.choplab.ui.chop.AutoChopController {
  public java.lang.Object dispatch(com.choplab.ui.chop.AutoChopAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep class com.choplab.ui.chop.AutoChopState {
  public boolean getCanApply();
  public com.choplab.core.chop.AutoChopProblem getProblem();
  public boolean getWorking();
}
-keep class com.choplab.ui.mixer.MixerAction$Apply {
  com.choplab.ui.mixer.MixerAction$Apply INSTANCE;
}
-keep class com.choplab.ui.mixer.MixerAction$Change {
  public <init>(com.choplab.ui.mixer.MixerField, java.lang.String);
}
-keep class com.choplab.ui.mixer.MixerAction$Open {
  public <init>(com.choplab.ui.mixer.MixerTarget);
}
-keep class com.choplab.ui.mixer.MixerAction$Switch {
  public <init>(com.choplab.ui.mixer.MixerSwitch, boolean);
}
-keep interface com.choplab.ui.mixer.MixerAction {
}
-keep enum com.choplab.ui.mixer.MixerField {
  com.choplab.ui.mixer.MixerField DELAY_SEND;
  com.choplab.ui.mixer.MixerField DELAY_TIME;
  com.choplab.ui.mixer.MixerField FEEDBACK;
  com.choplab.ui.mixer.MixerField GAIN;
  com.choplab.ui.mixer.MixerField LOW_DB;
  com.choplab.ui.mixer.MixerField PAN;
  com.choplab.ui.mixer.MixerField REVERB_SEND;
}
-keep enum com.choplab.ui.mixer.MixerSwitch {
  com.choplab.ui.mixer.MixerSwitch DELAY;
  com.choplab.ui.mixer.MixerSwitch REVERB;
}
-keep class com.choplab.ui.mixer.MixerTarget$Bank {
  public <init>(int);
}
-keep class com.choplab.ui.mixer.MixerTarget$Master {
  com.choplab.ui.mixer.MixerTarget$Master INSTANCE;
}
-keep interface com.choplab.ui.mixer.MixerTarget {
}
-keep class com.choplab.ui.pattern.PatternAction$Grid {
  public <init>(int);
}
-keep class com.choplab.ui.pattern.PatternAction$Place {
  public <init>(java.lang.String);
}
-keep class com.choplab.ui.pattern.PatternAction$Queue {
  com.choplab.ui.pattern.PatternAction$Queue INSTANCE;
}
-keep class com.choplab.ui.pattern.PatternAction$Repeats {
  public <init>(int);
}
-keep class com.choplab.ui.pattern.PatternAction$Resize {
  public <init>(int, boolean, int, kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep class com.choplab.ui.pattern.PatternAction$Save {
  com.choplab.ui.pattern.PatternAction$Save INSTANCE;
}
-keep class com.choplab.ui.pattern.PatternAction$SelectPad {
  public <init>(int);
}
-keep class com.choplab.ui.pattern.PatternAction$Toggle {
  public <init>(int, int);
}
-keep class com.choplab.ui.pattern.PatternAction$Velocity {
  public <init>(float);
}
-keep interface com.choplab.ui.pattern.PatternAction {
}
-keep class com.choplab.ui.pattern.StepPatternController {
  public java.lang.Object dispatch(com.choplab.ui.pattern.PatternAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep class com.choplab.ui.pattern.StepPatternState {
  public boolean getEditable();
  public int getSelectedPadId();
}
-keep class com.choplab.ui.separation.FourStemController {
  public java.lang.Object apply(kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
  public static java.lang.Object settings$default(com.choplab.ui.separation.FourStemController, com.choplab.core.separation.StemMix, int, boolean, kotlin.coroutines.Continuation, int, java.lang.Object);
  public java.lang.Object start(kotlin.coroutines.Continuation);
}
-keep interface com.choplab.ui.separation.FourStemFactory {
  public com.choplab.core.separation.FourStemPort create();
}
-keep enum com.choplab.ui.separation.FourStemPhase {
  com.choplab.ui.separation.FourStemPhase FAILED;
  com.choplab.ui.separation.FourStemPhase READY;
}
-keep class com.choplab.ui.separation.FourStemState {
  public boolean getEditable();
  public com.choplab.ui.separation.FourStemPhase getPhase();
  public com.choplab.core.separation.SeparationProblem getProblem();
}
-keep interface com.choplab.ui.source.OnlineSourceHost {
}
-keep class com.choplab.ui.stretch.BeatStretchController {
  public java.lang.Object dispatch(com.choplab.ui.stretch.StretchAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep interface com.choplab.ui.stretch.BeatStretchHost {
  public com.choplab.core.ai.VocalPreviewPort getPreview();
  public java.lang.Object original(com.choplab.core.model.Project, com.choplab.core.edit.StretchDraft, kotlin.coroutines.Continuation);
  public java.lang.Object render(com.choplab.core.model.Project, com.choplab.core.edit.StretchDraft, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep class com.choplab.ui.stretch.StretchAction$Apply {
  com.choplab.ui.stretch.StretchAction$Apply INSTANCE;
}
-keep class com.choplab.ui.stretch.StretchAction$Bpm {
  public <init>(java.lang.String);
}
-keep class com.choplab.ui.stretch.StretchAction$Original {
  com.choplab.ui.stretch.StretchAction$Original INSTANCE;
}
-keep class com.choplab.ui.stretch.StretchAction$Prepare {
  com.choplab.ui.stretch.StretchAction$Prepare INSTANCE;
}
-keep class com.choplab.ui.stretch.StretchAction$Stretched {
  com.choplab.ui.stretch.StretchAction$Stretched INSTANCE;
}
-keep interface com.choplab.ui.stretch.StretchAction {
}
-keep class com.choplab.ui.stretch.StretchState {
  public boolean getEditable();
  public boolean getPrepared();
}
-keep class com.choplab.ui.vocal.CoachAction$Analyze {
  com.choplab.ui.vocal.CoachAction$Analyze INSTANCE;
}
-keep class com.choplab.ui.vocal.CoachAction$Input {
  public <init>(com.choplab.core.vocal.CoachVoiceInput);
}
-keep class com.choplab.ui.vocal.CoachAction$ListenGuide {
  com.choplab.ui.vocal.CoachAction$ListenGuide INSTANCE;
}
-keep class com.choplab.ui.vocal.CoachAction$Practice {
  com.choplab.ui.vocal.CoachAction$Practice INSTANCE;
}
-keep class com.choplab.ui.vocal.CoachAction$Range {
  public <init>(java.lang.String, java.lang.String);
}
-keep class com.choplab.ui.vocal.CoachAction$Reference {
  public <init>(java.lang.String);
}
-keep class com.choplab.ui.vocal.CoachAction$Take {
  public <init>(java.lang.String);
}
-keep interface com.choplab.ui.vocal.CoachAction {
}
-keep enum com.choplab.ui.vocal.CoachPhase {
  com.choplab.ui.vocal.CoachPhase READY_RESPONSE;
}
-keep class com.choplab.ui.vocal.PitchAction$Apply {
  com.choplab.ui.vocal.PitchAction$Apply INSTANCE;
}
-keep class com.choplab.ui.vocal.PitchAction$Prepare {
  com.choplab.ui.vocal.PitchAction$Prepare INSTANCE;
}
-keep class com.choplab.ui.vocal.PitchAction$PreviewCorrected {
  com.choplab.ui.vocal.PitchAction$PreviewCorrected INSTANCE;
}
-keep class com.choplab.ui.vocal.PitchAction$PreviewOriginal {
  com.choplab.ui.vocal.PitchAction$PreviewOriginal INSTANCE;
}
-keep interface com.choplab.ui.vocal.PitchAction {
}
-keep class com.choplab.ui.vocal.PitchEditorState {
  public boolean getEditable();
  public boolean getPrepared();
  public com.choplab.engine.PitchCorrectionReport getReport();
}
-keep enum com.choplab.ui.vocal.PracticePhase {
  com.choplab.ui.vocal.PracticePhase PLAYING;
}
-keep class com.choplab.ui.vocal.PreparedVocalPitch {
  public <init>(com.choplab.core.model.Asset, com.choplab.engine.PitchCorrectionReport);
}
-keep class com.choplab.ui.vocal.VocalAction$Apply {
  public <init>(java.lang.String);
}
-keep class com.choplab.ui.vocal.VocalAction$Choose {
  public <init>(java.lang.String, java.lang.String);
}
-keep class com.choplab.ui.vocal.VocalAction$FromLyrics {
  com.choplab.ui.vocal.VocalAction$FromLyrics INSTANCE;
}
-keep class com.choplab.ui.vocal.VocalAction$PreviewComp {
  public <init>(java.lang.String);
}
-keep class com.choplab.ui.vocal.VocalAction$SelectTake {
  public <init>(java.lang.String);
}
-keep class com.choplab.ui.vocal.VocalAction$StopPreview {
  com.choplab.ui.vocal.VocalAction$StopPreview INSTANCE;
}
-keep interface com.choplab.ui.vocal.VocalAction {
}
-keep class com.choplab.ui.vocal.VocalCoachController {
  public java.lang.Object dispatch(com.choplab.ui.vocal.CoachAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep interface com.choplab.ui.vocal.VocalCoachHost {
  public com.choplab.core.vocal.VocalCoachAnalyzer getAnalyzer();
  public com.choplab.core.ai.VocalPreviewPort getPreview();
  public com.choplab.core.vocal.VocalPracticeRenderer getRenderer();
}
-keep class com.choplab.ui.vocal.VocalCoachState {
  public com.choplab.ui.vocal.CoachPhase getPhase();
  public com.choplab.core.vocal.CoachProblem getProblem();
  public com.choplab.core.vocal.VocalCoachReport getReport();
}
-keep class com.choplab.ui.vocal.VocalPitchController {
  public java.lang.Object dispatch(com.choplab.ui.vocal.PitchAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep interface com.choplab.ui.vocal.VocalPitchHost {
  public com.choplab.core.ai.VocalPreviewPort getPreview();
  public java.lang.Object original(com.choplab.core.model.Project, com.choplab.core.vocal.VocalPitchDraft, kotlin.coroutines.Continuation);
  public java.lang.Object render(com.choplab.core.model.Project, com.choplab.core.vocal.VocalPitchDraft, kotlin.jvm.functions.Function3, kotlin.coroutines.Continuation);
}
-keep class com.choplab.ui.vocal.VocalPracticeController {
  public kotlinx.coroutines.flow.StateFlow getState();
  public java.lang.Object preview(kotlin.coroutines.Continuation);
  public java.lang.Object stopAndJoin(kotlin.coroutines.Continuation);
  public void update(kotlin.jvm.functions.Function1);
}
-keep interface com.choplab.ui.vocal.VocalPracticePort {
  public com.choplab.core.ai.VocalPreviewPort getPreview();
  public com.choplab.core.vocal.VocalPracticeRenderer getRenderer();
}
-keep class com.choplab.ui.vocal.VocalPracticeState {
  public static com.choplab.ui.vocal.VocalPracticeState copy$default(com.choplab.ui.vocal.VocalPracticeState, java.lang.String, java.lang.String, double, boolean, com.choplab.ui.vocal.PracticePhase, com.choplab.core.vocal.PracticeProgress, com.choplab.core.vocal.PracticeProblem, boolean, int, java.lang.Object);
  public com.choplab.ui.vocal.PracticePhase getPhase();
}
-keep class com.choplab.ui.vocal.VocalPunchController {
  public kotlinx.coroutines.flow.StateFlow getState();
  public java.lang.Object record(kotlin.coroutines.Continuation);
  public void update(kotlin.jvm.functions.Function1);
}
-keep class com.choplab.ui.vocal.VocalPunchState {
  public static com.choplab.ui.vocal.VocalPunchState copy$default(com.choplab.ui.vocal.VocalPunchState, java.lang.String, java.lang.String, int, int, int, java.lang.String, boolean, boolean, boolean, boolean, int, com.choplab.core.vocal.PunchProblem, int, java.lang.Object);
}
-keep class com.choplab.ui.vocal.VocalTakeController {
  public java.lang.Object dispatch(com.choplab.ui.vocal.VocalAction, kotlin.coroutines.Continuation);
  public kotlinx.coroutines.flow.StateFlow getState();
}
-keep interface com.choplab.ui.vocal.VocalTakePort {
  public com.choplab.core.ai.VocalPreviewPort getPreview();
  public java.lang.Object render(com.choplab.core.model.Project, com.choplab.core.vocal.VocalCompDraft, java.lang.String, kotlin.coroutines.Continuation);
}
-keep class com.choplab.ui.vocal.VocalTakeState {
  public com.choplab.core.vocal.VocalCompDraft getDraft();
  public boolean getEditable();
  public com.choplab.core.vocal.VocalProblem getProblem();
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
-keep class kotlin.ExceptionsKt {
}
-keep class kotlin.ExceptionsKt__ExceptionsKt {
  public static void addSuppressed(java.lang.Throwable, java.lang.Throwable);
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
  public java.lang.Object getFirst();
  public java.lang.Object getSecond();
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
  public static kotlin.ranges.IntRange getIndices(double[]);
  public static kotlin.ranges.IntRange getIndices(float[]);
  public static java.lang.String joinToString$default(byte[], java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.lang.String joinToString$default(java.lang.Object[], java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.util.List take(byte[], int);
  public static java.util.List take(java.lang.Object[], int);
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
-keep,allowaccessmodification class kotlin.collections.CollectionsKt__IterablesKt {
  public static int collectionSizeOrDefault(java.lang.Iterable, int);
  public static java.util.List flatten(java.lang.Iterable);
}
-keep class kotlin.collections.CollectionsKt__IteratorsJVMKt {
  public static java.util.Iterator iterator(java.util.Enumeration);
}
-keep class kotlin.collections.CollectionsKt__MutableCollectionsKt {
  public static boolean addAll(java.util.Collection, java.lang.Iterable);
}
-keep class kotlin.collections.CollectionsKt___CollectionsKt {
  public static java.util.List distinct(java.lang.Iterable);
  public static java.util.List drop(java.lang.Iterable, int);
  public static java.lang.Object first(java.util.List);
  public static java.lang.Object firstOrNull(java.util.List);
  public static java.lang.String joinToString$default(java.lang.Iterable, java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
  public static java.lang.Object last(java.util.List);
  public static java.util.List minus(java.lang.Iterable, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Iterable);
  public static java.util.List plus(java.util.Collection, java.lang.Object);
  public static java.util.List plus(java.util.Collection, java.lang.Object[]);
  public static java.lang.Object single(java.util.List);
  public static java.util.List sorted(java.lang.Iterable);
  public static java.util.List sortedWith(java.lang.Iterable, java.util.Comparator);
  public static int[] toIntArray(java.util.Collection);
  public static java.util.List toList(java.lang.Iterable);
  public static java.util.Set toSet(java.lang.Iterable);
  public static java.util.List zip(java.lang.Iterable, java.lang.Iterable);
}
-keep class kotlin.collections.IntIterator {
  public int nextInt();
}
-keep class kotlin.collections.MapsKt {
}
-keep class kotlin.collections.MapsKt__MapsJVMKt {
  public static int mapCapacity(int);
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
  public kotlin.coroutines.CoroutineContext plus(kotlin.coroutines.CoroutineContext);
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
  public kotlin.coroutines.Continuation create(kotlin.coroutines.Continuation);
  protected java.lang.Object invokeSuspend(java.lang.Object);
}
-keep interface kotlin.enums.EnumEntries {
}
-keep class kotlin.io.ByteStreamsKt {
  public static long copyTo$default(java.io.InputStream, java.io.OutputStream, int, int, java.lang.Object);
  public static byte[] readBytes(java.io.InputStream);
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
-keep class kotlin.ranges.IntProgression {
}
-keep class kotlin.ranges.IntRange {
}
-keep class kotlin.ranges.RangesKt {
}
-keep class kotlin.ranges.RangesKt___RangesKt {
  public static int coerceAtLeast(int, int);
  public static long coerceAtLeast(long, long);
  public static int coerceIn(int, int, int);
  public static kotlin.ranges.IntProgression step(kotlin.ranges.IntProgression, int);
  public static kotlin.ranges.IntRange until(int, int);
}
-keep interface kotlin.sequences.Sequence {
  public java.util.Iterator iterator();
}
-keep class kotlin.sequences.SequencesKt {
}
-keep class kotlin.sequences.SequencesKt__SequencesKt {
  public static kotlin.sequences.Sequence asSequence(java.util.Iterator);
}
-keep class kotlin.sequences.SequencesKt___SequencesKt {
  public static kotlin.sequences.Sequence filter(kotlin.sequences.Sequence, kotlin.jvm.functions.Function1);
  public static java.util.List toList(kotlin.sequences.Sequence);
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
  public static java.lang.String decodeToString(byte[]);
  public static boolean endsWith$default(java.lang.String, java.lang.String, boolean, int, java.lang.Object);
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
-keep interface kotlinx.coroutines.CompletableJob {
}
-keep class kotlinx.coroutines.CoroutineDispatcher {
}
-keep interface kotlinx.coroutines.CoroutineScope {
  public kotlin.coroutines.CoroutineContext getCoroutineContext();
}
-keep class kotlinx.coroutines.CoroutineScopeKt {
  public static kotlinx.coroutines.CoroutineScope CoroutineScope(kotlin.coroutines.CoroutineContext);
  public static void cancel$default(kotlinx.coroutines.CoroutineScope, java.util.concurrent.CancellationException, int, java.lang.Object);
  public static java.lang.Object coroutineScope(kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
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
  public static kotlinx.coroutines.CoroutineDispatcher getDefault();
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
  public static void cancel$default(kotlinx.coroutines.Job, java.util.concurrent.CancellationException, int, java.lang.Object);
}
-keep class kotlinx.coroutines.JobKt {
  public static void cancel$default(kotlin.coroutines.CoroutineContext, java.util.concurrent.CancellationException, int, java.lang.Object);
}
-keep class kotlinx.coroutines.MainCoroutineDispatcher {
  public kotlinx.coroutines.MainCoroutineDispatcher getImmediate();
}
-keep class kotlinx.coroutines.NonCancellable {
  kotlinx.coroutines.NonCancellable INSTANCE;
}
-keep class kotlinx.coroutines.SupervisorKt {
  public static kotlinx.coroutines.CompletableJob SupervisorJob$default(kotlinx.coroutines.Job, int, java.lang.Object);
  public static java.lang.Object supervisorScope(kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.TimeoutCancellationException {
}
-keep class kotlinx.coroutines.TimeoutKt {
  public static java.lang.Object withTimeout(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
  public static java.lang.Object withTimeout-KLykuaI(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.YieldKt {
  public static java.lang.Object yield(kotlin.coroutines.Continuation);
}
-keep interface kotlinx.coroutines.flow.Flow {
}
-keep class kotlinx.coroutines.flow.FlowKt {
  public static java.lang.Object first(kotlinx.coroutines.flow.Flow, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}
-keep interface kotlinx.coroutines.flow.SharedFlow {
}
-keep interface kotlinx.coroutines.flow.StateFlow {
  public java.lang.Object getValue();
}
-keeppackagenames androidx.concurrent.futures
