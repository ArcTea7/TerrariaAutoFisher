package com.example.terrariaautofisher;

/** Same image-under-selection drag in both magnified and overview views. */
public final class CapturePan {
    private final SnapshotEditorModel snapshot;
    private int width,height,startLeft,startTop;
    private float downX,downY,dragScale,anchorX=Float.NaN,anchorY=Float.NaN;
    private boolean overview,dragging;
    public CapturePan(SnapshotEditorModel snapshot){this.snapshot=snapshot;}
    public void size(int width,int height){
        if(this.width!=width||this.height!=height){this.width=width;this.height=height;resetAnchor();}
    }
    public void overview(boolean value){overview=value;resetAnchor();end();}
    private void resetAnchor(){anchorX=Float.NaN;anchorY=Float.NaN;}
    public float scale(){
        return overview?Math.min(width/(float)snapshot.width,height/(float)snapshot.height):
            Math.min(width,height)/(float)(snapshot.getSide()+12);
    }
    private void anchor(){
        if(!Float.isNaN(anchorX))return;
        float s=scale();
        anchorX=(width-snapshot.width*s)/2+(snapshot.getLeft()+snapshot.getSide()/2f)*s;
        anchorY=(height-snapshot.height*s)/2+(snapshot.getTop()+snapshot.getSide()/2f)*s;
    }
    public float left(){
        if(overview)anchor();
        return (overview?anchorX:width/2f)-(snapshot.getLeft()+snapshot.getSide()/2f)*scale();
    }
    public float top(){
        if(overview)anchor();
        return (overview?anchorY:height/2f)-(snapshot.getTop()+snapshot.getSide()/2f)*scale();
    }
    public void down(float x,float y){
        if(overview)anchor();
        downX=x;downY=y;startLeft=snapshot.getLeft();startTop=snapshot.getTop();dragScale=scale();dragging=true;
    }
    public void move(float x,float y){
        if(dragging&&dragScale>0)snapshot.dragImage(startLeft,startTop,x-downX,y-downY,dragScale);
    }
    public void end(){dragging=false;}
}
