package com.example.terrariaautofisher;

/** Experimental local-only motion check. Hidden pixels are unknown, never bite evidence. */
public final class LavaBiteGuard {
    public static final int STILL=0, MOVING=1, OCCLUDED=2, UNSUITABLE=3;
    private final int width, height, side;
    private final boolean[] kept;
    private final int[] xs, ys;
    private final float[] values, weights;
    private int count;
    private boolean usable;
    private int warmExcluded;
    public static final class Observation {
        public int state, dx, dy, samples;
        public double score, restingScore, visible, gain;
    }
    public LavaBiteGuard(int width,int height,int side,boolean[] mask) {
        this.width=width;this.height=height;this.side=side;kept=mask.clone();
        xs=new int[side*side];ys=new int[xs.length];values=new float[xs.length];weights=new float[xs.length];
    }
    /** Bottom 45 percent of the user's kept selection, not of the whole screen. */
    public static int lowerStart(boolean[] mask,int side) {
        int first=side,last=-1;
        for(int y=0;y<side;y++)for(int x=0;x<side;x++)if(mask[y*side+x]){first=Math.min(first,y);last=Math.max(last,y);}
        return last<0 ? side : first+(int)Math.floor((last-first+1)*.55);
    }
    public void capture(float[] gray,byte[] warm,int x,int y) {
        count=0;warmExcluded=0;int lower=lowerStart(kept,side);
        double sum=0,square=0;
        for(int py=lower;py<side;py++)for(int px=0;px<side;px++) {
            if(!kept[py*side+px])continue;
            int i=(y+py)*width+x+px;
            if(warm[i]!=0){warmExcluded++;continue;}
            float value=gray[i];double edge=0;
            // Sample details and their immediate context, rather than large flat lava regions.
            if(px>0)edge=Math.max(edge,Math.abs(value-gray[i-1]));
            if(px+1<side)edge=Math.max(edge,Math.abs(value-gray[i+1]));
            if(py>lower)edge=Math.max(edge,Math.abs(value-gray[i-width]));
            if(py+1<side)edge=Math.max(edge,Math.abs(value-gray[i+width]));
            if(edge<5)continue;
            xs[count]=px;ys[count]=py;values[count]=value;
            weights[count]=(float)Math.min(3,1+edge/24);count++;sum+=value;square+=value*value;
        }
        double variance=count==0?0:square/count-(sum/count)*(sum/count);
        usable=count>=12 && variance>=36;
    }
    public int sampleCount(){return count;}
    public int excludedCount(){return warmExcluded;}
    public Observation inspect(float[] gray,byte[] warm,int x,int y) {
        Observation out=new Observation();out.samples=count;
        if(!usable){out.state=UNSUITABLE;return out;}
        Fit rest=fit(gray,warm,x,y);Fit best=rest;int dx=0,dy=0;
        for(int yy=-5;yy<=5;yy++)for(int xx=-3;xx<=3;xx++) {
            if(x+xx<0||y+yy<0||x+xx+side>width||y+yy+side>height)continue;
            Fit f=fit(gray,warm,x+xx,y+yy);
            // Prefer the resting location on ties. A weak/hidden candidate cannot win by hiding pixels.
            if(f.valid && (!best.valid||f.score>best.score+.005)){best=f;dx=xx;dy=yy;}
        }
        out.dx=dx;out.dy=dy;out.score=best.score;out.restingScore=rest.score;
        out.visible=best.visible;out.gain=best.score-rest.score;
        if(!best.valid || best.score<.78){out.state=OCCLUDED;return out;}
        // Compare with the best same-height alignment so sideways drift is not a bite.
        Fit horizontal=rest;
        for(int xx=-3;xx<=3;xx++)if(x+xx>=0&&x+xx+side<=width) {
            Fit f=fit(gray,warm,x+xx,y);
            if(f.valid&&(!horizontal.valid||f.score>horizontal.score))horizontal=f;
        }
        out.restingScore=horizontal.score;out.gain=best.score-horizontal.score;
        // An invalid/hidden resting comparison is not proof of displacement.
        // Otherwise a splash could win merely by leaving fewer pixels visible.
        out.state=horizontal.valid && Math.abs(dy)>=1 && out.gain>=.09 && best.error<=Math.max(3,horizontal.error*.75)
                ? MOVING : STILL;
        return out;
    }
    private static final class Fit {double score=-1,visible,error=255;boolean valid;}
    private Fit fit(float[] gray,byte[] warm,int x,int y) {
        Fit f=new Fit();double sw=0,sa=0,sb=0,saa=0,sbb=0,sab=0;int visible=0;
        for(int j=0;j<count;j++) {
            int i=(y+ys[j])*width+x+xs[j];if(warm[i]!=0)continue;
            double a=values[j],b=gray[i],w=weights[j];
            visible++;sw+=w;sa+=w*a;sb+=w*b;saa+=w*a*a;sbb+=w*b*b;sab+=w*a*b;
        }
        f.visible=visible/(double)Math.max(1,count);
        if(visible<12||f.visible<.60||sw<=0)return f;
        double ea=saa-sa*sa/sw,eb=sbb-sb*sb/sw;
        if(ea<sw*16||eb<sw*16)return f;
        double correlation=(sab-sa*sb/sw)/Math.sqrt(ea*eb);
        f.score=(correlation+1)*.5 - .15*(1-f.visible);
        double offset=(sb-sa)/sw,error=0;
        for(int j=0;j<count;j++) {
            int i=(y+ys[j])*width+x+xs[j];if(warm[i]!=0)continue;
            error+=weights[j]*Math.abs(gray[i]-values[j]-offset);
        }
        f.error=error/sw;f.valid=true;return f;
    }
    /** Cheap RGB test for saturated warm lava colors; white and neutral dark pixels survive. */
    public static boolean isWarm(int r,int g,int b) {
        return r>=80 && g>=35 && g*6>=r && r*10>=g*9 && b*4<=g*3 && r-b>=35;
    }
}
