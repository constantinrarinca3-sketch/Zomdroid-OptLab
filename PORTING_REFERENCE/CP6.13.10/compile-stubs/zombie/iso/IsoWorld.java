package zombie.iso;
public class IsoWorld {
    public static IsoWorld instance;
    public IsoCell currentCell;
    public IsoMetaGrid metaGrid;
    public IsoCell getCell(){return currentCell;}
    public IsoMetaGrid getMetaGrid(){return metaGrid;}
    public IsoMetaChunk getMetaChunk(int x,int y){return null;}
}
