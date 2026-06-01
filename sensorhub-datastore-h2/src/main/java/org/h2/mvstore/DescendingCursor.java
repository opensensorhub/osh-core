/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Inc. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.h2.mvstore;

import java.util.Iterator;
import java.util.NoSuchElementException;


/**
 * A cursor to iterate over elements in ascending order.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class DescendingCursor<K, V> implements Iterator<K> {
    private final K to;
    private CursorPos cursorPos;
    private CursorPos keeper;
    private K current;
    private K last;
    private V lastValue;
    private Page lastPage;

    public DescendingCursor(Page root, K from) {
        this(root, from, null);
    }

    public DescendingCursor(Page root, K from, K to) {
        this.cursorPos = traverseDown(root, from);
        this.to = to;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean hasNext() {
        if (cursorPos != null) {
            while (current == null) {
                Page page = cursorPos.page;
                int index = cursorPos.index;
                if (index < 0) {
                    CursorPos tmp = cursorPos;
                    cursorPos = cursorPos.parent;
                    tmp.parent = keeper;
                    keeper = tmp;
                    if(cursorPos == null)
                    {
                        return false;
                    }
                } else {
                    while (!page.isLeaf()) {
                        page = page.getChildPage(index);
                        int numKeys = page.isLeaf() ? page.getKeyCount() : page.map.getChildPageCount(page);
                        if (keeper == null) {
                            cursorPos = new CursorPos(page, numKeys-1, cursorPos);
                        } else {
                            CursorPos tmp = keeper;
                            keeper = keeper.parent;
                            tmp.parent = cursorPos;
                            tmp.page = page;
                            tmp.index = numKeys-1;
                            cursorPos = tmp;
                        }
                        index = numKeys-1;
                    }
                    if (index >= 0) {
                        K key = (K) page.getKey(index);
                        if (to != null && page.map.getKeyType().compare(key, to) < 0) {
                            return false;
                        }
                        current = last = key;
                        lastValue = (V) page.getValue(index);
                        lastPage = page;
                    }
                }
                --cursorPos.index;
            }
        }
        return current != null;
    }

    @Override
    public K next() {
        if(!hasNext()) {
            throw new NoSuchElementException();
        }
        current = null;
        return last;
    }

    /**
     * Get the last read key if there was one.
     *
     * @return the key or null
     */
    public K getKey() {
        return last;
    }

    /**
     * Get the last read value if there was one.
     *
     * @return the value or null
     */
    public V getValue() {
        return lastValue;
    }

    /**
     * Get the page where last retrieved key is located.
     *
     * @return the page
     */
    Page getPage() {
        return lastPage;
    }

    /**
     * Skip over that many entries. This method is relatively fast (for this map
     * implementation) even if many entries need to be skipped.
     *
     * @param n the number of entries to skip
     */
    public void skip(long n) {
        if (n < 10) {
            while (n-- > 0 && hasNext()) {
                next();
            }
        } else if(hasNext()) {
            assert cursorPos != null;
            CursorPos cp = cursorPos;
            CursorPos parent;
            while ((parent = cp.parent) != null) cp = parent;
            Page root = cp.page;
            @SuppressWarnings("unchecked")
            MVMap<K, ?> map = (MVMap<K, ?>) root.map;
            long index = map.getKeyIndex(next());
            last = map.getKey(index + n);
            this.cursorPos = traverseDown(root, last);
        }
    }

    @Override
    public void remove() {
        throw DataUtils.newUnsupportedOperationException(
                "Removal is not supported");
    }

    /**
     * Fetch the next entry that is equal or larger than the given key, starting
     * from the given page. This method retains the stack.
     *
     * @param p the page to start from
     * @param key the key to search, null means search for the first key
     */
    private static CursorPos traverseDown(Page p, Object key) {
        CursorPos cursorPos = key == null ? p.getPrependCursorPos(null) : CursorPos.traverseDown(p, key);
        if (cursorPos.index < 0) {
            cursorPos.index = -cursorPos.index - 1;
            // select previous instead of next
            cursorPos.index--;
        }
        return cursorPos;
    }
}

