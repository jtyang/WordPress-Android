package org.wordpress.android.ui.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.ListView;

class ReaderAnim {

    static enum Duration {
        SHORT,
        MEDIUM,
        LONG;

        private long toMillis(Context context) {
            switch (this) {
                case LONG:
                    return context.getResources().getInteger(android.R.integer.config_longAnimTime);
                case MEDIUM:
                    return context.getResources().getInteger(android.R.integer.config_mediumAnimTime);
                default:
                    return context.getResources().getInteger(android.R.integer.config_shortAnimTime);
            }
        }
    }

    private static ObjectAnimator getFadeInAnim(final View target, Duration duration) {
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(target, View.ALPHA, 0.0f, 1.0f);
        fadeIn.setDuration(duration.toMillis(target.getContext()));
        fadeIn.setInterpolator(new LinearInterpolator());
        fadeIn.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                target.setVisibility(View.VISIBLE);
            }
        });
        return fadeIn;
    }

    private static ObjectAnimator getFadeOutAnim(final View target, Duration duration) {
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(target, View.ALPHA, 1.0f, 0.0f);
        fadeOut.setDuration(duration.toMillis(target.getContext()));
        fadeOut.setInterpolator(new LinearInterpolator());
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                target.setVisibility(View.GONE);
            }
        });
        return fadeOut;
    }

    static void fadeIn(final View target, Duration duration) {
        if (target == null || duration == null) {
            return;
        }
        getFadeInAnim(target, duration).start();
    }

    static void fadeInFadeOut(final View target, Duration duration) {
        if (target == null || duration == null) {
            return;
        }

        ObjectAnimator fadeIn = getFadeInAnim(target, duration);
        ObjectAnimator fadeOut = getFadeOutAnim(target, duration);

        // keep view visible for 1/2 duration before fading it out
        long durationMillis = duration.toMillis(target.getContext());
        fadeOut.setStartDelay(durationMillis / 2);

        AnimatorSet set = new AnimatorSet();
        set.play(fadeOut).after(fadeIn);
        set.start();
    }

    static void animateListItemRemoval(ListView listView,
                                       int positionAbsolute,
                                       Animation.AnimationListener listener,
                                       int animResId) {
        if (listView == null) {
            return;
        }

        // passed value is the absolute position of this item, convert to relative or else we'll
        // remove the wrong item if list is scrolled
        int firstVisible = listView.getFirstVisiblePosition();
        int positionRelative = positionAbsolute - firstVisible;

        View listItem = listView.getChildAt(positionRelative);
        if (listItem == null) {
            return;
        }

        Animation animation = AnimationUtils.loadAnimation(listView.getContext(), animResId);
        if (listener != null) {
            animation.setAnimationListener(listener);
        }

        listItem.startAnimation(animation);
    }

}