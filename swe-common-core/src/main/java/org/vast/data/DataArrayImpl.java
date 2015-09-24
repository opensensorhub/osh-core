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

import java.util.List;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.Count;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.ValidationException;

/**
 * <p>
 * Array of identical components. Can be of variable size.
 * In the case of a variable size array, size is actually given
 * by another component: sizeComponent which should be a Count.
 * There are two cases of variable size component:
 *  - The component is explicitely listed in the component tree
 *    (in this case, the count component has a parent) 
 *  - The component is implicitely given before the array data
 *    (in this case, the count component has no parent)
 *    
 * 08-2014: Updated to implement new API autogenerated from XML schema
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * */
public class DataArrayImpl extends AbstractArrayImpl
{
    private static final long serialVersionUID = -585236845658753642L;
    protected final static String errorBlockMixed = "Error: DataArrays should never contain a DataBlockMixed";
    public final static String ARRAY_SIZE_FIELD = "elementCount";
    
    protected int currentSize;
    protected CountImpl implicitElementCount;
    
    
    /**
     * Default constructor.
     * The array will be variable size with implicit size until
     * either setSize() is called or the elementType property
     * is set to reference the ID of another component in the tree.
     */
    public DataArrayImpl()
    {        
    }
    
    
    public DataArrayImpl(int arraySize)
    {
        Count count = new CountImpl();
        count.setValue(arraySize);
        elementCount.setValue(count);
    }


    @Override
    public DataArrayImpl copy()
    {
    	DataArrayImpl newObj = new DataArrayImpl();
    	copyTo(newObj);
    	return newObj;
    }
    
    
    protected void copyTo(DataArrayImpl other)
    {
        super.copyTo(other);        
        other.currentSize = this.currentSize;
        if (!isVariableSize())
            other.elementCount.getValue().setValue(getComponentCount());
    }
    
    
    @Override
    protected void updateStartIndex(int startIndex)
    {
        dataBlock.startIndex = startIndex;
        
        if (dataBlock instanceof DataBlockMixed)
        {
            throw new IllegalStateException(errorBlockMixed);
        }
        else if (dataBlock instanceof DataBlockParallel)
        {
            getArrayComponent().updateStartIndex(startIndex);
        }
        else // case of primitive array
        {
            getArrayComponent().updateStartIndex(startIndex);
        }
    }
    
    
    @Override
    protected void updateAtomCount(int childAtomCountDiff)
    {
    	int arraySize = getComponentCount();
    	int atomCountDiff = 0;
        
        if (dataBlock != null)
        {
            // if using DataBlockList, each array element is
        	// independant and can be of variable length
        	if (dataBlock instanceof DataBlockList)
            {
            	atomCountDiff = childAtomCountDiff;
            	dataBlock.atomCount += atomCountDiff;
            }
            else
            {        	
            	atomCountDiff = childAtomCountDiff * arraySize;
            	AbstractDataBlock childBlock = getArrayComponent().dataBlock;
	            childBlock.resize(childBlock.atomCount * arraySize);
	            dataBlock.setUnderlyingObject(childBlock.getUnderlyingObject());
	            dataBlock.atomCount = childBlock.atomCount;
	            setData(dataBlock);
            }
        }
        
        if (parent != null)
            parent.updateAtomCount(atomCountDiff);
    }


    @Override
    public AbstractDataComponentImpl getComponent(int index)
    {
        checkIndex(index);
        AbstractDataComponentImpl component = getArrayComponent();
        
        if (dataBlock != null)
        {
            int startIndex = dataBlock.startIndex;
            
            if (dataBlock instanceof DataBlockMixed)
            {
                throw new IllegalStateException(errorBlockMixed);
            }            
            else if (dataBlock instanceof DataBlockParallel)
            {
                if (component instanceof DataArrayImpl)
                    startIndex += index * ((DataArrayImpl)component).getComponentCount();
                else
                    startIndex += index;
            }
            else if (dataBlock instanceof DataBlockList)
            {
                startIndex = 0;
                component.setData(((DataBlockList)dataBlock).get(index));
            }
            else // primitive block
            {
                startIndex += index * component.scalarCount;
            }
            
            // update child start index
            component.updateStartIndex(startIndex);
        }
        
        return component;
    }
    
    
    @Override
    public AbstractDataComponentImpl getComponent(String name)
	{
		if (name.equals(ARRAY_SIZE_FIELD) && isImplicitSize())
		    return getArraySizeComponent();
        
		if (!name.equals(elementType.getName()))
			return null;
		
		return getArrayComponent();
	}
    
    
    @Override
    public void setData(DataBlock dataBlock)
    {
    	// HACK makes sure scalar count was properly computed
        if (scalarCount < 0)
            this.assignNewDataBlock();
        
        this.dataBlock = (AbstractDataBlock)dataBlock;
    	
    	// always update size component if implicit variable size
        if (isImplicitSize())
        {
        	int newSize = 0;
        	
        	if (dataBlock instanceof DataBlockList)
        	{
        		newSize = ((DataBlockList)dataBlock).getListSize();
        	}
        	else
        	{
	        	// TODO should infer array size differently (keep size int in data block??)
	        	// TODO potential bug here with nested implicit variable size arrays
	        	newSize = dataBlock.getAtomCount() / getArrayComponent().scalarCount;
        	}
        	
        	updateSizeComponent(newSize);
        }
        
        // if variable size, error if size component value is not compatible with datablock size!
        else if (isVariableSize())
        {
            int arraySize = getArraySizeComponent().getData().getIntValue();
            if (dataBlock.getAtomCount() % arraySize != 0)
                throw new IllegalStateException("Datablock is incompatible with specified array size: " + arraySize);
        }
        
		// also assign dataBlock to child
        if (dataBlock instanceof DataBlockList)
        {
            if (((DataBlockList)dataBlock).getListSize() > 0)
                getArrayComponent().setData(((DataBlockList)dataBlock).get(0));
        }
        else
        {
        	AbstractDataBlock childBlock = ((AbstractDataBlock)dataBlock).copy();
    		childBlock.atomCount = getArrayComponent().scalarCount;
    		getArrayComponent().setData(childBlock);
        }
    }
    
    
    @Override
    public void clearData()
    {
        this.dataBlock = null;
        getArrayComponent().clearData();
        if (isVariableSize())
        	updateSizeComponent(0);
    }
    
    
    @Override
    public void validateData(List<ValidationException> errorList)
    {
    	// do only if constraints are specified on descendants
    	if (hasConstraints())
    	{
    		int numErrors = errorList.size();
    		
    		for (int i = 0; i < getComponentCount(); i++)
    		{
    			getComponent(i).validateData(errorList);
    			
    			// max N errors generated!
    			if (errorList.size() > numErrors + MAX_ARRAY_ERRORS)
    				return;
    		}
    	}
    }

    
    /**
     * Create the right data block to carry this array data
     * It can be either a scalar array (DataBlockDouble, etc...)
     * or a group of mixed types parallel arrays (DataBlockMixed)
     */
    @Override
    public AbstractDataBlock createDataBlock()
    {
        AbstractDataBlock childBlock = getArrayComponent().createDataBlock();
    	AbstractDataBlock newBlock = null;
    	int arraySize = getComponentCount();
        int newSize = 0;
        
    	if (arraySize >= 0)
    	{
    	    // if we want to keep compressed data as-is
            // TODO improve dealing with compressed data
            if (encodingInfo != null && ((BinaryBlock)encodingInfo).getCompression() != null) // && keepCompressed)
            {
                newBlock = new DataBlockCompressed();
                newSize = childBlock.atomCount * arraySize;
            }
            
    	    // if child is parallel block, create bigger parallel block
            else if (childBlock instanceof DataBlockParallel)
	        {
	        	newBlock = childBlock.copy();
                newSize = childBlock.atomCount * arraySize;
	        }
            
            // if child is tuple block, create parallel block
            else if (childBlock instanceof DataBlockTuple)
            {
                DataBlockParallel parallelBlock = new DataBlockParallel();
                parallelBlock.blockArray = ((DataBlockTuple)childBlock).blockArray;
                newSize = childBlock.atomCount * arraySize;
                newBlock = parallelBlock;
            }
            
            // if child is mixed block, create list block
            else if (childBlock instanceof DataBlockMixed)
            {
                DataBlockList blockList = new DataBlockList();
                blockList.add(childBlock.copy());
                newBlock = blockList;
                newSize = arraySize;
            }
	        
	        // if child is already a primitive block, create bigger primitive block
	        else
	        {
	        	newBlock = childBlock.copy();
                newSize = childBlock.atomCount * arraySize;
	        }
	        
	        newBlock.resize(newSize);
	        scalarCount = newBlock.atomCount;
    	}
        
        return newBlock;
    }


    /**
     * Check that the integer index given is in range: 0 to size of array - 1
     * @param index int
     * @throws DataException
     */
    protected void checkIndex(int index)
    {
        // error if index is out of range
        if ((index >= getComponentCount()) || (index < 0))
            throw new IndexOutOfBoundsException("Index " + index + " is out of bounds");
    }
    
    
    /**
     * Do everything that is necessary to properly resize data block 
     * @param oldArraySize
     * @param newArraySize
     */
    protected void resizeDataBlock(int oldArraySize, int newArraySize)
    {
    	if (dataBlock != null)
        {
            if (dataBlock instanceof DataBlockList)
            {
            	if (dataBlock.atomCount == 0)
            	{
            		AbstractDataBlock childBlock = getArrayComponent().createDataBlock();
            		((DataBlockList)dataBlock).add(childBlock);
            	}
            	dataBlock.resize(newArraySize);
            }
            else
            {
            	this.scalarCount = getArrayComponent().scalarCount * newArraySize;
            			
            	dataBlock.resize(scalarCount);
            	
            	// reassign a copy of dataBlock to child
            	AbstractDataBlock childBlock = ((AbstractDataBlock)dataBlock).copy();
        		childBlock.atomCount = getArrayComponent().scalarCount;
        		getArrayComponent().setData(childBlock);
            }
        }
    	
    	this.currentSize = newArraySize;
    	
    	// update parent atom count
        if (parent != null)
            parent.updateAtomCount(getArrayComponent().scalarCount * (newArraySize - oldArraySize));
    }
    
    
    /**
     * Dynamically update size of a VARIABLE SIZE array
     * Note that elementCount component must carry the right value at this time
     */
    public void updateSize()
    {
    	if (isVariableSize())
    	{
    		int newSize = 0;
    		int oldSize = this.currentSize;
    		
    		// stop here if parent also has variable size
            // in this case updateSize() will be called from parent anyway (see below)!!
            if (parent instanceof DataArray)
            {
                if (((DataArray)parent).isVariableSize())
                    return;
            }
            
            // get new size from array size component
            Count sizeComponent = getArraySizeComponent();
	    	if (sizeComponent != null)
	    	{
	    		DataBlock data = sizeComponent.getData();
	    		if (data != null)
                {
	    			// continue only if sized has changed
	    			newSize = data.getIntValue();
                    if (newSize == oldSize)
                        return;
                }
	    	}
            
            // take care of variable size child array
	    	// before we resize everything
	    	if (sizeComponent instanceof DataArray)
            {
                if (((DataArray)sizeComponent).isVariableSize())
                    ((DataArray)sizeComponent).updateSize();
            }
            
            // resize datablock
            resizeDataBlock(oldSize, newSize);
    	}
    }
    
    
    /**
     * Set the size of this VARIABLE SIZE array to a new value
     * Automatically updates the sizeData component value.
     * @param newSize
     */
    public void updateSize(int newSize)
    {
    	if (newSize == this.currentSize)
            return;
    	
    	if (newSize >= 0)
        {
    		int oldSize = this.currentSize; // don't use getComponentCount() because elementCount may have changed already
    		updateSizeComponent(newSize);
    		
    		// resize underlying datablock
    		resizeDataBlock(oldSize, newSize);
        }
    }
    
    
    /**
     * Set the size of this array to a new FIXED value
     * @param newSize
     */
    public void setFixedSize(int newSize)
    {
        if (newSize >= 0)
        {
        	int oldSize = getComponentCount();
    		
    		// set size count to fixed value
        	elementCount.setHref(null);
        	if (!elementCount.hasValue())
        	    elementCount.setValue(new CountImpl());
        	elementCount.getValue().setValue(newSize);
        	
        	// stop here if size is same as before!
        	if (newSize == oldSize)
                return;
        	
        	// resize underlying datablock
        	resizeDataBlock(oldSize, newSize);
        }
    }
    
    
    /**
     * Simply update value in size data component w/o resizing datablock
     * @param newSize
     */
    protected void updateSizeComponent(int newSize)
    {
    	Count arraySizeComp = getArraySizeComponent();
        
        // update value of size data
    	DataBlock data = arraySizeComp.getData();
        if (data == null)
        {
            arraySizeComp.renewDataBlock();
        	data = arraySizeComp.getData();
        }        
        data.setIntValue(newSize);
        
        // save size so that we can detect if it changes later
        // this avoids resizing arrays for nothing if size does not change!
        this.currentSize = newSize;
    }
    

    @Override
    public int getComponentCount()
    {
    	DataBlock data = getArraySizeComponent().getData();
    	    	
    	if (data != null)
    	{
    		int arraySize = data.getIntValue();
    		if (arraySize >= 0)
    			return arraySize;
    	}
    	
    	return 1;
    }
    
    
    @Override
    public int getComponentIndex(String name)
    {
        if (!elementType.getName().equals(name))
            return -1;
        return 0;
    }
    

    public String toString(String indent)
    {
        StringBuffer text = new StringBuffer();
        text.append("DataArray[");
        if (isVariableSize())
        	text.append("?=");
        text.append(getComponentCount());
        text.append("]\n");
        text.append(getArrayComponent().toString(indent + INDENT));

        return text.toString();
    }
    
    
    public final CountImpl getArraySizeComponent()
    {
	    // case of implicit size
        if (isImplicitSize())
        {
            if (implicitElementCount == null)
                implicitElementCount = new CountImpl();
            return implicitElementCount;
        }
        
	    // if variable size, try to find the size component up the component tree
        else if (isVariableSize())
	    {
	        String sizeIdRef = elementCount.getHref().substring(1);
	        DataComponent parentComponent = this.parent;
	        DataComponent sizeComponent = this;
	        
            while (parentComponent != null)
            {
                boolean found = false;
                for (int i=0; i<parentComponent.getComponentCount(); i++)
                {
                    sizeComponent = parentComponent.getComponent(i);
                    if (sizeComponent.isSetId() && sizeComponent.getId().equals(sizeIdRef))
                    {
                        found = true;
                        break;
                    }
                }
                
                if (found)
                    break;
                
                parentComponent = parentComponent.getParent();
            }
            
            if (parentComponent == null)
                throw new IllegalStateException("Could not found array size component with ID " + sizeIdRef);
            
            elementCount.setValue((Count)sizeComponent);
	    }
	    
        if (elementCount.hasValue())
            return (CountImpl)elementCount.getValue();
        else
            throw new IllegalStateException("The array element count hasn't been set. Please use one of setElementCount() or setFixedSize() methods");
    }
	
	
	private final AbstractDataComponentImpl getArrayComponent()
	{
	    return (AbstractDataComponentImpl)elementType.getValue();
	}
}
