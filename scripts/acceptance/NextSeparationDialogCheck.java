// Synthetic native Mac acceptance. Natural JVM exit verifies native UI timer cleanup.
import com.choplab.desktop.next.*;
import com.choplab.jvm.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import kotlin.Unit;
import kotlin.coroutines.*;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.ResultKt;
import java.util.function.Function;
public class NextSeparationDialogCheck {
  static <T>T call(Function<Continuation<T>,Object> fn) throws Exception {
    CompletableFuture<T> answer=new CompletableFuture<>();
    Continuation<T> c=new Continuation<>() {
      public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
      public void resumeWith(Object r){try{ResultKt.throwOnFailure(r);answer.complete((T)r);}catch(Throwable e){answer.completeExceptionally(e);}}
    };
    Object r=fn.apply(c);if(r!=IntrinsicsKt.getCOROUTINE_SUSPENDED())answer.complete((T)r);
    return answer.get(60,TimeUnit.SECONDS);
  }
 static Component find(Container parent,String name){for(Component c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container){Component v=find((Container)c,name);if(v!=null)return v;}}return null;}
 static JDialog dialog(){for(Window w:Window.getWindows())if(w instanceof JDialog&&w.isVisible())return (JDialog)w;return null;}
 static int renders=0;
 static NextSeparation job(Path root,String title){
  return new NextSeparation(root,f->Unit.INSTANCE,cancelled->new WavAudio(new WavInfo(44100,2,2,32,true),new float[]{.000001f,-.000002f,1.25f,-1.5f}),
   (audio,path,progress,cancelled)->{try{renders++;try(var out=Files.newOutputStream(path)){WavCodec.INSTANCE.writeFloat(out,audio.getSamples(),44100,2);}return Unit.INSTANCE;}catch(Exception e){throw new RuntimeException(e);}},title);
 }
 static void waitDialog()throws Exception{long end=System.nanoTime()+10_000_000_000L;while(dialog()==null){if(System.nanoTime()>end)throw new Exception("Dialog timeout");Thread.sleep(20);}}
 public static void main(String[]args)throws Exception{
  Locale.setDefault(Locale.JAPAN);Path root=Path.of(args[0]);Frame frame=new Frame("ChopLab isolated separation");
  EventQueue.invokeAndWait(()->{frame.setSize(420,100);frame.setVisible(true);});ExecutorService worker=Executors.newSingleThreadExecutor();
  try{
   Future<NextLibrary.Selection> answer=worker.submit(()->NextSeparationDialogCheck.<NextLibrary.Selection>call(c->NextSeparationDialog.INSTANCE.choose(frame,title->job(root.resolve("library"),title),c)));
   waitDialog();EventQueue.invokeAndWait(()->{if(renders!=0||((JButton)find(dialog(),"next-separation-use")).isEnabled())throw new AssertionError("Implicit start/use");((JButton)find(dialog(),"next-separation-start")).doClick();});
   long end=System.nanoTime()+15_000_000_000L;boolean ready=false;
   while(!ready){boolean[] value={false};EventQueue.invokeAndWait(()->value[0]=((JButton)find(dialog(),"next-separation-use")).isEnabled());ready=value[0];if(System.nanoTime()>end)throw new Exception("Result timeout");Thread.sleep(20);}
   if(answer.isDone())throw new AssertionError("Implicit selection");
   EventQueue.invokeAndWait(()->{JDialog d=dialog();JButton use=(JButton)find(d,"next-separation-use");if(!use.getText().equals("分離音を原曲として使う"))throw new AssertionError("Localization");BufferedImage img=new BufferedImage(d.getWidth(),d.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();d.paint(g);g.dispose();try{javax.imageio.ImageIO.write(img,"png",Path.of(args[1]).toFile());}catch(Exception e){throw new RuntimeException(e);}use.doClick();});
   NextLibrary.Selection result=answer.get(5,TimeUnit.SECONDS);if(result==null||!result.getTitle().equals("ドラム"))throw new AssertionError("Result title");
   try(var in=Files.newInputStream(result.getPath())){if(!WavCodec.INSTANCE.read(in,33554432,33554432).getInfo().getFloatingPoint())throw new AssertionError("Precision");}
   Future<NextLibrary.Selection> closed=worker.submit(()->NextSeparationDialogCheck.<NextLibrary.Selection>call(c->NextSeparationDialog.INSTANCE.choose(frame,title->job(root.resolve("library"),title),c)));
   waitDialog();EventQueue.invokeAndWait(()->{
    // Paint the idle Aqua progress bar before close, so its native animator has started.
    JDialog d=dialog();BufferedImage image=new BufferedImage(d.getWidth(),d.getHeight(),BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics=image.createGraphics();try{d.paint(graphics);}finally{graphics.dispose();}
    ((JButton)find(d,"next-separation-close")).doClick();
   });if(closed.get(5,TimeUnit.SECONDS)!=null||renders!=1)throw new AssertionError("Close mutated");
   System.out.println("NATIVE_SEPARATION_DIALOG_PASS localized=true explicitStart=true explicitUse=true floatResult=true closeDoesNotSelect=true syntheticInference=true");
  }finally{worker.shutdownNow();EventQueue.invokeAndWait(()->{for(Window w:Window.getWindows())w.dispose();});}
 }
}
