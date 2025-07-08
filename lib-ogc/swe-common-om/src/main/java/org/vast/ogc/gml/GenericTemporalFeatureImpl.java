/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are copyright (C) 2018, Sensia Software LLC
 All Rights Reserved. This software is the property of Sensia Software LLC.
 It cannot be duplicated, used, or distributed without the express written
 consent of Sensia Software LLC.
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.ogc.gml;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import javax.xml.namespace.QName;
import org.vast.util.TimeExtent;
import net.opengis.gml.v32.AbstractTimeGeometricPrimitive;
import net.opengis.gml.v32.TimeInstant;
import net.opengis.gml.v32.TimePeriod;
import net.opengis.gml.v32.TimePosition;
import net.opengis.gml.v32.impl.GMLFactory;


public class GenericTemporalFeatureImpl extends GenericFeatureImpl
{
    private static final long serialVersionUID = 2901698262626551458L;
    public static final QName PROP_VALID_TIME = new QName(GMLStaxBindings.NS_URI, "validTime", GMLStaxBindings.NS_PREFIX_GML);
    

    // constructor to be called by subclasses when overriding getType directly
    protected GenericTemporalFeatureImpl()
    {
        this.properties = new LinkedHashMap<>();
    }
    
    
    public GenericTemporalFeatureImpl(String type)
    {
        super(type);
    }
    
    
    public GenericTemporalFeatureImpl(QName qname)
    {
        super(qname);
    }
    

    @Override
    public TimeExtent getValidTime()
    {
        AbstractTimeGeometricPrimitive validTime = (AbstractTimeGeometricPrimitive)properties.get(PROP_VALID_TIME);
        if (validTime == null)
            return null;
        
        return GMLUtils.timePrimitiveToTimeExtent(validTime);
    }
    
    
    public void setValidTimeInstant(OffsetDateTime dateTime)
    {
        GMLFactory gmlFac = new GMLFactory();
        TimePosition time = gmlFac.newTimePosition(dateTime);
        TimeInstant instant = gmlFac.newTimeInstant(time);
        setProperty(PROP_VALID_TIME, instant);
    }
    
    
    public void setValidTimePeriod(OffsetDateTime beginTime, OffsetDateTime endTime)
    {
        if (beginTime.equals(endTime))
        {
            setValidTimeInstant(beginTime);
            return;
        }
        
        GMLFactory gmlFac = new GMLFactory();
        TimePosition begin = gmlFac.newTimePosition(beginTime);
        TimePosition end = gmlFac.newTimePosition(endTime);
        TimePeriod period = gmlFac.newTimePeriod(begin, end);
        setProperty(PROP_VALID_TIME, period);
    }
    
    
    public void setValidTime(TimeExtent timeExtent)
    {
        setProperty(PROP_VALID_TIME, GMLUtils.timeExtentToTimePrimitive(timeExtent, true));
    }
}
