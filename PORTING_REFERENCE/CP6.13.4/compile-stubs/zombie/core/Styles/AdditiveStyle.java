package zombie.core.Styles;
public final class AdditiveStyle implements Style { public static final AdditiveStyle instance=new AdditiveStyle(); public int getStyleID(){return 1;} public AlphaOp getAlphaOp(){return new AlphaOp();} public void setupState(){} public void resetState(){} public boolean getRenderSprite(){return true;} public void render(int a,int b){} }
