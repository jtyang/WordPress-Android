/**
 * One fragment to rule them all (Notes, that is)
 */
package org.wordpress.android.ui.notifications;

import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import org.wordpress.android.R;
import org.wordpress.android.models.Note;

public class NotificationsDetailListFragment extends ListFragment {
    private Note mNote;

    public NotificationsDetailListFragment(Note note) {
        mNote = note;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.notifications_follow_list, container, false);
    }

    @Override
    public void onActivityCreated(Bundle bundle) {
        super.onActivityCreated(bundle);

        ListView list = getListView();
        list.setDivider(getResources().getDrawable(R.drawable.list_divider));
        list.setDividerHeight(1);
        list.setHeaderDividersEnabled(false);
    }
}
