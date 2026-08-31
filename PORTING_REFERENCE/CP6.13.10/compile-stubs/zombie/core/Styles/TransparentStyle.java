package zombie.core.Styles;
public final class TransparentStyle implements Style { public static final TransparentStyle instance=new TransparentStyle(); public int getStyleID(){return 1;} public AlphaOp getAlphaOp(){return new AlphaOp();} public void setupState(){} public void resetState(){} public boolean getRenderSprite(){return true;} public void render(int a,int b){} }
