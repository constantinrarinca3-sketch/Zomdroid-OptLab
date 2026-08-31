package gnu.trove.map.hash;
import java.util.HashMap;
public class TObjectIntHashMap<K> { private final HashMap<K,Integer> m=new HashMap<K,Integer>(); public int clearCalls; public boolean isEmpty(){return m.isEmpty();} public void clear(){clearCalls++;m.clear();} public int get(K k){Integer v=m.get(k);return v==null?0:v;} public int put(K k,int v){Integer o=m.put(k,v);return o==null?0:o;} public int size(){return m.size();} }
