/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.sensorML;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;

import net.opengis.gml.v32.impl.GMLFactory;
import org.vast.ogc.geopose.Pose;
import org.vast.swe.SWEConstants;
import org.vast.util.Asserts;
import net.opengis.OgcPropertyList;
import net.opengis.gml.v32.AbstractGeometry;
import net.opengis.gml.v32.Point;
import net.opengis.gml.v32.Reference;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.PhysicalSystem;
import net.opengis.sensorml.v20.SpatialFrame;
import net.opengis.sensorml.v20.TemporalFrame;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Text;
import net.opengis.swe.v20.Time;
import net.opengis.swe.v20.Vector;


/**
 * POJO class for XML type PhysicalSystemType(@http://www.opengis.net/sensorml/2.0).
 *
 * This is a complex type.
 */
public class PhysicalSystemImpl extends AggregateProcessImpl implements PhysicalSystem
{
    private static final long serialVersionUID = -3256102933935938621L;
    protected Reference attachedTo;
    protected ArrayList<SpatialFrame> localReferenceFrameList = new ArrayList<SpatialFrame>();
    protected ArrayList<TemporalFrame> localTimeFrameList = new ArrayList<TemporalFrame>();
    protected OgcPropertyList<Serializable> positionList = new OgcPropertyList<Serializable>();
    protected OgcPropertyList<Time> timePositionList = new OgcPropertyList<Time>();
    
    
    @Override
    public QName getQName()
    {
        return PhysicalSystem.DEFAULT_QNAME;
    }
    
    
    /**
     * Gets the attachedTo property
     */
    @Override
    public Reference getAttachedTo()
    {
        return attachedTo;
    }
    
    
    /**
     * Checks if attachedTo is set
     */
    @Override
    public boolean isSetAttachedTo()
    {
        return (attachedTo != null);
    }
    
    
    /**
     * Sets the attachedTo property
     */
    @Override
    public void setAttachedTo(Reference attachedTo)
    {
        this.attachedTo = attachedTo;
    }
    
    
    /**
     * Gets the list of localReferenceFrame properties
     */
    @Override
    public List<SpatialFrame> getLocalReferenceFrameList()
    {
        return localReferenceFrameList;
    }
    
    
    /**
     * Returns number of localReferenceFrame properties
     */
    @Override
    public int getNumLocalReferenceFrames()
    {
        if (localReferenceFrameList == null)
            return 0;
        return localReferenceFrameList.size();
    }
    
    
    /**
     * Adds a new localReferenceFrame property
     */
    @Override
    public void addLocalReferenceFrame(SpatialFrame localReferenceFrame)
    {
        this.localReferenceFrameList.add(localReferenceFrame);
    }
    
    
    /**
     * Gets the list of localTimeFrame properties
     */
    @Override
    public List<TemporalFrame> getLocalTimeFrameList()
    {
        return localTimeFrameList;
    }
    
    
    /**
     * Returns number of localTimeFrame properties
     */
    @Override
    public int getNumLocalTimeFrames()
    {
        if (localTimeFrameList == null)
            return 0;
        return localTimeFrameList.size();
    }
    
    
    /**
     * Adds a new localTimeFrame property
     */
    @Override
    public void addLocalTimeFrame(TemporalFrame localTimeFrame)
    {
        this.localTimeFrameList.add(localTimeFrame);
    }
    
    
    /**
     * Gets the list of position properties
     */
    @Override
    public OgcPropertyList<Serializable> getPositionList()
    {
        return positionList;
    }
    
    
    /**
     * Returns number of position properties
     */
    @Override
    public int getNumPositions()
    {
        if (positionList == null)
            return 0;
        return positionList.size();
    }
    
    
    /**
     * Adds a new positionAsText property
     */
    @Override
    public void addPositionAsText(Text position)
    {
        this.positionList.add(position);
    }
    
    
    /**
     * Adds a new positionAsPoint property
     */
    @Override
    public void addPositionAsPoint(Point position)
    {
        this.positionList.add(position);
    }
    
    
    /**
     * Adds a new positionAsAbstractProcess property
     */
    public void addPositionAsPose(Pose pose)
    {
        this.positionList.add(pose);
    }
    
    
    /**
     * Adds a new positionAsVector property
     */
    @Override
    public void addPositionAsVector(Vector position)
    {
        this.positionList.add(position);
    }
    
    
    /**
     * Adds a new positionAsDataRecord property
     */
    @Override
    public void addPositionAsDataRecord(DataRecord position)
    {
        this.positionList.add(position);
    }
    
    
    /**
     * Adds a new positionAsDataArray1 property
     */
    @Override
    public void addPositionAsDataArray1(DataArray position)
    {
        this.positionList.add(position);
    }
    
    
    /**
     * Adds a new positionAsAbstractProcess property
     */
    @Override
    public void addPositionAsAbstractProcess(AbstractProcess position)
    {
        this.positionList.add(position);
    }
    
    
    /**
     * Gets the list of timePosition properties
     */
    @Override
    public OgcPropertyList<Time> getTimePositionList()
    {
        return timePositionList;
    }
    
    
    /**
     * Returns number of timePosition properties
     */
    @Override
    public int getNumTimePositions()
    {
        if (timePositionList == null)
            return 0;
        return timePositionList.size();
    }
    
    
    /**
     * Adds a new timePosition property
     */
    @Override
    public void addTimePosition(Time timePosition)
    {
        this.timePositionList.add(timePosition);
    }


    /**
     * Returns the geometry of the first position in the positionList
     */
    @Override
    public AbstractGeometry getGeometry()
    {
        if (!positionList.isEmpty())
        {
            var pos = positionList.get(0);
            if (pos instanceof AbstractGeometry geometry)
                return geometry;
            else if (pos instanceof Pose pose)
                return pose.toLocation();
            else if (pos instanceof DataComponent component) {
                var geometry = parseGeometry(component);
                if (geometry != null)
                    return geometry;
            }
        }
        
        return super.getGeometry();
    }


    @Override
    public void setGeometry(AbstractGeometry geom)
    {
        Asserts.checkArgument(geom instanceof Point, "geom must be a Point");
        addPositionAsPoint((Point)geom);
    }


    /**
     * Helper method to parse the geometry from a DataComponent
     *
     * @param dataComponent the DataComponent to extract the geometry from
     * @return the AbstractGeometry if found, null otherwise
     */
    private AbstractGeometry parseGeometry(DataComponent dataComponent)
    {
        // The DataComponent is itself a Vector
        if (dataComponent instanceof Vector vector) {
            return parseGeometry(vector);
        }

        // Find the sensor location field in the DataRecord and get the Vector from it
        for (int i = 0; i < dataComponent.getComponentCount(); i++) {
            DataComponent component = dataComponent.getComponent(i);
            if (component instanceof Vector vector && Objects.equals(vector.getDefinition(), SWEConstants.DEF_SENSOR_LOC)) {
                return parseGeometry(vector);
            }
        }

        return null;
    }


    /**
     * Helper method to parse the geometry from a Vector
     *
     * @param vector the Vector to extract the geometry from
     * @return the AbstractGeometry if found, null otherwise
     */
    private AbstractGeometry parseGeometry(Vector vector)
    {
        double[] coordinates = new double[vector.getNumCoordinates()];
        for (int i = 0; i < vector.getNumCoordinates(); i++) {
            coordinates[i] = vector.getComponent(i).getData().getDoubleValue();
        }

        Point point = new GMLFactory(true).newPoint();
        if (vector.isSetReferenceFrame()) {
            point.setSrsName(vector.getReferenceFrame());
            point.setSrsDimension(vector.getNumCoordinates());
        }
        point.setPos(coordinates);

        return point;
    }
}
