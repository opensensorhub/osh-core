/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import net.opengis.fes.v20.Conformance;
import net.opengis.fes.v20.FilterCapabilities;
import net.opengis.fes.v20.SpatialCapabilities;
import net.opengis.fes.v20.SpatialOperator;
import net.opengis.fes.v20.SpatialOperatorName;
import net.opengis.fes.v20.TemporalCapabilities;
import net.opengis.fes.v20.TemporalOperator;
import net.opengis.fes.v20.TemporalOperatorName;
import net.opengis.fes.v20.impl.FESFactory;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.BinaryMember;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.JSONEncoding;
import net.opengis.swe.v20.SimpleComponent;
import net.opengis.swe.v20.TextEncoding;
import net.opengis.swe.v20.Vector;
import net.opengis.swe.v20.XMLEncoding;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.persistence.FoiFilter;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.persistence.StorageConfig;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.api.service.ServiceException;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.persistence.StreamStorageConfig;
import org.sensorhub.impl.sensor.swe.SWETransactionalSensor;
import org.sensorhub.impl.service.HttpServer;
import org.sensorhub.impl.service.ogc.OGCServiceConfig.CapabilitiesInfo;
import org.sensorhub.impl.service.swe.Template;
import org.sensorhub.impl.service.swe.TransactionUtils;
import org.slf4j.Logger;
import org.vast.cdm.common.DataSource;
import org.vast.cdm.common.DataStreamParser;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.data.JSONEncodingImpl;
import org.vast.data.XMLEncodingImpl;
import org.vast.json.JsonStreamException;
import org.vast.json.JsonStreamWriter;
import org.vast.ogc.OGCRegistry;
import org.vast.ogc.def.DefinitionRef;
import org.vast.ogc.gml.GMLStaxBindings;
import org.vast.ogc.gml.GenericFeature;
import org.vast.ogc.om.IObservation;
import org.vast.ogc.om.OMUtils;
import org.vast.ogc.om.SamplingPoint;
import org.vast.ows.GetCapabilitiesRequest;
import org.vast.ows.OWSException;
import org.vast.ows.OWSExceptionReport;
import org.vast.ows.OWSRequest;
import org.vast.ows.OWSUtils;
import org.vast.ows.sos.*;
import org.vast.ows.swe.DeleteSensorRequest;
import org.vast.ows.swe.DeleteSensorResponse;
import org.vast.ows.swe.DescribeSensorRequest;
import org.vast.ows.swe.SWESOfferingCapabilities;
import org.vast.ows.swe.UpdateSensorRequest;
import org.vast.ows.swe.UpdateSensorResponse;
import org.vast.sensorML.SMLStaxBindings;
import org.vast.sensorML.json.SMLJsonStreamWriter;
import org.vast.swe.AbstractDataWriter;
import org.vast.swe.DataSourceDOM;
import org.vast.swe.FilteredWriter;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.SWEStaxBindings;
import org.vast.swe.fast.DataBlockProcessor;
import org.vast.swe.fast.FilterByDefinition;
import org.vast.swe.json.SWEJsonStreamWriter;
import org.vast.util.ReaderException;
import org.vast.util.TimeExtent;
import org.vast.xml.DOMHelper;
import org.vast.xml.IXMLWriterDOM;
import org.vast.xml.IndentingXMLStreamWriter;
import org.vast.xml.XMLImplFinder;
import org.w3c.dom.Element;
import com.google.common.base.Strings;
import com.vividsolutions.jts.geom.Polygon;


/**
 * <p>
 * Extension of SOSServlet deployed as a SensorHub service
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 7, 2013
 */
@SuppressWarnings("serial")
public class SOSServlet extends org.vast.ows.sos.SOSServlet
{
    private static final String INVALID_WS_REQ_MSG = "Invalid Websocket request: ";        
    private static final QName EXT_REPLAY = new QName("replayspeed"); // kvp params are always lower case
    private static final QName EXT_WS = new QName("websocket");
    
    final transient SOSServiceConfig config;
    final transient SOSSecurity securityHandler;
    final transient ReentrantReadWriteLock capabilitiesLock = new ReentrantReadWriteLock();
    final transient SOSServiceCapabilities capabilities = new SOSServiceCapabilities();
    final transient Map<String, SOSOfferingCapabilities> offeringCaps = new HashMap<>();
    final transient Map<String, String> procedureToOfferingMap = new HashMap<>();
    final transient Map<String, String> templateToOfferingMap = new HashMap<>();    
    final transient Map<String, ISOSDataProviderFactory> dataProviders = new LinkedHashMap<>();
    final transient Map<String, ISOSDataConsumer> dataConsumers = new LinkedHashMap<>();
    final transient Map<String, ISOSCustomSerializer> customFormats = new HashMap<>();
    WebSocketServletFactory wsFactory;
    
    
    protected SOSServlet(SOSServiceConfig config, SOSSecurity securityHandler, Logger log) throws SensorHubException
    {
        super(log);
        this.config = config;
        this.securityHandler = securityHandler;
        generateCapabilities();
    }
    
    
    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        
        // create websocket factory
        try
        {
            WebSocketPolicy wsPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
            wsFactory = WebSocketServletFactory.Loader.load(getServletContext(), wsPolicy);
            wsFactory.start();
        }
        catch (Exception e)
        {
            throw new ServletException("Cannot initialize websocket factory", e);
        }
    }
    
    
    @Override
    public void destroy()
    {
        stop();
        
        // destroy websocket factory
        try
        {
            wsFactory.stop();
        }
        catch (Exception e)
        {
            log.error("Cannot stop websocket factory", e);
        }
    }


    protected void stop()
    {
        // cleanup all providers
        for (ISOSDataProviderFactory provider: dataProviders.values())
            provider.cleanup();
        
        // cleanup all consumers
        for (ISOSDataConsumer consumer: dataConsumers.values())
            consumer.cleanup();
    }
    
    
    /**
     * Generates the SOSServiceCapabilities object with info from data source
     */
    protected void generateCapabilities() throws SensorHubException
    {
        offeringCaps.clear();
        procedureToOfferingMap.clear();
        templateToOfferingMap.clear();
        dataProviders.clear();
        dataConsumers.clear();
        customFormats.clear();        
        
        // get main capabilities info from config
        CapabilitiesInfo serviceInfo = config.ogcCapabilitiesInfo;
        capabilities.getSupportedVersions().add(DEFAULT_VERSION);
        capabilities.getIdentification().setTitle(serviceInfo.title);
        capabilities.getIdentification().setDescription(serviceInfo.description);
        capabilities.setFees(serviceInfo.fees);
        capabilities.setAccessConstraints(serviceInfo.accessConstraints);
        capabilities.setServiceProvider(serviceInfo.serviceProvider);
        
        // supported operations and extensions
        String endpoint = config.getPublicEndpoint();
        capabilities.getProfiles().add(SOSServiceCapabilities.PROFILE_RESULT_RETRIEVAL);
        capabilities.getProfiles().add(SOSServiceCapabilities.PROFILE_OMXML);
        capabilities.getGetServers().put("GetCapabilities", endpoint);
        capabilities.getGetServers().put("DescribeSensor", endpoint);
        capabilities.getGetServers().put("GetFeatureOfInterest", endpoint);
        capabilities.getGetServers().put("GetObservation", endpoint);
        capabilities.getGetServers().put("GetResult", endpoint);
        capabilities.getGetServers().put("GetResultTemplate", endpoint);
        capabilities.getPostServers().putAll(capabilities.getGetServers());
        
        if (config.enableTransactional)
        {
            capabilities.getProfiles().add(SOSServiceCapabilities.PROFILE_SENSOR_INSERTION);
            capabilities.getProfiles().add(SOSServiceCapabilities.PROFILE_SENSOR_DELETION);
            capabilities.getProfiles().add(SOSServiceCapabilities.PROFILE_OBS_INSERTION);
            capabilities.getProfiles().add(SOSServiceCapabilities.PROFILE_RESULT_INSERTION);
            capabilities.getPostServers().put("InsertSensor", endpoint);
            capabilities.getPostServers().put("DeleteSensor", endpoint);
            capabilities.getPostServers().put("InsertObservation", endpoint);
            capabilities.getPostServers().put("InsertResultTemplate", endpoint);
            capabilities.getPostServers().put("InsertResult", endpoint);
            capabilities.getGetServers().put("InsertResult", endpoint);
            
            // insertion capabilities
            SOSInsertionCapabilities insertCaps = new SOSInsertionCapabilities();
            insertCaps.getProcedureFormats().add(SWESOfferingCapabilities.FORMAT_SML2);
            insertCaps.getFoiTypes().add(SamplingPoint.TYPE);
            insertCaps.getObservationTypes().add(IObservation.OBS_TYPE_SCALAR);
            insertCaps.getObservationTypes().add(IObservation.OBS_TYPE_RECORD);
            insertCaps.getObservationTypes().add(IObservation.OBS_TYPE_ARRAY);
            insertCaps.getSupportedEncodings().add(SOSServiceCapabilities.SWE_ENCODING_TEXT);
            insertCaps.getSupportedEncodings().add(SOSServiceCapabilities.SWE_ENCODING_BINARY);
            capabilities.setInsertionCapabilities(insertCaps);
        }
        
        // filter capabilities
        FESFactory fac = new FESFactory();
        FilterCapabilities filterCaps = fac.newFilterCapabilities();
        capabilities.setFilterCapabilities(filterCaps);
        
        // conformance
        Conformance filterConform = filterCaps.getConformance();
        filterConform.addConstraint(fac.newConstraint("ImplementsQuery", Boolean.TRUE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsAdHocQuery", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsFunctions", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsResourceld", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsMinStandardFilter", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsStandardFilter", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsMinSpatialFilter", Boolean.TRUE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsSpatialFilter", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsMinTemporalFilter", Boolean.TRUE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsTemporalFilter", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsVersionNav", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsSorting", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsExtendedOperators", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsMinimumXPath", Boolean.FALSE.toString()));
        filterConform.addConstraint(fac.newConstraint("ImplementsSchemaElementFunc", Boolean.FALSE.toString()));
        
        // supported temporal filters
        TemporalCapabilities timeFilterCaps = fac.newTemporalCapabilities();
        timeFilterCaps.getTemporalOperands().add(new QName(null, "TimeInstant", "gml"));
        timeFilterCaps.getTemporalOperands().add(new QName(null, "TimePeriod", "gml"));
        TemporalOperator timeOp = fac.newTemporalOperator();
        timeOp.setName(TemporalOperatorName.DURING);
        timeFilterCaps.getTemporalOperators().add(timeOp);
        filterCaps.setTemporalCapabilities(timeFilterCaps);
        
        // supported spatial filters
        SpatialCapabilities spatialFilterCaps = fac.newSpatialCapabilities();
        spatialFilterCaps.getGeometryOperands().add(new QName(null, "Envelope", "gml"));
        SpatialOperator spatialOp = fac.newSpatialOperator();
        spatialOp.setName(SpatialOperatorName.BBOX);
        spatialFilterCaps.getSpatialOperators().add(spatialOp);
        filterCaps.setSpatialCapabilities(spatialFilterCaps);
        
        // process each provider config
        if (config.dataProviders != null)
        {
            for (SOSProviderConfig providerConf: config.dataProviders)
            {
                try
                {
                    // instantiate provider factories and map them to offering URIs
                    ISOSDataProviderFactory provider = providerConf.getFactory(this);
                    dataProviders.put(providerConf.offeringID, provider);
                    if (provider.isEnabled())
                        showProviderCaps(provider);
                }
                catch (Exception e)
                {
                    log.error("Error while initializing provider " + providerConf.offeringID, e);
                }
            }
        }
        
        // process each consumer config
        if (config.dataConsumers != null)
        {
            for (SOSConsumerConfig consumerConf: config.dataConsumers)
            {
                try
                {
                    // for now we support only virtual sensors as consumers
                    ISOSDataConsumer consumer = consumerConf.getConsumer();
                    dataConsumers.put(consumerConf.offeringID, consumer);
                }
                catch (SensorHubException e)
                {
                    log.error("Error while initializing consumer " + consumerConf.offeringID, e);
                }
            }
        }
        
        // preload custom format serializers
        ModuleRegistry moduleReg = SensorHub.getInstance().getModuleRegistry();
        for (SOSCustomFormatConfig allowedFormat: config.customFormats)
        {
            try
            {
                ISOSCustomSerializer serializer = (ISOSCustomSerializer)moduleReg.loadClass(allowedFormat.className);
                customFormats.put(allowedFormat.mimeType, serializer);
            }
            catch (Exception e)
            {
                log.error("Error while initializing custom " + allowedFormat.mimeType + " serializer", e);
            }
        }
    }
    
    
    protected SOSOfferingCapabilities generateCapabilities(ISOSDataProviderFactory provider) throws IOException
    {
        try
        {
            SOSOfferingCapabilities caps = provider.generateCapabilities();
            
            // add supported formats
            caps.getResponseFormats().add(SWESOfferingCapabilities.FORMAT_OM2);
            caps.getResponseFormats().add(SWESOfferingCapabilities.FORMAT_OM2_JSON);
            caps.getProcedureFormats().add(SWESOfferingCapabilities.FORMAT_SML2);
            caps.getProcedureFormats().add(SWESOfferingCapabilities.FORMAT_SML2_JSON);
            
            return caps;
        }
        catch (SensorHubException e)
        {
            throw new IOException("Cannot generate capabilities", e);
        }
    }
    
    
    protected void showProviderCaps(ISOSDataProviderFactory provider)
    {
        SOSProviderConfig providerConf = provider.getConfig();
                
        try
        {
            capabilitiesLock.writeLock().lock();
            
            // generate offering metadata
            SOSOfferingCapabilities offCaps = generateCapabilities(provider);
            String procedureID = offCaps.getMainProcedure();
            
            // update offering if it was already advertised
            if (offeringCaps.containsKey(providerConf.offeringID))
            {
                // replace old offering
                SOSOfferingCapabilities oldCaps = offeringCaps.put(providerConf.offeringID, offCaps);
                capabilities.getLayers().set(capabilities.getLayers().indexOf(oldCaps), offCaps);
                
                if (log.isDebugEnabled())
                    log.debug("Offering " + "\"" + offCaps.getIdentifier() + "\" updated for procedure " + procedureID);
            }
            
            // otherwise add new offering
            else
            {
                // add to maps and layer list
                offeringCaps.put(offCaps.getIdentifier(), offCaps);                
                procedureToOfferingMap.put(procedureID, offCaps.getIdentifier());                
                capabilities.getLayers().add(offCaps);
                
                if (log.isDebugEnabled())
                    log.debug("Offering " + "\"" + offCaps.getIdentifier() + "\" added for procedure " + procedureID);
            }
        }
        catch (Exception e)
        {
            log.error("Cannot generate offering " + providerConf.offeringID, e);
        }
        finally
        {
            capabilitiesLock.writeLock().unlock();
        }
    }
    
    
    protected void hideProviderCaps(ISOSDataProviderFactory provider)
    {
        SOSProviderConfig providerConf = provider.getConfig();
        
        try
        {
            capabilitiesLock.writeLock().lock();
            
            // stop here if provider is not advertised
            if (!offeringCaps.containsKey(providerConf.offeringID))
                return;
            
            // remove offering from capabilities
            SOSOfferingCapabilities offCaps = offeringCaps.remove(providerConf.offeringID);
            capabilities.getLayers().remove(offCaps);
            
            // remove from procedure map
            String procedureID = offCaps.getMainProcedure();
            procedureToOfferingMap.remove(procedureID);
            
            if (log.isDebugEnabled())
                log.debug("Offering " + "\"" + offCaps.getIdentifier() + "\" removed for procedure " + procedureID);
        }
        finally
        {
            capabilitiesLock.writeLock().unlock();
        }
    }
    
    
    /*
     * Completely removes a provider and corresponding offering
     * This is called when the data source of a StreamDataProvider is deleted
     */
    protected synchronized void removeProvider(String offeringID)
    {
        // delete provider
        ISOSDataProviderFactory provider = dataProviders.remove(offeringID);
        if (provider != null)
        {
            hideProviderCaps(provider);
            provider.cleanup();
        }
        
        // delete provider config
        Iterator<SOSProviderConfig> it = config.dataProviders.iterator();
        while (it.hasNext())
        {
            if (offeringID.equals(it.next().offeringID))
                it.remove();
        }
        
        // delete consumer
        ISOSDataConsumer consumer = dataConsumers.remove(offeringID);
        if (consumer != null)
            consumer.cleanup();
                
        // delete consumer config
        Iterator<SOSConsumerConfig> it2 = config.dataConsumers.iterator();
        while (it2.hasNext())
        {
            if (offeringID.equals(it2.next().offeringID))
                it2.remove();
        }
    }
    
    
    /*
     * Transforms a SensorWithStorageProvider into a SensorDataProvider
     * This is called when the storage module is deleted 
     */
    protected synchronized void onStorageDeleted(String offeringID)
    {
        try
        {
            // update provider
            ISOSDataProviderFactory provider = dataProviders.remove(offeringID);
            if (provider != null)
            {                
                provider.cleanup();
                                
                // update provider config
                SensorDataProviderConfig providerConfig = (SensorDataProviderConfig)provider.getConfig();
                providerConfig.storageID = null;
                
                // replace old provider
                provider = providerConfig.getFactory(this);
                dataProviders.put(offeringID, provider);                
                showProviderCaps(provider);
            }
            
            // update consumer
            ISOSDataConsumer consumer = dataConsumers.remove(offeringID);
            if (consumer != null)
            {
                consumer.cleanup();
                
                // update consumer config
                SensorConsumerConfig consumerConfig = (SensorConsumerConfig)consumer.getConfig();
                consumerConfig.storageID = null;
                                
                // replace old consumer
                consumer = consumerConfig.getConsumer();
                dataConsumers.put(offeringID, consumer);
            }
        }
        catch (SensorHubException e)
        {
            log.error("Error while updating offering " + offeringID, e);
        }
    }
    
    
    /*
     * Transforms a SensorWithStorageProvider into a StorageDataProvider
     * This happens when the sensor module is deleted 
     */
    protected synchronized void onSensorDeleted(String offeringID)
    {
        try
        {
            // update provider
            ISOSDataProviderFactory provider = dataProviders.remove(offeringID);
            if (provider != null)
            {                
                provider.cleanup();
                
                // update provider config
                StorageDataProviderConfig providerConfig = new StorageDataProviderConfig();
                providerConfig.enabled = true;
                providerConfig.storageID = ((SensorDataProviderConfig)provider.getConfig()).storageID;
                providerConfig.offeringID = offeringID;
                config.dataProviders.replaceOrAdd(providerConfig);
                
                // instantiate and register provider
                provider = providerConfig.getFactory(this);
                dataProviders.put(offeringID, provider);                
                showProviderCaps(provider);
            }
            
            // remove consumer
            ISOSDataConsumer consumer = dataConsumers.remove(offeringID);
            if (consumer != null)
            {
                consumer.cleanup();
                config.dataConsumers.remove(consumer.getConfig());
            }
        }
        catch (SensorHubException e)
        {
            log.error("Error while updating offering " + offeringID, e);
        }
    }
    
    
    /*
     * Retrieves SensorML object for the given procedure unique ID
     */
    protected AbstractProcess generateSensorML(String uri, TimeExtent timeExtent) throws ServiceException
    {
        try
        {
            ISOSDataProviderFactory factory = getDataProviderFactoryBySensorID(uri);
            double time = Double.NaN;
            if (timeExtent != null)
                time = timeExtent.getBaseTime();
            return factory.generateSensorMLDescription(time);            
        }
        catch (Exception e)
        {
            throw new ServiceException("Error while retrieving SensorML description for sensor " + uri, e);
        }
    }
    
    
    /*
     * Create and associate storage with the given sensor module and corresponding offering
     */
    protected IModule<?> addStorageForSensor(ISensorModule<?> sensorModule) throws IOException
    {
        ModuleRegistry moduleReg = SensorHub.getInstance().getModuleRegistry();
        String sensorUID = sensorModule.getUniqueIdentifier();
            
        try
        {
            String storageID = sensorUID + "#storage";
            
            // create new storage module if needed
            IModule<?> storageModule = moduleReg.getLoadedModuleById(storageID);
            if (storageModule == null)
            {
                // create new storage module
                StreamStorageConfig streamStorageConfig = new StreamStorageConfig();
                streamStorageConfig.id = storageID;
                streamStorageConfig.name = sensorModule.getName() + " Storage";
                streamStorageConfig.autoStart = true;
                streamStorageConfig.dataSourceID = sensorUID;
                streamStorageConfig.storageConfig = (StorageConfig)config.newStorageConfig.clone();
                streamStorageConfig.storageConfig.setStorageIdentifier(sensorUID);
                storageModule = moduleReg.loadModule(streamStorageConfig);
                                    
                /*// also add related features to storage
                if (storage instanceof IObsStorage)
                {
                    for (FeatureRef featureRef: request.getRelatedFeatures())
                        ((IObsStorage) storage).storeFoi(featureRef.getTarget());
                }*/
            }
            
            return storageModule;
        }
        catch (SensorHubException e)
        {
            throw new IOException("Cannot create storage for sensor " + sensorUID, e);
        }        
    }
    
    
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        // set current authentified user
        String userID = ISecurityManager.ANONYMOUS_USER;
        if (req.getRemoteUser() != null)
            userID = req.getRemoteUser();
        
        try
        {
            // check if we have an upgrade request for websockets
            if (wsFactory.isUpgradeRequest(req, resp))
            {
                // parse request
                try
                {
                    OWSRequest owsReq = this.parseRequest(req, resp, false);
                    
                    if (owsReq != null)
                    {
                        owsReq.getExtensions().put(EXT_WS, true);
                        
                        if (owsReq instanceof GetResultRequest)
                        {
                            acceptWebSocket(owsReq, new SOSWebSocketOut(this, owsReq, userID, log));
                        }
                        else if (owsReq instanceof InsertResultRequest)
                        {
                            this.handleRequest(owsReq);
                        }
                        else
                            throw new ServletException(INVALID_WS_REQ_MSG + owsReq.getOperation() + " is not supported via this protocol");
                    }
                }
                catch (Exception e)
                {
                    String errorMsg = "Error while processing Websocket request";
                    resp.sendError(400, errorMsg);
                    log.trace(errorMsg, e);
                }

                return;
            }
            
            // otherwise process as classical HTTP request
            securityHandler.setCurrentUser(userID);
            super.service(req, resp);
        }
        finally
        {
            securityHandler.clearCurrentUser();
        }
    }
    
    
    protected void acceptWebSocket(final OWSRequest owsReq, final WebSocketListener socket) throws IOException
    {
        wsFactory.acceptWebSocket(new WebSocketCreator() {
            @Override
            public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
            {
                return socket;
            }            
        }, owsReq.getHttpRequest(), owsReq.getHttpResponse());
    }


    @Override
    protected void handleRequest(GetCapabilitiesRequest request) throws IOException, OWSException
    {
        // check that version 2.0.0 is supported by client
        if (!request.getAcceptedVersions().isEmpty())
        {
            if (!request.getAcceptedVersions().contains(DEFAULT_VERSION))
                throw new SOSException(SOSException.version_nego_failed_code, "AcceptVersions", null,
                        "Only version " + DEFAULT_VERSION + " is supported by this server");
        }
        
        // set selected version
        request.setVersion(DEFAULT_VERSION);
        
        // security check
        securityHandler.checkPermission(securityHandler.sos_read_caps);
        
        // make sure capabilities are up to date
        try
        {
            capabilitiesLock.writeLock().lock();
            
            // update operation URLs dynamically if base URL not set in config
            if (Strings.isNullOrEmpty(HttpServer.getInstance().getConfiguration().proxyBaseUrl))
            {
                String endpointUrl = request.getHttpRequest().getRequestURL().toString();
                capabilities.updateAllEndpointUrls(endpointUrl);
            }
            
            // ask providers to refresh their capabilities if needed.
            // we do that here so capabilities doc contains the most up-to-date info.
            // we don't always do it when changes occur because high frequency changes 
            // would trigger too many updates (e.g. new measurements changing time periods)
            for (ISOSDataProviderFactory provider: dataProviders.values())
            {
                try
                {
                    if (provider.isEnabled())
                        provider.updateCapabilities();
                }
                catch (SensorHubException e)
                {
                    log.error("Cannot update capabilities of provider " + provider.getConfig().name, e);
                }
            }
        }
        finally
        {
            capabilitiesLock.writeLock().unlock();
        }
        
        try
        {
            capabilitiesLock.readLock().lock();
            sendResponse(request, capabilities);
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
        
    
    @Override
    protected void handleRequest(DescribeSensorRequest request) throws IOException, OWSException
    {        
        String sensorID = request.getProcedureID();
                
        // check query parameters
        OWSExceptionReport report = new OWSExceptionReport();
        checkQueryProcedure(sensorID, report);
        String offeringID = procedureToOfferingMap.get(sensorID);
        checkQueryProcedureFormat(offeringID, request.getFormat(), report);
        report.process();
        
        // security check
        securityHandler.checkPermission(offeringID, securityHandler.sos_read_sensor);
        
        try
        {
            // get procedure description
            AbstractProcess processDesc = generateSensorML(sensorID, request.getTime());
            if (processDesc == null)
                throw new SOSException(SOSException.invalid_param_code, "validTime"); 
            
            // init XML or JSON writer
            String format = request.getFormat();        
            XMLStreamWriter writer = getResponseStreamWriter(request, format);
            boolean isJson = writer instanceof JsonStreamWriter;
            if (writer == null)
                throw new SOSException(SOSException.invalid_param_code, "procedureDescriptionFormat", format, "Procedure description format " + format + " is not supported");
                    
            // prepare SensorML writing
            SMLStaxBindings smlBindings = new SMLStaxBindings();
            smlBindings.setNamespacePrefixes(writer);
            smlBindings.declareNamespacesOnRootElement();
            
            // start XML response
            writer.writeStartDocument();
            
            // wrap SensorML description inside response
            if (!isJson)
            {
                // wrap with SOAP envelope if requested
                startSoapEnvelope(request, writer);
                
                String swesNsUri = OGCRegistry.getNamespaceURI(SOSUtils.SWES, DEFAULT_VERSION);
                writer.writeStartElement(SWES_PREFIX, "DescribeSensorResponse", swesNsUri);
                writer.writeNamespace(SWES_PREFIX, swesNsUri);
                
                writer.writeStartElement(SWES_PREFIX, "procedureDescriptionFormat", swesNsUri);
                writer.writeCharacters(format);
                writer.writeEndElement();
                
                writer.writeStartElement(SWES_PREFIX, "description", swesNsUri);
                writer.writeStartElement(SWES_PREFIX, "SensorDescription", swesNsUri);
                writer.writeStartElement(SWES_PREFIX, "data", swesNsUri);
            }
            
            smlBindings.writeAbstractProcess(writer, processDesc);
            
            // close SOAP elements
            if (!isJson)
                endSoapEnvelope(request, writer);
            
            writer.writeEndDocument();
            writer.close();
        }
        catch (ServiceException e)
        {
            throw new IOException("Cannot generate SensorML document", e);
        }
        catch (XMLStreamException e)
        {
            throw new IOException(SEND_RESPONSE_ERROR_MSG, e);
        }
    }
    
    
    @Override
    protected void handleRequest(GetObservationRequest request) throws IOException, OWSException
    {
        ISOSDataProvider dataProvider = null;
        
        // set default format
        if (request.getFormat() == null)
            request.setFormat(GetObservationRequest.DEFAULT_FORMAT);
        
        // build offering set (also from procedures ID)
        Set<String> selectedOfferings = new HashSet<>();
        for (String procID: request.getProcedures())
        {
            String offering = procedureToOfferingMap.get(procID);
            if (offering != null)
                selectedOfferings.add(offering);                
        }
        if (selectedOfferings.isEmpty())
            selectedOfferings.addAll(request.getOfferings());
        else if (!request.getOfferings().isEmpty())
            selectedOfferings.retainAll(request.getOfferings());
        
        // if no offering or procedure specified scan all offerings
        if (selectedOfferings.isEmpty())
            selectedOfferings.addAll(offeringCaps.keySet());
        
        // check query parameters
        OWSExceptionReport report = new OWSExceptionReport();
        checkQueryOfferings(request.getOfferings(), report);
        checkQueryObservables(request.getObservables(), report);
        checkQueryProcedures(request.getProcedures(), report);
        for (String offering: selectedOfferings)
            checkQueryFormat(offering, request.getFormat(), report);
        report.process();
        
        // security check
        for (String offeringID: selectedOfferings)
            securityHandler.checkPermission(offeringID, securityHandler.sos_read_obs);
            
        try
        {
            // prepare obs stream writer for requested O&M version
            String format = request.getFormat();
            String omVersion = format.substring(format.lastIndexOf('/') + 1);
            @SuppressWarnings("unchecked")
            IXMLWriterDOM<IObservation> obsWriter = (IXMLWriterDOM<IObservation>)OGCRegistry.createWriter(OMUtils.OM, OMUtils.OBSERVATION, omVersion);
            String sosNsUri = OGCRegistry.getNamespaceURI(SOSUtils.SOS, DEFAULT_VERSION);
            
            // init XML or JSON writer
            XMLStreamWriter writer = getResponseStreamWriter(request, format);
            boolean isJson = writer instanceof JsonStreamWriter;
            if (writer == null)
                throw new SOSException(SOSException.invalid_param_code, "responseFormat", format, "Response format " + format + " is not supported");
            
            // start XML response
            writer.writeStartDocument();
            
            if (!isJson)
            {
                // wrap with SOAP envelope if needed
                startSoapEnvelope(request, writer);
                
                // wrap all observations inside response
                writer.writeStartElement(SOS_PREFIX, "GetObservationResponse", sosNsUri);
                writer.writeNamespace(SOS_PREFIX, sosNsUri);
            }
            
            // send obs from each selected offering
            // TODO sort by time by multiplexing obs from different offerings?
            boolean firstObs = true;
            for (String offering: selectedOfferings)
            {
                Set<String> selectedObservables = request.getObservables();
                                
                // if no observables were selected, add all of them
                // we'll filter redundant one later
                boolean sendAllObservables = false;
                if (selectedObservables.isEmpty())
                {
                   SOSOfferingCapabilities caps = offeringCaps.get(offering);
                   selectedObservables = new LinkedHashSet<>();
                   selectedObservables.addAll(caps.getObservableProperties());
                   sendAllObservables = true;
                }
                
                // setup data provider
                SOSDataFilter filter = new SOSDataFilter(selectedObservables, request.getTime(), request.getFoiIDs(), request.getSpatialFilter());
                filter.setMaxObsCount(config.maxObsCount);
                dataProvider = getDataProvider(offering, filter);
                
                // write each observation in stream
                // we use stream writer to limit memory usage
                IObservation obs;
                while ((obs = dataProvider.getNextObservation()) != null)
                {
                    DataComponent obsResult = obs.getResult();
                    
                    // write a different obs for each requested observable
                    for (String observable: selectedObservables)
                    {                    
                        obs.setObservedProperty(new DefinitionRef(observable));
                        
                        // filter obs result
                        if (!observable.equals(obsResult.getDefinition()))
                        {
                            DataComponent singleResult = SWEHelper.findComponentByDefinition(obsResult, observable);
                            obs.setResult(singleResult);
                        }
                        else
                        {
                            // make sure we reset the whole result in case it was trimmed during previous iteration
                            obs.setResult(obsResult);
                        }
                        
                        // remove redundant obs in wildcard case
                        DataComponent result = obs.getResult();
                        if (sendAllObservables && (result instanceof DataRecord || result instanceof DataChoice))
                            continue;
                        
                        // set correct obs type depending on final result structure                        
                        if (result instanceof SimpleComponent)
                            obs.setType(IObservation.OBS_TYPE_SCALAR);
                        else if (result instanceof DataRecord || result instanceof Vector)
                            obs.setType(IObservation.OBS_TYPE_RECORD);
                        else if (result instanceof DataArray)
                            obs.setType(IObservation.OBS_TYPE_ARRAY);
                        
                        // first write obs as DOM
                        DOMHelper dom = new DOMHelper();
                        Element obsElt = obsWriter.write(dom, obs);
                        
                        // write common namespaces on root element
                        if (firstObs)
                        {
                            for (Entry<String, String> nsDef: dom.getXmlDocument().getNSTable().entrySet())
                                writer.writeNamespace(nsDef.getKey(), nsDef.getValue());        
                            firstObs = false;
                        }
                        
                        // serialize observation DOM tree into stream writer
                        writer.writeStartElement(SOS_PREFIX, "observationData", sosNsUri);
                        dom.writeToStreamWriter(obsElt, writer);
                        writer.writeEndElement();
                        writer.flush();
                    }
                }
            }
            
            // close SOAP elements
            if (!isJson)
                endSoapEnvelope(request, writer);
            
            // this will automatically close all open elements
            writer.writeEndDocument();
            writer.close();
        }
        catch (XMLStreamException e)
        {
            throw new IOException(SEND_RESPONSE_ERROR_MSG, e);
        }
        finally
        {
            if (dataProvider != null)
                dataProvider.close();
        }
    }
    
    
    @Override
    protected void handleRequest(GetResultTemplateRequest request) throws IOException, OWSException
    {
        // check query parameters        
        OWSExceptionReport report = new OWSExceptionReport();
        checkQueryObservables(request.getOffering(), request.getObservables(), report);
        report.process();
        
        // security check
        securityHandler.checkPermission(request.getOffering(), securityHandler.sos_read_obs);
        
        // setup data provider
        SOSDataFilter filter = new SOSDataFilter(request.getObservables());
        ISOSDataProvider dataProvider = getDataProvider(request.getOffering(), filter);
        
        try
        {
            // build filtered component tree
            // always keep sampling time and entity ID if present
            DataComponent filteredStruct = dataProvider.getResultStructure().copy();
            request.getObservables().add(SWEConstants.DEF_SAMPLING_TIME);
            String entityComponentUri = SOSProviderUtils.findEntityIDComponentURI(filteredStruct);
            if (entityComponentUri != null)
                request.getObservables().add(entityComponentUri);
            filteredStruct.accept(new DataStructFilter(request.getObservables()));
            
            // build and send response
            if (OWSUtils.JSON_MIME_TYPE.equals(request.getFormat()))
            {
                OutputStream os = new BufferedOutputStream(request.getResponseStream());
                SWEJsonStreamWriter writer = new SWEJsonStreamWriter(os, StandardCharsets.UTF_8.name());
                request.getHttpResponse().setContentType(OWSUtils.JSON_MIME_TYPE);
                SWEStaxBindings sweBindings = new SWEStaxBindings();
                writer.writeStartDocument();
                sweBindings.writeDataComponent(writer, filteredStruct, false);
                writer.writeEndDocument();
                writer.close();
            }
            else
            {
                GetResultTemplateResponse resp = new GetResultTemplateResponse();
                resp.setResultStructure(filteredStruct);
                resp.setResultEncoding(dataProvider.getDefaultResultEncoding());
                sendResponse(request, resp);
            }
        }
        catch (XMLStreamException e)
        {
            throw new IOException(SEND_RESPONSE_ERROR_MSG, e);
        }
        finally
        {
            dataProvider.close();
        }
    }
    
    
    protected void handleRequest(GetResultRequest request) throws IOException, OWSException
    {
        ISOSDataProvider dataProvider = null;
        
        // check query parameters
        OWSExceptionReport report = new OWSExceptionReport();
        checkQueryObservables(request.getOffering(), request.getObservables(), report);
        checkQueryProcedures(request.getOffering(), request.getProcedures(), report);
        checkQueryTime(request.getOffering(), request.getTime(), report);
        report.process();
        
        // security check
        securityHandler.checkPermission(request.getOffering(), securityHandler.sos_read_obs);
        boolean isWs = isWebSocketRequest(request);        
        
        // setup data filter (including extensions)
        SOSDataFilter filter = new SOSDataFilter(request.getObservables(), request.getTime(), request.getFoiIDs(), request.getSpatialFilter());
        filter.setMaxObsCount(config.maxRecordCount);
        if (request.getExtensions().containsKey(EXT_REPLAY))
        {
            String replaySpeed = (String)request.getExtensions().get(EXT_REPLAY);
            filter.setReplaySpeedFactor(Double.parseDouble(replaySpeed));
        }
        
        // setup data provider
        dataProvider = getDataProvider(request.getOffering(), filter);
        DataComponent resultStructure = dataProvider.getResultStructure();
        DataEncoding resultEncoding = dataProvider.getDefaultResultEncoding();
        boolean customFormatUsed = false;
        
        try
        {
            // use JSON, XML or custom format if requested
            if (resultEncoding instanceof TextEncoding && OWSUtils.JSON_MIME_TYPE.equals(request.getFormat()))
                resultEncoding = new JSONEncodingImpl();
            else if (resultEncoding instanceof TextEncoding && OWSUtils.XML_MIME_TYPE.equals(request.getFormat()))
                resultEncoding = new XMLEncodingImpl();
            else
                customFormatUsed = writeCustomFormatStream(request, dataProvider);
            
            // if no custom format was written write standard SWE common data stream
            if (!customFormatUsed)
            {
                OutputStream os = new BufferedOutputStream(request.getResponseStream());
                
                // force disable xmlWrapper in some cases
                if (resultEncoding instanceof JSONEncodingImpl ||
                    resultEncoding instanceof BinaryEncoding || isWs)
                    request.setXmlWrapper(false);
                
                // write small xml wrapper if requested
                if (request.isXmlWrapper())
                {
                    String nsUri = OGCRegistry.getNamespaceURI(SOSUtils.SOS, request.getVersion());
                    os.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes());
                    os.write(("<GetResultResponse xmlns=\"" + nsUri + "\">\n<resultValues>\n").getBytes());
                }
                
                // else change response content type according to encoding
                else if (request.getHttpResponse() != null)
                {
                    if (resultEncoding instanceof TextEncoding)
                        request.getHttpResponse().setContentType(OWSUtils.TEXT_MIME_TYPE);
                    else if (resultEncoding instanceof JSONEncoding)
                        request.getHttpResponse().setContentType(OWSUtils.JSON_MIME_TYPE);
                    else if (resultEncoding instanceof XMLEncoding)
                        request.getHttpResponse().setContentType(OWSUtils.XML_MIME_TYPE);
                    else if (resultEncoding instanceof BinaryEncoding)
                        request.getHttpResponse().setContentType(OWSUtils.BINARY_MIME_TYPE);
                    else
                        throw new IllegalStateException("Unsupported encoding: " + resultEncoding.getClass().getCanonicalName());
                }

                // prepare writer for selected encoding
                DataStreamWriter writer = SWEHelper.createDataWriter(resultEncoding);
                
                // we also do filtering here in case data provider hasn't modified the datablocks
                // always keep sampling time and entity ID if present
                request.getObservables().add(SWEConstants.DEF_SAMPLING_TIME);
                String entityComponentUri = SOSProviderUtils.findEntityIDComponentURI(resultStructure);
                if (entityComponentUri != null)
                    request.getObservables().add(entityComponentUri);
                // temporary hack to switch btw old and new writer architecture
                if (writer instanceof AbstractDataWriter)
                    writer = new FilteredWriter((AbstractDataWriter)writer, request.getObservables());
                else
                    ((DataBlockProcessor)writer).setDataComponentFilter(new FilterByDefinition(request.getObservables()));
                writer.setDataComponents(resultStructure);
                writer.setOutput(os);
                                
                // write all records in output stream
                writer.startStream(!isWs);
                DataBlock nextRecord;
                while ((nextRecord = dataProvider.getNextResultRecord()) != null)
                {
                    writer.write(nextRecord);
                    writer.flush();
                }
                writer.endStream();
                
                // close xml wrapper if needed
                if (request.isXmlWrapper())
                    os.write("\n</resultValues>\n</GetResultResponse>".getBytes());          
                        
                os.flush();
            }
        }
        catch (IOException e)
        {
            throw new IOException(SEND_RESPONSE_ERROR_MSG, e);
        }
        finally
        {
            dataProvider.close();
        }
    }
    
    
    @Override
    protected void handleRequest(final GetFeatureOfInterestRequest request) throws IOException, OWSException
    {
        OWSExceptionReport report = new OWSExceptionReport();
        Set<String> selectedProcedures = new LinkedHashSet<>();
                
        // get list of procedures to scan
        Set<String> procedures = request.getProcedures();
        if (procedures != null && !procedures.isEmpty())
        {
            // check listed procedures are valid
            for (String procID: procedures)
                checkQueryProcedure(procID, report);
                        
            selectedProcedures.addAll(procedures);
        }
        else
        {
            // otherwise just include all procedures
            selectedProcedures.addAll(procedureToOfferingMap.keySet());
        }
        
        // process observed properties
        Set<String> observables = request.getObservables();
        if (observables != null && !observables.isEmpty())
        {
            // first check observables are valid in at least one offering
            for (String obsProp: observables)
            {
                boolean found = false;
                for (SOSOfferingCapabilities offering: capabilities.getLayers())
                {
                    if (offering.getObservableProperties().contains(obsProp))
                    {
                        found = true;
                        break;
                    }
                }
                
                if (!found)
                    report.add(new SOSException(SOSException.invalid_param_code, "observedProperty", obsProp, "Observed property " + obsProp + " is not available"));
            }
            
            // keep only procedures with selected observed properties            
            Iterator<String> it = selectedProcedures.iterator();
            while (it.hasNext())
            {
                String offeringID = procedureToOfferingMap.get(it.next());
                SOSOfferingCapabilities offering = offeringCaps.get(offeringID);
                
                boolean found = false;
                for (String obsProp: observables)
                {
                    offering.getObservableProperties().contains(obsProp);
                    found = true;
                    break;
                }
                
                if (!found)
                    it.remove();
            }
        }
        
        // if errors were detected, send them now
        report.process();
        
        // security check
        for (String procID: selectedProcedures)
        {
            String offeringID = procedureToOfferingMap.get(procID);
            securityHandler.checkPermission(offeringID, securityHandler.sos_read_foi);
        }
        
        // prepare feature filter
        final Polygon poly;
        if (request.getSpatialFilter() != null)
            poly = request.getBbox().toJtsPolygon();
        else
            poly = null;
        
        IFoiFilter filter = new FoiFilter()
        {
            public Polygon getRoi() { return poly; }
            public Set<String> getFeatureIDs() { return request.getFoiIDs(); };
        };
            
        try
        {
            // init xml document writing
            OutputStream os = new BufferedOutputStream(request.getResponseStream());
            XMLOutputFactory factory = XMLImplFinder.getStaxOutputFactory();
            factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true);
            XMLStreamWriter xmlWriter = factory.createXMLStreamWriter(os, StandardCharsets.UTF_8.name());
            
            // prepare GML features writing
            GMLStaxBindings gmlBindings = new GMLStaxBindings();
            gmlBindings.registerFeatureBindings(new SMLStaxBindings());
            gmlBindings.declareNamespacesOnRootElement();        
            
            // start XML response
            xmlWriter.writeStartDocument();
            
            // wrap with SOAP envelope if requested
            startSoapEnvelope(request, xmlWriter);
            
            // write response root element
            String sosNsUri = OGCRegistry.getNamespaceURI(SOSUtils.SOS, DEFAULT_VERSION);
            xmlWriter.writeStartElement(SOS_PREFIX, "GetFeatureOfInterestResponse", sosNsUri);
            xmlWriter.writeNamespace(SOS_PREFIX, sosNsUri);
            gmlBindings.writeNamespaces(xmlWriter);        
            
            // scan offering corresponding to each selected procedure
            boolean first = true;
            HashSet<String> returnedFids = new HashSet<>();
            
            for (String procID: selectedProcedures)
            {
                ISOSDataProviderFactory provider = getDataProviderFactoryBySensorID(procID);
                
                // output selected features
                Iterator<AbstractFeature> it2 = provider.getFoiIterator(filter);
                while (it2.hasNext())
                {
                    AbstractFeature f = it2.next();
                    
                    // make sure we don't send twice the same feature
                    if (returnedFids.contains(f.getUniqueIdentifier()))
                        continue;
                    returnedFids.add(f.getUniqueIdentifier());
                    
                    // write namespace on root because in many cases it is common to all features
                    if (first)
                    {
                        gmlBindings.ensureNamespaceDecl(xmlWriter, f.getQName());
                        if (f instanceof GenericFeature)
                        {
                            for (Entry<QName, Object> prop: ((GenericFeature)f).getProperties().entrySet())
                                gmlBindings.ensureNamespaceDecl(xmlWriter, prop.getKey());
                        }
                        
                        first = false;
                    }
                    
                    xmlWriter.writeStartElement(sosNsUri, "featureMember");
                    gmlBindings.writeAbstractFeature(xmlWriter, f);
                    xmlWriter.writeEndElement();
                    xmlWriter.flush();
                    os.write('\n');
                }
            }
            
            // close SOAP elements
            endSoapEnvelope(request, xmlWriter);
                    
            xmlWriter.writeEndDocument();
            xmlWriter.close();
        }
        catch (SensorHubException e)
        {
            throw new IOException("Cannot get features from provider", e);
        }
        catch (XMLStreamException e)
        {
            throw new IOException(SEND_RESPONSE_ERROR_MSG, e);
        }
    }
    
    
    @Override
    protected void handleRequest(InsertSensorRequest request) throws IOException, OWSException
    {
        checkTransactionalSupport(request);
        
        // security check
        securityHandler.checkPermission(securityHandler.sos_insert_sensor);
        
        // check query parameters
        OWSExceptionReport report = new OWSExceptionReport();
        TransactionUtils.checkSensorML(request.getProcedureDescription(), report);
        report.process();
        
        // get sensor UID
        String sensorUID = request.getProcedureDescription().getUniqueIdentifier();
        log.info("Registering new sensor {}", sensorUID);
        
        // offering name is derived from sensor UID
        String offeringID = sensorUID + "-sos";
        
        ///////////////////////////////////////////////////////////////////////////////////////
        // we configure things step by step so we can fix config if it was partially altered //
        ///////////////////////////////////////////////////////////////////////////////////////
        HashSet<ModuleConfig> configSaveList = new HashSet<>();
        ModuleRegistry moduleReg = SensorHub.getInstance().getModuleRegistry();
        
        // create new virtual sensor module if needed            
        IModule<?> sensorModule = moduleReg.getLoadedModuleById(sensorUID);
        if (sensorModule == null)
        {
            sensorModule = TransactionUtils.createSensorModule(sensorUID, request.getProcedureDescription());
            configSaveList.add(sensorModule.getConfiguration());
        }            
        // else simply update description
        else
            TransactionUtils.updateSensorDescription(sensorModule, request.getProcedureDescription());
        
        // also create associated storage if requested
        IModule<?> storageModule = null;
        if (config.newStorageConfig != null)
        {
            storageModule = addStorageForSensor((SWETransactionalSensor)sensorModule);
            configSaveList.add(storageModule.getConfiguration());
            
            // force regenerate provider and consumer if needed
            ISOSDataProviderFactory provider = dataProviders.get(offeringID);
            if (provider != null && !(provider instanceof StorageDataProviderFactory))
                dataProviders.remove(offeringID);
            ISOSDataConsumer consumer = dataConsumers.get(offeringID);
            if (consumer != null && !(consumer instanceof SensorWithStorageConsumer))
                dataConsumers.remove(offeringID);
        }
        
        // add new provider if needed
        ISOSDataProviderFactory provider = dataProviders.get(offeringID);
        if (provider == null)
        {
            // generate new provider config
            SensorDataProviderConfig providerConfig = new SensorDataProviderConfig();
            providerConfig.enabled = true;
            providerConfig.sensorID = sensorUID;
            providerConfig.storageID = (storageModule != null) ? storageModule.getLocalID() : null;
            providerConfig.offeringID = offeringID;
            providerConfig.liveDataTimeout = 600;
            config.dataProviders.replaceOrAdd(providerConfig);
            
            // instantiate and register provider
            try
            {
                provider = providerConfig.getFactory(this);
                dataProviders.put(offeringID, provider);
            }
            catch (SensorHubException e)
            {
                throw new IOException("Cannot create new provider", e);
            }
            
            // add new permissions for this offering
            securityHandler.addOfferingPermissions(offeringID);
            
            configSaveList.add(config);
        }
        
        // add new consumer if needed
        ISOSDataConsumer consumer = dataConsumers.get(offeringID);
        if (consumer == null)
        {
            // generate new consumer config
            SensorConsumerConfig consumerConfig = new SensorConsumerConfig();
            consumerConfig.enabled = true;
            consumerConfig.offeringID = offeringID;
            consumerConfig.sensorID = sensorUID;
            consumerConfig.storageID = (storageModule != null) ? storageModule.getLocalID() : null;
            config.dataConsumers.replaceOrAdd(consumerConfig);
            
            // instantiate and register consumer
            try
            {
                consumer = consumerConfig.getConsumer();
                dataConsumers.put(offeringID, consumer);
            }
            catch (SensorHubException e)
            {
                throw new IOException("Cannot create new consumer", e);
            }
            
            configSaveList.add(config);
        }
        
        // save module configs so we don't loose anything on restart
        moduleReg.saveConfiguration(configSaveList.toArray(new ModuleConfig[0]));
        
        // update capabilities
        showProviderCaps(provider);
        
        // build and send response
        InsertSensorResponse resp = new InsertSensorResponse();
        resp.setAssignedOffering(offeringID);
        resp.setAssignedProcedureId(sensorUID);
        sendResponse(request, resp);
    }
    
    
    @Override
    protected void handleRequest(DeleteSensorRequest request) throws IOException, OWSException
    {
        checkTransactionalSupport(request);
        
        // check query parameters
        String sensorUID = request.getProcedureId();
        OWSExceptionReport report = new OWSExceptionReport();
        checkQueryProcedure(sensorUID, report);
        report.process();
        
        // security check
        String offeringID = procedureToOfferingMap.get(sensorUID);
        securityHandler.checkPermission(offeringID, securityHandler.sos_delete_sensor);            

        // destroy associated virtual sensor
        try
        {
            dataProviders.remove(offeringID);
            SWETransactionalSensor virtualSensor = (SWETransactionalSensor)dataConsumers.remove(offeringID);
            SensorHub.getInstance().getModuleRegistry().destroyModule(virtualSensor.getLocalID());
        }
        catch (SensorHubException e)
        {
            throw new IOException("Cannot delete virtual sensor " + sensorUID, e);
        }
        
        // TODO also destroy storage if requested in config 
        
        // build and send response
        DeleteSensorResponse resp = new DeleteSensorResponse(SOSUtils.SOS);
        resp.setDeletedProcedure(sensorUID);
        sendResponse(request, resp);
    }
    
    
    @Override
    protected void handleRequest(UpdateSensorRequest request) throws IOException, OWSException
    {
        checkTransactionalSupport(request);
        
        // check query parameters
        String sensorUID = request.getProcedureId();
        OWSExceptionReport report = new OWSExceptionReport();
        checkQueryProcedure(sensorUID, report);
        report.process();
        
        // security check
        String offeringID = procedureToOfferingMap.get(sensorUID);
        securityHandler.checkPermission(offeringID, securityHandler.sos_update_sensor);
        
        // check that format is supported
        checkQueryProcedureFormat(offeringID, request.getProcedureDescriptionFormat(), report);            
        
        // check that SensorML contains correct unique ID
        TransactionUtils.checkSensorML(request.getProcedureDescription(), report);            
        report.process();
        
        // get consumer and update
        ISOSDataConsumer consumer = getDataConsumerBySensorID(request.getProcedureId());                
        consumer.updateSensor(request.getProcedureDescription());
        
        // build and send response
        UpdateSensorResponse resp = new UpdateSensorResponse(SOSUtils.SOS);
        resp.setUpdatedProcedure(sensorUID);
        sendResponse(request, resp);
    }
    
    
    @Override
    protected void handleRequest(InsertObservationRequest request) throws IOException, OWSException
    {
        checkTransactionalSupport(request);
        
        // retrieve consumer for selected offering
        ISOSDataConsumer consumer = getDataConsumerByOfferingID(request.getOffering());
        
        // security check
        securityHandler.checkPermission(request.getOffering(), securityHandler.sos_insert_obs);
        
        // send new observation
        consumer.newObservation(request.getObservations().toArray(new IObservation[0]));            
        
        // build and send response
        InsertObservationResponse resp = new InsertObservationResponse();
        sendResponse(request, resp);
    }
    
    
    @Override
    protected void handleRequest(InsertResultTemplateRequest request) throws IOException, OWSException
    {
        checkTransactionalSupport(request);
        
        // retrieve consumer for selected offering
        String offeringID = request.getOffering();
        ISOSDataConsumer consumer = getDataConsumerByOfferingID(offeringID);
                    
        // security check
        securityHandler.checkPermission(offeringID, securityHandler.sos_insert_obs);
                    
        // get template ID
        // the same template ID is always returned for a given observable            
        String templateID = consumer.newResultTemplate(request.getResultStructure(),
                                                       request.getResultEncoding(),
                                                       request.getObservationTemplate());
                    
        // update caps only if template was not already registered
        if (!templateToOfferingMap.containsKey(templateID))
        {
            templateToOfferingMap.put(templateID, offeringID);
            
            // update offering capabilities
            showProviderCaps(getDataProviderFactoryByOfferingID(offeringID));
        }
        
        // build and send response
        InsertResultTemplateResponse resp = new InsertResultTemplateResponse();
        resp.setAcceptedTemplateId(templateID);
        sendResponse(request, resp);
    }
    
    
    @Override
    protected void handleRequest(InsertResultRequest request) throws IOException, OWSException
    {
        DataStreamParser parser = null;
        
        checkTransactionalSupport(request);
        
        // retrieve consumer based on template id
        String templateID = request.getTemplateId();
        ISOSDataConsumer consumer = getDataConsumerByTemplateID(templateID);
        
        // security check
        String offeringID = templateToOfferingMap.get(templateID);
        securityHandler.checkPermission(offeringID, securityHandler.sos_insert_obs);
        
        // get template info
        Template template = consumer.getTemplate(templateID);
        DataComponent dataStructure = template.component;
        DataEncoding encoding = template.encoding;
        
        try
        {
            InputStream resultStream;
            
            // select data source (either inline XML or in POST body for KVP)
            DataSource dataSrc = request.getResultDataSource();
            if (dataSrc instanceof DataSourceDOM) // inline XML
            {
                encoding = SWEHelper.ensureXmlCompatible(encoding);
                resultStream = dataSrc.getDataStream();
            }
            else // POST body
            {
                resultStream = new BufferedInputStream(request.getHttpRequest().getInputStream());
            }
            
            // create parser
            parser = SWEHelper.createDataParser(encoding);
            parser.setDataComponents(dataStructure);
            parser.setInput(resultStream);

            // if websocket, parse records in the callback
            if (isWebSocketRequest(request))
            {
                WebSocketListener socket = new SOSWebSocketIn(parser, consumer, templateID, log);
                this.acceptWebSocket(request, socket);
            }
            else
            {
                // parse each record and send it to consumer
                DataBlock nextBlock = null;
                while ((nextBlock = parser.parseNextBlock()) != null)
                    consumer.newResultRecord(templateID, nextBlock);
                
                // build and send response
                InsertResultResponse resp = new InsertResultResponse();
                sendResponse(request, resp);
            }
        }
        catch (ReaderException e)
        {
            throw new SOSException("Error in SWE encoded data", e);
        }
        finally
        {
            if (parser != null)
                parser.close();
        }
    }
    
    
    /*
     * Create proper writer depending on selected format
     */
    protected XMLStreamWriter getResponseStreamWriter(OWSRequest request, String format) throws IOException
    {
        OutputStream os = new BufferedOutputStream(request.getResponseStream());
        
        if (SWESOfferingCapabilities.FORMAT_SML2.equals(format) ||
            SWESOfferingCapabilities.FORMAT_OM2.equals(format))
        {
            try
            {
                XMLOutputFactory factory = XMLImplFinder.getStaxOutputFactory();
                XMLStreamWriter writer = factory.createXMLStreamWriter(os, StandardCharsets.UTF_8.name());
                request.getHttpResponse().setContentType(OWSUtils.XML_MIME_TYPE);
                return new IndentingXMLStreamWriter(writer);
            }
            catch (XMLStreamException e)
            {
                throw new IOException("Cannot create XML stream writer", e);
            }
        }
        else if (SWESOfferingCapabilities.FORMAT_SML2_JSON.equals(format))
        {
            try
            {
                request.getHttpResponse().setContentType(OWSUtils.JSON_MIME_TYPE);
                return new SMLJsonStreamWriter(os, StandardCharsets.UTF_8.name());
            }
            catch (JsonStreamException e)
            {
                throw new IOException("Cannot create JSON stream writer", e);
            }
        }
        else if (SWESOfferingCapabilities.FORMAT_OM2_JSON.equals(format))
        {
            try
            {
                request.getHttpResponse().setContentType(OWSUtils.JSON_MIME_TYPE);
                return new SWEJsonStreamWriter(os, StandardCharsets.UTF_8.name());
            }
            catch (JsonStreamException e)
            {
                throw new IOException("Cannot create JSON stream writer", e);
            }
        }
        
        
        return null;
    }
    
    
    protected SOSOfferingCapabilities checkAndGetOffering(String offeringID) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            SOSOfferingCapabilities offCaps = offeringCaps.get(offeringID);
            
            if (offCaps == null)
                throw new SOSException(SOSException.invalid_param_code, "offering", offeringID, null);
            
            return offCaps;
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }


    protected void checkQueryOfferings(Set<String> offerings, OWSExceptionReport report) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            
            for (String offering: offerings)
            {
                if (!offeringCaps.containsKey(offering))
                    report.add(new SOSException(SOSException.invalid_param_code, "offering", offering, "Offering " + offering + " is not available on this server"));
            }
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }   
    }
    
    
    protected void checkQueryObservables(String offeringID, Set<String> observables, OWSExceptionReport report) throws SOSException
    {
        SWESOfferingCapabilities offering = checkAndGetOffering(offeringID);
        for (String obsProp: observables)
        {
            if (!offering.getObservableProperties().contains(obsProp))
                report.add(new SOSException(SOSException.invalid_param_code, "observedProperty", obsProp, "Observed property " + obsProp + " is not available for offering " + offeringID));
        }
    }
    
    
    protected void checkQueryObservables(Set<String> observables, OWSExceptionReport report) throws SOSException
    {
        for (String obsProp: observables)
        {
            boolean found = false;
            
            for (SOSOfferingCapabilities offering: offeringCaps.values())
            {            
                if (offering.getObservableProperties().contains(obsProp))
                {
                    found = true;
                    break;
                }
            }
            
            if (!found)
                report.add(new SOSException(SOSException.invalid_param_code, "observedProperty", obsProp, "Observed property " + obsProp + " is not available on this server"));
        }   
    }


    protected void checkQueryProcedures(String offeringID, Set<String> procedures, OWSExceptionReport report) throws SOSException
    {
        SWESOfferingCapabilities offering = checkAndGetOffering(offeringID);
        for (String procID: procedures)
        {
            if (!offering.getProcedures().contains(procID))
                report.add(new SOSException(SOSException.invalid_param_code, "procedure", procID, "Procedure " + procID + " is not available for offering " + offeringID));
        }
    }
    
    
    protected void checkQueryProcedures(Set<String> procedures, OWSExceptionReport report) throws SOSException
    {
        for (String procID: procedures)
        {
            boolean found = false;
            
            for (SOSOfferingCapabilities offering: offeringCaps.values())
            {            
                if (offering.getProcedures().contains(procID))
                {
                    found = true;
                    break;
                }
            }
            
            if (!found)
                report.add(new SOSException(SOSException.invalid_param_code, "procedure", procID, "Procedure " + procID + " is not available on this server"));
        }   
    }


    protected void checkQueryFormat(String offeringID, String format, OWSExceptionReport report) throws SOSException
    {
        SOSOfferingCapabilities offering = checkAndGetOffering(offeringID);
        if (!offering.getResponseFormats().contains(format))
            report.add(new SOSException(SOSException.invalid_param_code, "format", format, "Format " + format + " is not available for offering " + offeringID));
    }


    protected void checkQueryTime(String offeringID, TimeExtent requestTime, OWSExceptionReport report) throws SOSException
    {
        // reject null time period
        if (requestTime.isNull())
            return;
        
        // reject if startTime > stopTime
        if (requestTime.getStartTime() > requestTime.getStopTime())
            report.add(new SOSException("The requested period must begin before it ends"));
        
        // check that provider can handle the requested time
        ISOSDataProviderFactory provider = dataProviders.get(offeringID);
        if (!provider.checkQueryTime(requestTime))
            report.add(new SOSException(SOSException.invalid_param_code, "phenomenonTime", requestTime.getIsoString(0), null));            
    }
    
    
    protected void checkQueryProcedure(String sensorUID, OWSExceptionReport report) throws SOSException
    {
        if (sensorUID == null || !procedureToOfferingMap.containsKey(sensorUID))
            report.add(new SOSException(SOSException.invalid_param_code, "procedure", sensorUID, null));
    }
    
    
    protected void checkQueryProcedureFormat(String offeringID, String format, OWSExceptionReport report) throws SOSException
    {
        // ok if default format can be used
        if (format == null)
            return;
        
        SWESOfferingCapabilities offering = offeringCaps.get(offeringID);
        if (offering != null)
        {
            if (!offering.getProcedureFormats().contains(format))
                report.add(new SOSException(SOSException.invalid_param_code, "procedureDescriptionFormat", format, "Procedure description format " + format + " is not available for offering " + offeringID));
        }
    }
    
    
    protected ISOSDataProvider getDataProvider(String offering, SOSDataFilter filter) throws IOException, OWSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            
            checkAndGetOffering(offering);
            ISOSDataProviderFactory factory = dataProviders.get(offering);
            if (factory == null)
                throw new IllegalStateException("No provider factory found");
            
            return factory.getNewDataProvider(filter);
        }        
        catch (SensorHubException e)
        {
            throw new IOException("Cannot get provider for offering " + offering, e);
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
    
    
    protected ISOSDataProviderFactory getDataProviderFactoryByOfferingID(String offering) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            
            ISOSDataProviderFactory factory = dataProviders.get(offering);
            if (factory == null)
                throw new IllegalStateException("No valid data provider factory found for offering " + offering);
            return factory;
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
    
    
    protected ISOSDataProviderFactory getDataProviderFactoryBySensorID(String sensorID) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();            
            String offering = procedureToOfferingMap.get(sensorID);
            return getDataProviderFactoryByOfferingID(offering);
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
    
    
    protected ISOSDataConsumer getDataConsumerByOfferingID(String offering) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            
            checkAndGetOffering(offering);
            ISOSDataConsumer consumer = dataConsumers.get(offering);
            
            if (consumer == null)
                throw new SOSException(SOSException.invalid_param_code, "offering", offering, "Transactional operations are not supported for offering " + offering);
                
            return consumer;
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
    
    
    protected ISOSDataConsumer getDataConsumerBySensorID(String sensorID) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            
            String offering = procedureToOfferingMap.get(sensorID);
            if (offering == null)
                throw new SOSException(SOSException.invalid_param_code, "procedure", sensorID, "Transactional operations are not supported for procedure " + sensorID);
            
            return getDataConsumerByOfferingID(offering);
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
    
    
    protected void checkTransactionalSupport(OWSRequest request) throws SOSException
    {
        if (!config.enableTransactional)
            throw new SOSException(SOSException.invalid_param_code, "request", request.getOperation(), request.getOperation() + " operation is not supported on this endpoint"); 
    }


    @Override
    protected String getDefaultVersion()
    {
        return DEFAULT_VERSION;
    }
    
    
    protected ISOSDataConsumer getDataConsumerByTemplateID(String templateID) throws SOSException
    {
        try
        {
            capabilitiesLock.readLock().lock();
            
            String offering = templateToOfferingMap.get(templateID);
            ISOSDataConsumer consumer = dataConsumers.get(offering);
            if (offering == null || consumer == null)
                throw new SOSException(SOSException.invalid_param_code, "template", templateID, "Invalid template ID");
            
            return consumer;
        }
        finally
        {
            capabilitiesLock.readLock().unlock();
        }
    }
    
    
    /*
     * Check if request comes from a compatible browser
     */
    protected boolean isRequestFromBrowser(OWSRequest request)
    {
        // don't do multipart with websockets
        HttpServletRequest httpRequest = request.getHttpRequest();
        if (httpRequest == null)
            return false;
        if (request.getHttpResponse() == null)
            return false;
        
        String userAgent = httpRequest.getHeader("User-Agent");
        if (userAgent == null)
            return false;
        
        if (userAgent.contains("Firefox"))
            return true;
        if (userAgent.contains("Chrome"))
            return true;
        
        return false;
    }
    
    
    /*
     * Check if request is through websocket protocol
     */
    protected boolean isWebSocketRequest(OWSRequest request)
    {
        return request.getExtensions().containsKey(EXT_WS);
    }
    
    
    protected void startSoapEnvelope(OWSRequest request, XMLStreamWriter writer) throws XMLStreamException
    {
        String soapUri = request.getSoapVersion(); 
        if (soapUri != null)
        {
            writer.writeStartElement(SOAP_PREFIX, "Envelope", soapUri);
            writer.writeNamespace(SOAP_PREFIX, soapUri);
            writer.writeStartElement(SOAP_PREFIX, "Body", soapUri);
        }
    }
    
    
    protected void endSoapEnvelope(OWSRequest request, XMLStreamWriter writer) throws XMLStreamException
    {
        String soapUri = request.getSoapVersion(); 
        if (soapUri != null)
        {
            writer.writeEndElement();
            writer.writeEndElement();
        }
    }
    
    
    protected boolean writeCustomFormatStream(GetResultRequest request, ISOSDataProvider dataProvider) throws IOException, SOSException
    {
        String format = request.getFormat();
        
        // auto select video format in some common cases
        if (format == null)
        {
            DataEncoding resultEncoding = dataProvider.getDefaultResultEncoding();
            if (resultEncoding instanceof BinaryEncoding)
            {
                List<BinaryMember> mbrList = ((BinaryEncoding)resultEncoding).getMemberList();
                BinaryBlock videoFrameSpec = null;
                
                // try to find binary block encoding def in list
                for (BinaryMember spec: mbrList)
                {
                    if (spec instanceof BinaryBlock)
                    {
                        videoFrameSpec = (BinaryBlock)spec;
                        break;
                    }
                }
                        
                if (videoFrameSpec != null)
                {            
                    if (isRequestFromBrowser(request) && videoFrameSpec.getCompression().equals("H264"))
                        format = "video/mp4";
                    
                    else if (isRequestFromBrowser(request) && videoFrameSpec.getCompression().equals("JPEG"))
                        format = "video/x-motion-jpeg";            
                }
            }
        }
        
        // try to find matching implementation for selected format
        if (format != null)
        {
            ISOSCustomSerializer serializer = customFormats.get(format);
            
            if (serializer != null)
            {
                serializer.write(dataProvider, request);
                return true;
            }
            else
                throw new SOSException(SOSException.invalid_param_code, "format", format, "Unsupported format " + format);
        }
        
        return false;
    }
}
