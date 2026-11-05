package com.example.terrariaautofisher;

/** Frozen-image navigation. Once a gesture has multiple fingers, it cannot
 * become a one-finger pan or selection until the next fresh touch-down. */
public final class LineEditorGesture {
    private final int imageWidth,imageHeight;
    private int width,height;
    private float zoom=1,cx,cy,downX,downY,previousX,previousY;
    private boolean active,moved,multiple;
    public LineEditorGesture(int imageWidth,int imageHeight){
        this.imageWidth=imageWidth;this.imageHeight=imageHeight;
        cx=imageWidth/2f;cy=imageHeight/2f;
    }
    public void size(int width,int height){this.width=width;this.height=height;}
    public float scale(){return Math.min(width/(float)imageWidth,height/(float)imageHeight)*zoom;}
    public float centerX(){return cx;}
    public float centerY(){return cy;}
    public float imageX(float x){return cx+(x-width/2f)/scale();}
    public float imageY(float y){return cy+(y-height/2f)/scale();}
    private void clamp(){cx=Math.max(0,Math.min(imageWidth,cx));cy=Math.max(0,Math.min(imageHeight,cy));}
    public void down(float x,float y){
        active=true;moved=false;multiple=false;
        downX=previousX=x;downY=previousY=y;
    }
    public void multiple(){if(active)multiple=true;}
    public void scaleBy(float factor,float focusX,float focusY){
        if(!active||scale()<=0||!Float.isFinite(factor)||factor<=0)return;
        multiple=true;
        float beforeX=imageX(focusX),beforeY=imageY(focusY);
        zoom=Math.max(1,Math.min(10,zoom*factor));
        cx=beforeX-(focusX-width/2f)/scale();
        cy=beforeY-(focusY-height/2f)/scale();clamp();
    }
    public void move(float x,float y,int pointers,boolean scaling,boolean pan,float slop){
        if(!active)return;
        if(pointers>1||scaling)multiple=true;
        if(Math.abs(x-downX)+Math.abs(y-downY)>slop)moved=true;
        if(pan&&!multiple&&pointers==1&&scale()>0){
            cx-=(x-previousX)/scale();cy-=(y-previousY)/scale();clamp();
        }
        previousX=x;previousY=y;
    }
    public boolean canSelect(){return active&&!moved&&!multiple;}
    public void end(){active=false;}
    public void zoomBy(float factor){zoom=Math.max(1,Math.min(10,zoom*factor));clamp();}
    public void overview(){zoom=1;cx=imageWidth/2f;cy=imageHeight/2f;end();}
}
