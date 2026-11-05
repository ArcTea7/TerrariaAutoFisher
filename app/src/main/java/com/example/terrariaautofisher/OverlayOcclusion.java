package com.example.terrariaautofisher;

/** Only current visible windows participate. Hidden windows have no rectangles. */
public final class OverlayOcclusion {
    private OverlayOcclusion(){}
    public static boolean intersects(int[] windows,int l,int t,int r,int b){
        for(int i=0;i+3<windows.length;i+=4)
            if(windows[i]<r&&windows[i+2]>l&&windows[i+1]<b&&windows[i+3]>t)return true;
        return false;
    }
    public static boolean line(int[] windows,int[][] centers,int imageW,int imageH,int screenW,int screenH){
        if(windows.length==0||imageW<=0||imageH<=0)return false;
        for(int i=0;i<centers[0].length;i++){
            int x=centers[0][i],y=centers[1][i];if(x<0||y<0)continue;
            int l=(int)Math.floor((x-7)*screenW/(double)imageW);
            int t=(int)Math.floor((y-7)*screenH/(double)imageH);
            int r=(int)Math.ceil((x+8)*screenW/(double)imageW);
            int b=(int)Math.ceil((y+8)*screenH/(double)imageH);
            if(intersects(windows,l,t,r,b))return true;
        }
        return false;
    }
}
