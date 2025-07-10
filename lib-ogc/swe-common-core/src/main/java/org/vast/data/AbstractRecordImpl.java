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

import java.util.*;
import org.vast.util.Asserts;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataType;
import net.opengis.swe.v20.ValidationException;


/**
 * <p>
 * Implementation of an heterogeneous list of data components
 * This is the base type for DataRecord and Vector
 * </p>
 *
 * @author Alex Robin
 * * @param <ComponentType> 
 */
public abstract class AbstractRecordImpl<ComponentType extends DataComponent> extends AbstractDataComponentImpl
{
    private static final long serialVersionUID = -8053251170642596902L;
    protected DataComponentPropertyList<ComponentType> fieldList;

        
    public AbstractRecordImpl()
    {
        fieldList = new DataComponentPropertyList<>(this);
    }
    
    
    public AbstractRecordImpl(int size)
    {
        this.fieldList = new DataComponentPropertyList<>(this, size);
    }
    
    
    @Override
    protected void updateStartIndex(int startIndex)
    {
        dataBlock.startIndex = startIndex;
        
        if (dataBlock instanceof DataBlockMixed)
        {
            // TODO updateStartIndex for group with DataBlockMixed
        }
        else if (dataBlock instanceof DataBlockParallel)
        {
            for (int i = 0; i < fieldList.size(); i++)
            {
                AbstractDataComponentImpl childComponent = (AbstractDataComponentImpl)fieldList.get(i);
                childComponent.updateStartIndex(startIndex);
            }
        }
        else // case of primitive array
        {
            int scalarTotal = 0;
            for (int i = 0; i < fieldList.size(); i++)
            {
                AbstractDataComponentImpl childComponent = (AbstractDataComponentImpl)fieldList.get(i);
                childComponent.updateStartIndex(startIndex + scalarTotal);
                scalarTotal += childComponent.scalarCount;
            }
        }
    }
    
    
    @Override
    protected void updateAtomCount(int childAtomCountDiff)
    {
        if (dataBlock != null)
            dataBlock.atomCount += childAtomCountDiff;
        
        if (parent != null)
            parent.updateAtomCount(childAtomCountDiff);
    }


    @Override
    public AbstractDataComponentImpl getComponent(int index)
    {
        return (AbstractDataComponentImpl)fieldList.get(index);
    }
    
    
    @Override
    public AbstractDataComponentImpl getComponent(String name)
    {
        return (AbstractDataComponentImpl)fieldList.get(name);
    }
    
    
    @Override
    public int getComponentIndex(String name)
    {
        DataComponent comp = fieldList.get(name);
        if (comp == null)
            return -1;
        return fieldList.indexOf(comp);
    }
    
    
    @Override
    public AbstractDataComponentImpl removeComponent(int index)
    {
        clearData();
        ComponentType component = fieldList.remove(index);
        ((AbstractDataComponentImpl)component).setParent(null);
        return (AbstractDataComponentImpl)component;
    }


    @Override
    public AbstractDataComponentImpl removeComponent(String name)
    {
        int index = getComponentIndex(name);
        return removeComponent(index);
    }


    @Override
    public void setData(DataBlock dataBlock)
    {
    	Asserts.checkNotNull(dataBlock, DataBlock.class);
    	
    	// HACK makes sure scalar count was properly computed
        if (scalarCount < 0)
            createDataBlock();
        
    	this.dataBlock = (AbstractDataBlock)dataBlock;

		// also assign dataBlock to children
    	if (dataBlock instanceof DataBlockParallel)
    	{
    		for (int i = 0; i < fieldList.size(); i++)
    		{
    			AbstractDataBlock childBlock = ((DataBlockParallel)dataBlock).blockArray[i].copy();
    			childBlock.startIndex = ((DataBlockParallel)dataBlock).startIndex;
    			((AbstractDataComponentImpl)fieldList.get(i)).setData(childBlock);
    		}
    	}
        else if (dataBlock instanceof DataBlockMixed)
        {
            for (int i = 0; i < fieldList.size(); i++)
            {
                AbstractDataBlock childBlock = ((DataBlockMixed)dataBlock).blockArray[i];
                ((AbstractDataComponentImpl)fieldList.get(i)).setData(childBlock);
            }
        }
        else if (dataBlock instanceof DataBlockTuple)
        {
        	int currentIndex = 0;
            for (int i = 0; i < fieldList.size(); i++)
            {
                AbstractDataComponentImpl nextComponent = (AbstractDataComponentImpl)fieldList.get(i);
                AbstractDataBlock childBlock = ((AbstractDataBlock)dataBlock).copy();
                childBlock.atomCount = nextComponent.scalarCount;
                childBlock.startIndex += currentIndex;
                currentIndex += childBlock.atomCount;
                nextComponent.setData(childBlock);
            }
        }
    	else // case of big primitive array
    	{
    		int currentIndex = 0;
    		for (int i = 0; i < fieldList.size(); i++)
    		{
    			AbstractDataComponentImpl nextComponent = (AbstractDataComponentImpl)fieldList.get(i);
    			AbstractDataBlock childBlock = ((AbstractDataBlock)dataBlock).copy();
    			childBlock.atomCount = nextComponent.scalarCount;
    			childBlock.startIndex += currentIndex;
    			currentIndex += childBlock.atomCount;
    			nextComponent.setData(childBlock);
    		}
    	}
    }
    
    
    @Override
    public void clearData()
    {
        this.dataBlock = null;
        for (DataComponent field: fieldList)
            field.clearData();
    }
    
    
    @Override
    public void validateData(List<ValidationException> errorList)
    {
        for (DataComponent field: fieldList)
    	    ((AbstractDataComponentImpl)field).validateData(errorList);
    }
    
    
    /**
     * Create object adapted to carry data for this container
     * TODO could save the calculated structure and do a shallow copy for the nexts...
     * Saved copy would be discarded every time the structure changes
     */
    @Override
    public AbstractDataBlock createDataBlock() 
    {
    	DataType currentType;
        DataType previousType = DataType.OTHER;
        AbstractDataBlock newBlock = null;
        AbstractDataBlock nextBlock = null;
        DataBlockMixed mixedBlock;
        AbstractDataComponentImpl nextComponent;
        boolean sameType = true;
        boolean allScalars = true;
        int totalSize = 0;        
        
    	// create a mixed block with all children block
    	int childNumber = fieldList.size();
    	mixedBlock = new DataBlockMixed(childNumber);
        
        for (int i=0; i<childNumber; i++)
        {
        	nextComponent = (AbstractDataComponentImpl)fieldList.get(i);
            nextBlock = nextComponent.createDataBlock();
        	currentType = nextBlock.getDataType();
        	totalSize += nextBlock.atomCount;
        	mixedBlock.blockArray[i] = nextBlock;
        	
        	/*if ((currentType == DataType.MIXED) || (i != 0 && currentType != previousType))
        		sameType = false;
        	
        	else if ((nextComponent instanceof DataArray) && ((DataArray)nextComponent).variableSize)
        		sameType = false;*/
            
            if (nextComponent instanceof DataArrayImpl || nextBlock instanceof DataBlockMixed || nextBlock.atomCount > 1)
            {
                allScalars = false;
            }
            else
            {
                if (i != 0 && (currentType != previousType))
                    sameType = false;
            }
        	
        	previousType = currentType;
        }
        
        // if we want to keep compressed data as-is
        // TODO improve dealing with compressed data
        if (encodingInfo != null && ((BinaryBlock)encodingInfo).getCompression() != null) // && keepCompressed)
        {
            newBlock = new DataBlockCompressed();
            newBlock.atomCount = totalSize;
        }
        
        // if everything was of same type, create a big shared primitive block
        else if (nextBlock != null && allScalars && sameType)
        {
        	newBlock = nextBlock.copy();
        	newBlock.resize(totalSize);
        }
        
        // otherwise if only scalars, use DataBlockTuple or PrimitiveBlock
        else if (allScalars)
        {
            DataBlockTuple tupleBlock = new DataBlockTuple(totalSize);
            
            int currentIndex = 0;
            for (int i=0; i<childNumber; i++)
            {
                AbstractDataBlock childBlock = mixedBlock.blockArray[i];
                
                if (childBlock instanceof DataBlockTuple)
                {
                    for (int j=0; j<childBlock.atomCount; j++)
                    {
                        tupleBlock.blockArray[currentIndex] = ((DataBlockTuple)childBlock).blockArray[j];
                        currentIndex++;
                    }
                }
                else
                {
                    for (int j=0; j<childBlock.atomCount; j++)
                    {
                        AbstractDataBlock block = childBlock.copy();
                        block.resize(1);
                        tupleBlock.blockArray[currentIndex] = block;
                        currentIndex++;
                    }
                }
            }
            
            tupleBlock.atomCount = totalSize;
            newBlock = tupleBlock;
        }
        
        // otherwise use the mixed block
        else
        {
        	newBlock = mixedBlock;
        	newBlock.atomCount = totalSize;
        }
        
        newBlock.startIndex = 0;
        scalarCount = totalSize;
        return newBlock;
    }
    
    
    /**
     * Call this when some child component data is re-assigned
     * (e.g. variable size array data)
     */
    public void updateDataBlock()
    {
        int groupSize = fieldList.size();
        
        if (!(this.dataBlock instanceof DataBlockMixed))
            throw new IllegalStateException("Can only update a DataBlockMixed");
        var dataBlk = (DataBlockMixed)this.dataBlock;
        dataBlk.atomCount = 0;
        
        for (int i=0; i<groupSize; i++)
        {
            AbstractDataComponentImpl childComponent = (AbstractDataComponentImpl)fieldList.get(i);
            dataBlk.blockArray[i] = childComponent.dataBlock;
            dataBlk.atomCount += childComponent.dataBlock.atomCount;
        }
    }
    
    
    /**
     * Specific to DataRecord and used by ProcessChain
     * Allows to combine child blocks into one mixed block
     * when blocks are coming from different independent sources
     */
    public void combineDataBlocks()
    {
        int groupSize = fieldList.size();
        DataBlockMixed newBlock = new DataBlockMixed(groupSize);
        
        for (int i=0; i<groupSize; i++)
        {
            AbstractDataComponentImpl childComponent = (AbstractDataComponentImpl)fieldList.get(i);
            
            if (childComponent.dataBlock == null)
            {
                if (childComponent instanceof AbstractRecordImpl)
                    ((AbstractRecordImpl<?>)childComponent).combineDataBlocks();
                else
                    childComponent.assignNewDataBlock();
            }

            newBlock.blockArray[i] = childComponent.dataBlock;
            newBlock.atomCount += childComponent.dataBlock.atomCount;
        }
        
        this.dataBlock = newBlock;
    }


    @Override
    public int getComponentCount()
    {
        return fieldList.size();
    }


	@Override
	public boolean hasConstraints()
	{
	    for (DataComponent field: fieldList)
        {
            if (field.hasConstraints())
                return true;
        }
		
		return false;
	}
}
