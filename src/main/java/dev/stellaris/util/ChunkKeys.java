package dev.stellaris.util;

public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static long fromBlock(int blockX, int blockZ) {
        return pack(blockX >> 4, blockZ >> 4);
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) key;
    }

    public static int chunkZ(long key) {
        return (int) (key >> 32);
    }

    public static long blockKey(int blockX, int blockZ) {
        return ((long) blockZ << 32) | (blockX & 0xFFFFFFFFL);
    }
}
