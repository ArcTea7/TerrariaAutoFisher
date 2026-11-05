package com.example.terrariaautofisher;

/** Fresh, unobstructed missing-target observations authorize one recovery tap.
 * The worker observes frames; the main thread claims a ticket before tapping.
 * No camera frames means no permission to tap. */
public final class MissingTargetRecovery {
    public static final long MISSING_MS = 5000;
    public static final long MAX_FRAME_GAP_MS = 1000;
    private long missingSince=-1, lastFrame=-1, serial, pending;

    public synchronized void reset() {
        missingSince=-1;lastFrame=-1;pending=0;serial++;
    }

    /** eligible is false during obstruction, pause, or automatic taps/settling. */
    public synchronized long observe(long now,boolean present,boolean eligible) {
        if(!eligible||present){reset();return 0;}
        if(lastFrame>=0&&(now<=lastFrame||now-lastFrame>MAX_FRAME_GAP_MS))reset();
        lastFrame=now;
        if(missingSince<0)missingSince=now;
        if(pending!=0||now-missingSince<MISSING_MS)return 0;
        pending=++serial;
        return pending;
    }

    /** Reject a queued request if a newer frame saw the target, reset the run,
     * or capture stopped producing fresh evidence before the main thread ran. */
    public synchronized boolean claim(long ticket,long now) {
        if(ticket==0||ticket!=pending)return false;
        boolean fresh=lastFrame>=0&&now>=lastFrame&&now-lastFrame<=MAX_FRAME_GAP_MS;
        reset();
        return fresh;
    }
}
