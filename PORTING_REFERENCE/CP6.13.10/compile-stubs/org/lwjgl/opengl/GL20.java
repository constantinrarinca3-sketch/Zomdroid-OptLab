package org.lwjgl.opengl;
public final class GL20 {
  public static int calls; public static int lastLoc; public static float lastValue; public static boolean throwNext;
  public static void glUniform1f(int loc, float value) {
    if (throwNext) { throwNext=false; throw new RuntimeException("synthetic GL failure"); }
    calls++; lastLoc=loc; lastValue=value;
  }
  public static void glVertexAttribPointer(int i,int size,int type,boolean normalized,int stride,long ptr){}
}
