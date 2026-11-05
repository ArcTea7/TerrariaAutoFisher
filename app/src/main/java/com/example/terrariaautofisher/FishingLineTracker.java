package com.example.terrariaautofisher;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** One-tap color-assisted tracing and local line displacement; no screen-wide live search. */
public final class FishingLineTracker {
    public interface Image { int width(); int height(); int rgb(int x,int y); }
    public static Image rgba(ByteBuffer buffer,int rowStride,int pixelStride,int width,int height) {
        int start=buffer.position();
        if(width<16||height<16||pixelStride<3||rowStride<(long)width*pixelStride||
                start+(height-1L)*rowStride+(width-1L)*pixelStride+2>=buffer.limit())
            throw new IllegalArgumentException("Incomplete color frame");
        return new Image(){
            public int width(){return width;} public int height(){return height;}
            public int rgb(int x,int y){int i=start+y*rowStride+x*pixelStride;
                return (buffer.get(i)&255)<<16|(buffer.get(i+1)&255)<<8|buffer.get(i+2)&255;}
        };
    }
    public static Image pixels(int[] pixels,int width,int height) {
        if(pixels.length!=width*height)throw new IllegalArgumentException("Wrong frozen image");
        return new Image(){public int width(){return width;}public int height(){return height;}
            public int rgb(int x,int y){return pixels[y*width+x];}};
    }
    public static final class Selection {
        public final int width,height,color,seedX,seedY;
        public final boolean vertical;
        public final int[] x,y;
        public final double[] rest;
        Selection(int width,int height,int color,int seedX,int seedY,boolean vertical,
                  int[] x,int[] y,double[] rest) {
            this.width=width;this.height=height;this.color=color;this.seedX=seedX;this.seedY=seedY;
            this.vertical=vertical;this.x=x.clone();this.y=y.clone();this.rest=rest.clone();
            validate();
        }
        private void validate(){
            if(width<16||height<16||width>1280||height>1280||x.length<8||x.length>180||
                    y.length!=x.length||rest.length!=x.length||seedX<5||seedY<5||
                    seedX>=width-5||seedY>=height-5)throw new IllegalArgumentException("Invalid line selection");
            int usable=0;
            for(int i=0;i<x.length;i++){
                if(x[i]<5||y[i]<5||x[i]>=width-5||y[i]>=height-5||
                        (!Double.isNaN(rest[i])&&(!Double.isFinite(rest[i])||Math.abs(rest[i])>6)))
                    throw new IllegalArgumentException("Invalid line position");
                if(Double.isFinite(rest[i]))usable++;
            }
            if(usable<8||usable<x.length*.60)throw new IllegalArgumentException("Line is too faint or obstructed. Tap another visible section");
        }
        public Selection trim(boolean start){
            if(x.length<=10)throw new IllegalArgumentException("Keep a longer visible line section");
            int cut=Math.max(1,x.length/10),from=start?cut:0,to=start?x.length:x.length-cut;
            return new Selection(width,height,color,seedX,seedY,vertical,
                    Arrays.copyOfRange(x,from,to),Arrays.copyOfRange(y,from,to),Arrays.copyOfRange(rest,from,to));
        }
        public void save(File file)throws IOException{
            File temp=new File(file.getPath()+".tmp");
            try(DataOutputStream out=new DataOutputStream(new FileOutputStream(temp))){
                out.writeInt(0x4c494e31);out.writeInt(width);out.writeInt(height);out.writeInt(color);
                out.writeInt(seedX);out.writeInt(seedY);out.writeBoolean(vertical);out.writeInt(x.length);
                for(int i=0;i<x.length;i++){out.writeInt(x[i]);out.writeInt(y[i]);out.writeDouble(rest[i]);}
            }
            if(!temp.renameTo(file)){temp.delete();throw new IOException("Could not save line");}
        }
        public static Selection load(File file)throws IOException{
            try(DataInputStream in=new DataInputStream(new FileInputStream(file))){
                if(in.readInt()!=0x4c494e31)throw new IOException("Unknown line format");
                int w=in.readInt(),h=in.readInt(),color=in.readInt(),sx=in.readInt(),sy=in.readInt();
                boolean vertical=in.readBoolean();int n=in.readInt();
                if(n<8||n>180)throw new IOException("Invalid line length");
                int[] x=new int[n],y=new int[n];double[] rest=new double[n];
                for(int i=0;i<n;i++){x[i]=in.readInt();y[i]=in.readInt();rest[i]=in.readDouble();}
                if(in.read()!=-1)throw new IOException("Invalid line file length");
                try{return new Selection(w,h,color,sx,sy,vertical,x,y,rest);}
                catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
            }
        }
    }
    private static double luma(int c){return .299*((c>>16)&255)+.587*((c>>8)&255)+.114*(c&255);}
    private static double colorFit(int c,int target){
        double mean=(((c>>16)&255)+((c>>8)&255)+(c&255))/3.0;
        double tm=(((target>>16)&255)+((target>>8)&255)+(target&255))/3.0;
        double error=0;
        for(int shift=0;shift<=16;shift+=8){double d=(((c>>shift)&255)+8)/(mean+8)-(((target>>shift)&255)+8)/(tm+8);error+=d*d;}
        return Math.max(0,1-Math.sqrt(error/3)/.65);
    }
    private static double colorDistance(int a,int b){
        return (Math.abs(((a>>16)&255)-((b>>16)&255))+
                Math.abs(((a>>8)&255)-((b>>8)&255))+Math.abs((a&255)-(b&255)))/3.0;
    }
    /** A colored line can be darker than the water. Require separation from
     * both sides and a different color profile; brightness alone still works. */
    private static double contrast(int c,int first,int second){
        double bright=luma(c)-Math.max(luma(first),luma(second));
        if(bright>=8)return bright;
        double separation=Math.min(colorDistance(c,first),colorDistance(c,second));
        double chroma=1-Math.max(colorFit(first,c),colorFit(second,c));
        return Math.max(bright,separation*chroma);
    }
    private static double ridge(Image a,int x,int y,boolean vertical,int color){
        if(x<5||y<5||x>=a.width()-5||y>=a.height()-5)return 0;
        int c=a.rgb(x,y);
        double fit=colorFit(c,color);
        if(fit==0)return 0;
        int first=vertical?a.rgb(x-4,y):a.rgb(x,y-4);
        int second=vertical?a.rgb(x+4,y):a.rgb(x,y+4);
        return contrast(c,first,second)*fit;
    }
    /** Samples actual rendered pixels. No hardcoded white/cyan/lava color is used. */
    public static Selection trace(Image a,int tapX,int tapY){return trace(a,tapX,tapY,-1);}
    private static Selection trace(Image a,int tapX,int tapY,int knownColor){
        double best=-1e9;int sx=-1,sy=-1,color=0;
        for(int y=Math.max(6,tapY-4);y<Math.min(a.height()-6,tapY+5);y++)
            for(int x=Math.max(6,tapX-4);x<Math.min(a.width()-6,tapX+5);x++){
                int c=a.rgb(x,y);
                double edge=Math.max(contrast(c,a.rgb(x-4,y),a.rgb(x+4,y)),
                        contrast(c,a.rgb(x,y-4),a.rgb(x,y+4)));
                double score=edge*(knownColor<0?1:colorFit(c,knownColor))-2*Math.hypot(x-tapX,y-tapY);
                if(score>best){best=score;sx=x;sy=y;color=c;}
            }
        if(best<8)throw new IllegalArgumentException("No distinct line here. Zoom in and tap its visible middle");
        if(knownColor>=0)color=knownColor;
        double xx=0,xy=0,yy=0,total=0;
        for(int y=Math.max(1,sy-7);y<Math.min(a.height()-1,sy+8);y++)
            for(int x=Math.max(1,sx-7);x<Math.min(a.width()-1,sx+8);x++){
                int c=a.rgb(x,y);double fit=colorFit(c,color),dx=x-sx,dy=y-sy;
                double weight=Math.max(0,luma(c)-luma(color)*.25)*fit*fit*fit*Math.exp(-(dx*dx+dy*dy)/40);
                xx+=weight*dx*dx;xy+=weight*dx*dy;yy+=weight*dy*dy;total+=weight;
            }
        if(total<1)throw new IllegalArgumentException("Line has too little contrast");
        double angle=.5*Math.atan2(2*xy,xx-yy),dx=Math.cos(angle),dy=Math.sin(angle);
        boolean vertical=Math.abs(dy)>=Math.abs(dx);
        double slope=vertical?dx/dy:dy/dx;slope=Math.max(-1,Math.min(1,slope));
        List<int[]> before=walk(a,sx,sy,vertical,slope,-1,color),after=walk(a,sx,sy,vertical,slope,1,color);
        Collections.reverse(before);List<int[]> all=new ArrayList<>(before);all.add(new int[]{sx,sy});all.addAll(after);
        if(all.size()<21)throw new IllegalArgumentException("Found only a short fragment. Tap farther from the hook, rod or particles");
        int n=(all.size()-6+1)/2;
        if(n>180)n=180;int[] xs=new int[n],ys=new int[n];
        for(int i=0;i<n;i++){int[] point=all.get(3+i*2);xs[i]=point[0];ys[i]=point[1];}
        double[] rest=positions(a,xs,ys,vertical,color);
        return new Selection(a.width(),a.height(),color,sx,sy,vertical,xs,ys,rest);
    }
    private static List<int[]> walk(Image a,int sx,int sy,boolean vertical,double slope,int sign,int color){
        List<int[]> out=new ArrayList<>();double cross=vertical?sx:sy;int p=vertical?sy:sx;
        for(int k=1;k<=180;k++){
            int q=p+sign*k;double predicted=cross+sign*slope,best=-1e9,strength=0;int bx=0,by=0,bc=0;
            for(int c=(int)Math.round(predicted)-2;c<=(int)Math.round(predicted)+2;c++){
                int x=vertical?c:q,y=vertical?q:c;double s=ridge(a,x,y,vertical,color),score=s-6*Math.abs(c-predicted);
                if(score>best){best=score;strength=s;bx=x;by=y;bc=c;}
            }
            if(strength<8)break;int thick=0;
            for(int offset=-3;offset<=3;offset++)if(ridge(a,bx+(vertical?offset:0),by+(vertical?0:offset),vertical,color)>=strength*.5)thick++;
            if(thick>4)break;
            out.add(new int[]{bx,by});slope=.8*slope+.2*(bc-cross)/sign;cross=bc;
        }
        return out;
    }
    private static double[] positions(Image a,int[] xs,int[] ys,boolean vertical,int color){
        double[] values=new double[xs.length],s=new double[13];Arrays.fill(values,Double.NaN);
        for(int j=0;j<values.length;j++){
            int x=xs[j],y=ys[j],peak=0;
            for(int i=0;i<13;i++){
                int o=i-6;s[i]=Math.max(0,ridge(a,x+(vertical?o:0),y+(vertical?0:o),vertical,color));
                if(s[i]>s[peak])peak=i;
            }
            double strength=s[peak];int thick=0;for(double v:s)if(v>=strength*.5)thick++;
            if(strength<8||thick>4||peak==0||peak==12)continue;
            double sum=0,moment=0;
            for(int i=Math.max(0,peak-1);i<=Math.min(12,peak+1);i++){sum+=s[i];moment+=(i-6)*s[i];}
            if(sum>0)values[j]=moment/sum;
        }
        return values;
    }
    public static final class Result {
        public String phase,reason,message;public boolean bite;
        public double displacement,visible,support,threshold,noise;
        public int points;public long pendingMs;
        public int areaSearches,pathGeneration;
        public boolean areaMode;
    }
    private Selection selection;
    private final double sensitivity;
    private long lastTime=-1,pendingStart=-1,eventAt=-1,startedAt=-1;
    private int evidenceBits,quietFrames;
    private boolean armed,waiting,seenMissing;
    private double noise;
    private boolean areaMode;
    private int areaMargin;
    private int areaLeft,areaTop,areaRight,areaBottom;
    private int[] liveX,liveY;
    private long lossSince=-1,lastAreaSearch=-1,lastMotionAt=-1;
    private Selection recoveryCandidate;
    private Selection areaReference;
    private int recoveryFrames,areaSearches,pathGeneration;
    private long candidateSince;
    public FishingLineTracker(Selection selection,double sensitivity){
        this.selection=selection;
        if(!Double.isFinite(sensitivity)||sensitivity<.10||sensitivity>2)throw new IllegalArgumentException("Invalid sensitivity");
        this.sensitivity=sensitivity;
    }
    public FishingLineTracker(Selection selection,double sensitivity,boolean enableArea,int margin){
        this(selection,sensitivity);
        if(margin<8||margin>48)throw new IllegalArgumentException("Invalid search margin");
        areaMode=enableArea;areaMargin=margin;
        areaReference=selection;
        liveX=selection.x.clone();liveY=selection.y.clone();
        int minX=selection.width,minY=selection.height,maxX=0,maxY=0;
        for(int i=0;i<selection.x.length;i++){minX=Math.min(minX,selection.x[i]);maxX=Math.max(maxX,selection.x[i]);minY=Math.min(minY,selection.y[i]);maxY=Math.max(maxY,selection.y[i]);}
        areaLeft=Math.max(5,minX-margin);areaTop=Math.max(5,minY-margin);
        areaRight=Math.min(selection.width-6,maxX+margin);areaBottom=Math.min(selection.height-6,maxY+margin);
    }
    private static double median(double[] v,int n){if(n==0)return 0;Arrays.sort(v,0,n);return n%2==1?v[n/2]:(v[n/2-1]+v[n/2])*.5;}
    private double displacementMedian(double[] values,double[] weights,int n){
        if(n==0)return 0;
        for(int i=1;i<n;i++){double v=values[i],w=weights[i];int j=i-1;
            while(j>=0&&values[j]>v){values[j+1]=values[j];weights[j+1]=weights[j];j--;}
            values[j+1]=v;weights[j+1]=w;
        }
        double total=0;for(int i=0;i<n;i++)total+=weights[i];double sum=0;
        for(int i=0;i<n;i++){sum+=weights[i];if(sum>=total*.5)return values[i];}
        return values[n-1];
    }
    private static final class Seed {
        int x,y;double score;
        Seed(int x,int y,double score){this.x=x;this.y=y;this.score=score;}
    }
    /** Recover the same color/connected outline, never just the brightest pixel. */
    private Selection searchArea(Image frame){
        areaSearches++;
        ArrayList<Seed> seeds=new ArrayList<>();
        int majorMin=selection.vertical?areaTop:areaLeft,majorMax=selection.vertical?areaBottom:areaRight;
        int crossMin=selection.vertical?areaLeft:areaTop,crossMax=selection.vertical?areaRight:areaBottom;
        int step=Math.max(2,(majorMax-majorMin+1)/40);
        for(int major=majorMin;major<=majorMax;major+=step)for(int cross=crossMin;cross<=crossMax;cross++){
            int x=selection.vertical?cross:major,y=selection.vertical?major:cross;
            int c=frame.rgb(x,y);
            if(luma(c)<Math.max(12,luma(selection.color)*.20)||colorFit(c,selection.color)<.50)continue;
            double strength=ridge(frame,x,y,selection.vertical,selection.color);
            if(strength<8)continue;
            double distance=Double.MAX_VALUE;
            for(int i=0;i<selection.x.length;i+=3)distance=Math.min(distance,Math.hypot(x-selection.x[i],y-selection.y[i]));
            double score=Math.min(120,strength)-.35*distance;
            int insert=0;while(insert<seeds.size()&&seeds.get(insert).score>score)insert++;
            if(insert>=24)continue;
            boolean close=false;
            for(Seed s:seeds)if(Math.hypot(s.x-x,s.y-y)<8){close=true;break;}
            if(close)continue;
            seeds.add(insert,new Seed(x,y,score));if(seeds.size()>24)seeds.remove(seeds.size()-1);
        }
        Selection best=null;double bestScore=-1;int tried=0;
        for(Seed seed:seeds){
            if(tried++>=6)break;
            try {
                Selection traced=trace(frame,seed.x,seed.y,selection.color);
                Selection aligned=alignCandidate(frame,traced);
                if(aligned==null)continue;
                double distance=0;int n=0;
                for(int i=0;i<aligned.x.length;i++)if(Double.isFinite(aligned.rest[i])){
                    distance+=Math.hypot(aligned.x[i]-selection.x[i],aligned.y[i]-selection.y[i]);n++;
                }
                double score=n/(double)aligned.x.length-.01*distance/Math.max(1,n);
                if(score>bestScore){best=aligned;bestScore=score;}
            }catch(IllegalArgumentException ignored){}
        }
        return best;
    }
    private Selection alignCandidate(Image frame,Selection traced){
        if(traced.vertical!=areaReference.vertical)return null;
        int n=areaReference.x.length;
        int[] xs=areaReference.x.clone(),ys=areaReference.y.clone();
        boolean[] mapped=new boolean[n];int known=0,upper=0,upperTotal=Math.max(1,n/3);
        int[] majors=traced.vertical?traced.y:traced.x,crosses=traced.vertical?traced.x:traced.y;
        for(int i=0;i<n;i++){
            int major=areaReference.vertical?areaReference.y[i]:areaReference.x[i];
            if(major<majors[0]||major>majors[majors.length-1])continue;
            int k=0;while(k+1<majors.length&&majors[k+1]<major)k++;
            if(k+1>=majors.length)continue;
            double fraction=(major-majors[k])/(double)Math.max(1,majors[k+1]-majors[k]);
            int cross=(int)Math.round(crosses[k]+fraction*(crosses[k+1]-crosses[k]));
            int originalCross=areaReference.vertical?areaReference.x[i]:areaReference.y[i];
            // Keep the geometry near the user's line, even inside a broad bounding box.
            if(Math.abs(cross-originalCross)>areaMargin)continue;
            int x=areaReference.vertical?cross:major,y=areaReference.vertical?major:cross;
            if(x<areaLeft||x>areaRight||y<areaTop||y>areaBottom)continue;
            xs[i]=x;ys[i]=y;mapped[i]=true;
        }
        double[] rest=positions(frame,xs,ys,areaReference.vertical,areaReference.color);
        for(int i=0;i<n;i++){
            if(!mapped[i])rest[i]=Double.NaN;
            if(Double.isFinite(rest[i])){known++;if(i<upperTotal)upper++;}
        }
        if(known<n*.80||upper<upperTotal*.70)return null;
        try{return new Selection(areaReference.width,areaReference.height,areaReference.color,
                traced.seedX,traced.seedY,areaReference.vertical,xs,ys,rest);}
        catch(IllegalArgumentException invalid){return null;}
    }
    private void decorateArea(Result r){r.areaMode=areaMode;r.areaSearches=areaSearches;r.pathGeneration=pathGeneration;}
    private Result areaRecovery(Image frame,long now,Result r){
        // Recovery can never emit an event or carry one-frame bite evidence over a relocation.
        r.bite=false;r.phase="SEARCHING";r.reason="LINE_AREA_SEARCH";r.message="Searching inside the selected line area";
        evidenceBits=0;pendingStart=-1;r.pendingMs=0;
        if(recoveryCandidate!=null){
            double[] p=positions(frame,recoveryCandidate.x,recoveryCandidate.y,recoveryCandidate.vertical,recoveryCandidate.color);
            double[] shifts=new double[p.length];int n=0,close=0;
            for(int i=0;i<p.length;i++)if(Double.isFinite(p[i])&&Double.isFinite(recoveryCandidate.rest[i])){
                double d=p[i]-recoveryCandidate.rest[i];shifts[n++]=d;if(Math.abs(d)<.75)close++;
            }
            if(n>=p.length*.80&&close>=n*.75&&Math.abs(median(shifts,n))<.15){
                recoveryFrames++;
                r.reason="LINE_AREA_VERIFY";r.message="Checking recovered line";
                r.visible=n/(double)p.length;r.displacement=0;r.support=0;
                if(recoveryFrames>=4&&now-candidateSince>=150){
                    selection=recoveryCandidate;liveX=selection.x.clone();liveY=selection.y.clone();
                    recoveryCandidate=null;recoveryFrames=0;lossSince=-1;noise=0;
                    waiting=false;armed=true;quietFrames=0;seenMissing=false;pathGeneration++;
                    r.phase="WATCHING";r.reason="LINE_AREA_REACQUIRED";r.message="Line recovered. Watching its movement";
                    r.displacement=0;r.support=0;r.visible=n/(double)p.length;r.noise=0;
                }
                decorateArea(r);return r;
            }
            recoveryCandidate=null;recoveryFrames=0;
        }
        if(lastAreaSearch<0||now-lastAreaSearch>=250){
            lastAreaSearch=now;recoveryCandidate=searchArea(frame);
            if(recoveryCandidate!=null){candidateSince=now;recoveryFrames=1;r.reason="LINE_AREA_VERIFY";r.message="Checking recovered line";}
        }
        if(recoveryCandidate==null&&lossSince>=0&&now-lossSince>3000){
            r.reason="LINE_AREA_NOT_FOUND";r.message="No verified line in area. Enlarge the area or Capture again";
        }
        decorateArea(r);return r;
    }
    private void followLivePath(Image frame){
        double[] p=positions(frame,liveX,liveY,selection.vertical,selection.color);
        for(int i=0;i<p.length;i++)if(Double.isFinite(p[i])&&Double.isFinite(selection.rest[i])){
            double absolute=p[i]-selection.rest[i]+(selection.vertical?liveX[i]-selection.x[i]:liveY[i]-selection.y[i]);
            if(Math.abs(absolute)>.75||Math.abs(p[i])>1.5)continue;
            int step=(int)Math.max(-1,Math.min(1,Math.round(p[i])));
            if(selection.vertical)liveX[i]=Math.max(areaLeft,Math.min(areaRight,liveX[i]+step));
            else liveY[i]=Math.max(areaTop,Math.min(areaBottom,liveY[i]+step));
        }
        // Only the search centers move. selection/rest remain the cast's bite reference.
    }
    /** Display-only sampled outline; does not update learning, tracking or bite state. */
    public float[][] inspectionPath(Image frame){
        int[] xs=areaMode?liveX:selection.x,ys=areaMode?liveY:selection.y;
        double[] offsets=positions(frame,xs,ys,selection.vertical,selection.color);
        float[][] path=new float[2][xs.length];
        for(int i=0;i<xs.length;i++){
            boolean visible=Double.isFinite(offsets[i])&&Double.isFinite(selection.rest[i]);
            path[0][i]=visible?(float)(xs[i]+(selection.vertical?offsets[i]:0)):Float.NaN;
            path[1][i]=visible?(float)(ys[i]+(selection.vertical?0:offsets[i])):Float.NaN;
        }
        return path;
    }
    /** Read-only centers for keeping visible floating UI out of detector input. */
    public int[][] inspectionCenters(){
        int[][] points={ (areaMode?liveX:selection.x).clone(),(areaMode?liveY:selection.y).clone() };
        for(int i=0;i<points[0].length;i++)if(!Double.isFinite(selection.rest[i])){
            points[0][i]=-1;points[1][i]=-1;
        }
        return points;
    }
    /** An intentional recovery cast must settle before movement can be a bite. */
    public void expectCast(long now){
        waiting=true;armed=false;seenMissing=true;quietFrames=0;eventAt=now;
        evidenceBits=0;pendingStart=-1;recoveryCandidate=null;recoveryFrames=0;
        lossSince=-1;lastMotionAt=-1;
    }
    public Result process(Image frame,long now){
        if(frame.width()!=selection.width||frame.height()!=selection.height)throw new IllegalArgumentException("Screen changed. Capture the line again");
        Result r=new Result();r.points=selection.x.length;r.noise=noise;r.threshold=Math.max(sensitivity,noise*5+.08);
        decorateArea(r);
        r.phase=waiting?"RECAST":"WATCHING";r.reason="LINE_FRESH_FRAME_REQUIRED";r.message="Waiting for a fresh line frame";
        if(now<=lastTime)return r;
        if(startedAt<0)startedAt=now;
        if(lastTime>=0&&now-lastTime>250){
            evidenceBits=0;pendingStart=-1;quietFrames=0;
            if(areaMode){recoveryCandidate=null;recoveryFrames=0;lossSince=now;}
        }
        lastTime=now;
        double[] p=positions(frame,areaMode?liveX:selection.x,areaMode?liveY:selection.y,selection.vertical,selection.color);
        double[] delta=new double[p.length],weights=new double[p.length];int n=0,positive=0,negative=0;
        double supportLimit=Math.max(.18,sensitivity*1.75);
        for(int i=0;i<p.length;i++)if(Double.isFinite(p[i])&&Double.isFinite(selection.rest[i])){
            double d=p[i]-selection.rest[i];
            if(areaMode)d+=selection.vertical?liveX[i]-selection.x[i]:liveY[i]-selection.y[i];
            delta[n]=d;
            // The rod end is relatively anchored. Emphasize the pond end while
            // still requiring motion/visibility support across the whole path.
            double u=i/(double)Math.max(1,p.length-1);
            if(selection.y[0]>selection.y[p.length-1])u=1-u;
            weights[n++]=1+3*u*u;
            if(d>supportLimit)positive++;if(d<-supportLimit)negative++;
        }
        r.visible=n/(double)p.length;r.displacement=displacementMedian(delta,weights,n);r.support=Math.max(positive,negative)/(double)Math.max(1,n);
        boolean motion=r.visible>=.60&&r.support>=.30&&Math.abs(r.displacement)>r.threshold;
        boolean quiet=r.visible>.75&&Math.abs(r.displacement)<.15&&r.support<.55;
        if(areaMode){
            if(waiting&&r.visible<.50)seenMissing=true;
            if(armed&&!waiting&&motion)lastMotionAt=now;
            if(recoveryCandidate!=null)return areaRecovery(frame,now,r);
            boolean needsRecovery=r.visible<.60 || ((waiting||!armed)&&!quiet);
            if(needsRecovery){if(lossSince<0)lossSince=now;}else lossSince=-1;
            boolean safe=pendingStart<0 && (lastMotionAt<0||now-lastMotionAt>350);
            if(needsRecovery&&safe&&now-lossSince>=300&&(!waiting||now-eventAt>=(seenMissing?900:2200)))
                return areaRecovery(frame,now,r);
        }
        if(waiting){
            if(r.visible<.50)seenMissing=true;
            quietFrames=quiet?quietFrames+1:0;
            if(now-eventAt>=(seenMissing?900:2200)&&quietFrames>=6){waiting=false;armed=true;quietFrames=0;}
            evidenceBits=0;pendingStart=-1;
            r.phase=waiting?"RECAST":"WATCHING";r.reason=waiting?"LINE_WAITING_FOR_RETURN":"LINE_REARMED";
            r.message=waiting?(now-eventAt>5000?"Line has not returned. Cast to the same spot or Capture again":"Waiting for the cast line to return and settle"):"Watching fishing line";return r;
        }
        if(!armed){
            quietFrames=quiet?quietFrames+1:0;
            if(quietFrames>=6)armed=true;
            if(!armed&&now-startedAt>3000){
                r.phase="NEEDS_REFERENCE";r.reason="LINE_SETUP_CHANGED";
                r.message="Selected line has moved or is unclear. Capture it again, or adjust sensitivity";return r;
            }
            r.phase=armed?"WATCHING":"STARTING";r.reason=armed?"LINE_ARMED":"LINE_SETTLING";r.message=armed?"Watching fishing line":"Waiting for the selected line to settle";return r;
        }
        if(r.visible<.60){
            evidenceBits=0;pendingStart=-1;r.reason="LINE_OBSCURED";r.message="Line obscured. Holding its selected path";return r;
        }
        if(pendingStart>=0&&now-pendingStart>220){pendingStart=-1;evidenceBits=0;}
        evidenceBits=((evidenceBits<<1)|(motion?1:0))&31;
        if(motion&&pendingStart<0)pendingStart=now;
        r.pendingMs=pendingStart<0?0:now-pendingStart;
        if(motion&&Integer.bitCount(evidenceBits)>=2&&r.pendingMs>=35){
            waiting=true;armed=false;seenMissing=false;quietFrames=0;eventAt=now;evidenceBits=0;pendingStart=-1;
            r.phase="BITE";r.reason="LINE_MOVEMENT_CONFIRMED";r.message="Line bite detected - reel manually";r.bite=true;return r;
        }
        if(quiet&&pendingStart<0)noise=.95*noise+.05*Math.min(.08,Math.abs(r.displacement));
        if(areaMode&&pendingStart<0&&r.visible>.80&&Math.abs(r.displacement)<r.threshold)
            followLivePath(frame);
        r.reason=motion?"LINE_BITE_CHECK":"LINE_RESTING";r.message=motion?"Checking fishing-line movement":"Watching fishing line";return r;
    }
}
