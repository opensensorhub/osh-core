/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2018 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.h2.mvstore;

import java.util.AbstractMap.SimpleEntry;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import org.h2.mvstore.rtree.SpatialKey;


/**
 * <p>
 * Improved RTreeCursor so we don't have to fetch values with a separate call
 * to map.get().
 * </p>
 * 
 * @param <V> 
 *
 * @author Alex Robin
 * @since Jan 23, 2022
 */
public class RTreeEntryCursor<V> implements Iterator<Entry<SpatialKey, V>> {

    private final SpatialKey filter;
    private CursorPos pos;
    private SpatialKey currentKey, lastKey;
    private V currentVal, lastVal;
    private final Page root;
    private boolean initialized;

    protected RTreeEntryCursor(Page root, SpatialKey filter) {
        this.root = root;
        this.filter = filter;
    }


    public boolean hasNext() {
        if (!initialized) {
            // init
            pos = new CursorPos(root, 0, null);
            fetchNext();
            initialized = true;
        }
        return currentKey != null;
    }

    /**
     * Skip over that many entries. This method is relatively fast (for this
     * map implementation) even if many entries need to be skipped.
     *
     * @param n the number of entries to skip
     */
    public void skip(long n) {
        while (hasNext() && n-- > 0) {
            fetchNext();
        }
    }

    @Override
    public Entry<SpatialKey, V> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        lastKey = currentKey;
        lastVal = currentVal;
        fetchNext();
        return new SimpleEntry<>(lastKey, lastVal);
    }

    @Override
    public void remove() {
        throw DataUtils.newUnsupportedOperationException(
                "Removing is not supported");
    }

    /**
     * Fetch the next entry if there is one.
     */
    @SuppressWarnings("unchecked")
    protected void fetchNext() {
        while (pos != null) {
            Page p = pos.page;
            if (p.isLeaf()) {
                while (pos.index < p.getKeyCount()) {
                    int index = pos.index++;
                    SpatialKey c = (SpatialKey) p.getKey(index);
                    if (filter == null || check(true, c, filter)) {
                        currentKey = c;
                        currentVal = (V)p.getValue(index);
                        return;
                    }
                }
            } else {
                boolean found = false;
                while (pos.index < p.getKeyCount()) {
                    int index = pos.index++;
                    SpatialKey c = (SpatialKey) p.getKey(index);
                    if (filter == null || check(false, c, filter)) {
                        Page child = pos.page.getChildPage(index);
                        pos = new CursorPos(child, 0, pos);
                        found = true;
                        break;
                    }
                }
                if (found) {
                    continue;
                }
            }
            // parent
            pos = pos.parent;
        }
        currentKey = null;
    }

    /**
     * Check a given key.
     *
     * @param leaf if the key is from a leaf page
     * @param key the stored key
     * @param test the user-supplied test key
     * @return true if there is a match
     */
    @SuppressWarnings("unused")
    protected boolean check(boolean leaf, SpatialKey key, SpatialKey test) {
        return true;
    }

}
