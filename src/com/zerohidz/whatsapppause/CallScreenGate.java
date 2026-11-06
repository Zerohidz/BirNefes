package com.zerohidz.whatsapppause;

/** Call-related overlay and Home decisions. Holds telephony states only, never a number. */
public final class CallScreenGate {
    public static final int IDLE = 0, RINGING = 1, OFFHOOK = 2;
    private int callState = IDLE;
    private boolean recheck, answered, ended, restrictedBeneath, callScreenOnTop;

    /** True when ringing started or stopped, so the overlay must be rendered again. */
    public boolean onCallState(int state) {
        boolean changed = (callState == RINGING) != (state == RINGING);
        answered = callState == RINGING && state == OFFHOOK;
        ended = callState != IDLE && state == IDLE;
        if (ended) callScreenOnTop = false;
        callState = state;
        return changed;
    }
    /** The last state report answered a ringing call. */
    public boolean answered() { return answered; }
    /** The last state report ended every call. */
    public boolean ended() { return ended; }
    /** Tracks what sits under the call screen; call screen windows themselves are not reported. */
    public void window(boolean restricted) { restrictedBeneath = restricted; callScreenOnTop = false; }
    public void callScreenWindow() { callScreenOnTop = true; }
    /** An answer that opened no call screen leaves the restricted app in front during the call. */
    public boolean recheckAfterAnswer(boolean keyguardLocked) {
        return callState == OFFHOOK && restrictedBeneath && !callScreenOnTop && !keyguardLocked;
    }
    /** One-shot unless a new call began: a restricted app under the ended call needs a new check. */
    public boolean recheckAfterCall(boolean keyguardLocked, boolean callActive) {
        if (callActive) return false;
        boolean marked = restrictedBeneath;
        restrictedBeneath = false;
        return marked && !keyguardLocked;
    }
    /** Ringing only: OFFHOOK is not a general suspension of blocking. */
    public boolean suppressOverlay(boolean audioRinging) { return callState == RINGING || audioRinging; }
    public boolean callActive(boolean audioInCall) { return callState != IDLE || audioInCall; }
    public void screenOff(boolean gateActive) { recheck |= gateActive; }
    /** One-shot: consumed by the next screen-on whatever the outcome. */
    public boolean screenOn(boolean keyguardLocked, boolean callActive) {
        boolean marked = recheck;
        recheck = false;
        return marked && !keyguardLocked && !callActive;
    }
}
