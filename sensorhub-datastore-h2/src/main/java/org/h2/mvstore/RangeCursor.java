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
import org.sensorhub.impl.datastore.IteratorWrapper;


/**
 * <p>
 * Custom MVMap cursor adding support for an end key to stop iteration.
 * </p>
 *
 * @author Alex Robin
 * @param <K> Key Type
 * @param <V> Value Type
 * @since Oct 25, 2016
 */
public class RangeCursor<K, V> extends IteratorWrapper<K, K>
{
    MVMap<K, V> map;
    K to;
    
    
    public RangeCursor(MVMap<K, V> map, K from)
    {
        this(map, from, null);
    }
    
    
    public RangeCursor(MVMap<K, V> map, K from, K to)
    {
        super(map.cursor(from));
        this.map = map;
        this.to = to;
    }
    
    
    @Override
    protected void preloadNext()
    {
        next = null;
        
        if (it.hasNext())
        {
            next = it.next();
            if (to != null && map.getKeyType().compare(next, to) > 0)
                next = null;
        }
    }
    
    
    @SuppressWarnings("unchecked")
    public K getKey()
    {
        return ((Cursor<K, V>)it).getKey();
    }
    
    
    @SuppressWarnings("unchecked")
    public V getValue()
    {
        return ((Cursor<K, V>)it).getValue();
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


    @Override
    protected K process(K elt)
    {
        return elt;
    }

}
