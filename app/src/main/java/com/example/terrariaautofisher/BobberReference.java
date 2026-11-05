package com.example.terrariaautofisher;

import java.io.*;

/** One frozen grayscale crop, with optional pixels excluded by the user. */
public final class BobberReference {
    public static final int MAX_SIDE = 64;
    public final int side;
    private final float[] pixels;
    private final boolean[] mask;

    public BobberReference(int side, float[] pixels, boolean[] mask) {
        if (side < 16 || side > MAX_SIDE || pixels.length != side * side || mask.length != pixels.length)
            throw new IllegalArgumentException("Invalid snapshot size");
        this.side = side;
        this.pixels = pixels.clone();
        this.mask = mask.clone();
        int count = 0;
        double sum = 0, squares = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (!Float.isFinite(pixels[i]) || pixels[i] < 0 || pixels[i] > 255)
                throw new IllegalArgumentException("Invalid snapshot pixels");
            if (mask[i]) { count++; sum += pixels[i]; squares += pixels[i] * (double)pixels[i]; }
        }
        if (count < 32 || Math.sqrt(Math.max(0, squares/count - (sum/count)*(sum/count))) < 12)
            throw new IllegalArgumentException("Keep more of the visible bobber, or retake the snapshot");
    }

    public float[] pixels() { return pixels.clone(); }
    public boolean[] mask() { return mask.clone(); }

    public static BobberReference capture(float[] frame, int width, int height, int side) {
        if (frame.length != width * height || width < side + 8 || height < side + 8)
            throw new IllegalArgumentException("Snapshot does not fit inside the search area");
        float[] crop = new float[side * side];
        boolean[] mask = new boolean[crop.length];
        java.util.Arrays.fill(mask, true);
        int left = (width-side)/2, top = (height-side)/2;
        for (int y=0; y<side; y++) System.arraycopy(frame, (top+y)*width+left, crop, y*side, side);
        return new BobberReference(side, crop, mask);
    }

    public void save(File file) throws IOException {
        // Small app-private file. A failed write leaves the previous reference intact.
        File temporary = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temporary))) {
            out.writeInt(0x424F4231);
            out.writeInt(side);
            for (int i=0; i<pixels.length; i++) { out.writeFloat(pixels[i]); out.writeBoolean(mask[i]); }
        }
        if (!temporary.renameTo(file)) {
            temporary.delete();
            throw new IOException("Could not save bobber reference");
        }
    }

    public static BobberReference load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != 0x424F4231) throw new IOException("Unknown reference format");
            int side = in.readInt();
            if (side < 16 || side > MAX_SIDE) throw new IOException("Invalid reference size");
            float[] pixels = new float[side*side];
            boolean[] mask = new boolean[pixels.length];
            for (int i=0; i<pixels.length; i++) { pixels[i]=in.readFloat(); mask[i]=in.readBoolean(); }
            if (in.read() != -1) throw new IOException("Invalid reference length");
            try { return new BobberReference(side, pixels, mask); }
            catch (IllegalArgumentException error) { throw new IOException(error.getMessage(), error); }
        }
    }
}
