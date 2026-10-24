package com.vktea.friends;

import android.content.Context;
import java.util.List;

/** Read-only complete-list loading. Callbacks run on main; cancellation suppresses delivery. */
public interface FriendsRepository extends AutoCloseable {
    interface Request { void cancel(); }
    interface Callback<T> { void success(T value); void error(FriendsError error); }
    static FriendsRepository create(Context context) { return new VkFriendsRepository(context.getApplicationContext()); }
    Request load(Callback<List<Friend>> callback);
    @Override void close();
}
