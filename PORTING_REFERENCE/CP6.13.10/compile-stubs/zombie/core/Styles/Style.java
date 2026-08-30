package zombie.core.Styles;
public interface Style { int getStyleID(); AlphaOp getAlphaOp(); void setupState(); void resetState(); boolean getRenderSprite(); void render(int a,int b); }
