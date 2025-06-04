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

import java.util.AbstractSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import org.h2.mvstore.Page.PageReference;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.ObjectDataType;
import org.vast.util.Asserts;


/**
 * <p>
 * Implementation of a radix tree for use as an H2 MVStore map.<br/>
 * The last child of a page is used to store the reference to a binary tree
 * that contains the values.
 * 
 * WARNING: This is NOT working since H2 MVStore version 1.4.198
 * </p>
 *
 * @author Alex Robin
 * @param <K> Type of key usable with this map
 * @param <V> Type of value usable with this map
 * @date Oct 9, 2018
 */
public class MVRadixTreeMap<K, V> extends MVMap<K, V>
{
    
    static class SearchNode
    {
        Page page;
        int childIndex;
        
        SearchNode(Page page, int childIndex)
        {
            this.page = page;
            this.childIndex = childIndex;
        }
        
        public String toString()
        {
            return new String((byte[])page.getKey(childIndex));
        }
    }
    
    /*
     * Class used to store compare and search results  
     */
    static class SearchContext
    {
        // current offset in full key where to start comparison with prefix
        int offset;
        
        // comparison result flag: 0 if prefix match, -1 if smaller, 1 if bigger
        int compare;
        
        // length of match between prefix and full key, starting at offset
        int matchLength;
        
        // length remaining in key for further matching
        int keyRemaining;
        
        // true if node key is present in its entirety in full key at offset
        boolean fullNodeMatch;
        
        // found index within page (negative if no exact match found)
        int index;
        
        // found page
        // this is the page attached to index if index>=0
        // otherwise it is the parent page
        Page page;
        
        // stacks containing info of each node along the path to the selected key
        Deque<SearchNode> nodeStack = new LinkedList<>();
        
        Page getSelectedPage()
        {
            if (!nodeStack.isEmpty())
            {
                SearchNode lastNode = nodeStack.peek();
                if (lastNode.childIndex < 0)
                    return lastNode.page;
                else
                    return lastNode.page.getChildPage(lastNode.childIndex);
            }
            else
                return page.getChildPage(index);
        }
        
        boolean isExactMatch()
        {
            return fullNodeMatch && index >= 0;
        }
    }


    MVRadixTreeMap(Map<String, Object> config)
    {
        super(config);
    }
    
    
    /*@Override
    void setRootPos(long rootPos, long version)
    {
        root = rootPos == 0 ? createEmptyNode(-1) : readPage(rootPos);
        root.setVersion(version);
    }*/
    
    
    @Override
    public V get(Page root, Object key)
    {
        Page valuePage = getValuePage(root, key);
        
        if (valuePage != null)
            return getFirstValue(valuePage);
        else
            return null;
    }
    
    
    /*
     * Look for value key in B-Tree pages
     */
    protected boolean containsValueKey(Page p, Object key) {
        int x = p.binarySearch(key);
        if (!p.isLeaf()) {
            if (x < 0) {
                x = -x - 1;
            } else {
                x++;
            }
            p = p.getChildPage(x);
            return containsValueKey(p, key);
        }
        if (x >= 0) {
            return true;
        }
        return false;
    }
    
    
    public boolean containsValue(Object key, V value)
    {
        Page valuePage = getValuePage(getRootPage(), key);
        if (valuePage == null)
            return false;
        
        return containsValueKey(valuePage, getValueKey(value));
    }
    
    
    protected Page getValuePage(Page root, Object key)
    {
        // use prefix search to look for matching key index and page
        SearchContext context = new SearchContext();
        prefixBinarySearch(root, key, context, false);
        
        // if exact match was found, return the first leaf value, if any
        if (context.isExactMatch())
            return getValuePage(context.getSelectedPage());
        else
            return null;
    }
    
    
    protected Page getValuePage(Page p)
    {
        // values are always in the last child page
        int valuesNodeIndex = p.getKeyCount();
        if (p.getCounts(valuesNodeIndex) > 0)
            return p.getChildPage(valuesNodeIndex);
        
        return null;
    }
    
    
    protected boolean hasValues(Page p)
    {
        // values are always in the last child page (after the last key)
        int valuesNodeIndex = p.getKeyCount();
        return (p.getCounts(valuesNodeIndex) > 0);
    }
    
    
    @SuppressWarnings("unchecked")
    protected V getFirstValue(Page valuePage)
    {
        while (!valuePage.isLeaf())
            valuePage = valuePage.getChildPage(0);
        
        if (valuePage.getKeyCount() == 0)
            return null;
        
        return (V)valuePage.getValue(0);
    }
    
    
    /*
     * Look recursively for the given prefix in the page tree
     */
    protected void prefixBinarySearch(Page p, Object key, SearchContext context, boolean recordStack)
    {
        checkKeyNotNull(key);
        
        int x = prefixBinarySearchInPage(p, key, context);
        context.index = x;
        context.page = p;
                
        // return if key cannot match anymore
        if (x < 0)
            return;
        
        // add result to context
        Object childPrefix = p.getKey(x);
        Page childPage = p.getChildPage(x);
                
        // record stack for traversal
        if (recordStack)
            context.nodeStack.push(new SearchNode(p, x));
        
        // stop here if done matching prefix
        if (context.keyRemaining == 0 || !context.fullNodeMatch)
            return;
                
        // increase offset for next prefix comparison
        context.offset = context.offset + getPrefixLength(childPrefix);
        prefixBinarySearch(childPage, key, context, recordStack);
    }


    /*
     * Find the closest key in the page using binary search
     * A positive result indicates an exact match with a key at the given index
     * A negative result gives the index where the key could be inserted, minus one
     */
    protected int prefixBinarySearchInPage(Page p, Object key, SearchContext context)
    {
        int low = 0;
        int high = p.getKeyCount() - 1;
        
        while (low <= high)
        {
            int x = (low + high) >>> 1;
            comparePrefix(key, p.getKey(x), context);
            if (context.compare > 0)
                low = x + 1;
            else if (context.compare < 0)
                high = x - 1;
            else
                return x;
        }
        
        return -(low + 1);
    }
    
    
    public long getChildCount(K key)
    {
        SearchContext context = new SearchContext();
        prefixBinarySearch(getRootPage(), key, context, false);
        if (context.keyRemaining == 0 && context.index >= 0)
            return context.page.getCounts(context.index);
        else
            return 0;
    }
    
    
    /*@Override
    protected K getFirstLast(Page root, boolean first)
    {
        SearchContext context = new SearchContext();
        if (getFirstLast(getRootPage(), first, true, context))
            return buildFullKey(context);
        else
            return null;
    }*/
    
    
    /*
     * Find first or last key in a tree branch starting from the given page
     */
    protected boolean getFirstLast(Page p, boolean first, boolean excludeSelf, SearchContext context)
    {
        boolean exclude = excludeSelf;
        
        while (p != null)
        {
            if (!exclude && (first || p.getKeyCount() == 0) && hasValues(p))
            {
                context.nodeStack.push(new SearchNode(p, -1));
                return true;
            }
            
            exclude = false;
            
            // stop here if no more children
            if (p.getKeyCount() == 0)
                return false;
            
            // get next child index to scan
            int childIndex = first ? 0 : p.getKeyCount()-1;
            
            // add to stack as we go down
            context.nodeStack.push(new SearchNode(p, childIndex));
            
            p = p.getChildPage(childIndex);
        }
        
        return false;
    }
    

    /*@Override
    protected K getMinMax(K key, boolean min, boolean excluding)
    {
        SearchContext context = new SearchContext();
        return getPreviousOrNext(key, min, excluding, context);
    }*/
    
    
    protected K getPreviousOrNext(K key, boolean previous, boolean excluding, SearchContext context)
    {
        // use prefix search to look for matching key index and page
        prefixBinarySearch(getRootPage(), key, context, true);
        boolean exactMatch = context.isExactMatch();
        boolean found = false;
        
        // exact match
        if (exactMatch)
        {
            Page selectedPage = context.getSelectedPage();
            
            // if next requested, we just go down
            if (!previous || !excluding && hasValues(selectedPage))
                found = getFirstLast(selectedPage, true, excluding, context);
            
            // else we need to search siblings or parents
            else
                found = getPreviousOrNext(context, previous);
        }
        
        // partial match
        else if (context.index >= 0)
        {
            Page selectedPage = context.getSelectedPage();
            
            // need to compare reminder of prefix to know what direction to take
            Object[] split = splitKey(context.page.getKey(context.index), context.matchLength);
            Object prefixEnd = split[1];
            
            if (context.keyRemaining > 0)
            {
                context.offset += context.matchLength;
                comparePrefix(key, prefixEnd, context);
            }
            
            // key is after prefix
            if (context.compare > 0 && previous)
                found = getFirstLast(selectedPage, false, false, context);
                
            // key is before prefix
            else if ((context.keyRemaining == 0 || context.compare < 0) && !previous)
                found = getFirstLast(selectedPage, true, false, context);
            
            else
                found = getPreviousOrNext(context, previous);
        }
        
        // else need to navigate the tree
        else
        {
            int index = -context.index + (previous ? -1 : -2);
            context.nodeStack.push(new SearchNode(context.page, index));
            found = getPreviousOrNext(context, previous);
        }
        
        // return full key if something found
        if (found)
            return buildFullKey(context);
        else
            return null;
    }
    
    
    /*
     * Navigate the tree to find next or previous key
     */
    protected boolean getPreviousOrNext(SearchContext context, boolean previous)
    {
        SearchNode node = context.nodeStack.peek();
        if (node == null)
            return false;
        
        Page p = node.page;
        int nextIndex = previous ? node.childIndex-1 : node.childIndex+1;
        
        if (nextIndex >= 0 && nextIndex < p.getKeyCount())
        {
            node.childIndex = nextIndex;
            Page childPage = p.getChildPage(nextIndex);
            return getFirstLast(childPage, !previous, false, context);
        }        
        else if (previous && nextIndex == -1 && hasValues(p))
        {
            // select this node since it has values
            context.nodeStack.pop();
            return true;
        }
        else
        {
            if (!getPreviousOrNextPageUp(context, previous))
                return false;
            return getPreviousOrNext(context, previous);
        }
    }
    
    
    /*
     * Navigate up the tree until we find a branch going in the right direction.
     */
    protected boolean getPreviousOrNextPageUp(SearchContext context, boolean previous)
    {
        while (!context.nodeStack.isEmpty())
        {
            SearchNode node = context.nodeStack.peek();
            Page p = node.page;
            int nextIndex = previous ? node.childIndex-1 : node.childIndex+1;
            
            if (nextIndex >= 0 && nextIndex < p.getKeyCount())
                return true;
            else if (previous && nextIndex == -1 && hasValues(p))
                return true;
            
            context.nodeStack.pop();
        }
        
        return false;
    }
    
    
    @Override
    @SuppressWarnings("unchecked")
    public synchronized V put(K key, V value)
    {
        DataUtils.checkArgument(value != null, "The value may not be null");
        beforeWrite();
        RootReference rootReference = flushAndGetRoot();
        long v = rootReference.version;
        Page p = rootReference.root.copy();
        Object result = put(p, v, key, value, new SearchContext());
        rootReference.updateRootPage(p, 1);
        return (V) result;
    }


    protected Object put(Page p, long writeVersion, Object key, Object value, SearchContext context)
    {
        checkKeyNotNull(key);
        
        int index = prefixBinarySearchInPage(p, key, context);
        Page childPage;
                
        // no prefix match, need to create a new node
        if (index < 0)
        {
            index = -index - 1;
            childPage = createEmptyNode(writeVersion);
            addValue(childPage, value);
            
            Object keyPart = tailKey(key, context.offset);
            if (keyPart == null)
                return null;
            
            p.insertNode(index, keyPart, childPage);
            return null;
        }

        // else found prefix match
        else 
        {
            // if entire node key was matched
            if (context.fullNodeMatch)
            {
                // copy selected page because it's going to be modified
                childPage = p.getChildPage(index).copy();
                
                // if exact match, add value to this page
                // else if end of key still has to be processed, keep going down
                if (context.keyRemaining == 0)
                {
                    addValue(childPage, value);
                    p.setChild(index, childPage);
                    return null;
                }
                else
                {
                    // increase offset for next prefix comparison
                    context.offset = context.offset + getPrefixLength(p.getKey(index));
                    Object val = put(childPage, writeVersion, key, value, context);
                    p.setChild(index, childPage); // do this here to properly update total count                    
                    return val;
                }
            }
            
            // else need to split node key
            else
            {
                Page oldPage = p.getChildPage(index);
                Object[] split = splitKey(p.getKey(index), context.matchLength);
                Object prefixBegin = split[0];
                Object prefixEnd = split[1];
                
                // create new page to insert
                childPage = createEmptyNode(writeVersion);
                childPage.insertNode(0, prefixEnd, oldPage);
                
                // insert at selected index with shorter key
                p.setChild(index, childPage);
                p.setKey(index, prefixBegin);
                
                return put(p, writeVersion, key, value, context);
            }
        }
    }
    
    
    protected void addValue(Page p, Object value)
    {
        int valuesNodeIndex = p.getKeyCount(); // it's always the last child page
        long valueCount = p.getCounts(valuesNodeIndex);
        Page valuePage;
        
        // create the leaf page if needed
        if (valueCount == 0)
        {
            valuePage = Page.createLeaf(
                this,
                new Object[] {getValueKey(value)},
                new Object[] {value},
                0);

            p.setChild(valuesNodeIndex, valuePage);
        }
        else
        {
            valuePage = p.getChildPage(valuesNodeIndex).copy();
            
            // split if value page is still a leaf page and reached memory threshold
            // we need this because super.put doesn't handle splitting a top level leaf page
            if (valuePage.isLeaf() && valuePage.getMemory() > store.getPageSplitSize() && valuePage.getKeyCount() > 1)
            {
                long numValues = valuePage.getTotalCount();
                Page c1 = valuePage;
                
                int at = c1.getKeyCount() / 2;
                Object k = c1.getKey(at);
                Page c2 = c1.split(at);
                valuePage = Page.createNode(
                    this,
                    new Object[] {k},
                    new PageReference[] {
                        new PageReference(c1),
                        new PageReference(c2)
                    },
                    numValues, 0);
            }
            
            var valueKey = getValueKey(value);
            int index = valuePage.binarySearch(valueKey);
            valuePage.insertLeaf(-index - 1, valueKey, value);
            
            p.setChild(valuesNodeIndex, valuePage); // need to do that to properly update counts
        }
    }
    
    
    @Override
    @SuppressWarnings("unchecked")
    public V remove(final Object key)
    {
        return (V) remove(key, null, false);
    }
    
    
    public boolean remove(final Object key, final boolean removeSubKeys)
    {
        return (remove(key, null, removeSubKeys) != null);
    }
    
    
    @Override
    public synchronized boolean remove(final Object key, final Object value)
    {
        return (remove(key, value, false) != null);
    }
    
    
    protected synchronized Object remove(final Object key, final Object value, final boolean removeSubKeys) 
    {
        checkKeyNotNull(key);
        
        // use prefix search to look for matching key index and page
        SearchContext context = new SearchContext();
        prefixBinarySearch(getRootPage(), key, context, true);
        
        // continue only if exact match found
        if (context.isExactMatch())
        {
            beforeWrite();
            RootReference rootReference = flushAndGetRoot();
            long v = rootReference.version;
            
            synchronized (this)
            {
                Page p = context.getSelectedPage();
                Page newPage = null;
                Object oldValue = null;
                
                // remove only value if specified
                if (value != null && hasValues(p))
                {
                    newPage = p.copy();
                    Page newValuePage = getValuePage(newPage).copy();
                    int index = newValuePage.binarySearch(key);
                    oldValue = newValuePage.getValue(index);
                    newValuePage.remove(index);
                    newPage.setChild(newPage.getKeyCount(), newValuePage);
                }
                
                // or remove key and all subkeys
                else if (removeSubKeys)
                {
                    oldValue = Boolean.TRUE;
                    p.removeAllRecursive(v);
                }
                
                // otherwise remove all values for the selected key
                else if (p.getKeyCount() > 0)
                {
                    Page valuePage = getValuePage(p);
                    if (valuePage != null)
                    {
                        oldValue = getFirstValue(valuePage);
                        newPage = removeAllValues(p, v);
                    }
                }
                
                // copy and update parent pages all the way to root
                Page newChild = newPage;
                Page parent = null;
                for (SearchNode node: context.nodeStack)
                {
                    parent = node.page.copy();
                    if (newChild != null)
                        parent.setChild(node.childIndex, newChild);
                    else
                        parent.remove(node.childIndex);
                    newChild = parent;
                }
                
                rootReference.updateRootPage(parent, 1);
                return oldValue;
            }
        }
        
        return null;
    }
    
    
    protected Page removeAllValues(Page p, long version)
    {
        p = p.copy();
        
        Page valuePage = getValuePage(p);
        if (valuePage != null)
        {
            p.setChild(p.getKeyCount(), null);
            valuePage.removeAllRecursive(version);
        }
        
        return p;
    }
    
    
    /*@Override
    public Cursor<K, V> cursor(K from)
    {
        throw new UnsupportedOperationException("Use prefixCursor() or entryCursor() instead");
    }*/
    
    
    /**
     * Get a cursor that iterates through all keys starting at the 
     * given key or key prefix
     * @param startPrefix
     * @return the cursor object to iterate through keys
     */
    public RadixTreeCursor<K, V> prefixCursor(K startPrefix)
    {
        return new RadixTreeCursor<>(this, getRootPage(), startPrefix);
    }
    
    
    /**
     * Get a cursor that iterates through all entries associated to keys
     * starting at the given key or key prefix (a key can be reported multiple
     * times if several values are attached to it)
     * @param startPrefix
     * @return the cursor object to iterate through keys
     */
    public RadixTreeCursor<K, V> entryCursor(K startPrefix)
    {
        return new RadixTreeCursor<>(this, getRootPage(), startPrefix, true);
    }
    
    
    /*@Override
    public Set<Map.Entry<K, V>> entrySet()
    {
        return new AbstractSet<Entry<K, V>>()
        {
            @Override
            public Iterator<Entry<K, V>> iterator()
            {
                final RadixTreeCursor<K, V> cursor = entryCursor(firstKey());
                return new Iterator<Entry<K, V>>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return cursor.hasNext();
                    }

                    @Override
                    public Entry<K, V> next()
                    {
                        K k = cursor.next();
                        return new SimpleEntry<>(k, cursor.getValue());
                    }

                    @Override
                    public void remove()
                    {
                        cursor.remove();
                    }
                };
            }

            @Override
            public int size()
            {
                return MVRadixTreeMap.this.size();
            }

            @Override
            public boolean contains(Object o)
            {
                return MVRadixTreeMap.this.containsKey(o);
            }
        };
    }*/


    @Override
    public Set<K> keySet()
    {
        return new AbstractSet<K>()
        {
            @Override
            public Iterator<K> iterator()
            {
                return prefixCursor(firstKey());
            }

            @Override
            public int size()
            {
                return MVRadixTreeMap.this.size();
            }

            @Override
            public boolean contains(Object o)
            {
                return MVRadixTreeMap.this.containsKey(o);
            }
        };
    }


    protected Object[] splitKey(Object prefix, int splitIndex)
    {
        return ((RadixKeyDataType) getKeyType()).splitKey(prefix, splitIndex);
    }


    protected Object tailKey(Object key, int offset)
    {
        return ((RadixKeyDataType) getKeyType()).tailKey(key, offset);
    }
    
    
    protected int getPrefixLength(Object prefix)
    {
        return ((RadixKeyDataType)getKeyType()).getPrefixLength(prefix);
    }
    
    
    protected void comparePrefix(Object fullKey, Object prefix, SearchContext context)
    {
        ((RadixKeyDataType)getKeyType()).comparePrefix(fullKey, prefix, context);
    }
    
    
    @SuppressWarnings("unchecked")
    protected K buildFullKey(SearchContext context)
    {
        return (K) ((RadixKeyDataType)getKeyType()).buildFullKey(context);
    }
    
    
    protected Object getValueKey(Object value)
    {
        return ((RadixKeyDataType)getKeyType()).getValueKey(value);
    }
    
    
    protected Page createEmptyNode(long version)
    {
        return Page.createEmptyNode(this);
    }
    
    
    protected final void checkKeyNotNull(Object k)
    {
        Asserts.checkNotNull(k, "Key");
    }
    
    
    protected String keyAsString(Object key)
    {
        if (key instanceof String)
            return (String)key;
        else if (key instanceof byte[])
            return new String((byte[])key);
        else
            return key.toString();
    }
    
    
    public String asString()
    {
        StringBuilder buf = new StringBuilder();
        toString(getRootPage(), buf, 0);
        return buf.toString();
    }
    
    
    protected void toString(Page p, StringBuilder buf, int indent)
    {
        for (int i=0; i<p.getKeyCount(); i++)
        {
            for (int j=0; j<indent; j++)
                buf.append(' ');
            
            // print prefix
            buf.append(keyAsString(p.getKey(i)));
        
            // print number of values if any
            Page childPage = p.getChildPage(i);
            if (hasValues(childPage))
                toStringValuePage(getValuePage(childPage), buf, indent);
            buf.append('\n');
            
            // recursively print sub prefixes            
            toString(childPage, buf, indent+1);
        }
    }
    
    
    protected void toStringValuePage(Page p, StringBuilder buf, int indent)
    {
        buf.append(" (").append(p.getTotalCount()).append(" values, ");
        buf.append(countLeafPages(p)).append(" leaf pages)");
        
        buf.append('\n');
        for (int j=0; j<indent+1; j++)
            buf.append(' ');
        
        buf.append("values: ");
        printValues(p, buf);
        buf.setLength(buf.length()-2);
    }
    
    
    protected int countLeafPages(Page p)
    {
        if (p.isLeaf())
            return 1;
        
        int leafCount = 0;
        for (int i = 0; i < p.getRawChildPageCount(); i++)
        {
            Page c = p.getChildPage(i);
            leafCount += countLeafPages(c);
        }
        
        return leafCount;
    }
    
    
    protected void printValues(Page p, StringBuilder buf)
    {
        if (p.isLeaf())
        {
            for (int i = 0; i < p.getKeyCount(); i++)
                buf.append(p.getValue(i)).append(", ");
        }
        else
        {
            for (int i = 0; i < 1/*p.getRawChildPageCount()*/; i++)
            {
                Page c = p.getChildPage(i);
                printValues(c, buf);
            }
            
            buf.append("... ");
        }
    }
    

    /**
     * A builder for this class.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class Builder<K, V> implements MapBuilder<MVRadixTreeMap<K, V>, K, V>
    {
        protected RadixKeyDataType keyType;
        protected DataType valueType;


        public Builder<K, V> keyType(RadixKeyDataType keyType)
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
            this.keyType = (RadixKeyDataType)dataType;
        }

        @Override
        public void setValueType(DataType dataType)
        {
            this.valueType = dataType;
        }

        @Override
        public MVRadixTreeMap<K, V> create(MVStore store, Map<String, Object> config)
        {
            Asserts.checkNotNull(keyType, RadixKeyDataType.class);

            if (valueType == null)
                valueType = new ObjectDataType();
            
            config.put("store", store);
            config.put("key", keyType);
            config.put("val", valueType);

            return new MVRadixTreeMap<>(config);
        }
    }

}
