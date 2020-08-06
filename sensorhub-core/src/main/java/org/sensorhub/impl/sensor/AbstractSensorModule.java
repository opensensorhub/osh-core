/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import net.opengis.OgcProperty;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.gml.v32.Point;
import net.opengis.gml.v32.TimeIndeterminateValue;
import net.opengis.gml.v32.TimePosition;
import net.opengis.gml.v32.impl.GMLFactory;
import net.opengis.sensorml.v20.AbstractPhysicalProcess;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Vector;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.sensor.ISensorControlInterface;
import org.sensorhub.api.sensor.ISensorDataInterface;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.api.sensor.PositionConfig.LLALocation;
import org.sensorhub.api.sensor.PositionConfig.EulerOrientation;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.api.sensor.SensorEvent;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.utils.MsgUtils;
import org.vast.ogc.om.SamplingPoint;
import org.vast.sensorML.PhysicalSystemImpl;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;


/**
 * <p>
 * Class providing default implementation of common sensor API methods.<br/>
 * This can be used as the base for most sensor driver implementations as it
 * generates defaults for the following:
 * <ul>
 * <li>A random Unique ID using a UUID (the same is used between restarts)</li>
 * <li>A short XML ID</li>
 * <li>A default SensorML description including IDs, temporal validity, I/Os
 * and position (location + orientation) if the sensor configuration provides
 * static location and/or orientation</li>
 * <li>A feature of interest if the sensor configuration provides static
 * location</li>
 * </ul>
 * </p>
 * <p>
 * All of these items can be overriden by derived classes.<br/>
 * It also provides helper methods to implement automatic reconnection.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <ConfigType> 
 * @since Oct 30, 2014
 */
public abstract class AbstractSensorModule<ConfigType extends SensorConfig> extends AbstractModule<ConfigType> implements ISensorModule<ConfigType>
{
    public final static String DEFAULT_XMLID_PREFIX = "SENSOR_";
    protected final static String LOCATION_OUTPUT_ID = "SENSOR_LOCATION";
    protected final static String LOCATION_OUTPUT_NAME = "sensorLocation";
    protected final static String ERROR_NO_UPDATE = "Sensor Description update is not supported by driver ";
    protected final static String ERROR_NO_HISTORY = "History of sensor description is not supported by driver ";
    protected final static String ERROR_NO_ENTITIES = "Multiple entities are not supported by driver ";
    
    protected final static String UUID_URI_PREFIX = "urn:uuid:";
    protected final static String STATE_UNIQUE_ID = "UniqueID";
    protected final static String STATE_LAST_SML_UPDATE = "LastUpdatedSensorDescription";
    
    private Map<String, ISensorDataInterface> obsOutputs = new LinkedHashMap<String, ISensorDataInterface>();
    private Map<String, ISensorDataInterface> statusOutputs = new LinkedHashMap<String, ISensorDataInterface>();
    private Map<String, ISensorControlInterface> controlInputs = new LinkedHashMap<String, ISensorControlInterface>();
        
    protected DefaultLocationOutput locationOutput;
    protected AbstractProcess sensorDescription = new PhysicalSystemImpl();
    protected long lastUpdatedSensorDescription = Long.MIN_VALUE;
    protected Object sensorDescLock = new Object();
    
    protected String xmlID;
    protected String uniqueID;
    protected boolean randomUniqueID;
    protected AbstractFeature foi = null;
            
    
    @Override
    public void init() throws SensorHubException
    {
        // reset internal state
        this.uniqueID = null;
        this.xmlID = null;
        this.foi = null;
        this.locationOutput = null;
        this.sensorDescription = new PhysicalSystemImpl();
        removeAllOutputs();
        removeAllControlInputs();        
    }
    
    
    @Override
    public final synchronized void init(ConfigType config) throws SensorHubException
    {
        // this sets config and calls init() of derived class
        super.init(config);
        
        // generate random unique ID if sensor driver hasn't generated one
        // if a random UUID has already been generated it will be restored
        // by loadState() method that is called after init()
        if (this.uniqueID == null)
        {
            String uuid = UUID.randomUUID().toString();
            this.uniqueID = UUID_URI_PREFIX + uuid;
        
            if (this.xmlID == null)
                generateXmlIDFromUUID(uuid);
            
            this.randomUniqueID = true;
        }
        
        // add location output if a location is provided
        LLALocation loc = config.getLocation();
        if (loc != null && locationOutput == null)
        {
            addLocationOutput(Double.NaN);
            locationOutput.updateLocation(System.currentTimeMillis()/1000., loc.lon, loc.lat, loc.alt);
        }
            
    }


    /**
     * Call this method to add each sensor observation or status output
     * @param dataInterface interface to add as sensor output
     * @param isStatus set to true when registering a status output
     */
    protected void addOutput(ISensorDataInterface dataInterface, boolean isStatus)
    {
        if (isStatus)
            statusOutputs.put(dataInterface.getName(), dataInterface);
        else
            obsOutputs.put(dataInterface.getName(), dataInterface);
    }
    
    
    /**
     * Helper method to add a location output so that all sensors can update their location
     * in a consistent manner.
     * @param updatePeriod estimated location update period or NaN if sensor is mostly static
     */
    protected void addLocationOutput(double updatePeriod)
    {
        // TODO deal with other CRS than 4979
        locationOutput = new DefaultLocationOutputLLA(this, updatePeriod);
        addOutput(locationOutput, true);
    }
    
    
    /**
     * Removes all outputs previously added to this sensor
     */
    protected void removeAllOutputs()
    {
        statusOutputs.clear();
        obsOutputs.clear();
    }
    
    
    /**
     * Call this method to add each sensor control input
     * @param controlInterface interface to add as sensor control input
     */
    protected void addControlInput(ISensorControlInterface controlInterface)
    {
        controlInputs.put(controlInterface.getName(), controlInterface);
    }
    
    
    /**
     * Removes all control inputs previously added to this sensor
     */
    protected void removeAllControlInputs()
    {
        controlInputs.clear();
    }
    
    
    @Override
    public String getUniqueIdentifier()
    {
        return uniqueID;
    }
    

    @Override
    public AbstractProcess getCurrentDescription()
    {
        synchronized (sensorDescLock)
        {
            if (sensorDescription == null || !sensorDescription.isSetIdentifier())
                updateSensorDescription();
        
            return sensorDescription;
        }
    }
    
    
    @Override
    public long getLastDescriptionUpdate()
    {
        return lastUpdatedSensorDescription;
    }
    
    
    /**
     * This method should be called whenever the sensor description needs to be regenerated.<br/>
     * This default implementation reads the base description from the SensorML file if provided
     * and then appends the unique sensor identifier, time validity and the description of all
     * registered outputs and control inputs. This will also update the lastUpdatedSensorDescription
     * time stamp and send a SENSOR_CHANGED event when 
     * @throws SensorException
     */
    protected void updateSensorDescription()
    {
        synchronized (sensorDescLock)
        {
            // by default we return the static description provided in config
            String smlUrl = config.getSensorDescriptionURL();
            if (smlUrl != null && smlUrl.length() > 0)
            {
                try
                {
                    SMLUtils utils = new SMLUtils(SMLUtils.V2_0);
                    InputStream is = new URL(smlUrl).openStream();
                    sensorDescription = (AbstractPhysicalProcess)utils.readProcess(is);
                }
                catch (IOException e)
                {
                    throw new IllegalStateException("Error while parsing static SensorML description for sensor " +
                                                    MsgUtils.moduleString(this), e);
                }
            }
            else
            {
                sensorDescription = new PhysicalSystemImpl();
            }
            
            //////////////////////////////////////////////////////////////
            // add stuffs if not already defined in static SensorML doc //
            //////////////////////////////////////////////////////////////
            if (lastUpdatedSensorDescription == Long.MIN_VALUE)
                lastUpdatedSensorDescription = System.currentTimeMillis();
            double newValidityTime = lastUpdatedSensorDescription / 1000.;
            
            // default IDs
            String gmlId = sensorDescription.getId();
            if (gmlId == null || gmlId.length() == 0)
                sensorDescription.setId(xmlID);
            if (!sensorDescription.isSetIdentifier())
                sensorDescription.setUniqueIdentifier(uniqueID);
            
            // description
            if (sensorDescription.getName() == null && config.name != null)
                sensorDescription.setName(config.name);
            
            // time validity
            if (sensorDescription.getNumValidTimes() == 0)
            {
                GMLFactory fac = new GMLFactory();
                TimePosition begin = fac.newTimePosition(newValidityTime);
                TimePosition end = fac.newTimePosition();
                end.setIndeterminatePosition(TimeIndeterminateValue.NOW);
                sensorDescription.addValidTimeAsTimePeriod(fac.newTimePeriod(begin, end));
            }
            
            // outputs
            if (sensorDescription.getNumOutputs() == 0)
            {
                for (Entry<String, ? extends ISensorDataInterface> output: getAllOutputs().entrySet())
                {
                    DataComponent outputDesc = output.getValue().getRecordDescription();
                    if (outputDesc == null)
                        continue;
                    outputDesc = outputDesc.copy();
                    sensorDescription.addOutput(output.getKey(), outputDesc);
                }
            }
            
            // control parameters
            if (sensorDescription.getNumParameters() == 0)
            {
                for (Entry<String, ? extends ISensorControlInterface> param: getCommandInputs().entrySet())
                {
                    DataComponent paramDesc = param.getValue().getCommandDescription();
                    if (paramDesc == null)
                        continue;
                    paramDesc = paramDesc.copy();
                    paramDesc.setUpdatable(true);
                    sensorDescription.addParameter(param.getKey(), paramDesc);
                }
            }
            
            // sensor position
            String localFrameRef = '#' + getLocalFrameID();
            GeoPosHelper fac = new GeoPosHelper();
            Vector locVector = null;
            Vector orientVector = null;
            
            if (sensorDescription instanceof AbstractPhysicalProcess)
            {
                AbstractPhysicalProcess sensorDescWithPos = (AbstractPhysicalProcess)sensorDescription;
                
                // get static location from config if available
                LLALocation loc = config.getLocation();
                if (loc != null)
                {
                    // update GML location if point template was provided
                    if (sensorDescWithPos.getNumPositions() > 0)
                    {
                        Object smlLoc = sensorDescWithPos.getPositionList().get(0);
                        if (smlLoc instanceof Point) 
                        {
                            Point gmlLoc = (Point)smlLoc;
                            double[] pos;
                            
                            if (Double.isNaN(loc.alt))
                            {
                                gmlLoc.setSrsName(SWEHelper.getEpsgUri(4326));
                                pos = new double[2];
                            }
                            else
                            {
                                gmlLoc.setSrsName(SWEHelper.getEpsgUri(4979));
                                pos = new double[3];
                                pos[2] = loc.alt;
                            }
                                            
                            pos[0] = loc.lat;
                            pos[1] = loc.lon;
                            gmlLoc.setPos(pos);
                        }
                    }
                    
                    // else include location as a SWE vector
                    else
                    {
                        locVector = fac.newLocationVectorLLA(SWEConstants.DEF_SENSOR_LOC);
                        locVector.assignNewDataBlock();
                        locVector.getComponent(0).getData().setDoubleValue(loc.lat);
                        locVector.getComponent(1).getData().setDoubleValue(loc.lon);
                        locVector.getComponent(2).getData().setDoubleValue(loc.alt);
                        locVector.setLocalFrame(localFrameRef);
                    }
                }
                
                // get static orientation from config if available
                EulerOrientation orient = config.getOrientation();
                if (orient != null)
                {
                    orientVector = fac.newEulerOrientationNED(SWEConstants.DEF_SENSOR_ORIENT);
                    orientVector.assignNewDataBlock();
                    orientVector.getComponent(0).getData().setDoubleValue(orient.heading);
                    orientVector.getComponent(1).getData().setDoubleValue(orient.pitch);
                    orientVector.getComponent(2).getData().setDoubleValue(orient.roll);
                    orientVector.setLocalFrame(localFrameRef);
                }
                
                if (locVector != null || orientVector != null)
                {
                    if (orientVector == null) // only location
                        sensorDescWithPos.addPositionAsVector(locVector);
                    else if (locVector == null) // only orientation
                        sensorDescWithPos.addPositionAsVector(orientVector);
                    else // both
                    {
                        DataRecord pos = fac.createDataRecord()
                                .addField("location", locVector)
                                .addField("orientation", orientVector)
                                .build();
                        sensorDescWithPos.addPositionAsDataRecord(pos);
                    }
                }
                
                // else reference location output if any
                else if (locationOutput != null)
                {
                    // if update rate is high, set sensorML location as link to output
                    if (locationOutput.getAverageSamplingPeriod() < 3600.)
                    {
                        OgcProperty<?> linkProp = SWEHelper.newLinkProperty("#" + LOCATION_OUTPUT_ID);
                        sensorDescWithPos.getPositionList().add(linkProp);
                    }
                }
            }
        }
    }
    
    
    protected void notifyNewDescription(long updateTime)
    {
        // send event
        lastUpdatedSensorDescription = updateTime;
        eventHandler.publishEvent(new SensorEvent(updateTime, this, SensorEvent.Type.SENSOR_CHANGED));
    }
        
    
    protected String getLocalFrameID()
    {
        return "REF_FRAME_" + xmlID;
    }


    @Override
    public Map<String, ISensorDataInterface> getAllOutputs()
    {
        Map<String, ISensorDataInterface> allOutputs = new LinkedHashMap<String, ISensorDataInterface>();  
        allOutputs.putAll(obsOutputs);
        allOutputs.putAll(statusOutputs);
        return Collections.unmodifiableMap(allOutputs);
    }


    @Override
    public Map<String, ISensorDataInterface> getStatusOutputs()
    {
        return Collections.unmodifiableMap(statusOutputs);
    }


    @Override
    public Map<String, ISensorDataInterface> getObservationOutputs()
    {
        return Collections.unmodifiableMap(obsOutputs);
    }


    @Override
    public Map<String, ISensorControlInterface> getCommandInputs()
    {
        return Collections.unmodifiableMap(controlInputs);
    }
    
    
    @Override
    public AbstractFeature getCurrentFeatureOfInterest()
    {
        // add default feature of interest if location is set in config
        LLALocation loc = config.getLocation();
        if (foi == null && loc != null)
        {            
            SamplingPoint sf = new SamplingPoint();
            sf.setId("FOI_" + xmlID);
            sf.setUniqueIdentifier(uniqueID + "-foi");
            if (config.name != null)
                sf.setName(config.name);
            sf.setDescription("Sampling point for " + config.name);
            sf.setHostedProcedureUID(uniqueID);
            Point point = new GMLFactory().newPoint();
            point.setSrsName(SWEConstants.REF_FRAME_4979);
            point.setSrsDimension(3);
            point.setPos(new double[] {loc.lat, loc.lon, loc.alt});
            sf.setShape(point);
            this.foi = sf;
        }
        
        return foi;
    }


    @Override
    public void updateConfig(ConfigType config) throws SensorHubException
    {
        super.updateConfig(config);
        if (config.sensorML != null)
        {
            // TODO detect if SensorML has really changed
            updateSensorDescription();
            notifyNewDescription(System.currentTimeMillis());
        }
    }

    
    @Override
    public void loadState(IModuleStateManager loader) throws SensorHubException
    {
        super.loadState(loader);
        
        // set unique ID to the one previously saved
        String uniqueID = loader.getAsString(STATE_UNIQUE_ID);
        if (uniqueID != null && randomUniqueID)
        {
            this.uniqueID = uniqueID;
            this.generateXmlIDFromUUID(uniqueID);
        }
        
        Long lastUpdateTime = loader.getAsLong(STATE_LAST_SML_UPDATE);
        if (lastUpdateTime != null)
            this.lastUpdatedSensorDescription = lastUpdateTime;
    }
    
    
    @Override
    public void saveState(IModuleStateManager saver) throws SensorHubException
    {
        super.saveState(saver);
        
        // save unique ID if it was automatically generated as UUID
        if (uniqueID != null && randomUniqueID)
            saver.put(STATE_UNIQUE_ID, this.uniqueID);
        
        saver.put(STATE_LAST_SML_UPDATE, this.lastUpdatedSensorDescription);
        saver.flush();
    }
    
    
    protected String getDefaultIdSuffix()
    {
        String localID = getLocalID();
        int endIndex = Math.min(localID.length(), 8);
        String idSuffix = localID.substring(0, endIndex);
        return idSuffix.replaceAll(" ", "");
    }
    
    
    /**
     * Generates the sensor unique ID by concatenating a prefix and suffix.<br/>
     * If no suffix is provided, the first 8 characters of the local ID are used
     * to make the ID more unique
     * @param prefix Unique ID prefix
     * @param suffix Unique ID suffix or null if autogenerated
     */
    protected void generateUniqueID(String prefix, String suffix)
    {
        if (suffix == null)
            suffix = getDefaultIdSuffix();
        else
            suffix.replaceAll(" ", ""); // remove spaces
        
        this.uniqueID = prefix + suffix;
    }
    
    
    /**
     * Generates the sensor XML ID by concatenating a prefix and suffix.<br/>
     * If no suffix is provided, the first 8 characters of the local ID are used
     * to make the ID more unique
     * @param prefix XML ID prefix
     * @param suffix XML ID suffix or null if autogenerated
     */
    protected void generateXmlID(String prefix, String suffix)
    {
        if (suffix == null)
            suffix = getDefaultIdSuffix();
        
        suffix.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        this.xmlID = prefix + suffix.toUpperCase();
    }
    
    
    protected void generateXmlIDFromUUID(String uuid)
    {
        int endIndex = Math.min(8, uuid.length());
        String shortId = uuid.substring(0, endIndex);
        this.xmlID = DEFAULT_XMLID_PREFIX + shortId.toUpperCase();
    }
}
