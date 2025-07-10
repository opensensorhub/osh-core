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
	protected int blockIndex;
	protected int localIndex;


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
		selectBlock(index);
		return blockArray[blockIndex].getDataType(localIndex);
	}


	@Override
    public void resize(int size)
	{
		// resize all sub blocks
		for (int i=0; i<blockArray.length; i++)
			blockArray[i].resize(size/blockArray.length);
		
		this.atomCount = size;
	}


	protected void selectBlock(int index)
	{
		blockIndex = index % blockArray.length;
        localIndex = startIndex + index / blockArray.length;
        localIndex -= blockArray[blockIndex].startIndex;
	}


	@Override
    public String toString()
	{
		StringBuffer buffer = new StringBuffer();
		buffer.append("PARALLEL: ");
		buffer.append('[');

		selectBlock(0);
		int start = blockIndex;
		selectBlock(getAtomCount() - 1);
		int stop = blockIndex + 1;
		
		for (int i = start; i < stop; i++)
		{
			buffer.append(blockArray[i].toString());
			if (i < stop - 1)
				buffer.append(',');
		}

		buffer.append(']');
		return buffer.toString();
	}


	@Override
    public boolean getBooleanValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getBooleanValue(localIndex);
	}


	@Override
    public byte getByteValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getByteValue(localIndex);
	}


	@Override
    public short getShortValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getShortValue(localIndex);
	}


	@Override
    public int getIntValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getIntValue(localIndex);
	}


	@Override
    public long getLongValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getLongValue(localIndex);
	}


	@Override
    public float getFloatValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getFloatValue(localIndex);
	}


	@Override
    public double getDoubleValue(int index)
	{
		selectBlock(index);
        //System.out.println(blockIndex + " " + localIndex);
		return blockArray[blockIndex].getDoubleValue(localIndex);
	}


	@Override
    public String getStringValue(int index)
	{
		selectBlock(index);
		return blockArray[blockIndex].getStringValue(localIndex);
	}


    @Override
    public Instant getTimeStamp(int index)
    {
        selectBlock(index);
        return blockArray[blockIndex].getTimeStamp(localIndex);
    }


    @Override
    public OffsetDateTime getDateTime(int index)
    {
        selectBlock(index);
        return blockArray[blockIndex].getDateTime(localIndex);
    }


	@Override
    public void setBooleanValue(int index, boolean value)
	{
		selectBlock(index);
		blockArray[blockIndex].setBooleanValue(localIndex, value);
	}


	@Override
    public void setByteValue(int index, byte value)
	{
		selectBlock(index);
		blockArray[blockIndex].setByteValue(localIndex, value);
	}


	@Override
    public void setShortValue(int index, short value)
	{
		selectBlock(index);
		blockArray[blockIndex].setShortValue(localIndex, value);
	}


	@Override
    public void setIntValue(int index, int value)
	{
		selectBlock(index);
		blockArray[blockIndex].setIntValue(localIndex, value);
	}


	@Override
    public void setLongValue(int index, long value)
	{
		selectBlock(index);
		blockArray[blockIndex].setLongValue(localIndex, value);
	}


	@Override
    public void setFloatValue(int index, float value)
	{
		selectBlock(index);
		blockArray[blockIndex].setFloatValue(localIndex, value);
	}


	@Override
    public void setDoubleValue(int index, double value)
	{
		selectBlock(index);
		blockArray[blockIndex].setDoubleValue(localIndex, value);
	}


	@Override
    public void setStringValue(int index, String value)
	{
		selectBlock(index);
		blockArray[blockIndex].setStringValue(localIndex, value);
	}


    @Override
    public void setTimeStamp(int index, Instant value)
    {
        selectBlock(index);
        blockArray[blockIndex].setTimeStamp(localIndex, value);
    }


    @Override
    public void setDateTime(int index, OffsetDateTime value)
    {
        selectBlock(index);
        blockArray[blockIndex].setDateTime(localIndex, value);
    }
}
