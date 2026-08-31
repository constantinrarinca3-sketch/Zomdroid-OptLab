package zombie.core.textures;
public class Texture {
  public static int lastTextureID;
  public static int bindCount;
  public boolean bindAlways;
  private boolean destroyed;
  private boolean valid=true;
  private boolean ready=true;
  private int id;
  public int bindCalls;
  public Texture() {}
  public Texture(int id) { this.id=id; }
  public boolean isDestroyed(){return destroyed;}
  public boolean isValid(){return valid;}
  public boolean isReady(){return ready;}
  public int getID(){return id;}
  public void setID(int v){id=v;}
  public void setDestroyed(boolean v){destroyed=v;}
  public void setValid(boolean v){valid=v;}
  public void setReady(boolean v){ready=v;}
  public void bind(){ bind(3553); }
  public void bind(int target){ bindCalls++; lastTextureID=id; bindCount++; }
}
