package com.example.terrariaautofisher;

/** Small, dependency-free image matcher. All calls belong to one analysis thread. */
public final class BobberDetector {
    public static final int SIZE = 112;
    public static final int PATCH = 24;
    private final int width, height, centerX, centerY;
    private final int patchSize;
    private boolean[] referenceMask;
    private final int[] templateCount = new int[5];
    private final int[][] sampleOffsets = new int[5][];
    private final float[][] sampleValues = new float[5][];
    private final int[] effectiveStep = new int[5];
    public static final int MAX_WIDTH = 320;
    public static final int MAX_HEIGHT = 224;

    public BobberDetector() { this(SIZE, SIZE); }
    public BobberDetector(int width, int height) { this(width, height, PATCH); }
    public BobberDetector(int width, int height, int side) {
        if (side < 16 || side > BobberReference.MAX_SIDE || width < side + 8 || height < side + 8) throw new IllegalArgumentException("Area too small");
        this.width = width; this.height = height; this.patchSize = side;
        centerX = (width - patchSize) / 2; centerY = (height - patchSize) / 2;
        baselineY = centerY;
        candidateScores = new float[(width-patchSize+1)*(height-patchSize+1)];
    }
    public static double captureScale(int screenWidth, int screenHeight) {
        return Math.min(1, 1280.0 / Math.max(screenWidth, screenHeight));
    }
    public static int maxSourceWidth(int screenWidth, int screenHeight) {
        return Math.min(screenWidth, (int)(MAX_WIDTH / captureScale(screenWidth, screenHeight)));
    }
    public static int maxSourceHeight(int screenWidth, int screenHeight) {
        return Math.min(screenHeight, (int)(MAX_HEIGHT / captureScale(screenWidth, screenHeight)));
    }
    public static int sampleWidth(int areaWidth, int screenWidth, int screenHeight) {
        return Math.max(PATCH+8, Math.min(MAX_WIDTH, (int)Math.round(areaWidth*captureScale(screenWidth,screenHeight))));
    }
    public static int sampleHeight(int areaHeight, int screenWidth, int screenHeight) {
        return Math.max(PATCH+8, Math.min(MAX_HEIGHT, (int)Math.round(areaHeight*captureScale(screenWidth,screenHeight))));
    }

    public static final class Result {
        public final String phase;
        public final String message;
        public final float confidence;
        public final boolean bite;
        /** Coordinates are analysis pixels, relative to the outer search area. */
        public final int candidateX, candidateY, reliableX, reliableY, referenceSide;
        public final String reason, searchSource;
        public final boolean hasReliablePosition;
        public final int wideSearches;
        /** Local measurements, never a probability or a confirmed item catch. */
        public double localError, changedFraction, restMatch, downwardPixels;
        public long pendingMs;
        public boolean armed;
        Result(String phase, String message, float confidence, boolean bite,
               int candidateX, int candidateY, int reliableX, int reliableY, int referenceSide,
               String reason, String searchSource, boolean hasReliablePosition, int wideSearches) {
            this.phase = phase; this.message = message;
            this.confidence = confidence; this.bite = bite;
            this.candidateX=candidateX; this.candidateY=candidateY;
            this.reliableX=reliableX; this.reliableY=reliableY;
            this.referenceSide=referenceSide; this.reason=reason; this.searchSource=searchSource;
            this.hasReliablePosition=hasReliablePosition; this.wideSearches=wideSearches;
        }
    }

    private float[] template;
    private int returnFrames;
    private boolean waitingForRecast;
    private boolean sawMissingBobber;
    private double baselineY;
    private double lastReturnX, lastReturnY;
    private boolean searching;
    private int recoveryFrames;
    private long rearmAfter;
    private long fallbackRearmAfter;
    private long syntheticTime;
    private int trackedX = -1, trackedY = -1;
    private final double[] templateSum = new double[5];
    private final double[] templateEnergy = new double[5];
    private final float[] candidateScores;
    private int acquisitionX=-1, acquisitionY=-1;
    private boolean hasReliablePosition;
    private long lastWideSearch=-1;
    private int wideSearches;
    private Match observed;
    private String reason="NO_REFERENCE", searchSource="none";
    // Search throttling applies only to the expensive outer search, never to nearby tracking.
    private static final long WIDE_INTERVAL_MS=200;
    private static final double ACQUIRE_SCORE=.84;
    /** Recovery uses this detector's own resting-candidate cutoff, not Line's visibility percentage. */
    public static boolean hasRestingCandidate(Result result) {
        return result.confidence >= (float)ACQUIRE_SCORE;
    }
    private static final int LOCAL_X=8, LOCAL_Y=12;
    private float[] restingPatch;
    private double restingMatch, quietError=1;
    private long restSince, settleStartedMs, pendingSince=-1, lastProcessTime=-1;
    private int quietFrames, pendingEvidence, pendingClearFrames;
    private boolean armed;
    private double localError, changedFraction, downwardPixels;
    private static final long BITE_WINDOW_MS=180;

    public Result process(float[] image) {
        syntheticTime += 100;
        return process(image, syntheticTime);
    }
    public Result process(float[] image, long nowMs) {
        if (image.length != width * height) throw new IllegalArgumentException("Wrong frame size");
        if (template == null) return result("NEEDS_REFERENCE", "Take a snapshot of the resting bobber first", 0, false);
        if (nowMs <= lastProcessTime)
            return result(waitingForRecast ? "RECAST" : searching ? "SEARCHING" : "WATCHING",
                    "Waiting for a fresh frame", observed == null ? 0 : observed.score, false);
        lastProcessTime=nowMs;
        if (waitingForRecast) {
            pendingSince=-1; localError=0; changedFraction=0; downwardPixels=0;
            Match match=find(image,nowMs); observed=match;
            if (match.score < .72) sawMissingBobber = true;
            boolean steady = match.score >= ACQUIRE_SCORE
                    && Math.hypot(match.x-lastReturnX,match.y-lastReturnY) <= 1.5;
            returnFrames = steady ? returnFrames + 1 : 0;
            lastReturnX = match.x; lastReturnY = match.y;
            if (match.score >= ACQUIRE_SCORE) { acquisitionX=match.x; acquisitionY=match.y; }
            boolean normalReturn = sawMissingBobber && returnFrames >= 4 && nowMs >= rearmAfter;
            boolean stableReturn = returnFrames >= 8 && nowMs >= fallbackRearmAfter;
            if (normalReturn || stableReturn) {
                waitingForRecast=false; searching=false;
                commitPosition(match); establishRest(image,match,nowMs);
                reason="RECAST_SETTLED";
                return result("WATCHING", "Bobber settled. Checking steady position", match.score, false);
            }
            reason="WAITING_FOR_RECAST";
            return result("RECAST", "Still running: waiting for a resting bobber", match.score, false);
        }
        if (searching) {
            localError=0; changedFraction=0; downwardPixels=0;
            Match match=find(image,nowMs); observed=match;
            boolean steady=acquisitionX>=0 && Math.hypot(match.x-acquisitionX,match.y-acquisitionY)<=2;
            recoveryFrames=match.score>=ACQUIRE_SCORE ? (steady ? recoveryFrames+1 : 1) : 0;
            if (match.score>=ACQUIRE_SCORE) { acquisitionX=match.x; acquisitionY=match.y; }
            else { acquisitionX=-1; acquisitionY=-1; }
            reason="ACQUIRING_"+recoveryFrames+"_OF_4";
            if(recoveryFrames<4)
                return result("SEARCHING", "Still running: locating the resting bobber",match.score,false);
            searching=false; commitPosition(match); establishRest(image,match,nowMs);
            reason="ACQUIRED";
            return result("WATCHING","Bobber found. Checking steady position",match.score,false);
        }

        // WATCHING never starts with a wide search. A weak match during a dip is
        // evidence to assess at the last resting position, not permission to move it.
        Match match=findNearby(image,trackedX,trackedY); observed=match; searchSource="nearby only";
        Shape shape=restingChange(image);
        localError=shape.error; changedFraction=shape.fraction;
        downwardPixels=match.y-baselineY;
        // Pixel changes alone include ordinary shimmer and lighting. Require a
        // simultaneous loss of the resting appearance, relative to this cast.
        boolean appearanceLost=match.score<restingMatch-.08 || match.score<.82;
        boolean changed=appearanceLost && shape.error>=Math.max(7.0,quietError*3+3) && shape.fraction>=.18;
        boolean dipped=downwardPixels>=1.5 && downwardPixels<=8 && match.score>=.65;
        boolean evidence=changed || dipped;
        if(!armed) {
            // Four acquisition frames plus a short steady check, not an open-ended
            // training period. A cast rising to the surface must settle first.
            if(!evidence && match.score>=ACQUIRE_SCORE && Math.abs(downwardPixels)<=1) {
                quietFrames++;
                if(quietFrames>=3 && nowMs-restSince>=150) armed=true;
                followRest(image,match,shape);
            } else {
                quietFrames=0; restSince=nowMs;
                if(match.score>=ACQUIRE_SCORE && !changed) {
                    commitPosition(match); establishRest(image,match,nowMs);
                } else if(nowMs-settleStartedMs>=1000) {
                    enterSearch(); reason="SETTLING_LOST";
                    return result("SEARCHING","Locating a settled bobber",match.score,false);
                }
            }
            reason=armed ? "ARMED" : "SETTLING";
            return result("WATCHING",armed ? "Watching bobber" : "Waiting for bobber to settle",match.score,false);
        }

        if(evidence || pendingSince>=0) {
            if(pendingSince<0) { pendingSince=nowMs; pendingEvidence=0; pendingClearFrames=0; }
            // Only a fully verified, exceptionally strong remote object can prove
            // relocation. Ordinary water lookalikes cannot replace pending evidence.
            if(changed && match.score<.80 && wideDue(nowMs)) {
                Match remote=wideMatch(image,nowMs);
                searchSource="nearby; relocation checked";
                if(remote.score>=.94 && remote.score>match.score+.12 &&
                        (Math.abs(remote.x-trackedX)>LOCAL_X+2 || Math.abs(remote.y-trackedY)>LOCAL_Y+2)) {
                    enterSearch(); acquisitionX=remote.x; acquisitionY=remote.y;
                    observed=remote; searchSource="verified relocation"; reason="RELOCATED";
                    return result("SEARCHING","Locating moved bobber",remote.score,false);
                }
            }
            long age=nowMs-pendingSince;
            if(age>BITE_WINDOW_MS) {
                pendingSince=nowMs; pendingEvidence=0; pendingClearFrames=0;
            }
            pendingEvidence=((pendingEvidence<<1)|(evidence?1:0))&7;
            pendingClearFrames=evidence?0:pendingClearFrames+1;
            if(evidence && Integer.bitCount(pendingEvidence)>=2 && nowMs-pendingSince>=35) {
                waitingForRecast=true; armed=false;
                sawMissingBobber=false; returnFrames=0;
                rearmAfter=nowMs+900; fallbackRearmAfter=nowMs+2200;
                lastReturnX=match.x; lastReturnY=match.y;
                // Start recast recovery at the frozen resting anchor, not a weak dip candidate.
                acquisitionX=trackedX; acquisitionY=trackedY;
                reason=changed ? "LOCAL_CHANGE_CONFIRMED" : "LOCAL_DIP_CONFIRMED";
                return result("BITE","Bite detected - reel manually",match.score,true);
            }
            if(pendingClearFrames>=2) {
                pendingSince=-1; pendingEvidence=0;
                reason="DISTURBANCE_CLEARED";
                return result("WATCHING","Watching bobber",match.score,false);
            }
            reason=evidence ? "LOCAL_BITE_CHECK" : "LOCAL_BITE_GAP";
            return result("WATCHING","Checking possible bite",match.score,false);
        }
        followRest(image,match,shape);
        reason="RESTING_OR_DRIFTING";
        return result("WATCHING","Watching bobber",match.score,false);
    }

    /** A deliberate recovery cast must return and settle before a bite can fire. */
    public void expectCast(long nowMs) {
        waitingForRecast=true; searching=false; armed=false;
        sawMissingBobber=true; returnFrames=0; recoveryFrames=0;
        rearmAfter=nowMs+900; fallbackRearmAfter=nowMs+2200;
        lastReturnX=-1; lastReturnY=-1;
        acquisitionX=trackedX; acquisitionY=trackedY;
        pendingSince=-1; pendingEvidence=0; pendingClearFrames=0;
    }

    private void enterSearch() {
        searching=true; armed=false; recoveryFrames=0;
        acquisitionX=-1; acquisitionY=-1; pendingSince=-1; pendingEvidence=0;
    }

    private void establishRest(float[] image, Match match, long nowMs) {
        baselineY=match.y;
        restingPatch=new float[patchSize*patchSize];
        for(int y=0;y<patchSize;y++)
            System.arraycopy(image,(match.y+y)*width+match.x,restingPatch,y*patchSize,patchSize);
        restingMatch=match.score; quietError=1; restSince=nowMs; settleStartedMs=nowMs; quietFrames=0;
        armed=false; pendingSince=-1; pendingEvidence=0; pendingClearFrames=0;
        localError=0; changedFraction=0; downwardPixels=0;
    }

    private void followRest(float[] image, Match match, Shape shape) {
        if(match.score<ACQUIRE_SCORE || Math.abs(match.y-baselineY)>1 ||
                (shape.error>=7 && match.score<.90)) return;
        commitPosition(match);
        baselineY+=(match.y-baselineY)*.15;
        quietError=Math.min(4,quietError*.95+shape.error*.05);
        restingMatch=restingMatch*.95+match.score*.05;
        // The saved reference stays frozen. This single resting observation only
        // follows slow lighting changes, and is never updated during a possible bite.
        for(int y=0;y<patchSize;y++) for(int x=0;x<patchSize;x++) {
            int i=y*patchSize+x;
            if(referenceMask[i]) restingPatch[i]=restingPatch[i]*.90f+image[(match.y+y)*width+match.x+x]*.10f;
        }
    }

    private static final class Shape { double error=Double.MAX_VALUE, fraction; }
    private Shape restingChange(float[] image) {
        Shape best=new Shape(); int n=templateCount[1];
        int y=(int)Math.round(baselineY);
        // Align sideways drift only. Aligning vertically here would hide the dip.
        for(int x=Math.max(0,trackedX-LOCAL_X);x<=Math.min(width-patchSize,trackedX+LOCAL_X);x++) {
            double difference=0;
            for(int py=0;py<patchSize;py++)for(int px=0;px<patchSize;px++) {
                int i=py*patchSize+px;
                if(referenceMask[i]) difference+=image[(y+py)*width+x+px]-restingPatch[i];
            }
            double offset=difference/n, error=0; int changed=0;
            for(int py=0;py<patchSize;py++)for(int px=0;px<patchSize;px++) {
                int i=py*patchSize+px;
                if(!referenceMask[i])continue;
                double delta=Math.abs(image[(y+py)*width+x+px]-restingPatch[i]-offset);
                error+=delta; if(delta>=Math.max(12,quietError*3+6))changed++;
            }
            error/=n;
            if(error<best.error) { best.error=error; best.fraction=changed/(double)n; }
        }
        return best;
    }

    private Match findNearby(float[] image,int cx,int cy) {
        Match local=search(image,Math.max(0,cx-LOCAL_X),Math.min(width-patchSize,cx+LOCAL_X),
                Math.max(0,cy-LOCAL_Y),Math.min(height-patchSize,cy+LOCAL_Y),2,3);
        // Always check the exact trusted anchor, even when sparse peaks prefer water.
        double anchor=scoreAt(image,cx,cy,1);
        if(anchor>local.score) { local.score=anchor; local.x=cx; local.y=cy; }
        return local;
    }
    private boolean wideDue(long nowMs) { return lastWideSearch<0 || nowMs-lastWideSearch>=WIDE_INTERVAL_MS; }
    private Match wideMatch(float[] image,long nowMs) {
        lastWideSearch=nowMs; wideSearches++;
        return search(image,0,width-patchSize,0,height-patchSize,4,8);
    }

    private void commitPosition(Match match) {
        trackedX=match.x; trackedY=match.y; hasReliablePosition=true;
    }

    /** Explicit single-frame capture; monitoring never learns or replaces a reference. */
    public BobberReference captureReference(float[] image) {
        BobberReference reference = BobberReference.capture(image, width, height, patchSize);
        setReference(reference);
        return reference;
    }

    public void setReference(BobberReference reference) {
        if (reference.side != patchSize) throw new IllegalArgumentException("Reference size changed");
        template = reference.pixels();
        referenceMask = reference.mask();
        for (int step=1; step<=4; step*=2) {
            int n=0;
            double sum=0, squares=0;
            for (int y=0; y<patchSize; y+=step) for (int x=0; x<patchSize; x+=step) {
                int index=y*patchSize+x;
                if (!referenceMask[index]) continue;
                double value=template[index]; sum+=value; squares+=value*value; n++;
            }
            templateCount[step]=n;
            templateSum[step]=sum;
            templateEnergy[step]=n==0 ? 0 : squares-sum*sum/n;
            sampleOffsets[step]=new int[n]; sampleValues[step]=new float[n];
            int selected=0;
            for(int y=0;y<patchSize;y+=step)for(int x=0;x<patchSize;x+=step) {
                int index=y*patchSize+x;
                if(!referenceMask[index])continue;
                sampleOffsets[step][selected]=y*width+x;
                sampleValues[step][selected]=template[index]; selected++;
            }
            effectiveStep[step]=step>1 && (n<8 || templateEnergy[step]<1e-6) ? 1 : step;
        }
        trackedX=-1; trackedY=-1;
        baselineY=centerY;
        acquisitionX=-1; acquisitionY=-1; hasReliablePosition=false;
        lastWideSearch=-1; wideSearches=0; observed=null;
        reason="REFERENCE_LOADED"; searchSource="none";
        waitingForRecast=false; sawMissingBobber=false;
        returnFrames=0; recoveryFrames=0;
        // Locate the saved appearance; this does not modify or learn the reference.
        searching=true; armed=false; restingPatch=null; pendingSince=-1;
        lastProcessTime=-1; quietFrames=0; pendingEvidence=0; pendingClearFrames=0;
        localError=0; changedFraction=0; downwardPixels=0; restingMatch=0;
    }

    private static final class Match {
        int x, y;
        double score = -1;
    }

    private Match find(float[] image, long nowMs) {
        int cx = acquisitionX >= 0 && (searching || waitingForRecast) ? acquisitionX : trackedX >= 0 ? trackedX : centerX;
        int cy = acquisitionY >= 0 && (searching || waitingForRecast) ? acquisitionY : trackedY >= 0 ? trackedY : centerY;
        Match local = search(image, Math.max(0,cx-LOCAL_X), Math.min(width-patchSize,cx+LOCAL_X),
                Math.max(0,cy-LOCAL_Y), Math.min(height-patchSize,cy+LOCAL_Y), 2, 3);
        searchSource="nearby";
        // An unconfirmed recast candidate can drift onto similar water pixels. Keep checking
        // the confirmed neighbourhood instead of letting that candidate permanently move the search.
        if (waitingForRecast && hasReliablePosition && local.score < .90 &&
                (Math.abs(cx-trackedX)>2 || Math.abs(cy-trackedY)>2)) {
            Match anchor=search(image,Math.max(0,trackedX-LOCAL_X),Math.min(width-patchSize,trackedX+LOCAL_X),
                    Math.max(0,trackedY-LOCAL_Y),Math.min(height-patchSize,trackedY+LOCAL_Y),2,3);
            if (anchor.score>local.score) { local=anchor; searchSource="last reliable"; }
        }
        // Preserve the previous .74 nearby tracking threshold, but require .84 to acquire/rearm.
        double required = searching || waitingForRecast ? ACQUIRE_SCORE : .74;
        boolean firstLocation = !hasReliablePosition && acquisitionX < 0 && lastWideSearch < 0;
        // A passable but unsettled recast must not suppress wider verification forever.
        // Preserve the existing 200 ms budget; ordinary Watching keeps its fast nearby path.
        if (!firstLocation && local.score >= required && !waitingForRecast) return local;
        if (lastWideSearch >= 0 && nowMs-lastWideSearch < WIDE_INTERVAL_MS) {
            searchSource+="; wide cooldown";
            return local;
        }
        lastWideSearch=nowMs; wideSearches++;
        Match wide=search(image,0,width-patchSize,0,height-patchSize,4,8);
        if (wide.score > local.score) { searchSource="wide"; return wide; }
        searchSource+="; wide checked";
        return local;
    }

    /** Fully verify several separated peaks; a sparse-grid lookalike must not hide the real object. */
    private Match search(float[] image, int minX, int maxX, int minY, int maxY, int sampleStep, int peaks) {
        int columns=maxX-minX+1, rows=maxY-minY+1, count=columns*rows;
        int index=0;
        for (int y=minY;y<=maxY;y++) for(int x=minX;x<=maxX;x++)
            candidateScores[index++]=(float)scoreAt(image,x,y,sampleStep);
        Match best=new Match();
        for(int peak=0;peak<peaks;peak++) {
            int chosen=-1;
            float maximum=-Float.MAX_VALUE;
            for(int i=0;i<count;i++) if(candidateScores[i]>maximum) { maximum=candidateScores[i];chosen=i; }
            if(chosen<0 || maximum<=-1) break;
            int column=chosen%columns, row=chosen/columns;
            Match coarse=new Match();coarse.x=minX+column;coarse.y=minY+row;
            Match full=refine(image,coarse);
            if(full.score>best.score)best=full;
            // Adjacent pixels describe the same peak; spend verification on other positions too.
            for(int y=Math.max(0,row-3);y<=Math.min(rows-1,row+3);y++)
                for(int x=Math.max(0,column-3);x<=Math.min(columns-1,column+3);x++)
                    candidateScores[y*columns+x]=-Float.MAX_VALUE;
        }
        return best;
    }

    private Match refine(float[] image, Match candidate) {
        Match best=new Match();
        best.x=candidate.x;best.y=candidate.y;
        int coarseX = best.x, coarseY = best.y;
        for (int y = Math.max(0, coarseY - 2); y <= Math.min(height - patchSize, coarseY + 2); y++)
            for (int x = Math.max(0, coarseX - 2); x <= Math.min(width - patchSize, coarseX + 2); x++) {
                double score = scoreAt(image, x, y, 1);
                if (score > best.score) { best.score = score; best.x = x; best.y = y; }
            }
        return best;
    }

    private double scoreAt(float[] image, int left, int top, int requestedStep) {
        int step=effectiveStep[requestedStep];
        int[] offsets=sampleOffsets[step];
        float[] values=sampleValues[step];
        int n=offsets.length;
        if(n<8)return -1;
        double sum=0, energy=0, dot=0;
        int origin=top*width+left;
        for(int i=0;i<n;i++) {
            double value=image[origin+offsets[i]];
            sum+=value; energy+=value*value; dot+=value*values[i];
        }
        double denominator=Math.sqrt(Math.max(0,energy-sum*sum/n)*templateEnergy[step]);
        return denominator<1e-6 ? -1 : (dot-sum*templateSum[step]/n)/denominator;
    }

    private Result result(String phase, String message, double confidence, boolean bite) {
        Result out=new Result(phase, message, (float)Math.max(0, Math.min(1, confidence)), bite,
                observed==null || observed.score<0 ? -1 : observed.x, observed==null || observed.score<0 ? -1 : observed.y,
                trackedX, trackedY, patchSize, reason, searchSource, hasReliablePosition, wideSearches);
        out.localError=localError; out.changedFraction=changedFraction; out.restMatch=restingMatch;
        out.downwardPixels=downwardPixels; out.pendingMs=pendingSince<0?0:lastProcessTime-pendingSince;
        out.armed=armed;
        return out;
    }
}
