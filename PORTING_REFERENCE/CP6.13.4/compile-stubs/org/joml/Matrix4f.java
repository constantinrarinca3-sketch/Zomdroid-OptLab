package org.joml;
public class Matrix4f implements Matrix4fc {
  private int token;
  public Matrix4f(){ identity(); }
  public Matrix4f identity(){ token=0; return this; }
  public Matrix4f set(Matrix4fc o){ token = o instanceof Matrix4f ? ((Matrix4f)o).token : 0; return this; }
  public Matrix4f mul(Matrix4fc o){ token = token * 31 + (o instanceof Matrix4f ? ((Matrix4f)o).token : 0); return this; }
  @Override public boolean equals(Object o){ return o instanceof Matrix4f && ((Matrix4f)o).token==token; }
  public Matrix4f setToken(int v){ token=v; return this; }
  public int token(){ return token; }
}
