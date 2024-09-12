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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import net.opengis.OgcProperty;
import net.opengis.gml.v32.Point;
import net.opengis.gml.v32.TimeIndeterminateValue;
import net.opengis.gml.v32.TimePosition;
import net.opengis.gml.v32.impl.GMLFactory;
import net.opengis.sensorml.v20.AbstractPhysicalProcess;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Vector;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.api.sensor.PositionConfig.LLALocation;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemGroupDriver;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemDisabledEvent;
import org.sensorhub.api.system.SystemEnabledEvent;
import org.sensorhub.api.sensor.PositionConfig.EulerOrientation;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.utils.MsgUtils;
import org.vast.ogc.gml.IFeature;
import org.vast.ogc.om.SamplingPoint;
import org.vast.sensorML.PhysicalSystemImpl;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;
import org.vast.util.Asserts;


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
 * All of these items can be overridden by derived classes.<br/>
 * It also provides helper methods to implement automatic reconnection.
 * </p>
 * 
 * @param <T> Type of sensor module config
 *
 * @author Alex Robin
 * @since Oct 30, 2014
 */
public abstract class AbstractSensorModule<T extends SensorConfig> extends AbstractModule<T> implements ISensorModule<T>
{
    public static final String DEFAULT_XMLID_PREFIX = "SENSOR_";
    protected static final String LOCATION_OUTPUT_ID = "SENSOR_LOCATION";
    protected static final String LOCATION_OUTPUT_NAME = "sensorLocation";

    protected static final String UUID_URI_PREFIX = "urn:uuid:";
    protected static final String STATE_UNIQUE_ID = "UniqueID";
    protected static final String STATE_LAST_SML_UPDATE = "LastUpdatedSensorDescription";

    private final Map<String, IStreamingDataInterface> obsOutputs = new LinkedHashMap<>();
    private final Map<String, IStreamingDataInterface> statusOutputs = new LinkedHashMap<>();
    private final Map<String, IStreamingControlInterface> controlInputs = new LinkedHashMap<>();
    protected final Map<String, IFeature> foiMap = new TreeMap<>();
    
    protected ISystemGroupDriver<?> parentSystem;
    protected DefaultLocationOutput locationOutput;
    protected AbstractProcess sensorDescription = new PhysicalSystemImpl();
    protected volatile long lastUpdatedSensorDescription = Long.MIN_VALUE;
    protected final Object sensorDescLock = new Object();

    protected boolean randomUniqueID;
    protected volatile String xmlID;
    protected volatile String uniqueID;
    
    
    @Override
    protected void beforeInit() throws SensorHubException
    {
        super.beforeInit();

        // reset internal state
        this.uniqueID = null;
        this.xmlID = null;
        this.locationOutput = null;
        this.sensorDescription = new PhysicalSystemImpl();
        removeAllOutputs();
        removeAllControlInputs();
    }


    /**
     * This method generates the following information:
     * <ul>
     * <li>A default unique ID and XML ID if none were provided by the driver</li>
     * <li>A default event source information object. If the system is member
     * of a group, all events are published to the parent group by default</li>
     * <li>The sensor description latest updated flag is set to the value 
     * provided in the driver configuration</li>
     * <li>If location is provided in config, a generic feature interest
     * and a location output</li>
     * </ul>
     * In most cases, derived classes overriding this method must call it
     * using the super keyword.
     */
    @Override
    protected void afterInit() throws SensorHubException
    {
        // generate random unique ID in case sensor driver hasn't generate one
        // if a random UUID has already been generated it will be restored by
        // loadState() method that is called after init()
        if (this.uniqueID == null)
        {
            String uuid = UUID.randomUUID().toString();
            this.uniqueID = UUID_URI_PREFIX + uuid;

            if (this.xmlID == null)
                generateXmlIDFromUUID(uuid);

            this.randomUniqueID = true;
        }
        
        // set last description update time if provided in config
        if (config.lastUpdated != null)
            this.lastUpdatedSensorDescription = config.lastUpdated.getTime();

        // add location output and foi if a location is set in config
        if (config.getLocation() != null)
        {
            LLALocation loc = config.getLocation();
            
            if (locationOutput == null)
                addLocationOutput(Double.NaN);
        
            if (foiMap.isEmpty())
            {
                // add 
                SamplingPoint sf = new SamplingPoint();
                sf.setId("FOI_" + xmlID);
                sf.setUniqueIdentifier(uniqueID + ":foi");
                if (config.name != null)
                    sf.setName(config.name);
                sf.setDescription("Sampling point for " + config.name);
                sf.setHostedProcedureUID(uniqueID);
                Point point = new GMLFactory(true).newPoint();
                point.setSrsName(SWEConstants.REF_FRAME_4979);
                point.setSrsDimension(3);
                point.setPos(new double[] {loc.lat, loc.lon, loc.alt});
                sf.setShape(point);
                foiMap.put(sf.getUniqueIdentifier(), sf);
            }
        }
        
        super.afterInit();
    }
    
    
    /**
     * This methods does the following:
     * <ul>
     * <li>Register the driver with the system registry if the driver is
     * connected to a hub (i.e. setParentHub() has been called)</li>
     * <li>Send a location data event if a location output has been created</li>
     * </ul>
     * In most cases, derived classes overriding this method must call it
     * using the super keyword.
     */
    @Override
    protected void beforeStart() throws SensorHubException
    {
        super.beforeStart();

        if(getParentSystem() != null && !getParentSystem().isEnabled())
            throw new SensorException("Parent system must be started");

        // register sensor with registry if attached to a hub and we have no parent
        try
        {
            if (hasParentHub() && getParentHub().getSystemDriverRegistry() != null && (getParentSystem() == null || getParentSystem().isEnabled()))
                getParentHub().getSystemDriverRegistry().register(this).get(); // for now, block here until init is also async
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ProcessingException("Interrupted while registering driver", e);
        }
        catch (ExecutionException e)
        {
            throw new SensorException("Error registering driver", e.getCause());
        }
        
        // send new location event
        var loc = config.getLocation();
        if (locationOutput != null && loc != null)
            locationOutput.updateLocation(System.currentTimeMillis()/1000., loc.lon, loc.lat, loc.alt, false);
    }
    
    
    @Override
    protected void afterStop() throws SensorHubException
    {
        // unregister sensor if attached to a hub
        try
        {
            if (hasParentHub() && getParentHub().getSystemDriverRegistry() != null)
                getParentHub().getSystemDriverRegistry().unregister(this).get();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ProcessingException("Interrupted while unregistering driver", e);
        }
        catch (ExecutionException e)
        {
            throw new SensorException("Error unregistering driver", e.getCause());
        }
        
        super.afterStop();
    }


    /**
     * Call this method to add each sensor observation or status output
     * @param dataInterface interface to add as sensor output
     * @param isStatus set to true when registering a status output
     */
    protected void addOutput(IStreamingDataInterface dataInterface, boolean isStatus)
    {
        Asserts.checkNotNull(dataInterface, IStreamingDataInterface.class);
        
        synchronized(obsOutputs)
        {
            if (isStatus)
                statusOutputs.put(dataInterface.getName(), dataInterface);
            else
                obsOutputs.put(dataInterface.getName(), dataInterface);
            
            try
            {
                // if output is added after start(), register it now
                if (isStarted() && hasParentHub() && getParentHub().getSystemDriverRegistry() != null)
                    getParentHub().getSystemDriverRegistry().register(dataInterface).get();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while registering sensor output", e);
            }
            catch (ExecutionException e)
            {
                throw new IllegalStateException("Error registering sensor output", e.getCause());
            }
        }
    }


    /**
     * Helper method to add a location output so that all sensors can update their location
     * in a consistent manner.
     * @param updatePeriod estimated location update period or NaN if sensor is mostly static
     */
    protected void addLocationOutput(double updatePeriod)
    {
        synchronized(obsOutputs)
        {
            if (locationOutput == null)
            {
                // TODO deal with other CRS than 4979
                locationOutput = new DefaultLocationOutputLLA(this, getLocalFrameID(), updatePeriod);
                addOutput(locationOutput, true);
            }
        }
    }


    /**
     * Removes all outputs previously added to this sensor
     */
    protected void removeAllOutputs()
    {
        synchronized(obsOutputs)
        {
            statusOutputs.clear();
            obsOutputs.clear();
        }
    }


    /**
     * Call this method to add each sensor control input
     * @param controlInterface interface to add as sensor control input
     */
    protected void addControlInput(IStreamingControlInterface controlInterface)
    {
        Asserts.checkNotNull(controlInterface, IStreamingControlInterface.class);
        
        synchronized(controlInputs)
        {
            controlInputs.put(controlInterface.getName(), controlInterface);
            
            try
            {
                // if control input is added after start(), register it now
                if (isStarted() && hasParentHub() && getParentHub().getSystemDriverRegistry() != null)
                    getParentHub().getSystemDriverRegistry().register(controlInterface).get();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while registering command input", e);
            }
            catch (ExecutionException e)
            {
                throw new IllegalStateException("Error registering command input", e.getCause());
            }
        }
    }


    /**
     * Removes all control inputs previously added to this sensor
     */
    protected void removeAllControlInputs()
    {
        synchronized(controlInputs)
        {
            controlInputs.clear();
        }
    }
    
    
    /**
     * Adds a new FOI attached to this system driver
     * @param foi
     */
    protected void addFoi(IFeature foi)
    {
        Asserts.checkNotNull(foi, IFeature.class);
        OshAsserts.checkValidUID(foi.getUniqueIdentifier());
        
        synchronized(foiMap)
        {
            // add to driver map
            foiMap.put(foi.getUniqueIdentifier(), foi);
            
            // also register it if driver is already started
            if (isStarted())
            {
                try
                {
                    getParentHub().getSystemDriverRegistry().register(this, foi).get();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while registering FOI", e);
                }
                catch (ExecutionException e)
                {
                    throw new IllegalStateException("Error registering new FOI", e.getCause());
                }
            }
        }
    }


    @Override
    public String getUniqueIdentifier()
    {
        return uniqueID;
    }


    @Override
    public String getParentSystemUID()
    {
        synchronized (sensorDescLock)
        {
            return parentSystem != null ? parentSystem.getUniqueIdentifier() : null;
        }
    }


    @Override
    public ISystemGroupDriver<? extends ISystemDriver> getParentSystem()
    {
        synchronized (sensorDescLock)
        {
            return parentSystem;
        }
    }
    
    
    public void attachToParent(ISystemGroupDriver<? extends ISystemDriver> parentSystem)
    {
        synchronized (sensorDescLock)
        {
            this.parentSystem = parentSystem;
        }
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
    public long getLatestDescriptionUpdate()
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
                    sensorDescription = utils.readProcess(is);
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

            // name & description
            sensorDescription.setDefinition(SWEConstants.DEF_SENSOR);
            if (sensorDescription.getName() == null && config.name != null)
                sensorDescription.setName(config.name);
            if (sensorDescription.getDescription() == null && config.description != null)
                sensorDescription.setDescription(config.description);
            
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
                for (Entry<String, ? extends IStreamingDataInterface> output: getOutputs().entrySet())
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
                for (Entry<String, ? extends IStreamingControlInterface> param: getCommandInputs().entrySet())
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
	                    DataRecord pos = fac.createRecord()
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
        eventHandler.publish(new SystemChangedEvent(updateTime, uniqueID));
    }


    protected String getLocalFrameID()
    {
        return "REF_FRAME_" + xmlID;
    }


    @Override
    public Map<String, ? extends IStreamingDataInterface> getOutputs()
    {
        Map<String, IStreamingDataInterface> allOutputs = new LinkedHashMap<>();
        
        synchronized (obsOutputs)
        {
            allOutputs.putAll(obsOutputs);
        }
        
        synchronized (statusOutputs)
        {
            allOutputs.putAll(statusOutputs);
        }
    
        return Collections.unmodifiableMap(allOutputs);
    }


    @Override
    public Map<String, ? extends IStreamingDataInterface> getStatusOutputs()
    {
        synchronized (statusOutputs)
        {
            return Collections.unmodifiableMap(statusOutputs);
        }
    }


    @Override
    public Map<String, ? extends IStreamingDataInterface> getObservationOutputs()
    {
        synchronized (obsOutputs)
        {
            return Collections.unmodifiableMap(obsOutputs);
        }
    }


    @Override
    public Map<String, IStreamingControlInterface> getCommandInputs()
    {
        synchronized (controlInputs)
        {
            return Collections.unmodifiableMap(controlInputs);
        }
    }


    @Override
    public Map<String, ? extends IFeature> getCurrentFeaturesOfInterest()
    {
        synchronized (foiMap)
        {
            return Collections.unmodifiableMap(foiMap);
        }
    }


    @Override
    public synchronized void updateConfig(T config) throws SensorHubException
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
    protected void setState(ModuleState newState)
    {
        super.setState(newState);
        
        // send system enabled/disabled events
        if (newState == ModuleState.STARTED && uniqueID != null)
            eventHandler.publish(new SystemEnabledEvent(uniqueID, getParentSystemUID()));
        else if (newState == ModuleState.STOPPED && uniqueID != null)
            eventHandler.publish(new SystemDisabledEvent(uniqueID, getParentSystemUID()));
    }


    @Override
    public synchronized void loadState(IModuleStateManager loader) throws SensorHubException
    {
        super.loadState(loader);

        // set unique ID to the one previously saved
        String savedUniqueID = loader.getAsString(STATE_UNIQUE_ID);
        if (savedUniqueID != null && randomUniqueID)
        {
            this.uniqueID = savedUniqueID;
            this.generateXmlIDFromUUID(savedUniqueID);
        }

        //Long lastUpdateTime = loader.getAsLong(STATE_LAST_SML_UPDATE);
        //if (lastUpdateTime != null)
        //    this.lastUpdatedSensorDescription = lastUpdateTime;
    }


    @Override
    public synchronized void saveState(IModuleStateManager saver) throws SensorHubException
    {
        super.saveState(saver);

        // save unique ID if it was automatically generated as UUID
        if (uniqueID != null && randomUniqueID)
            saver.put(STATE_UNIQUE_ID, this.uniqueID);

        saver.put(STATE_LAST_SML_UPDATE, this.lastUpdatedSensorDescription);
        saver.flush();
    }


    @Override
    public boolean isEnabled()
    {
        return isStarted();
    }


    protected String getDefaultIdSuffix()
    {
        String localID = getLocalID();
        int endIndex = Math.min(localID.length(), 8);
        String idSuffix = localID.substring(0, endIndex);
        return idSuffix.replaceAll("\\s+", "");
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
            suffix = suffix.replaceAll("\\s+", ""); // remove white spaces

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

        suffix = suffix.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        this.xmlID = prefix + suffix.toUpperCase();
    }


    protected void generateXmlIDFromUUID(String uuid)
    {
        int endIndex = Math.min(8, uuid.length());
        String shortId = uuid.substring(0, endIndex);
        this.xmlID = DEFAULT_XMLID_PREFIX + shortId.toUpperCase();
    }
}
