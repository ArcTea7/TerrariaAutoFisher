package com.example.terrariaautofisher;

/** Pure geometry: never place the diagnostic preview over pixels being analysed. */
public final class OverlayPlacement {
    private OverlayPlacement() {}
    /** Fresh opening, independent of watched bounds or previously dragged positions. */
    public static int[] openCorner(int sw,int sh,int w,int h,int menuX,int menuWidth,int margin){
        int maxX=Math.max(0,sw-w),maxY=Math.max(0,sh-h);
        boolean menuOnLeft=menuX+menuWidth/2.0<sw/2.0;
        return new int[]{Math.max(0,Math.min(maxX,menuOnLeft?maxX-margin:margin)),
            Math.max(0,Math.min(maxY,margin))};
    }
    /** Nearest valid panel position. All returned positions stay outside both regions. */
    public static int[] nearest(int sw, int sh, int w, int h, int x, int y,
                                int[] watched, int[] other, int gap) {
        if(w<=0 || h<=0 || w>sw || h>sh)return null;
        int[] xs={Math.max(0,Math.min(sw-w,x)),0,sw-w,watched[0]-w-gap,
            watched[2]+gap,other[0]-w-gap,other[2]+gap};
        int[] ys={Math.max(0,Math.min(sh-h,y)),0,sh-h,watched[1]-h-gap,
            watched[3]+gap,other[1]-h-gap,other[3]+gap};
        int[] best=null;double distance=Double.MAX_VALUE;
        for(int py:ys)for(int px:xs){
            if(px<0||py<0||px+w>sw||py+h>sh)continue;
            if(overlaps(px,py,w,h,watched,gap)||overlaps(px,py,w,h,other,gap))continue;
            double d=(double)(px-x)*(px-x)+(double)(py-y)*(py-y);
            if(d<distance){distance=d;best=new int[]{px,py};}
        }
        return best;
    }
    public static int[] outside(int screenWidth, int screenHeight, int width, int height,
                                int[] watched, int[] menu, int gap) {
        if(width<=0 || height<=0 || width>screenWidth || height>screenHeight)return null;
        int[] xs={0,screenWidth-width,watched[0]-width-gap,watched[2]+gap,
                menu[0]-width-gap,menu[2]+gap};
        int[] ys={0,screenHeight-height,watched[1]-height-gap,watched[3]+gap,
                menu[1]-height-gap,menu[3]+gap};
        for(int y:ys)for(int x:xs) {
            if(x<0 || y<0 || x+width>screenWidth || y+height>screenHeight)continue;
            if(!overlaps(x,y,width,height,watched,gap) && !overlaps(x,y,width,height,menu,gap))
                return new int[]{x,y};
        }
        return null;
    }
    private static boolean overlaps(int x,int y,int w,int h,int[] box,int gap) {
        return x<box[2]+gap && x+w>box[0]-gap && y<box[3]+gap && y+h>box[1]-gap;
    }
}
