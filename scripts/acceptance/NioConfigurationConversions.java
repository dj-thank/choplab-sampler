// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license, reproduced in licenses/r8-verification-BSD.txt.
// Adapted from the R8 project authors' BSD-licensed GenerateCustomConversion and
// CustomConversionAsmRewriteDescription at c331a820ddae7dea41f41a04375b784486d0f705.
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
public class NioConfigurationConversions {
  static final Map<String,String> OWNERS = new HashMap<>();
  static {
    for (String type : new String[]{"nio/file/attribute/PosixFilePermission","util/stream/Collector$Characteristics"}) {
      OWNERS.put("java/"+type,"java/"+type+"$EnumConversion");
      OWNERS.put("j$/"+type,"java/"+type+"$EnumConversion");
    }
    for (String type : new String[]{"util/stream/DoubleStream","util/stream/IntStream","util/stream/LongStream","util/stream/Stream","nio/file/spi/FileSystemProvider","nio/file/spi/FileTypeDetector","nio/file/Path","nio/file/WatchEvent","nio/file/WatchEvent$Kind","nio/file/OpenOption","nio/file/attribute/FileAttribute"}) {
      OWNERS.put("java/"+type,"java/"+type+"$Wrapper");
      OWNERS.put("j$/"+type,"java/"+type+"$VivifiedWrapper");
    }
  }
  public static void main(String[] args) throws Exception {
    Path input=Path.of(args[0]),output=Path.of(args[1]);
    try (var files=Files.walk(input.resolve("java"))) {
      for(Path file:files.filter(p->p.toString().endsWith(".class")).toList()) {
        ClassReader reader=new ClassReader(Files.readAllBytes(file));
        ClassWriter writer=new ClassWriter(reader,0);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
          public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,name,descriptor,signature,exceptions)) {
              public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean isInterface) {
                if(opcode==Opcodes.INVOKESTATIC && name.equals("wrap_convert")) {
                  String first=descriptor.substring(2,descriptor.indexOf(';'));
                  String rewritten=OWNERS.get(first);
                  if(rewritten==null) throw new IllegalArgumentException("Unsupported conversion: "+first);
                  super.visitMethodInsn(opcode,rewritten,"convert",descriptor,isInterface);
                } else super.visitMethodInsn(opcode,owner,name,descriptor,isInterface);
              }
            };
          }
        },0);
        Path target=output.resolve(input.relativize(file));Files.createDirectories(target.getParent());Files.write(target,writer.toByteArray());
      }
    }
  }
}
