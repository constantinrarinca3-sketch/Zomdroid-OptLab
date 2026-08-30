package zombie.iso;
import zombie.core.textures.ColorInfo;
import zombie.util.list.PZArrayList;
public class IsoGridSquare {
  public interface ILighting {}
  public final ILighting[] lighting=new ILighting[4];
  public PZArrayList<IsoObject> getObjects(){return null;}
  public boolean isOverlayDone(){return false;}
  public void setOverlayDone(boolean b){}
  public int getX(){return 0;} public int getY(){return 0;} public int getZ(){return 0;}
  public ColorInfo getLightInfo(int p){return null;}
  public void cacheLightInfo(){}
}
