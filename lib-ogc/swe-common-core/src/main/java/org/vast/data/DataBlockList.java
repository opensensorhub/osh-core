/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.data;

import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataType;


/**
 * <p>
 * Uses the composite pattern to hold a list of child DataBlocks.
 * This class also implements the DataBlock interface. 
 * </p>
 *
 * @author Alex Robin
 * */
public class DataBlockList extends AbstractDataBlock
{
    private static final long serialVersionUID = -413032909256132305L;
    protected List<DataBlock> blockList; // either ArrayList or LinkedList so it's serializable
	protected int blockAtomCount = -1;
	protected boolean equalBlockSize;
	transient ThreadLocal<CachedIndex> cachedIndex = new ThreadLocal<>();
	
	static class CachedIndex
	{
	    int lastIndex; // last requested index
	    int cumulIndex;
	    int blockIndex;
	    int localIndex;
	}
    
    
	private DataBlockList()
	{
	}
	
	
    public DataBlockList(boolean equalItemSize)
    {
    	this(1, false, equalItemSize);
    }
    
    
    public DataBlockList(int listSize, boolean equalItemSize)
    {
        this(listSize, true, equalItemSize);
    }
    
    
    public DataBlockList(int listSize, boolean useArrayList, boolean equalItemSize)
    {
    	if (useArrayList)
    		this.blockList = new ArrayList<>(listSize);
    	else
    		this.blockList = new LinkedList<>();
    	
    	this.equalBlockSize = equalItemSize;
    }
    
    
    @Override
    public DataBlockList copy()
    {
        DataBlockList newBlock = new DataBlockList();
        newBlock.startIndex = this.startIndex;
        newBlock.blockAtomCount = this.blockAtomCount;
        newBlock.equalBlockSize = this.equalBlockSize;
        newBlock.blockList = this.blockList;
        newBlock.atomCount = this.atomCount;
        return newBlock;
    }
    
    
    @Override
    public DataBlockList renew()
    {
        DataBlockList newBlock = new DataBlockList();
        newBlock.startIndex = this.startIndex;
        newBlock.blockAtomCount = this.blockAtomCount;
        newBlock.equalBlockSize = this.equalBlockSize;
        newBlock.blockList = new LinkedList<>();
        
        // renew all blocks in the list
        Iterator<DataBlock> it = this.blockList.iterator();
        while (it.hasNext())
            newBlock.add(it.next().renew());
        
        return newBlock;
    }
    
    
    @Override
    public DataBlockList clone()
    {
        DataBlockList newBlock = new DataBlockList();
        newBlock.startIndex = this.startIndex;
        newBlock.blockAtomCount = this.blockAtomCount;
        newBlock.equalBlockSize = this.equalBlockSize;
        newBlock.blockList = new LinkedList<>();
        
        // fully copy (clone) all blocks in the list
        Iterator<DataBlock> it = this.blockList.iterator();
        while (it.hasNext())
            newBlock.add(it.next().clone());
            
        return newBlock;
    }
    
    
    @Override
    public List<DataBlock> getUnderlyingObject()
    {
        return blockList;
    }
    
    
    @Override
    @SuppressWarnings("unchecked")
    public void setUnderlyingObject(Object obj)
    {
    	this.blockList = (List<DataBlock>)(Serializable)obj;
    }
    
    
    @Override
    public DataType getDataType()
	{
		return DataType.MIXED;
	}


    @Override
    public DataType getDataType(int index)
	{
		var cachedIndex = selectBlock(index);
		return blockList.get(cachedIndex.blockIndex).getDataType();
	}
	
	
	@Override
    public void resize(int size)
	{
		if (blockList instanceof ArrayList)
		    ((ArrayList<DataBlock>)blockList).ensureCapacity(size);
	    
	    if (!blockList.isEmpty())
        {
		    DataBlock childBlock = get(0);
		    atomCount = childBlock.getAtomCount() * size;
    		blockList.clear();        
        
            for (int i=0; i<size; i++)
                blockList.add(childBlock.clone());
        }        
	}
    
    
    @Override
    public void updateAtomCount()
    {
        int newAtomCount = 0;
        for (DataBlock block: blockList)
        {
            block.updateAtomCount();
            newAtomCount += block.getAtomCount();
        }
        this.atomCount = newAtomCount;
    }

    
	protected final CachedIndex selectBlock(int index)
	{
		int desiredIndex = index + startIndex;
		
		// use thread local index so we can read concurrently from multiple threads
        CachedIndex cachedIdx = cachedIndex.get();
        if (cachedIdx == null) {
            cachedIdx = new CachedIndex();
            cachedIndex.set(cachedIdx);
        }
        
		if (equalBlockSize)
		{
		    cachedIdx.blockIndex = desiredIndex / blockAtomCount;
		    cachedIdx.localIndex = desiredIndex % blockAtomCount;
		}
		else
		{
		    // speed up sequential scans by restarting from previous index
	        // but reset if desired index is going back down
			if (index <= cachedIdx.lastIndex) {
			    cachedIdx.lastIndex = 0;
			    cachedIdx.cumulIndex = 0;
                cachedIdx.blockIndex = 0;
                cachedIdx.localIndex = 0;
			}
			
			int size = 0;
            int cumul = cachedIdx.cumulIndex;
            int i = cachedIdx.blockIndex;
	
            while (desiredIndex >= cumul)
			{
				size = blockList.get(i).getAtomCount();
				cumul += size;
				i++;
			}
	
			// actually use previous block because we went one block too far
			cumul -= size;
			cachedIdx.blockIndex = i - 1;
			cachedIdx.localIndex = desiredIndex - cumul;
			
			// save indexing variables in cache for next call
			cachedIdx.lastIndex = index;
			cachedIdx.cumulIndex = cumul;
		}
		
		return cachedIdx;
	}
    
    
    public ListIterator<DataBlock> blockIterator()
    {
        return this.blockList.listIterator();
    }
    
    
    public int getListSize()
    {
    	return this.blockList.size();
    }
    
    
    public void add(DataBlock block)
    {
    	if (blockAtomCount < 0)
    		blockAtomCount = block.getAtomCount();
    	
    	else if (block.getAtomCount() != blockAtomCount)
    		equalBlockSize = false;

        blockList.add(block);
    	atomCount += block.getAtomCount();
    }
    
    
    public void add(int blockIndex, AbstractDataBlock block)
    {
    	blockList.add(blockIndex, block);
    }
    
    
    public void set(int blockIndex, DataBlock block)
    {
    	DataBlock oldBlock = blockList.set(blockIndex, block);
    	atomCount -= oldBlock.getAtomCount();
    	atomCount += block.getAtomCount();
    }
    
    
    public DataBlock get(int blockIndex)
    {
        return blockList.get(blockIndex);
    }
    
    
    public void remove(AbstractDataBlock block)
    {
    	blockList.remove(block);
    	atomCount -= block.atomCount;
    }
    
    
    public void remove(int blockIndex)
    {
    	DataBlock oldBlock = blockList.remove(blockIndex);
    	atomCount -= oldBlock.getAtomCount();
    }
	
	
    @Override
    public String toString()
    {
    	StringBuilder buffer = new StringBuilder();
		buffer.append("LIST " + super.toString());
		buffer.append('\n');
    	int imax = blockList.size();
    	
    	for (int i=0; i<imax; i++)
    	{
    		buffer.append(blockList.get(i).toString());
        	buffer.append('\n');        		
    	}
    	
    	return buffer.toString();
    }
	
	
	@Override
    public boolean getBooleanValue(int index)
	{
	    var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getBooleanValue(idx.localIndex);
	}


	@Override
    public byte getByteValue(int index)
	{
	    var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getByteValue(idx.localIndex);
	}


	@Override
    public short getShortValue(int index)
	{
		var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getShortValue(idx.localIndex);
	}


	@Override
	public int getIntValue(int index)
	{
		var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getIntValue(idx.localIndex);
	}


	@Override
    public long getLongValue(int index)
	{
		var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getLongValue(idx.localIndex);
	}


	@Override
    public float getFloatValue(int index)
	{
		var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getFloatValue(idx.localIndex);
	}


	@Override
    public double getDoubleValue(int index)
	{
		var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getDoubleValue(idx.localIndex);
	}


	@Override
    public String getStringValue(int index)
	{
		var idx = selectBlock(index);
		return blockList.get(idx.blockIndex).getStringValue(idx.localIndex);
	}
    

    @Override
    public Instant getTimeStamp(int index)
    {
        var idx = selectBlock(index);
        return blockList.get(idx.blockIndex).getTimeStamp(idx.localIndex);
    }


    @Override
    public OffsetDateTime getDateTime(int index)
    {
        var idx = selectBlock(index);
        return blockList.get(idx.blockIndex).getDateTime(idx.localIndex);
    }


	@Override
    public void setBooleanValue(int index, boolean value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setBooleanValue(idx.localIndex, value);
	}


	@Override
    public void setByteValue(int index, byte value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setByteValue(idx.localIndex, value);
	}


	@Override
    public void setShortValue(int index, short value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setShortValue(idx.localIndex, value);
	}


	@Override
    public void setIntValue(int index, int value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setIntValue(idx.localIndex, value);
	}


	@Override
    public void setLongValue(int index, long value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setLongValue(idx.localIndex, value);
	}


	@Override
    public void setFloatValue(int index, float value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setFloatValue(idx.localIndex, value);
	}


	@Override
    public void setDoubleValue(int index, double value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setDoubleValue(idx.localIndex, value);
	}


	@Override
    public void setStringValue(int index, String value)
	{
		var idx = selectBlock(index);
		blockList.get(idx.blockIndex).setStringValue(idx.localIndex, value);
	}


    @Override
    public void setTimeStamp(int index, Instant value)
    {
        var idx = selectBlock(index);
        blockList.get(idx.blockIndex).setTimeStamp(idx.localIndex, value);
    }


    @Override
    public void setDateTime(int index, OffsetDateTime value)
    {
        var idx = selectBlock(index);
        blockList.get(idx.blockIndex).setDateTime(idx.localIndex, value);
    }
}