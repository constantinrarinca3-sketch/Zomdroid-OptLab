package zombie.core.Styles;

public interface Style {
    int getStyleID();
    default AlphaOp getAlphaOp() { return AlphaOp.KEEP; }
}
