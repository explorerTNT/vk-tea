package com.vktea.documents;

import android.content.Context;
import java.util.List;

public interface DocumentsRepository extends AutoCloseable {
    interface Request { void cancel(); }
    interface Callback { void success(Page page); void error(Error error); }
    final class Page {
        public final List<DocumentItem> items;
        public final int total, consumed;
        public Page(List<DocumentItem> items, int total, int consumed) {
            this.items = items; this.total = total; this.consumed = consumed;
        }
    }
    final class Error {
        public enum Kind { AUTH, ACCESS, RATE_LIMIT, NETWORK, API }
        public final Kind kind;
        public Error(Kind kind) { this.kind = kind; }
    }
    static DocumentsRepository create(Context context) { return new VkDocumentsRepository(context.getApplicationContext()); }
    Request load(String query, int offset, Callback callback);
    @Override void close();
}
