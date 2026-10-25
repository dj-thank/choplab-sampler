// Copyright (c) 2021, the R8 project authors.
// Derived from R8's DesugaredLibraryJDK11Undesugarer.
// BSD license: licenses/r8-verification-BSD.txt; standalone ASM adapter by ChopLab contributors.
// Upstream: r8.googlesource.com/r8/+/077a41997c296ed70aea6ac09ae24d8330421055
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Private experiment following R8's published JDK11 undesugaring and builder exclusions. */
public class NioUndesugar {
  static final Map<String,String> OWNERS=Map.ofEntries(
    Map.entry("java/util/DesugarTimeZone","java/util/TimeZone"),
    Map.entry("java/lang/DesugarLong","java/lang/Long"),
    Map.entry("java/lang/DesugarInteger","java/lang/Integer"),
    Map.entry("java/lang/DesugarDouble","java/lang/Double"),
    Map.entry("java/util/DesugarArrays","java/util/Arrays"),
    Map.entry("java/lang/DesugarMath","java/lang/Math"),
    Map.entry("java/io/DesugarBufferedReader","java/io/BufferedReader"),
    Map.entry("java/io/DesugarInputStream","java/io/InputStream"),
    Map.entry("sun/misc/DesugarUnsafe","jdk/internal/misc/Unsafe"),
    Map.entry("wrapper/adapter/HybridFileSystemProvider","sun/nio/fs/DefaultFileSystemProvider"),
    Map.entry("wrapper/adapter/HybridFileTypeDetector","sun/nio/fs/DefaultFileTypeDetector"));
  public static void main(String[] args) throws Exception {
    try(var in=new ZipFile(args[0]); var out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1]),StandardOpenOption.CREATE_NEW))) {
      var entries=in.entries();
      while(entries.hasMoreElements()) {
        var e=entries.nextElement();var name=e.getName();
        if(!name.endsWith(".class") || name.equals("sun/nio/fs/DefaultFileSystemProvider.class") ||
          name.equals("sun/nio/fs/DefaultFileTypeDetector.class")) continue;
        var reader=new ClassReader(in.getInputStream(e).readAllBytes());var writer=new ClassWriter(reader,0);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
          public MethodVisitor visitMethod(int access,String n,String d,String sig,String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,n,d,sig,exceptions)) {
              public void visitMethodInsn(int op,String owner,String n,String d,boolean itf) {
                if(op==Opcodes.INVOKESTATIC && OWNERS.containsKey(owner)) {
                  String target=OWNERS.get(owner);int end=d.indexOf(';'),start=d.indexOf('L');
                  if(end>=0 && d.substring(start+1,end).equals(target)) {op=Opcodes.INVOKEVIRTUAL;d="("+d.substring(end+1);}
                  owner=target;
                }
                super.visitMethodInsn(op,owner,n,d,itf);
              }
            };
          }
        },0);
        var bytes=writer.toByteArray();var z=new ZipEntry(name);z.setTime(0);z.setMethod(ZipEntry.STORED);z.setSize(bytes.length);z.setCompressedSize(bytes.length);
        var crc=new CRC32();crc.update(bytes);z.setCrc(crc.getValue());out.putNextEntry(z);out.write(bytes);out.closeEntry();
      }
    }
  }
}
