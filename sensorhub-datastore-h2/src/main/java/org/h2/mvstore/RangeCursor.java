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

import java.util.AbstractMap.SimpleEntry;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


/**
 * <p>
 * Wrapper for native H2 Cursor and DescendingCursor
 * </p>
 *
 * @author Alex Robin
 * @param <K> Key Type
 * @param <V> Value Type
 * @since Oct 25, 2016
 */
public class RangeCursor<K, V> implements Iterator<K>
{
    final Iterator<K> cursor; 
    final MVMap<K, V> map;
    final boolean descending;
    
    
    public RangeCursor(MVMap<K, V> map, K from)
    {
        this(map, from, null);
    }
    
    
    public RangeCursor(MVMap<K, V> map, K from, K to)
    {
        this(map, from, to, false);
    }
    
    
    public RangeCursor(MVMap<K, V> map, K from, K to, boolean descending)
    {
        this.cursor = descending ?
            new DescendingCursor<K, V>(map.getRootPage(), from, to) :
            new Cursor<K, V>(map.getRootPage(), from, to);
        this.map = map;
        this.descending = descending;
    }
    
    
    @Override
    public boolean hasNext()
    {
        return cursor.hasNext();
    }
    
    
    @Override
    public K next()
    {
        return cursor.next();
    }
    
    
    @SuppressWarnings("unchecked")
    public K getKey()
    {
        return descending ?
            ((DescendingCursor<K, V>)cursor).getKey() :
            ((Cursor<K, V>)cursor).getKey();
    }
    
    
    @SuppressWarnings("unchecked")
    public V getValue()
    {
        return descending ?
            ((DescendingCursor<K, V>)cursor).getValue() :
            ((Cursor<K, V>)cursor).getValue();
    }
    
    
    public Spliterator<K> keyIterator()
    {
        return Spliterators.spliteratorUnknownSize(this, Spliterator.DISTINCT | Spliterator.ORDERED);
    }
    
    
    public Stream<K> keyStream()
    {
        return StreamSupport.stream(keyIterator(), false);
    }
    
    
    public Spliterator<V> valueIterator()
    {
        return Spliterators.spliteratorUnknownSize(new Iterator<V>() {
            @Override
            public boolean hasNext()
            {
                return RangeCursor.this.hasNext();
            }

            @Override
            public V next()
            {
                RangeCursor.this.next();
                return RangeCursor.this.getValue();
            }            
        }, Spliterator.DISTINCT | Spliterator.ORDERED);
    }
    
    
    public Stream<V> valueStream()
    {
        return StreamSupport.stream(valueIterator(), false);
    }
    
    
    public Spliterator<Entry<K, V>> entryIterator()
    {
        return Spliterators.spliteratorUnknownSize(new Iterator<Entry<K, V>>() {
            @Override
            public boolean hasNext()
            {
                return RangeCursor.this.hasNext();
            }

            @Override
            public Entry<K, V> next()
            {
                RangeCursor.this.next();
                return new SimpleEntry<>(getKey(), getValue());
            }            
        }, Spliterator.DISTINCT | Spliterator.ORDERED);
    }
    
    
    public Stream<Entry<K, V>> entryStream()
    {
        return StreamSupport.stream(entryIterator(), false);
    }
}
