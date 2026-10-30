package dev.spog.teamlocator.client.path;

import java.util.Arrays;

/**
 * A hash table from packed block positions to values, without boxing either. The search does a
 * lookup or two per block it considers, and a {@code HashMap<Long, ...>} allocated a key for every
 * one of them; this keeps the keys in a plain array.
 *
 * <p>Holds an object and a double per key, so one table serves both the search's nodes and its
 * cache of floor heights. Open addressing with linear probing; never shrinks, since a search is
 * thrown away once it finishes.
 */
final class LongTable<V> {
    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private Object[] objects;
    private double[] doubles;
    private int size;
    private int mask;

    LongTable(int capacity) {
        int n = Integer.highestOneBit(Math.max(16, capacity) * 2 - 1);
        keys = new long[n];
        Arrays.fill(keys, EMPTY);
        objects = new Object[n];
        doubles = new double[n];
        mask = n - 1;
    }

    int size() {
        return size;
    }

    /** The slot holding {@code key}, or -1 if it is not in the table. */
    int find(long key) {
        int i = slot(key);
        while (true) {
            long k = keys[i];
            if (k == key) {
                return i;
            }
            if (k == EMPTY) {
                return -1;
            }
            i = (i + 1) & mask;
        }
    }

    @SuppressWarnings("unchecked")
    V object(int slot) {
        return (V) objects[slot];
    }

    double number(int slot) {
        return doubles[slot];
    }

    /** Adds or replaces the object for a key. */
    void putObject(long key, V value) {
        int slot = insert(key); // first: it may grow the arrays
        objects[slot] = value;
    }

    /** Adds or replaces the number for a key. */
    void putNumber(long key, double value) {
        int slot = insert(key); // first: it may grow the arrays
        doubles[slot] = value;
    }

    private int insert(long key) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int i = slot(key);
        while (keys[i] != EMPTY && keys[i] != key) {
            i = (i + 1) & mask;
        }
        if (keys[i] == EMPTY) {
            keys[i] = key;
            size++;
        }
        return i;
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldObjects = objects;
        double[] oldDoubles = doubles;
        keys = new long[oldKeys.length * 2];
        Arrays.fill(keys, EMPTY);
        objects = new Object[keys.length];
        doubles = new double[keys.length];
        mask = keys.length - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] != EMPTY) {
                int i = slot(oldKeys[j]);
                while (keys[i] != EMPTY) {
                    i = (i + 1) & mask;
                }
                keys[i] = oldKeys[j];
                objects[i] = oldObjects[j];
                doubles[i] = oldDoubles[j];
            }
        }
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
