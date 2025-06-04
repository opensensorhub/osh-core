/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.h2.mvstore;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.h2.mvstore.MVRadixTreeMap.SearchContext;
import org.vast.util.Asserts;


public class RadixTreeCursor<K, V> implements Iterator<K>
{
    private final MVRadixTreeMap<K, ?> map;
    private final SearchContext context;
    private final K from;
    private K currentKey, nextKey;
    private boolean initialized;
    private boolean allValues;
    private Cursor<K, V> valueCursor;


    RadixTreeCursor(MVRadixTreeMap<K, ?> map, Page root, K from)
    {
        this.map = map;
        this.from = from;
        this.context = new SearchContext();
    }
    
    
    RadixTreeCursor(MVRadixTreeMap<K, ?> map, Page root, K from, boolean allValues)
    {
        this(map, root, from);
        this.allValues = allValues;
    }


    @Override
    public boolean hasNext()
    {
        if (nextKey == null)
            fetchNext();
        
        return nextKey != null;
    }


    @Override
    public K next()
    {
        if (nextKey == null && !hasNext())
            throw new NoSuchElementException();
            
        currentKey = nextKey;
        nextKey = null;
        return currentKey;
    }


    public K getKey()
    {
        return currentKey;
    }


    public V getValue()
    {
        Asserts.checkNotNull(currentKey);
        
        if (valueCursor == null)
            return null;
        else
            return valueCursor.getValue();
    }


    @Override
    public void remove()
    {
        map.remove(getKey());
    }


    private void fetchNext()
    {
        // get next value if any
        if (valueCursor != null && valueCursor.hasNext())
        {
            valueCursor.next();
            nextKey = currentKey;
            return;
        }
        
        // else go to next key
        K key = null;
        if (!initialized)
        {
            key = map.getPreviousOrNext(from, false, false, context);
            initialized = true;
        }
        else
        {
            if (map.getPreviousOrNext(context, false))
                key = map.buildFullKey(context);
        }
            
        if (key != null)
        {
            context.offset = 0;
            map.comparePrefix(key, from, context);
            if (context.fullNodeMatch)
            {
                nextKey = key;
                setValueCursor();
            }
        }
    }
    
    
    @SuppressWarnings("unchecked")
    private void setValueCursor()
    {
        if (allValues)
        {
            Page selectedPage = context.getSelectedPage();
            Page valuePage = map.getValuePage(selectedPage);
            valueCursor = new Cursor<>(valuePage, (K)valuePage.getKey(0));
            if (valueCursor.hasNext())
                valueCursor.next();
        }
    }
    
    
    public Stream<K> keyStream()
    {
        allValues = false;        
        Spliterator<K> it = Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED | Spliterator.DISTINCT);
        return StreamSupport.stream(it, false);
    }
    
    
    /**
     * @return The stream of selected values.
     * (The returned stream can contain duplicated elements; call its distinct()
     * method if duplicates cannot be handled by client)
     */
    public Stream<V> valueStream()
    {
        Spliterator<V> it = Spliterators.spliteratorUnknownSize(new Iterator<V>() {
            @Override
            public boolean hasNext()
            {
                return RadixTreeCursor.this.hasNext();
            }

            @Override
            public V next()
            {
                RadixTreeCursor.this.next();
                return getValue();
            }            
        }, Spliterator.ORDERED);

        return StreamSupport.stream(it, false);
    }

}