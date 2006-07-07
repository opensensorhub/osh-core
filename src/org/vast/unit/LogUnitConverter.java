/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License Version
 1.1 (the "License"); you may not use this file except in compliance with
 the License. You may obtain a copy of the License at
 http://www.mozilla.org/MPL/MPL-1.1.html
 
 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.
 
 The Original Code is the "SensorML DataProcessing Engine".
 
 The Initial Developer of the Original Code is the
 University of Alabama in Huntsville (UAH).
 Portions created by the Initial Developer are Copyright (C) 2006
 the Initial Developer. All Rights Reserved.
 
 Contributor(s): 
    Alexandre Robin <robin@nsstc.uah.edu>
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.unit;


/**
 * <p><b>Title:</b><br/>
 * Log Unit Converter
 * </p>
 *
 * <p><b>Description:</b><br/>
 * 
 * </p>
 *
 * <p>Copyright (c) 2005</p>
 * @author Alexandre Robin
 * @date May 4, 2006
 * @version 1.0
 */
public class LogUnitConverter extends AbstractUnitConverter
{
    protected boolean srcLog = false;
    protected boolean destLog = false;
    protected double srcLogBase = 1.0;
    protected double destLogBase = 1.0;
    protected double srcLogScale = 1.0;
    protected double destLogScale = 1.0;
    protected double srcScaleFactor = 1.0;
    protected double destScaleFactor = 1.0;
      
    
    /**
     * Default constructor using given units as source and destination
     * @param sourceUnit
     * @param destinationUnit
     */
	public LogUnitConverter(Unit sourceUnit, Unit destinationUnit)
	{
        super(sourceUnit, destinationUnit);
        
        if (conversionPossible)
        {
            this.srcScaleFactor = sourceUnit.getScaleToSI();
            this.destScaleFactor = destinationUnit.getScaleToSI();
            conversionNeeded = true;
        }
	}
	
	
    @Override
	public double convert(double value)
	{
        if (!conversionPossible)
            throw new IllegalStateException("Units are not compatible: Conversion is impossible");
        
        if (conversionNeeded)
        {
			if (srcLog)
            {
			    value /= srcLogScale;
			    value = Math.pow(srcLogBase, value);
			    value *= srcScaleFactor;
            }
            
            else if (destLog)
            {
                value *= srcScaleFactor;
                value = logN(value, destLogBase);
                value *= destLogScale;
            }
            
            return value;
        }
		
        return value;
	}
        
    
    /**
     * Computes the logarithm base N of the given value
     * @param value
     * @param base
     * @return
     */
    private double logN(double value, double base)
    {
        return Math.log(value)/Math.log(base);
    }
}
