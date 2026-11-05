package com.example.terrariaautofisher;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** One frozen, pixel-aligned capture. Crop movement and paint never read live frames. */
public final class SnapshotEditorModel {
    public final int width, height;
    private final float[] pixels;
    private final boolean[] kept;
    private int[] colors;
    private int left, top, side;

    public SnapshotEditorModel(float[] pixels, int width, int height, int side, int left, int top) {
        if (width < 16 || height < 16 || width > 1280 || height > 1280 || pixels.length != width*height)
            throw new IllegalArgumentException("Invalid frozen image");
        for (float value : pixels) if (!Float.isFinite(value) || value < 0 || value > 255)
            throw new IllegalArgumentException("Invalid frozen pixels");
        this.width=width; this.height=height; this.pixels=pixels.clone();
        kept=new boolean[pixels.length]; Arrays.fill(kept,true);
        checkSide(side); this.side=side; moveTo(left,top);
    }

    /** Same grayscale conversion and source pixel grid as RoiSampling; no resizing. */
    public static SnapshotEditorModel fromRgba(ByteBuffer buffer, int rowStride, int pixelStride,
                                               int width, int height, int side, int left, int top) {
        return fromRgba(buffer,rowStride,pixelStride,width,height,side,left,top,false);
    }
    public static SnapshotEditorModel fromRgba(ByteBuffer buffer,int rowStride,int pixelStride,
                                               int width,int height,int side,int left,int top,boolean keepColor) {
        if(width<16 || height<16 || width>1280 || height>1280 || pixelStride<3 ||
                rowStride<(long)width*pixelStride ||
                buffer.position()+(height-1L)*rowStride+(width-1L)*pixelStride+2>=buffer.limit())
            throw new IllegalArgumentException("Incomplete frozen frame");
        float[] image=new float[width*height]; int start=buffer.position();
        int[] rgb=keepColor ? new int[image.length] : null;
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int i=start+y*rowStride+x*pixelStride;
            if(rgb!=null)rgb[y*width+x]=(buffer.get(i)&255)<<16|(buffer.get(i+1)&255)<<8|buffer.get(i+2)&255;
            image[y*width+x]=.299f*(buffer.get(i)&255)+.587f*(buffer.get(i+1)&255)+.114f*(buffer.get(i+2)&255);
        }
        SnapshotEditorModel result=new SnapshotEditorModel(image,width,height,side,left,top);
        result.colors=rgb;return result;
    }

    public boolean hasColor(){return colors!=null;}
    public FishingLineTracker.Image colorImage(){
        if(colors==null)throw new IllegalArgumentException("Take a new color capture in Line mode first");
        return FishingLineTracker.pixels(colors,width,height);
    }

    public int getLeft(){return left;}
    public int getTop(){return top;}
    public int getSide(){return side;}
    public float pixel(int x,int y){return pixels[y*width+x];}
    public boolean isKept(int x,int y){return kept[y*width+x];}

    public void moveTo(int x,int y) {
        left=Math.max(0,Math.min(width-side,x)); top=Math.max(0,Math.min(height-side,y));
    }
    public void centerOn(double x,double y) {moveTo((int)Math.round(x-side/2.0),(int)Math.round(y-side/2.0));}
    public void dragImage(int initialLeft,int initialTop,float dx,float dy,float scale) {
        if(!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(scale) || scale<=0)
            throw new IllegalArgumentException("Invalid drag");
        moveTo(Math.round(initialLeft-dx/scale),Math.round(initialTop-dy/scale));
    }
    public void setSide(int size) {
        checkSide(size); double x=left+side/2.0,y=top+side/2.0;
        side=size; centerOn(x,y);
    }
    private void checkSide(int size) {
        if(size<16 || size>BobberReference.MAX_SIDE || size>width || size>height)
            throw new IllegalArgumentException("Invalid crop size");
    }
    /** Brush touches frozen image coordinates; moving never transfers a mask to different pixels. */
    public void paint(int x,int y,int radius,boolean keep) {
        if(radius<0 || radius>4)throw new IllegalArgumentException("Invalid brush size");
        // A gesture starting outside the yellow selection does not paint through its edge.
        if(x<left || y<top || x>=left+side || y>=top+side)return;
        for(int yy=Math.max(top,y-radius);yy<Math.min(top+side,y+radius+1);yy++)
            for(int xx=Math.max(left,x-radius);xx<Math.min(left+side,x+radius+1);xx++)
                kept[yy*width+xx]=keep;
    }
    public void resetMask() {Arrays.fill(kept,true);}

    /** Validate only when saving, so a blank initial crop can be moved onto the hook. */
    public BobberReference reference() {
        float[] crop=new float[side*side]; boolean[] mask=new boolean[crop.length];
        for(int y=0;y<side;y++) {
            System.arraycopy(pixels,(top+y)*width+left,crop,y*side,side);
            System.arraycopy(kept,(top+y)*width+left,mask,y*side,side);
        }
        return new BobberReference(side,crop,mask);
    }

    /** Move the existing outer watch area to the selected hook, preserving its size. */
    public int[] watchPosition(int screenWidth,int screenHeight,int areaWidth,int areaHeight) {
        if(screenWidth<width || screenHeight<height || areaWidth<=0 || areaHeight<=0 ||
                areaWidth>screenWidth || areaHeight>screenHeight)
            throw new IllegalArgumentException("Screen changed; take a new snapshot");
        int x=(int)Math.round((left+side/2.0)*screenWidth/width-areaWidth/2.0);
        int y=(int)Math.round((top+side/2.0)*screenHeight/height-areaHeight/2.0);
        x=Math.max(0,Math.min(screenWidth-areaWidth,x));
        y=Math.max(0,Math.min(screenHeight-areaHeight,y));
        RoiSampling plan=RoiSampling.of(screenWidth,screenHeight,width,height,x,y,areaWidth,areaHeight);
        if(left<plan.left || top<plan.top || left+side>plan.left+plan.width || top+side>plan.top+plan.height)
            throw new IllegalArgumentException("Enlarge the outer watch area to fit this crop");
        return new int[]{x,y};
    }
}
