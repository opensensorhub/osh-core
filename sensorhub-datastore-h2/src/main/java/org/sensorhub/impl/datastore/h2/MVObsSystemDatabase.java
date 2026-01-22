/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.io.File;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreTool;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.IObsSystemDatabaseModule;
import org.sensorhub.api.database.IProcedureDatabase;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.property.IPropertyStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.utils.FileUtils;


/**
 * <p>
 * Implementation of the {@link IObsSystemDatabase} interface backed by
 * a single H2 MVStore that contains all maps necessary to store observations,
 * commands, features of interest and system description history.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 23, 2019
 */
public class MVObsSystemDatabase extends AbstractModule<MVObsSystemDatabaseConfig> implements IObsSystemDatabase, IProcedureDatabase, IObsSystemDatabaseModule<MVObsSystemDatabaseConfig>
{
    final static String KRYO_CLASS_MAP_NAME = "kryo_class_map";
    final static String SYSTEM_STORE_NAME = "sys_store";
    final static String DEPL_STORE_NAME = "depl_store";
    final static String FOI_STORE_NAME = "foi_store";
    final static String OBS_STORE_NAME = "obs_store";
    final static String CMD_STORE_NAME = "cmd_store";
    final static String PROC_STORE_NAME = "proc_store";
    final static String PROP_STORE_NAME = "prop_store";
    
    MVStore mvStore;
    MVSystemStoreImpl sysStore;
    MVDeploymentStoreImpl deplStore;
    MVObsStoreImpl obsStore;
    MVFoiStoreImpl foiStore;
    MVCommandStoreImpl cmdStore;
    MVProcedureStoreImpl procStore;
    MVPropertyStoreImpl propStore;
    
    
    @Override
    protected void beforeInit() throws SensorHubException
    {
        super.beforeInit();
        
        // check file path is valid
        if (!FileUtils.isSafeFilePath(config.storagePath))
            throw new DataStoreException("Storage path contains illegal characters: " + config.storagePath);
    }
    
    
    @Override
    protected void doStart() throws SensorHubException
    {
        try
        {
            MVStore.Builder builder = new MVStore.Builder().fileName(config.storagePath);
            builder.backgroundExceptionHandler((t, e) -> {
                getLogger().error("Error in H2 background thread {}", t.getName(), e);
            });
            
            if (config.readOnly)
                builder.readOnly();
            
            if (config.memoryCacheSize > 0)
                builder.cacheSize(config.memoryCacheSize/1024);
            
            if (config.autoCommitBufferSize > 0)
                builder.autoCommitBufferSize(config.autoCommitBufferSize);

            if (config.autoCompactFillRate > 0)
                builder.autoCompactFillRate(config.autoCompactFillRate);
            
            if (config.useCompression)
                builder.compress();
            
            mvStore = builder.open();
            mvStore.setAutoCommitDelay(config.autoCommitPeriod*1000);
            mvStore.setVersionsToKeep(0);
            
            var idScope = getDatabaseNum() != null ? getDatabaseNum() : 0;
            
            // open system store
            sysStore = MVSystemStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(SYSTEM_STORE_NAME)
                .build());
            
            // open deployment store
            deplStore = MVDeploymentStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(DEPL_STORE_NAME)
                .build());
            
            // open foi store
            foiStore = MVFoiStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(FOI_STORE_NAME)
                .build());
            
            // open observation store
            obsStore = MVObsStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(OBS_STORE_NAME)
                .build());
            
            // open command store
            cmdStore = MVCommandStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(CMD_STORE_NAME)
                .build());
            
            // open procedure store
            procStore = MVProcedureStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(PROC_STORE_NAME)
                .build());
            
            // open property store
            propStore = MVPropertyStoreImpl.open(mvStore, idScope, config.idProviderType, MVDataStoreInfo.builder()
                .withName(PROP_STORE_NAME)
                .build());
            
            sysStore.linkTo(obsStore.getDataStreams());
            sysStore.linkTo(procStore);
            foiStore.linkTo(sysStore);
            foiStore.linkTo(obsStore);
            obsStore.linkTo(foiStore);
            obsStore.getDataStreams().linkTo(sysStore);
            cmdStore.getCommandStreams().linkTo(sysStore);
        }
        catch (Exception e)
        {
            throw new DataStoreException("Error while starting MVStore", e);
        }
    }
    
    
    @Override
    protected void afterStart()
    {
        if (hasParentHub() && config.databaseNum != null)
            getParentHub().getDatabaseRegistry().register(this);
    }
    
    
    @Override
    protected void beforeStop()
    {
        if (hasParentHub() && config.databaseNum != null)
            getParentHub().getDatabaseRegistry().unregister(this);
    }


    @Override
    protected void doStop() throws SensorHubException
    {
        if (mvStore != null) 
        {
            // must call commit first to make sure kryo persistent class resolver
            // is updated before we serialize it again in close
            mvStore.commit();
            mvStore.close();
            mvStore = null;
        }
        
        // log store info if debug is enabled
        if (config.printDebugInfoOnClose && getLogger().isDebugEnabled() && new File(config.storagePath).exists())
        {
            // log summary info
            var strWriter = new StringWriter();
            MVStoreTool.info(config.storagePath, strWriter);
            getLogger().debug("H2 debug info for '{}':\n{}", getName(), strWriter.toString());
            
            /*try
            {
                // dump detailed DB structure to file
                var dumpFileName = getName().toLowerCase().replace(' ', '_') + ".dump-" + Instant.now().getEpochSecond() + ".txt";
                var dumpFileWriter = new FileWriter(dumpFileName);
                dumpFileWriter.append(strWriter.getBuffer());
                dumpFileWriter.append('\n');
                MVStoreTool.dump(config.storagePath, dumpFileWriter, true);
            }
            catch (IOException e)
            {
                throw new SensorHubException("Error printing MVStore info", e);
            }*/
        }
        
        // compact store if requested
        if (config.compactOnClose)
            MVStoreTool.compact(config.storagePath, false);
    }


    @Override
    public Integer getDatabaseNum()
    {
        return config.databaseNum;
    }


    @Override
    public ISystemDescStore getSystemDescStore()
    {
        checkStarted();
        return sysStore;
    }


    @Override
    public IDeploymentStore getDeploymentStore()
    {
        checkStarted();
        return deplStore;
    }


    @Override
    public IObsStore getObservationStore()
    {
        checkStarted();
        return obsStore;
    }


    @Override
    public IFoiStore getFoiStore()
    {
        checkStarted();
        return foiStore;
    }


    @Override
    public ICommandStore getCommandStore()
    {
        checkStarted();
        return cmdStore;
    }


    @Override
    public IProcedureStore getProcedureStore()
    {
        checkStarted();
        return procStore;
    }


    @Override
    public IPropertyStore getPropertyStore()
    {
        checkStarted();
        return propStore;
    }


    @Override
    public void commit()
    {
        checkStarted();
        mvStore.commit();
    }
    
    
    @Override
    public synchronized <T> T executeTransaction(Callable<T> transaction) throws Exception
    {
        checkStarted();
        
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.commit();
        try
        {
            return transaction.call();
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }


    @Override
    public boolean isOpen()
    {
        return mvStore != null &&
            !mvStore.isClosed() &&
            isStarted();
    }


    @Override
    public boolean isReadOnly()
    {
        checkStarted();
        return mvStore.isReadOnly();
    }

    
    public MVStore getMVStore()
    {
        return mvStore;
    }
}
