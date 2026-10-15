package com.volcano.bmusic.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Fallback handler for wired headset media buttons. */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (key == null || key.getAction() != KeyEvent.ACTION_UP) return;
        switch (key.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                PlaybackService act = PlaybackService.get();
                if (act == null) {
                    PlaybackService.open(context, lastId(context));
                } else {
                    PlaybackService.toggle(context);
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                PlaybackService.toggle(context);
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                PlaybackService.next(context);
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                PlaybackService.prev(context);
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                PlaybackService.stop(context);
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                PlaybackService.toggle(context);
                break;
            default:
                break;
        }
        if (isOrderedBroadcast()) abortBroadcast();
    }

    private String lastId(Context c) {
        return com.volcano.bmusic.App.prefs().getString("last_id", "");
    }
}
