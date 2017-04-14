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
import net.opengis.OgcPropertyImpl;
import net.opengis.OgcPropertyList;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.Category;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponentVisitor;
import net.opengis.swe.v20.ValidationException;


/**
 * <p>
 * Exclusive list of DataComponents (Choice)
 * 08-2014: Updated to implement new API autogenerated from XML schema
 * </p>
 *
 * @author Alex Robin
 * */
public class DataChoiceImpl extends AbstractDataComponentImpl implements DataChoice
{
	protected static int UNSELECTED = -1;
	protected static String UNSELECTED_ERROR = "No item was selected in DataChoice ";
	protected int selected = UNSELECTED;
	protected Category choiceValue;
    protected DataComponentPropertyList<DataComponent> itemList;
    

    public DataChoiceImpl()
    {
    	this.itemList = new DataComponentPropertyList<DataComponent>(this);
    }
    
    
    public DataChoiceImpl(int size)
    {
        this.itemList = new DataComponentPropertyList<DataComponent>(this, size);
    }
    
    
    @Override
    public DataChoiceImpl copy()
    {
        DataChoiceImpl newObj = new DataChoiceImpl(itemList.size());
        super.copyTo(newObj);
        newObj.selected = selected;
        
        if (choiceValue != null)
            newObj.choiceValue = ((CategoryImpl)choiceValue).copy();
        
        itemList.copyTo(newObj.itemList);
        return newObj;
    }
    
    
    @Override
    protected void updateStartIndex(int startIndex)
    {
        // TODO don't think there is anything to do here
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
    public void addComponent(String name, DataComponent component)
    {
        addItem(name, component);
    }


    @Override
    public AbstractDataComponentImpl getComponent(int index)
    {
        return (AbstractDataComponentImpl)itemList.get(index);
    }
    
    
    @Override
    public AbstractDataComponentImpl getComponent(String name)
    {
        return (AbstractDataComponentImpl)itemList.get(name);
    }
    
    
    @Override
    public int getComponentIndex(String name)
    {
        DataComponent comp = itemList.get(name);
        if (comp == null)
            return -1;
        return itemList.indexOf(comp);
    }
    
    
    @Override
    public AbstractDataComponentImpl removeComponent(int index)
    {
        DataComponent component = itemList.remove(index);
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
        // HACK makes sure scalar count was properly computed
        if (scalarCount < 0)
            this.assignNewDataBlock();
        
        // must always be a datablock mixed!
    	DataBlockMixed mixedBlock = (DataBlockMixed)dataBlock;
    	this.dataBlock = mixedBlock;

		// first value = index of selected component
    	int index = mixedBlock.blockArray[0].getIntValue();    	
    	if (index == UNSELECTED)
    		return;
    	
    	checkIndex(index);
    	this.selected = index;
    	
    	// also assign block to selected child
    	AbstractDataComponentImpl selectedChild = (AbstractDataComponentImpl)itemList.get(index);
    	selectedChild.setData(mixedBlock.blockArray[1]);
    }
    
    
    @Override
    public void clearData()
    {
        this.dataBlock = null;
        for (DataComponent item: itemList)
            item.clearData();
    }
    
    
    @Override
    public void validateData(List<ValidationException> errorList)
    {
        ((AbstractDataComponentImpl)itemList.get(selected)).validateData(errorList);
    }
    
    
    @Override
    public AbstractDataBlock createDataBlock()
    {
    	DataBlockMixed newBlock = new DataBlockMixed(2);
    	newBlock.blockArray[0] = new DataBlockInt(1);

    	// if one item is selected, create data block for it
    	if (selected >= 0)
    	{
    	    // generate selected component data block
    	    AbstractDataComponentImpl component = ((AbstractDataComponentImpl)itemList.get(selected));
            component.assignNewDataBlock();
            AbstractDataBlock childData = (AbstractDataBlock)component.getData();
            
            // assign it to the new block array
            newBlock.blockArray[0].setIntValue(selected);
            newBlock.blockArray[1] = childData;
            newBlock.atomCount = childData.atomCount + 1;
    	}
    	
    	// otherwise keep things undefined
    	// selection will have to be done before the data is valid for processing or writing
    	else
    	{
    		newBlock.blockArray[0].setIntValue(UNSELECTED);
    		newBlock.atomCount = 1;
    	}
    	
    	this.scalarCount = newBlock.atomCount;
        return newBlock;
    }
    
    
    /**
     * Check that the integer index given is in range: 0 to item list size
     * @param index int
     * @throws DataException
     */
    protected void checkIndex(int index)
    {
        // error if index is out of range
        if ((index >= itemList.size()) || (index < 0))
            throw new IndexOutOfBoundsException("Index " + index + " is out of bounds");
    }


    @Override
    public int getComponentCount()
    {
        return itemList.size();
    }
    
    
    public int getSelected()
	{
		return selected;
	}
    
    
    @Override
    public AbstractDataComponentImpl getSelectedItem()
    {
    	if (selected < 0)
    		return null;
    	
    	return getComponent(selected);
    }
    

    @Override
    public void setSelectedItem(int index)
	{
		checkIndex(index);
		this.selected = index;
		
		if (this.dataBlock != null)
		{
			int prevAtomCount = dataBlock.atomCount;
			
			// generate selected component data block
			DataComponent selectedComponent = itemList.get(index);
            selectedComponent.assignNewDataBlock();
            AbstractDataBlock childData = (AbstractDataBlock)selectedComponent.getData();
            
            // assign it to the choice data block
			((DataBlockMixed)dataBlock).blockArray[0].setIntValue(index);
			((DataBlockMixed)dataBlock).blockArray[1] = childData;
			dataBlock.atomCount = childData.atomCount + 1;
			
			if (parent != null)
				parent.updateAtomCount(dataBlock.atomCount - prevAtomCount);
		}
	}
	
	
	@Override
	public void setSelectedItem(String name)
	{
		int index = getComponentIndex(name);
		if (index < 0)
			throw new IllegalStateException("Invalid component: " + name);
		
		setSelectedItem(index);
	}

	
    public void unselect()
    {
        this.selected = UNSELECTED;
        if (this.dataBlock != null)
        {
            ((DataBlockMixed)dataBlock).blockArray[0].setIntValue(UNSELECTED);
            ((DataBlockMixed)dataBlock).blockArray[1] = null;
            dataBlock.atomCount = 1;
        }
    }


    @Override
    public String toString(String indent)
    {
        StringBuffer text = new StringBuffer();
        text.append("DataChoice\n");

        for (int i=0; i<itemList.size(); i++)
        {
            text.append(indent);
            text.append(itemList.getProperty(i).getName());
            text.append(": ");
            text.append(getComponent(i).toString(indent + INDENT));
            if (i < itemList.size()-1)
                text.append('\n');
        }

        return text.toString();
    }
    
    
	@Override
	public boolean hasConstraints()
	{
	    for (DataComponent item: itemList)
        {
            if (item.hasConstraints())
                return true;
        }
        
        return false;
	}
	
	
	/* ************************************ */
    /*  Auto-generated Getters and Setters  */    
    /* ************************************ */
	
	/**
     * Gets the choiceValue property
     */
    @Override
    public Category getChoiceValue()
    {
        return choiceValue;
    }
    
    
    /**
     * Checks if choiceValue is set
     */
    @Override
    public boolean isSetChoiceValue()
    {
        return (choiceValue != null);
    }
    
    
    /**
     * Sets the choiceValue property
     */
    @Override
    public void setChoiceValue(Category choiceValue)
    {
        this.choiceValue = choiceValue;
    }
    
    
    /**
     * Gets the list of item properties
     */
    @Override
    public OgcPropertyList<DataComponent> getItemList()
    {
        return itemList;
    }
    
    
    /**
     * Returns number of item properties
     */
    @Override
    public int getNumItems()
    {
        return itemList.size();
    }
    
    
    /**
     * Gets the item property with the given name
     */
    @Override
    public DataComponent getItem(String name)
    {
        return itemList.get(name);
    }
    
    
    /**
     * Adds a new item property
     */
    @Override
    public void addItem(String name, DataComponent item)
    {
        itemList.add(new OgcPropertyImpl<DataComponent>(name, item));
    }


    @Override
    public void accept(DataComponentVisitor visitor)
    {
        visitor.visit(this);
    }
}
