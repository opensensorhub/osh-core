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
import org.h2.mvstore.type.ObjectDataType;


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

    protected MVBTreeMap(Map<String, Object> config)
    {
        super(config);
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
    private K getFullKey(Page p, Object key)
    {
        int x = p.binarySearch(key);
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
            return (K)p.getKey(x);
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
    private Entry<K, V> getEntry(Page p, Object key)
    {
        int x = p.binarySearch(key);
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
            return new SimpleEntry<>((K)p.getKey(x), (V)p.getValue(x));
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
    @SuppressWarnings("unchecked")
    private Entry<K, V> getMinMaxEntry(Page p, K key, boolean min, boolean excluding) {
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
            return new SimpleEntry<>((K)p.getKey(x), (V)p.getValue(x));
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
    public static class Builder<K, V> implements MapBuilder<MVBTreeMap<K, V>, K, V>
    {
        protected DataType keyType;
        protected DataType valueType;


        public Builder<K, V> keyType(DataType keyType)
        {
            this.keyType = keyType;
            return this;
        }

        public Builder<K, V> valueType(DataType valueType)
        {
            this.valueType = valueType;
            return this;
        }

        @Override
        public DataType getKeyType()
        {
            return keyType;
        }

        @Override
        public DataType getValueType()
        {
            return valueType;
        }

        @Override
        public void setKeyType(DataType dataType)
        {
            this.keyType = dataType;
        }

        @Override
        public void setValueType(DataType dataType)
        {
            this.valueType = dataType;
        }

        @Override
        public MVBTreeMap<K, V> create(MVStore store, Map<String, Object> config)
        {
            if (keyType == null)
                keyType = new ObjectDataType();

            if (valueType == null)
                valueType = new ObjectDataType();
            
            config.put("store", store);
            config.put("key", keyType);
            config.put("val", valueType);

            return new MVBTreeMap<>(config);
        }
    }
}
