package gnu.trove.map.hash;

import java.util.IdentityHashMap;

/** Compile/test subset of Trove's object-to-int map. */
public class TObjectIntHashMap<K> {
    private final IdentityHashMap<K, Integer> values = new IdentityHashMap<>();
    public int clearCalls;

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public void clear() {
        clearCalls++;
        values.clear();
    }

    public void put(K key, int value) {
        values.put(key, value);
    }

    public int size() {
        return values.size();
    }
}
