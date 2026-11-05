package com.example.terrariaautofisher;

import java.nio.ByteBuffer;

/** Pixel-aligned crop from the existing reduced screen capture; never rescale the crop again. */
public final class RoiSampling {
    public final int imageWidth, imageHeight, left, top, width, height;
    private RoiSampling(int imageWidth,int imageHeight,int left,int top,int width,int height) {
        this.imageWidth=imageWidth;this.imageHeight=imageHeight;
        this.left=left;this.top=top;this.width=width;this.height=height;
    }

    public static RoiSampling forScreen(int screenWidth,int screenHeight,int left,int top,int width,int height) {
        float scale=Math.min(1f,1280f/Math.max(screenWidth,screenHeight));
        return of(screenWidth,screenHeight,Math.max(1,Math.round(screenWidth*scale)),
                Math.max(1,Math.round(screenHeight*scale)),left,top,width,height);
    }

    public static RoiSampling of(int screenWidth,int screenHeight,int imageWidth,int imageHeight,
                                 int left,int top,int width,int height) {
        if(screenWidth<=0||screenHeight<=0||imageWidth<=0||imageHeight<=0||width<=0||height<=0)
            throw new IllegalArgumentException("Invalid screen/crop size");
        int x=clamp((int)Math.round(left*(double)imageWidth/screenWidth),0,imageWidth-1);
        int y=clamp((int)Math.round(top*(double)imageHeight/screenHeight),0,imageHeight-1);
        int right=clamp((int)Math.round((left+(double)width)*imageWidth/screenWidth),x+1,imageWidth);
        int bottom=clamp((int)Math.round((top+(double)height)*imageHeight/screenHeight),y+1,imageHeight);
        int sampledWidth=Math.min(BobberDetector.MAX_WIDTH,right-x);
        int sampledHeight=Math.min(BobberDetector.MAX_HEIGHT,bottom-y);
        if(sampledWidth<32||sampledHeight<32)
            throw new IllegalArgumentException("Enlarge the detection box before taking a snapshot");
        return new RoiSampling(imageWidth,imageHeight,x,y,sampledWidth,sampledHeight);
    }

    private static int clamp(int value,int minimum,int maximum){return Math.max(minimum,Math.min(maximum,value));}

    public void readGray(ByteBuffer pixels,int rowStride,int pixelStride,float[] output) {
        if(pixelStride<3||rowStride<=0||output.length!=width*height)
            throw new IllegalArgumentException("Unexpected capture buffer layout");
        int start=pixels.position();
        long last=(long)start+(top+height-1L)*rowStride+(left+width-1L)*pixelStride+2;
        if(last>=pixels.limit())throw new IllegalArgumentException("Incomplete capture buffer");
        int index=0;
        for(int y=0;y<height;y++) {
            int offset=start+(top+y)*rowStride+left*pixelStride;
            for(int x=0;x<width;x++,offset+=pixelStride) {
                int r=pixels.get(offset)&255,g=pixels.get(offset+1)&255,b=pixels.get(offset+2)&255;
                output[index++]=.299f*r+.587f*g+.114f*b;
            }
        }
    }
}
