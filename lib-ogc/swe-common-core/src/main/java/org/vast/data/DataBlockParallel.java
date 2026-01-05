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

import java.time.Instant;
import java.time.OffsetDateTime;
import net.opengis.swe.v20.DataType;


/**
 * <p>
 * Uses the composite pattern to carry a fixed size array
 * of parallel array DataBlocks.  Children datablocks will
 * thus be read in parallel.
 * </p>
 *
 * @author Alex Robin
 * */
public class DataBlockParallel extends AbstractDataBlock
{
	private static final long serialVersionUID = 6492226220927792777L;
    protected AbstractDataBlock[] blockArray;
    transient ThreadLocal<CachedIndex> cachedIndex = new ThreadLocal<>();
    
    static class CachedIndex
    {
        int blockIndex;
        int localIndex;
    }


	public DataBlockParallel()
	{
	}


	public DataBlockParallel(int numBlocks)
	{
		blockArray = new AbstractDataBlock[numBlocks];
	}
	
	
	public void setChildBlock(int blockIndex, AbstractDataBlock dataBlock)
	{
	    // check size is coherent with other child blocks
	    for (AbstractDataBlock block: blockArray)
	    {
	        if (block != null && block.atomCount != dataBlock.atomCount)
	            throw new IllegalArgumentException("All child data blocks of a parallel data block must have the same size");
	    }
	    
	    // update atom count
	    AbstractDataBlock oldBlock = blockArray[blockIndex];
	    if (oldBlock != null)
	        this.atomCount -= oldBlock.atomCount;
	    this.atomCount += dataBlock.atomCount;
	    
	    // set actual child block
	    blockArray[blockIndex] = dataBlock;
	}
	
	
	@Override
    public DataBlockParallel copy()
	{
		DataBlockParallel newBlock = new DataBlockParallel();
		newBlock.startIndex = this.startIndex;
		newBlock.blockArray = this.blockArray;
		newBlock.atomCount = this.atomCount;
		return newBlock;
	}
    
    
    @Override
    public DataBlockParallel renew()
    {
        DataBlockParallel newBlock = new DataBlockParallel();
        newBlock.startIndex = this.startIndex;
        newBlock.blockArray = new AbstractDataBlock[blockArray.length];
        
        // renew all blocks in the array
        for (int i=0; i<blockArray.length; i++)
            newBlock.blockArray[i] = this.blockArray[i].renew();
        
        newBlock.atomCount = this.atomCount;
        return newBlock;
    }
    
    
    @Override
    public DataBlockParallel clone()
    {
        DataBlockParallel newBlock = new DataBlockParallel();
        newBlock.startIndex = this.startIndex;
        newBlock.blockArray = new AbstractDataBlock[blockArray.length];
        
        // fully copy (clone) all blocks in the array
        for (int i=0; i<blockArray.length; i++)
            newBlock.blockArray[i] = this.blockArray[i].clone();
        
        newBlock.atomCount = this.atomCount;
        return newBlock;
    }
    
    
    @Override
    public AbstractDataBlock[] getUnderlyingObject()
    {
        return blockArray;
    }
    
    
    public void setUnderlyingObject(AbstractDataBlock[] blockArray)
    {
        this.blockArray = blockArray;
        
        // init atom count to the whole size
        this.atomCount = 0;
        for (AbstractDataBlock block: blockArray)
            this.atomCount += block.atomCount;
    }
    
    
    @Override
    public void setUnderlyingObject(Object obj)
    {
    	this.blockArray = (AbstractDataBlock[])obj;
    }
	
	
	@Override
    public DataType getDataType()
	{
		return DataType.MIXED;
	}


	@Override
    public DataType getDataType(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getDataType(idx.localIndex);
	}


	@Override
    public void resize(int size)
	{
		// resize all sub blocks
		for (int i=0; i<blockArray.length; i++)
			blockArray[i].resize(size/blockArray.length);
		
		this.atomCount = size;
	}


	protected CachedIndex selectBlock(int index)
	{
	    // use thread local index so we can read concurrently from multiple threads
        CachedIndex cachedIdx = cachedIndex.get();
        if (cachedIdx == null) {
            cachedIdx = new CachedIndex();
            cachedIndex.set(cachedIdx);
        }
        
        var blockIndex = index % blockArray.length;
        var localIndex = startIndex + index / blockArray.length;
        localIndex -= blockArray[blockIndex].startIndex;
        
        cachedIdx.blockIndex = blockIndex;
        cachedIdx.localIndex = localIndex;
        return cachedIdx;
	}


	@Override
    public String toString()
	{
		StringBuffer buffer = new StringBuffer();
		buffer.append("PARALLEL: ");
		buffer.append('[');

        if (atomCount > 0)
        {
    		var idx = selectBlock(0);
    		int start = idx.blockIndex;
    		idx = selectBlock(getAtomCount() - 1);
    		int stop = idx.blockIndex + 1;
    		
    		for (int i = start; i < stop; i++)
    		{
    			buffer.append(blockArray[i].toString());
    			if (i < stop - 1)
    				buffer.append(',');
    		}
        }

		buffer.append(']');
		return buffer.toString();
	}


	@Override
    public boolean getBooleanValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getBooleanValue(idx.localIndex);
	}


	@Override
    public byte getByteValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getByteValue(idx.localIndex);
	}


	@Override
    public short getShortValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getShortValue(idx.localIndex);
	}


	@Override
    public int getIntValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getIntValue(idx.localIndex);
	}


	@Override
    public long getLongValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getLongValue(idx.localIndex);
	}


	@Override
    public float getFloatValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getFloatValue(idx.localIndex);
	}


	@Override
    public double getDoubleValue(int index)
	{
		var idx = selectBlock(index);
        //System.out.println(blockIndex + " " + localIndex);
		return blockArray[idx.blockIndex].getDoubleValue(idx.localIndex);
	}


	@Override
    public String getStringValue(int index)
	{
		var idx = selectBlock(index);
		return blockArray[idx.blockIndex].getStringValue(idx.localIndex);
	}


    @Override
    public Instant getTimeStamp(int index)
    {
        var idx = selectBlock(index);
        return blockArray[idx.blockIndex].getTimeStamp(idx.localIndex);
    }


    @Override
    public OffsetDateTime getDateTime(int index)
    {
        var idx = selectBlock(index);
        return blockArray[idx.blockIndex].getDateTime(idx.localIndex);
    }


	@Override
    public void setBooleanValue(int index, boolean value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setBooleanValue(idx.localIndex, value);
	}


	@Override
    public void setByteValue(int index, byte value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setByteValue(idx.localIndex, value);
	}


	@Override
    public void setShortValue(int index, short value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setShortValue(idx.localIndex, value);
	}


	@Override
    public void setIntValue(int index, int value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setIntValue(idx.localIndex, value);
	}


	@Override
    public void setLongValue(int index, long value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setLongValue(idx.localIndex, value);
	}


	@Override
    public void setFloatValue(int index, float value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setFloatValue(idx.localIndex, value);
	}


	@Override
    public void setDoubleValue(int index, double value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setDoubleValue(idx.localIndex, value);
	}


	@Override
    public void setStringValue(int index, String value)
	{
		var idx = selectBlock(index);
		blockArray[idx.blockIndex].setStringValue(idx.localIndex, value);
	}


    @Override
    public void setTimeStamp(int index, Instant value)
    {
        var idx = selectBlock(index);
        blockArray[idx.blockIndex].setTimeStamp(idx.localIndex, value);
    }


    @Override
    public void setDateTime(int index, OffsetDateTime value)
    {
        var idx = selectBlock(index);
        blockArray[idx.blockIndex].setDateTime(idx.localIndex, value);
    }
}
