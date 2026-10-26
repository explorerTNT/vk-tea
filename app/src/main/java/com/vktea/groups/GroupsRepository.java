package com.vktea.groups;

import android.content.Context;
import java.util.List;

/** Read-only complete-list loading. Callbacks run on main; cancellation suppresses delivery. */
public interface GroupsRepository extends AutoCloseable {
    interface Request { void cancel(); }
    interface Callback<T> { void success(T value); void error(GroupsError error); }
    static GroupsRepository create(Context context) { return new VkGroupsRepository(context.getApplicationContext()); }
    Request load(Callback<List<Group>> callback);
    @Override void close();
}
