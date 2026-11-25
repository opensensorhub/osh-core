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

import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.h2.mvstore.type.DataType;


/**
 * <p>
 * Modified version of H2 MVMap to add entry retrieval operations
 * </p>
 *
 * @author Alex Robin
 * @param <K> Key type
 * @param <V> Value type
 * @date Apr 19, 2018
 */
public class MVBTreeMap<K, V> extends MVMap<K, V>
{

    protected MVBTreeMap(Map<String, Object> config, DataType<K> keyType, DataType<V> valueType)
    {
        super(config, keyType, valueType);
    }


    protected MVBTreeMap(MVBTreeMap<K, V> source)
    {
        super(source);
    }


    @Override
    protected MVBTreeMap<K, V> cloneIt()
    {
        // H2 uses this hook to create read-only version snapshots.
        return new MVBTreeMap<>(this);
    }


    public K getFullKey(Object key)
    {
        return getFullKey(getRootPage(), key);
    }


    /*
     * Same code as binarySearch method but returning full key.
     * This can be used when a partial key is used for retrieval
     */
    @SuppressWarnings({ "unchecked" })
    private K getFullKey(Page<K, V> p, Object key)
    {
        int x = p.binarySearch((K)key);
        if (!p.isLeaf()) {
            if (x < 0) {
                x = -x - 1;
            } else {
                x++;
            }
            p = p.getChildPage(x);
            return getFullKey(p, key);
        }
        if (x >= 0) {
            return p.getKey(x);
        }
        return null;
    }


    public Entry<K, V> getEntry(Object key)
    {
        return getEntry(getRootPage(), key);
    }


    /*
     * Same code as binarySearch method but returning full entry
     */
    @SuppressWarnings({ "unchecked" })
    private Entry<K, V> getEntry(Page<K, V> p, Object key)
    {
        int x = p.binarySearch((K)key);
        if (!p.isLeaf()) {
            if (x < 0) {
                x = -x - 1;
            } else {
                x++;
            }
            p = p.getChildPage(x);
            return getEntry(p, key);
        }
        if (x >= 0) {
            return new SimpleEntry<>(p.getKey(x), p.getValue(x));
        }
        return null;
    }


    public Entry<K, V> ceilingEntry(K key)
    {
        return getMinMaxEntry(getRootPage(), key, false, false);
    }


    public Entry<K, V> higherEntry(K key)
    {
        return getMinMaxEntry(getRootPage(), key, false, true);
    }


    public Entry<K, V> floorEntry(K key)
    {
        return getMinMaxEntry(getRootPage(), key, true, false);
    }


    public Entry<K, V> lowerEntry(K key)
    {
        return getMinMaxEntry(getRootPage(), key, true, true);
    }
    
    
    /*
     * Same code as getMinMax method but returning full entry
     */
    private Entry<K, V> getMinMaxEntry(Page<K, V> p, K key, boolean min, boolean excluding) {
        int x = p.binarySearch(key);
        if (p.isLeaf()) {
            if (x < 0) {
                x = -x - (min ? 2 : 1);
            } else if (excluding) {
                x += min ? -1 : 1;
            }
            if (x < 0 || x >= p.getKeyCount()) {
                return null;
            }
            return new SimpleEntry<>(p.getKey(x), p.getValue(x));
        }
        if (x++ < 0) {
            x = -x;
        }
        while (true) {
            if (x < 0 || x >= getChildPageCount(p)) {
                return null;
            }
            Entry<K, V> entry = getMinMaxEntry(p.getChildPage(x), key, min, excluding);
            if (entry != null) {
                return entry;
            }
            x += min ? -1 : 1;
        }
    }
    
    
    public Stream<K> keyStream()
    {
        Spliterator<K> it = Spliterators.spliteratorUnknownSize(keySet().iterator(), Spliterator.DISTINCT | Spliterator.ORDERED);
        return StreamSupport.stream(it, false);
    }
    

    /**
     * A builder for this class.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class Builder<K, V> extends MVMap.BasicBuilder<MVBTreeMap<K, V>, K, V>
    {
        public Builder()
        {
        }


        @Override
        public Builder<K, V> keyType(DataType<? super K> keyType)
        {
            setKeyType(keyType);
            return this;
        }


        @Override
        public Builder<K, V> valueType(DataType<? super V> valueType)
        {
            setValueType(valueType);
            return this;
        }


        @Override
        protected MVBTreeMap<K, V> create(Map<String, Object> config)
        {
            Object type = config.get("type");
            if (type == null)
                return new MVBTreeMap<>(config, getKeyType(), getValueType());
            throw new IllegalArgumentException("Incompatible map type");
        }
    }
}
